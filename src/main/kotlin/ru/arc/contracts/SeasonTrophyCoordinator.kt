package ru.arc.contracts

interface SeasonTrophyPersistence {
    fun state(catalog: ObserveSeasonCatalog): SeasonRuntimeState

    fun journalRecords(): List<SeasonTrophyJournalRecord>

    suspend fun persistState(state: SeasonRuntimeState)

    suspend fun persistJournal(record: SeasonTrophyJournalRecord)
}

sealed interface SeasonTrophyContributionOutcome {
    data class Committed(val receipt: SeasonTrophyContributionReceipt) : SeasonTrophyContributionOutcome

    data class Duplicate(val receipt: SeasonTrophyContributionReceipt) : SeasonTrophyContributionOutcome

    data class Rejected(val reason: SeasonTrophyContributionRejection) : SeasonTrophyContributionOutcome

    data class Cancelled(val contributionId: String, val code: String) : SeasonTrophyContributionOutcome

    data class ManualReview(val contributionId: String) : SeasonTrophyContributionOutcome

    data class Unavailable(val contributionId: String) : SeasonTrophyContributionOutcome
}

data class SeasonTrophyRecoverySummary(
    val cancelledPrepared: Int,
    val committedRemovedItems: Int,
    val movedToManualReview: Int,
)

class SeasonTrophyContributionCoordinator(
    private val persistence: SeasonTrophyPersistence,
    private val inventory: ContractInventoryGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun submit(
        catalog: ObserveSeasonCatalog,
        contributionId: String,
        stageId: String,
        itemKey: String,
        playerId: String,
        requestedQuantity: Int,
    ): SeasonTrophyContributionOutcome {
        val records = loadJournal() ?: return SeasonTrophyContributionOutcome.Unavailable(contributionId)
        val state = loadState(catalog) ?: return SeasonTrophyContributionOutcome.Unavailable(contributionId)
        records.firstOrNull { it.contributionId == contributionId }?.let { existing ->
            state.recentTrophyReceipts[contributionId]?.let { receipt ->
                if (existing.status == SeasonTrophyJournalStatus.ITEMS_REMOVED) {
                    persistJournal(SeasonTrophyJournalEngine.confirmStateCommitted(existing, clock()))
                }
                return SeasonTrophyContributionOutcome.Duplicate(receipt)
            }
            return when (existing.status) {
                SeasonTrophyJournalStatus.CANCELLED ->
                    SeasonTrophyContributionOutcome.Cancelled(contributionId, requireNotNull(existing.cancellationCode))
                SeasonTrophyJournalStatus.MANUAL_REVIEW -> SeasonTrophyContributionOutcome.ManualReview(contributionId)
                else -> SeasonTrophyContributionOutcome.Unavailable(contributionId)
            }
        }
        if (records.size >= SeasonTrophyJournalAudit.MAX_NETWORK_RECORDS ||
            records.any { it.status !in TERMINAL_STATUSES }
        ) return SeasonTrophyContributionOutcome.Unavailable(contributionId)

        val plan =
            SeasonTrophyContributionEngine.plan(
                catalog,
                state,
                contributionId,
                stageId,
                itemKey,
                playerId,
                requestedQuantity,
                clock(),
            )
        when (plan) {
            is SeasonTrophyContributionPlan.Duplicate -> return SeasonTrophyContributionOutcome.Duplicate(plan.receipt)
            is SeasonTrophyContributionPlan.Rejected -> return SeasonTrophyContributionOutcome.Rejected(plan.reason)
            is SeasonTrophyContributionPlan.Accepted -> Unit
        }

        val preparedInventory =
            try {
                inventory.prepare(playerId, plan.itemKey, plan.acceptedQuantity)
            } catch (_: Throwable) {
                null
            } ?: return SeasonTrophyContributionOutcome.Rejected(SeasonTrophyContributionRejection.INVENTORY_UNAVAILABLE)
        val prepared =
            try {
                SeasonTrophyJournalEngine.prepare(catalog, plan, preparedInventory.payloads, clock())
            } catch (_: Throwable) {
                return SeasonTrophyContributionOutcome.Rejected(SeasonTrophyContributionRejection.INVENTORY_UNAVAILABLE)
            }
        if (!persistJournal(prepared)) return SeasonTrophyContributionOutcome.Unavailable(contributionId)

        val started = SeasonTrophyJournalEngine.beginItemRemoval(prepared, clock())
        if (!persistJournal(started)) return SeasonTrophyContributionOutcome.Unavailable(contributionId)
        when (safeInventoryMutation { preparedInventory.removeExact() }) {
            ContractInventoryMutation.Confirmed -> Unit
            is ContractInventoryMutation.NotPerformed -> {
                val cancelled =
                    SeasonTrophyJournalEngine.confirmNoItemsRemoved(started, "inventory_changed_before_remove", clock())
                return if (persistJournal(cancelled)) {
                    SeasonTrophyContributionOutcome.Cancelled(contributionId, requireNotNull(cancelled.cancellationCode))
                } else {
                    SeasonTrophyContributionOutcome.Unavailable(contributionId)
                }
            }
            ContractInventoryMutation.Ambiguous -> {
                persistJournal(
                    SeasonTrophyJournalEngine.haltItemRemoval(
                        started,
                        SeasonTrophyReviewReason.INVENTORY_EVIDENCE_CONFLICT,
                        "Exact bound trophy removal outcome could not be proven",
                        clock(),
                    ),
                )
                return SeasonTrophyContributionOutcome.ManualReview(contributionId)
            }
        }
        val removed = SeasonTrophyJournalEngine.confirmItemsRemoved(started, clock())
        if (!persistJournal(removed)) return SeasonTrophyContributionOutcome.ManualReview(contributionId)
        return commit(catalog, removed)
    }

    suspend fun recover(catalog: ObserveSeasonCatalog): SeasonTrophyRecoverySummary {
        var state = loadState(catalog) ?: throw IllegalStateException("Season runtime state is unavailable")
        val records = loadJournal() ?: throw IllegalStateException("Season trophy journal is unavailable")
        var cancelled = 0
        var committed = 0
        var reviewed = 0
        records.sortedWith(compareBy<SeasonTrophyJournalRecord> { it.createdAt }.thenBy { it.contributionId })
            .forEach { record ->
                when (record.status) {
                    SeasonTrophyJournalStatus.PREPARED -> {
                        persistence.persistJournal(
                            SeasonTrophyJournalEngine.cancelPrepared(record, "restart_before_item_removal", clock()),
                        )
                        cancelled += 1
                    }
                    SeasonTrophyJournalStatus.ITEM_REMOVAL_STARTED -> {
                        persistence.persistJournal(
                            SeasonTrophyJournalEngine.haltItemRemoval(
                                record,
                                SeasonTrophyReviewReason.INTERRUPTED_ITEM_REMOVAL,
                                "Restart after durable bound trophy removal intent; never retry automatically",
                                clock(),
                            ),
                        )
                        reviewed += 1
                    }
                    SeasonTrophyJournalStatus.ITEMS_REMOVED -> {
                        if (record.contributionId in state.recentTrophyReceipts) {
                            persistence.persistJournal(SeasonTrophyJournalEngine.confirmStateCommitted(record, clock()))
                            committed += 1
                        } else {
                            val result =
                                runCatching {
                                    SeasonTrophyContributionEngine.commit(
                                        catalog,
                                        state,
                                        record.toPlan(state.revision),
                                        clock(),
                                    )
                                }
                            if (result.isSuccess) {
                                state = result.getOrThrow().state
                                persistence.persistState(state)
                                persistence.persistJournal(SeasonTrophyJournalEngine.confirmStateCommitted(record, clock()))
                                committed += 1
                            } else {
                                persistence.persistJournal(
                                    SeasonTrophyJournalEngine.haltStateCommit(
                                        record,
                                        "Removed bound trophy no longer fits exact project state",
                                        clock(),
                                    ),
                                )
                                reviewed += 1
                            }
                        }
                    }
                    SeasonTrophyJournalStatus.CANCELLED,
                    SeasonTrophyJournalStatus.STATE_COMMITTED,
                    SeasonTrophyJournalStatus.MANUAL_REVIEW,
                    -> Unit
                }
            }
        return SeasonTrophyRecoverySummary(cancelled, committed, reviewed)
    }

    private suspend fun commit(
        catalog: ObserveSeasonCatalog,
        removed: SeasonTrophyJournalRecord,
    ): SeasonTrophyContributionOutcome {
        val current = loadState(catalog) ?: return SeasonTrophyContributionOutcome.ManualReview(removed.contributionId)
        val result =
            runCatching { SeasonTrophyContributionEngine.commit(catalog, current, removed.toPlan(), clock()) }
                .getOrElse {
                    persistJournal(
                        SeasonTrophyJournalEngine.haltStateCommit(
                            removed,
                            "Removed bound trophy no longer fits exact project state",
                            clock(),
                        ),
                    )
                    return SeasonTrophyContributionOutcome.ManualReview(removed.contributionId)
                }
        try {
            persistence.persistState(result.state)
        } catch (_: Throwable) {
            return SeasonTrophyContributionOutcome.ManualReview(removed.contributionId)
        }
        val committed = SeasonTrophyJournalEngine.confirmStateCommitted(removed, clock())
        return if (persistJournal(committed)) {
            SeasonTrophyContributionOutcome.Committed(result.receipt)
        } else {
            SeasonTrophyContributionOutcome.ManualReview(removed.contributionId)
        }
    }

    private fun loadState(catalog: ObserveSeasonCatalog): SeasonRuntimeState? =
        runCatching { persistence.state(catalog).validatedAgainst(catalog) }.getOrNull()

    private fun loadJournal(): List<SeasonTrophyJournalRecord>? =
        runCatching {
            persistence.journalRecords().onEach { it.validated() }.also(SeasonTrophyJournalAudit::summarize)
        }.getOrNull()

    private suspend fun persistJournal(record: SeasonTrophyJournalRecord): Boolean =
        try {
            persistence.persistJournal(record.validated())
            true
        } catch (_: Throwable) {
            false
        }

    private suspend fun safeInventoryMutation(block: suspend () -> ContractInventoryMutation): ContractInventoryMutation =
        try {
            block()
        } catch (_: Throwable) {
            ContractInventoryMutation.Ambiguous
        }

    companion object {
        private val TERMINAL_STATUSES =
            setOf(SeasonTrophyJournalStatus.CANCELLED, SeasonTrophyJournalStatus.STATE_COMMITTED)
    }
}

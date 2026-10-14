package ru.arc.contracts

/** Persistence writes return only after the Redis-backed repository confirms durability. */
interface SeasonMoneyPersistence {
    fun state(catalog: ObserveSeasonCatalog): SeasonRuntimeState

    fun journalRecords(): List<SeasonMoneyJournalRecord>

    suspend fun persistState(state: SeasonRuntimeState)

    suspend fun persistJournal(record: SeasonMoneyJournalRecord)

    suspend fun deleteJournal(actionId: String)
}

interface SeasonMoneyGateway {
    suspend fun balanceMinor(playerId: String): Long?

    /** Must make at most one provider withdrawal call. */
    suspend fun withdraw(
        playerId: String,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): SeasonMoneyEvidence
}

data class SeasonMoneyEvidence(
    /** true=provider success, false=proven no mutation, null=ambiguous outcome. */
    val providerAccepted: Boolean?,
    val providerCallAttempted: Boolean?,
    val balanceAfterMinor: Long?,
    val failureCode: String? = null,
)

sealed interface SeasonMoneyActionOutcome {
    data class Committed(val receipt: SeasonMoneyActionReceipt) : SeasonMoneyActionOutcome

    data class Duplicate(val receipt: SeasonMoneyActionReceipt) : SeasonMoneyActionOutcome

    data class Rejected(val reason: SeasonMoneyRejection) : SeasonMoneyActionOutcome

    data class Cancelled(val actionId: String, val code: String) : SeasonMoneyActionOutcome

    data class ManualReview(val actionId: String) : SeasonMoneyActionOutcome

    data class Unavailable(val actionId: String) : SeasonMoneyActionOutcome
}

data class SeasonMoneyRecoverySummary(
    val cancelledPrepared: Int,
    val committedWithdrawals: Int,
    val movedToManualReview: Int,
)

/**
 * Serial, journal-first coordinator for project cash and prepaid dungeon
 * admission. A provider call is never retried after its intent is durable.
 */
class SeasonMoneyCoordinator(
    private val persistence: SeasonMoneyPersistence,
    private val money: SeasonMoneyGateway,
    private val dungeonLaunchGate: SeasonDungeonLaunchGate = SeasonDungeonLaunchGate(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun submit(
        catalog: ObserveSeasonCatalog,
        actionId: String,
        playerId: String,
        request: SeasonMoneyActionRequest,
    ): SeasonMoneyActionOutcome {
        val loadedRecords = loadJournal() ?: return SeasonMoneyActionOutcome.Unavailable(actionId)
        val records = pruneTerminalRecords(loadedRecords) ?: return SeasonMoneyActionOutcome.Unavailable(actionId)
        val state = loadState(catalog) ?: return SeasonMoneyActionOutcome.Unavailable(actionId)
        records.firstOrNull { it.actionId == actionId }?.let { existing ->
            state.recentReceipts[actionId]?.let { receipt ->
                if (existing.status == SeasonMoneyJournalStatus.FUNDS_WITHDRAWN) {
                    persistJournal(SeasonMoneyJournalEngine.confirmStateCommitted(existing, clock()))
                }
                return SeasonMoneyActionOutcome.Duplicate(receipt)
            }
            return when (existing.status) {
                SeasonMoneyJournalStatus.CANCELLED ->
                    SeasonMoneyActionOutcome.Cancelled(actionId, requireNotNull(existing.cancellationCode))
                SeasonMoneyJournalStatus.MANUAL_REVIEW -> SeasonMoneyActionOutcome.ManualReview(actionId)
                else -> SeasonMoneyActionOutcome.Unavailable(actionId)
            }
        }
        if (records.size >= SeasonMoneyJournalAudit.MAX_NETWORK_RECORDS) {
            return SeasonMoneyActionOutcome.Unavailable(actionId)
        }
        if (records.any { it.status !in TERMINAL_STATUSES }) {
            return SeasonMoneyActionOutcome.Unavailable(actionId)
        }

        val plan = SeasonMoneyActionEngine.plan(catalog, state, actionId, playerId, request, clock())
        when (plan) {
            is SeasonMoneyActionPlan.Duplicate -> return SeasonMoneyActionOutcome.Duplicate(plan.receipt)
            is SeasonMoneyActionPlan.Rejected -> return SeasonMoneyActionOutcome.Rejected(plan.reason)
            is SeasonMoneyActionPlan.Accepted -> Unit
        }

        val prepared = SeasonMoneyJournalEngine.prepare(catalog, plan, clock())
        if (!persistJournal(prepared)) return SeasonMoneyActionOutcome.Unavailable(actionId)
        val balanceBefore = runCatching { money.balanceMinor(playerId) }.getOrNull()
        if (balanceBefore == null || balanceBefore < plan.amountMinor) {
            val code = if (balanceBefore == null) "provider_balance_unavailable" else "insufficient_funds"
            val cancelled = SeasonMoneyJournalEngine.cancelPrepared(prepared, code, clock())
            return if (persistJournal(cancelled)) {
                SeasonMoneyActionOutcome.Cancelled(actionId, code)
            } else {
                SeasonMoneyActionOutcome.Unavailable(actionId)
            }
        }

        val started = SeasonMoneyJournalEngine.beginWithdrawal(prepared, balanceBefore, clock())
        if (!persistJournal(started)) return SeasonMoneyActionOutcome.Unavailable(actionId)
        val evidence =
            try {
                money.withdraw(playerId, plan.amountMinor, started.withdrawalReason, balanceBefore)
            } catch (_: Throwable) {
                SeasonMoneyEvidence(
                    providerAccepted = null,
                    providerCallAttempted = true,
                    balanceAfterMinor = runCatching { money.balanceMinor(playerId) }.getOrNull(),
                )
            }
        val exactAfter = runCatching { Math.subtractExact(balanceBefore, plan.amountMinor) }.getOrNull()
        if (evidence.providerAccepted == true && evidence.providerCallAttempted == true &&
            exactAfter != null && evidence.balanceAfterMinor == exactAfter
        ) {
            val withdrawn = SeasonMoneyJournalEngine.confirmFundsWithdrawn(started, exactAfter, clock())
            if (!persistJournal(withdrawn)) return SeasonMoneyActionOutcome.ManualReview(actionId)
            return commit(catalog, withdrawn)
        }
        if (evidence.providerAccepted == false && evidence.providerCallAttempted == false) {
            val code = stableEvidence(evidence.failureCode, "provider_call_skipped")
            val cancelled =
                SeasonMoneyJournalEngine.confirmNoProviderCall(
                    started,
                    evidence.balanceAfterMinor,
                    code,
                    clock(),
                )
            return if (persistJournal(cancelled)) {
                SeasonMoneyActionOutcome.Cancelled(actionId, code)
            } else {
                SeasonMoneyActionOutcome.Unavailable(actionId)
            }
        }
        if (evidence.providerAccepted == false && evidence.providerCallAttempted == true &&
            evidence.balanceAfterMinor == balanceBefore
        ) {
            val code = stableEvidence(evidence.failureCode, "provider_rejected")
            val cancelled = SeasonMoneyJournalEngine.confirmWithdrawalFailed(started, balanceBefore, code, clock())
            return if (persistJournal(cancelled)) {
                SeasonMoneyActionOutcome.Cancelled(actionId, code)
            } else {
                SeasonMoneyActionOutcome.Unavailable(actionId)
            }
        }

        persistJournal(
            SeasonMoneyJournalEngine.haltForReview(
                started,
                SeasonMoneyReviewReason.PROVIDER_EVIDENCE_CONFLICT,
                "withdrawal_outcome_ambiguous",
                evidence.balanceAfterMinor,
                evidence.providerCallAttempted,
                clock(),
            ),
        )
        return SeasonMoneyActionOutcome.ManualReview(actionId)
    }

    suspend fun recover(catalog: ObserveSeasonCatalog): SeasonMoneyRecoverySummary {
        var state = loadState(catalog) ?: throw IllegalStateException("Season runtime state is unavailable")
        val recoveredLaunches = dungeonLaunchGate.releaseExpired(catalog, state, clock())
        if (recoveredLaunches != state) {
            persistence.persistState(recoveredLaunches)
            state = recoveredLaunches
        }
        val loadedRecords = loadJournal() ?: throw IllegalStateException("Season money journal is unavailable")
        val records = pruneTerminalRecords(loadedRecords)
            ?: throw IllegalStateException("Season money journal retention failed")
        val catalogDigest = catalog.revisionDigest()
        var cancelled = 0
        var committed = 0
        var reviewed = 0
        records.asSequence()
            .filter { it.seasonId == catalog.id && it.catalogDigest == catalogDigest }
            .sortedWith(compareBy<SeasonMoneyJournalRecord> { it.createdAt }.thenBy { it.actionId })
            .forEach { record ->
            when (record.status) {
                SeasonMoneyJournalStatus.PREPARED -> {
                    persistence.persistJournal(
                        SeasonMoneyJournalEngine.cancelPrepared(record, "restart_before_withdrawal", clock()),
                    )
                    cancelled += 1
                }
                SeasonMoneyJournalStatus.WITHDRAWAL_STARTED -> {
                    persistence.persistJournal(
                        SeasonMoneyJournalEngine.haltForReview(
                            record,
                            SeasonMoneyReviewReason.INTERRUPTED_WITHDRAWAL,
                            "restart_after_withdrawal_intent",
                            record.balanceAfterMinor,
                            record.providerCallAttempted,
                            clock(),
                        ),
                    )
                    reviewed += 1
                }
                SeasonMoneyJournalStatus.FUNDS_WITHDRAWN -> {
                    val receipt = state.recentReceipts[record.actionId]
                    if (receipt != null) {
                        persistence.persistJournal(SeasonMoneyJournalEngine.confirmStateCommitted(record, clock()))
                        committed += 1
                    } else {
                        val plan = record.toAcceptedPlan()
                        val result = runCatching { SeasonMoneyActionEngine.commit(catalog, state, plan, clock()) }
                        if (result.isSuccess) {
                            state = result.getOrThrow().state
                            persistence.persistState(state)
                            persistence.persistJournal(SeasonMoneyJournalEngine.confirmStateCommitted(record, clock()))
                            committed += 1
                        } else {
                            persistence.persistJournal(
                                SeasonMoneyJournalEngine.haltForReview(
                                    record,
                                    SeasonMoneyReviewReason.STATE_EVIDENCE_CONFLICT,
                                    "state_commit_conflict",
                                    record.balanceAfterMinor,
                                    record.providerCallAttempted,
                                    clock(),
                                ),
                            )
                            reviewed += 1
                        }
                    }
                }
                SeasonMoneyJournalStatus.STATE_COMMITTED,
                SeasonMoneyJournalStatus.CANCELLED,
                SeasonMoneyJournalStatus.MANUAL_REVIEW,
                -> Unit
            }
        }
        return SeasonMoneyRecoverySummary(cancelled, committed, reviewed)
    }

    suspend fun bindAdmissions(
        catalog: ObserveSeasonCatalog,
        dungeonContractId: String,
        runId: String,
        participantIds: Set<String>,
        now: Long,
    ): DungeonAdmissionBindingResult {
        val current = loadState(catalog) ?: throw IllegalStateException("Season runtime state is unavailable")
        val result =
            SeasonMoneyActionEngine.bindAvailableAdmissions(
                catalog,
                current,
                dungeonContractId,
                runId,
                participantIds,
                now,
            )
        if (result.state != current) persistence.persistState(result.state)
        return result
    }

    suspend fun reserveDungeonLaunch(
        catalog: ObserveSeasonCatalog,
        dungeonContractId: String,
        participantIds: Set<String>,
        now: Long,
    ): SeasonDungeonLaunchReservation {
        val loaded = loadState(catalog) ?: throw IllegalStateException("Season runtime state is unavailable")
        val current = dungeonLaunchGate.releaseExpired(catalog, loaded, now)
        val result = dungeonLaunchGate.reserve(catalog, current, dungeonContractId, participantIds, now)
        persistence.persistState(result.state)
        return result
    }

    /** Persists token consumption before the caller permits native world cloning. */
    suspend fun authorizeDungeonInstance(
        catalog: ObserveSeasonCatalog,
        blueprintWorld: String,
        instanceWorld: String,
        now: Long,
    ): SeasonDungeonInstanceAuthorizationResult? {
        val loaded = loadState(catalog) ?: throw IllegalStateException("Season runtime state is unavailable")
        val current = dungeonLaunchGate.releaseExpired(catalog, loaded, now)
        if (current != loaded) persistence.persistState(current)
        val result = dungeonLaunchGate.authorizeInstance(catalog, current, blueprintWorld, instanceWorld, now)
            ?: return null
        persistence.persistState(result.state)
        return result
    }

    suspend fun cancelDungeonLaunch(
        catalog: ObserveSeasonCatalog,
        tokenId: String,
        now: Long,
    ): SeasonRuntimeState {
        val current = loadState(catalog) ?: throw IllegalStateException("Season runtime state is unavailable")
        val next = dungeonLaunchGate.cancel(catalog, current, tokenId, now)
        if (next != current) persistence.persistState(next)
        return next
    }

    suspend fun cancelAuthorizedDungeonInstance(
        catalog: ObserveSeasonCatalog,
        instanceWorld: String,
    ): SeasonRuntimeState {
        val current = loadState(catalog) ?: throw IllegalStateException("Season runtime state is unavailable")
        val next = dungeonLaunchGate.cancelAuthorizedRun(catalog, current, instanceWorld)
        if (next != current) persistence.persistState(next)
        return next
    }

    suspend fun recoverDungeonLaunches(
        catalog: ObserveSeasonCatalog,
        activeInstanceWorlds: Set<String>,
        now: Long,
    ): SeasonRuntimeState {
        val loaded = loadState(catalog) ?: throw IllegalStateException("Season runtime state is unavailable")
        val withoutExpired = dungeonLaunchGate.releaseExpired(catalog, loaded, now)
        val recovered = dungeonLaunchGate.releaseMissingAuthorizedRuns(catalog, withoutExpired, activeInstanceWorlds)
        if (recovered != loaded) persistence.persistState(recovered)
        return recovered
    }

    suspend fun consumeAuthorizedDungeonAdmissions(
        catalog: ObserveSeasonCatalog,
        instanceWorld: String,
        now: Long,
    ): SeasonRuntimeState {
        val current = loadState(catalog) ?: throw IllegalStateException("Season runtime state is unavailable")
        val next = dungeonLaunchGate.consumeAuthorizedRunAdmissions(catalog, current, instanceWorld, now)
        if (next != current) persistence.persistState(next)
        return next
    }

    suspend fun finishAuthorizedDungeonRun(
        catalog: ObserveSeasonCatalog,
        instanceWorld: String,
    ): SeasonRuntimeState {
        val current = loadState(catalog) ?: throw IllegalStateException("Season runtime state is unavailable")
        val next = dungeonLaunchGate.finishAuthorizedRun(catalog, current, instanceWorld)
        if (next != current) persistence.persistState(next)
        return next
    }

    suspend fun consumeAdmissions(
        catalog: ObserveSeasonCatalog,
        dungeonContractId: String,
        runId: String,
        playerIds: Set<String>,
        now: Long,
        instanceWorld: String? = null,
    ): DungeonAdmissionBindingResult {
        val current = loadState(catalog) ?: throw IllegalStateException("Season runtime state is unavailable")
        val result =
            SeasonMoneyActionEngine.consumeBoundAdmissions(
                catalog,
                current,
                dungeonContractId,
                runId,
                playerIds,
                now,
            )
        val finalState =
            if (instanceWorld == null) {
                result.state
            } else {
                dungeonLaunchGate.finishAuthorizedRun(catalog, result.state, instanceWorld)
            }
        if (finalState != current) persistence.persistState(finalState)
        return result.copy(state = finalState)
    }

    /** Commits already adjudicated provider evidence without making another provider call. */
    suspend fun commitReconciled(
        catalog: ObserveSeasonCatalog,
        reconciled: SeasonMoneyJournalRecord,
    ): SeasonMoneyActionOutcome {
        val valid = reconciled.validated()
        require(valid.status == SeasonMoneyJournalStatus.FUNDS_WITHDRAWN) {
            "Reconciled season money action does not contain proven funds"
        }
        require(
            valid.reconciliation?.resolution == SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED,
        ) { "Season money action lacks confirmed reconciliation evidence" }
        val current = loadState(catalog) ?: return SeasonMoneyActionOutcome.ManualReview(valid.actionId)
        val rebasedPlan = valid.toAcceptedPlan().copy(expectedStateRevision = current.revision)
        val result =
            runCatching { SeasonMoneyActionEngine.commit(catalog, current, rebasedPlan, clock()) }
                .getOrElse { return SeasonMoneyActionOutcome.ManualReview(valid.actionId) }
        if (result.changed) {
            try {
                persistence.persistState(result.state)
            } catch (_: Throwable) {
                return SeasonMoneyActionOutcome.ManualReview(valid.actionId)
            }
        }
        val committed = SeasonMoneyJournalEngine.confirmStateCommitted(valid, clock())
        return if (persistJournal(committed)) {
            SeasonMoneyActionOutcome.Committed(result.receipt)
        } else {
            SeasonMoneyActionOutcome.ManualReview(valid.actionId)
        }
    }

    private suspend fun commit(
        catalog: ObserveSeasonCatalog,
        withdrawn: SeasonMoneyJournalRecord,
    ): SeasonMoneyActionOutcome {
        val current = loadState(catalog) ?: return SeasonMoneyActionOutcome.ManualReview(withdrawn.actionId)
        val result =
            try {
                SeasonMoneyActionEngine.commit(catalog, current, withdrawn.toAcceptedPlan(), clock())
            } catch (_: Throwable) {
                persistJournal(
                    SeasonMoneyJournalEngine.haltForReview(
                        withdrawn,
                        SeasonMoneyReviewReason.STATE_EVIDENCE_CONFLICT,
                        "state_commit_conflict",
                        withdrawn.balanceAfterMinor,
                        withdrawn.providerCallAttempted,
                        clock(),
                    ),
                )
                return SeasonMoneyActionOutcome.ManualReview(withdrawn.actionId)
            }
        try {
            persistence.persistState(result.state)
        } catch (_: Throwable) {
            return SeasonMoneyActionOutcome.ManualReview(withdrawn.actionId)
        }
        val committed = SeasonMoneyJournalEngine.confirmStateCommitted(withdrawn, clock())
        return if (persistJournal(committed)) {
            SeasonMoneyActionOutcome.Committed(result.receipt)
        } else {
            SeasonMoneyActionOutcome.ManualReview(withdrawn.actionId)
        }
    }

    private fun loadState(catalog: ObserveSeasonCatalog): SeasonRuntimeState? =
        runCatching { persistence.state(catalog).validatedAgainst(catalog) }.getOrNull()

    private fun loadJournal(): List<SeasonMoneyJournalRecord>? =
        runCatching {
            persistence.journalRecords().onEach { it.validated() }.also(SeasonMoneyJournalAudit::summarize)
        }.getOrNull()

    private suspend fun persistJournal(record: SeasonMoneyJournalRecord): Boolean =
        try {
            persistence.persistJournal(record.validated())
            true
        } catch (_: Throwable) {
            false
        }

    private suspend fun pruneTerminalRecords(
        records: List<SeasonMoneyJournalRecord>,
    ): List<SeasonMoneyJournalRecord>? {
        val terminal = records.filter { it.status in TERMINAL_STATUSES }
        val delete =
            terminal.sortedWith(compareBy<SeasonMoneyJournalRecord> { it.updatedAt }.thenBy { it.actionId })
                .take((terminal.size - MAX_RETAINED_TERMINAL_RECORDS).coerceAtLeast(0))
        if (delete.isEmpty()) return records
        return try {
            delete.forEach { persistence.deleteJournal(it.actionId) }
            val deleted = delete.mapTo(mutableSetOf()) { it.actionId }
            records.filterNot { it.actionId in deleted }
        } catch (_: Throwable) {
            null
        }
    }

    private fun SeasonMoneyJournalRecord.toAcceptedPlan(): SeasonMoneyActionPlan.Accepted =
        SeasonMoneyActionPlan.Accepted(
            actionId = actionId,
            kind = kind,
            targetId = targetId,
            playerId = playerId,
            amountMinor = amountMinor,
            expectedStateRevision = expectedStateRevision,
            catalogDigest = catalogDigest,
            plannedAt = createdAt,
        )

    private fun stableEvidence(value: String?, fallback: String): String =
        value?.takeIf(SeasonMoneyJournalRecord::validEvidence) ?: fallback

    companion object {
        private val TERMINAL_STATUSES =
            setOf(
                SeasonMoneyJournalStatus.STATE_COMMITTED,
                SeasonMoneyJournalStatus.CANCELLED,
            )
        private const val MAX_RETAINED_TERMINAL_RECORDS = 2_048
    }
}

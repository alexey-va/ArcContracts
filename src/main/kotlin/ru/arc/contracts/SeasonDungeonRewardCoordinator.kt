package ru.arc.contracts

interface SeasonDungeonRewardPersistence {
    fun state(catalog: ObserveSeasonCatalog): SeasonRuntimeState

    fun journalRecords(): List<SeasonDungeonRewardJournalRecord>

    suspend fun persistState(state: SeasonRuntimeState)

    suspend fun persistJournal(record: SeasonDungeonRewardJournalRecord)
}

interface PreparedSeasonDungeonTrophyDelivery {
    suspend fun deliverExact(): ContractInventoryMutation
}

interface SeasonDungeonTrophyDeliveryGateway {
    suspend fun createPayload(playerId: String, itemKey: String): EscrowedItemPayload

    suspend fun prepareDelivery(
        playerId: String,
        payload: EscrowedItemPayload,
    ): PreparedSeasonDungeonTrophyDelivery?
}

sealed interface SeasonDungeonRewardOutcome {
    data class Committed(val receipt: SeasonDungeonRewardReceipt) : SeasonDungeonRewardOutcome

    data class Duplicate(val receipt: SeasonDungeonRewardReceipt) : SeasonDungeonRewardOutcome

    data class Rejected(val rejections: Set<SeasonDungeonRewardRejection>) : SeasonDungeonRewardOutcome

    data class Pending(val rewardId: String, val status: SeasonDungeonRewardJournalStatus) : SeasonDungeonRewardOutcome

    data class ManualReview(val rewardId: String) : SeasonDungeonRewardOutcome

    data class Unavailable(val rewardId: String) : SeasonDungeonRewardOutcome
}

class SeasonDungeonRewardCoordinator(
    private val persistence: SeasonDungeonRewardPersistence,
    private val payment: ContractPaymentGateway,
    private val trophies: SeasonDungeonTrophyDeliveryGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun deliver(
        catalog: ObserveSeasonCatalog,
        authorization: SeasonDungeonRunAuthorization,
        playerId: String,
        activeShare: Double,
        now: Long = clock(),
    ): SeasonDungeonRewardOutcome {
        val state = loadState(catalog) ?: return SeasonDungeonRewardOutcome.Unavailable("reward-unavailable")
        val planned =
            runCatching {
                SeasonDungeonRewardEngine.plan(catalog, state, authorization, playerId, activeShare, now)
            }.getOrElse { return SeasonDungeonRewardOutcome.Unavailable("reward-unavailable") }
        when (planned) {
            is SeasonDungeonRewardPlan.Duplicate -> return SeasonDungeonRewardOutcome.Duplicate(planned.receipt)
            is SeasonDungeonRewardPlan.Rejected -> return SeasonDungeonRewardOutcome.Rejected(planned.rejections)
            is SeasonDungeonRewardPlan.Accepted -> Unit
        }
        existing(planned.rewardId)?.let { return resume(catalog, it.rewardId) }
        val records = loadJournal() ?: return SeasonDungeonRewardOutcome.Unavailable(planned.rewardId)
        if (records.size >= SeasonDungeonRewardJournalAudit.MAX_NETWORK_RECORDS) {
            return SeasonDungeonRewardOutcome.Rejected(setOf(SeasonDungeonRewardRejection.RECEIPT_CAPACITY_REACHED))
        }
        val payload =
            runCatching { trophies.createPayload(planned.playerId, planned.trophyItemKey) }
                .getOrElse { return SeasonDungeonRewardOutcome.Unavailable(planned.rewardId) }
        val prepared =
            runCatching { SeasonDungeonRewardJournalEngine.prepare(planned, payload, clock()) }
                .getOrElse { return SeasonDungeonRewardOutcome.Unavailable(planned.rewardId) }
        if (!persistJournal(prepared)) return SeasonDungeonRewardOutcome.Unavailable(planned.rewardId)
        return resume(catalog, planned.rewardId)
    }

    suspend fun resume(catalog: ObserveSeasonCatalog, rewardId: String): SeasonDungeonRewardOutcome {
        val record = existing(rewardId) ?: return SeasonDungeonRewardOutcome.Unavailable(rewardId)
        val state = loadState(catalog) ?: return SeasonDungeonRewardOutcome.Unavailable(rewardId)
        state.recentDungeonRewardReceipts[rewardId]?.let { receipt ->
            if (record.status == SeasonDungeonRewardJournalStatus.STATE_COMMITTED) {
                return SeasonDungeonRewardOutcome.Duplicate(receipt.validated())
            }
            if (record.status == SeasonDungeonRewardJournalStatus.TROPHY_DELIVERED) {
                val committed = SeasonDungeonRewardJournalEngine.confirmStateCommitted(record, clock())
                return if (persistJournal(committed)) {
                    SeasonDungeonRewardOutcome.Duplicate(receipt.validated())
                } else {
                    SeasonDungeonRewardOutcome.ManualReview(rewardId)
                }
            }
            return SeasonDungeonRewardOutcome.ManualReview(rewardId)
        }
        return when (record.status) {
            SeasonDungeonRewardJournalStatus.PREPARED -> pay(catalog, record)
            SeasonDungeonRewardJournalStatus.PAID -> deliverTrophy(catalog, record)
            SeasonDungeonRewardJournalStatus.TROPHY_DELIVERED -> commit(catalog, record)
            SeasonDungeonRewardJournalStatus.STATE_COMMITTED ->
                SeasonDungeonRewardOutcome.ManualReview(rewardId)
            SeasonDungeonRewardJournalStatus.CANCELLED ->
                SeasonDungeonRewardOutcome.Pending(rewardId, record.status)
            SeasonDungeonRewardJournalStatus.PAYMENT_STARTED,
            SeasonDungeonRewardJournalStatus.TROPHY_DELIVERY_STARTED,
            SeasonDungeonRewardJournalStatus.MANUAL_REVIEW,
            -> SeasonDungeonRewardOutcome.ManualReview(rewardId)
        }
    }

    suspend fun resumePlayer(catalog: ObserveSeasonCatalog, playerId: String): List<SeasonDungeonRewardOutcome> {
        val pending = loadJournal().orEmpty().asSequence()
            .filter { it.playerId == playerId && it.status == SeasonDungeonRewardJournalStatus.PAID }
            .sortedWith(compareBy<SeasonDungeonRewardJournalRecord> { it.createdAt }.thenBy { it.rewardId })
            .toList()
        val outcomes = mutableListOf<SeasonDungeonRewardOutcome>()
        pending.forEach { outcomes += resume(catalog, it.rewardId) }
        return outcomes
    }

    suspend fun recover(catalog: ObserveSeasonCatalog): SeasonDungeonRewardJournalSummary {
        val records = loadJournal() ?: return SeasonDungeonRewardJournalSummary.unavailable()
        records.forEach { record ->
            when (record.status) {
                SeasonDungeonRewardJournalStatus.PAYMENT_STARTED,
                SeasonDungeonRewardJournalStatus.TROPHY_DELIVERY_STARTED,
                -> persistJournal(SeasonDungeonRewardJournalEngine.recoverInterrupted(record, clock()))
                SeasonDungeonRewardJournalStatus.PREPARED,
                SeasonDungeonRewardJournalStatus.PAID,
                SeasonDungeonRewardJournalStatus.TROPHY_DELIVERED,
                -> resume(catalog, record.rewardId)
                SeasonDungeonRewardJournalStatus.CANCELLED,
                SeasonDungeonRewardJournalStatus.STATE_COMMITTED,
                SeasonDungeonRewardJournalStatus.MANUAL_REVIEW,
                -> Unit
            }
        }
        return loadJournal()?.let(SeasonDungeonRewardJournalAudit::summarize)
            ?: SeasonDungeonRewardJournalSummary.unavailable()
    }

    private suspend fun pay(
        catalog: ObserveSeasonCatalog,
        prepared: SeasonDungeonRewardJournalRecord,
    ): SeasonDungeonRewardOutcome {
        val balanceBefore = runCatching { payment.balanceMinor(prepared.playerId) }.getOrNull()
            ?: return SeasonDungeonRewardOutcome.Pending(prepared.rewardId, prepared.status)
        val started = SeasonDungeonRewardJournalEngine.beginPayment(prepared, balanceBefore, clock())
        if (!persistJournal(started)) return SeasonDungeonRewardOutcome.Unavailable(prepared.rewardId)
        val evidence =
            try {
                payment.deposit(started.playerId, started.payoutMinor, started.paymentReason)
            } catch (_: Throwable) {
                ContractPaymentEvidence(providerAccepted = null, balanceAfterMinor = null)
            }
        val exactAfter = runCatching { Math.addExact(balanceBefore, started.payoutMinor) }.getOrNull()
        if (evidence.providerAccepted == true && exactAfter != null && evidence.balanceAfterMinor == exactAfter) {
            val paid =
                SeasonDungeonRewardJournalEngine.confirmPaid(
                    started,
                    exactAfter,
                    evidence.transactionId,
                    clock(),
                )
            if (!persistJournal(paid)) return SeasonDungeonRewardOutcome.ManualReview(started.rewardId)
            return deliverTrophy(catalog, paid)
        }
        val reason =
            if (evidence.providerAccepted == false && evidence.balanceAfterMinor == balanceBefore) {
                SeasonDungeonRewardReviewReason.PROVIDER_REJECTED
            } else {
                SeasonDungeonRewardReviewReason.PROVIDER_EVIDENCE_CONFLICT
            }
        val halted =
            SeasonDungeonRewardJournalEngine.haltPayment(
                started,
                reason,
                evidence.failureCode ?: "provider_result_not_exact",
                evidence.balanceAfterMinor,
                clock(),
            )
        persistJournal(halted)
        return SeasonDungeonRewardOutcome.ManualReview(started.rewardId)
    }

    private suspend fun deliverTrophy(
        catalog: ObserveSeasonCatalog,
        paid: SeasonDungeonRewardJournalRecord,
    ): SeasonDungeonRewardOutcome {
        if (paid.trophyDeliveryAttempts >= SeasonDungeonRewardJournalRecord.MAX_DELIVERY_ATTEMPTS) {
            val halted =
                SeasonDungeonRewardJournalEngine.haltPaid(
                    paid,
                    SeasonDungeonRewardReviewReason.TROPHY_DELIVERY_ATTEMPT_LIMIT,
                    "trophy_delivery_attempt_limit_reached",
                    clock(),
                )
            persistJournal(halted)
            return SeasonDungeonRewardOutcome.ManualReview(paid.rewardId)
        }
        val prepared =
            runCatching { trophies.prepareDelivery(paid.playerId, paid.trophyPayload) }.getOrNull()
                ?: return SeasonDungeonRewardOutcome.Pending(paid.rewardId, paid.status)
        val started = SeasonDungeonRewardJournalEngine.beginTrophyDelivery(paid, clock())
        if (!persistJournal(started)) return SeasonDungeonRewardOutcome.ManualReview(paid.rewardId)
        return when (val mutation = runCatching { prepared.deliverExact() }.getOrDefault(ContractInventoryMutation.Ambiguous)) {
            ContractInventoryMutation.Confirmed -> {
                val delivered = SeasonDungeonRewardJournalEngine.confirmTrophyDelivered(started, clock())
                if (!persistJournal(delivered)) {
                    SeasonDungeonRewardOutcome.ManualReview(started.rewardId)
                } else {
                    commit(catalog, delivered)
                }
            }
            is ContractInventoryMutation.NotPerformed -> {
                val retryable =
                    SeasonDungeonRewardJournalEngine.confirmTrophyNotDelivered(started, mutation.code, clock())
                if (persistJournal(retryable)) {
                    SeasonDungeonRewardOutcome.Pending(retryable.rewardId, retryable.status)
                } else {
                    SeasonDungeonRewardOutcome.ManualReview(started.rewardId)
                }
            }
            ContractInventoryMutation.Ambiguous -> {
                persistJournal(
                    SeasonDungeonRewardJournalEngine.haltTrophyDelivery(
                        started,
                        SeasonDungeonRewardReviewReason.TROPHY_DELIVERY_AMBIGUOUS,
                        "inventory_result_not_exact",
                        clock(),
                    ),
                )
                SeasonDungeonRewardOutcome.ManualReview(started.rewardId)
            }
        }
    }

    private suspend fun commit(
        catalog: ObserveSeasonCatalog,
        delivered: SeasonDungeonRewardJournalRecord,
    ): SeasonDungeonRewardOutcome {
        val current = loadState(catalog) ?: return SeasonDungeonRewardOutcome.ManualReview(delivered.rewardId)
        val result =
            runCatching {
                SeasonDungeonRewardEngine.commit(
                    catalog,
                    current,
                    delivered.toPlan(expectedRevision = current.revision),
                    clock(),
                )
            }.getOrElse {
                persistJournal(
                    SeasonDungeonRewardJournalEngine.haltStateCommit(
                        delivered,
                        "reward_receipt_does_not_match_current_state",
                        clock(),
                    ),
                )
                return SeasonDungeonRewardOutcome.ManualReview(delivered.rewardId)
            }
        if (result.changed && !persistState(result.state)) {
            return SeasonDungeonRewardOutcome.ManualReview(delivered.rewardId)
        }
        val committed = SeasonDungeonRewardJournalEngine.confirmStateCommitted(delivered, clock())
        return if (persistJournal(committed)) {
            SeasonDungeonRewardOutcome.Committed(result.receipt)
        } else {
            SeasonDungeonRewardOutcome.ManualReview(delivered.rewardId)
        }
    }

    private fun loadState(catalog: ObserveSeasonCatalog): SeasonRuntimeState? =
        runCatching { persistence.state(catalog).validatedAgainst(catalog) }.getOrNull()

    private fun loadJournal(): List<SeasonDungeonRewardJournalRecord>? =
        runCatching {
            persistence.journalRecords().onEach { it.validated() }.also(SeasonDungeonRewardJournalAudit::summarize)
        }.getOrNull()

    private fun existing(rewardId: String): SeasonDungeonRewardJournalRecord? =
        loadJournal()?.firstOrNull { it.rewardId == rewardId }

    private suspend fun persistState(state: SeasonRuntimeState): Boolean =
        runCatching { persistence.persistState(state) }.isSuccess

    private suspend fun persistJournal(record: SeasonDungeonRewardJournalRecord): Boolean =
        runCatching { persistence.persistJournal(record.validated()) }.isSuccess
}

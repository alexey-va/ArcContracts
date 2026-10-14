package ru.arc.contracts

/** Persistence boundary whose write methods return only after Redis durability. */
interface ContractSubmissionPersistence {
    fun contractState(definition: ResourceContractDefinition): ResourceContractState

    fun journalRecords(): List<ContractSubmissionJournalRecord>

    suspend fun persistJournal(record: ContractSubmissionJournalRecord)

    suspend fun persistContract(
        definition: ResourceContractDefinition,
        state: ResourceContractState,
    )
}

/** One main-thread inventory snapshot kept only for the duration of a submission. */
interface PreparedContractInventory {
    val payloads: List<EscrowedItemPayload>

    /** Evaluate the final precondition on the inventory thread immediately before changing any slot. */
    suspend fun removeExact(canRemove: () -> Boolean = { true }): ContractInventoryMutation

    suspend fun restoreExact(): ContractInventoryMutation
}

interface ContractInventoryGateway {
    /** Deposit desks restrict the transaction to the stacks actually offered. */
    suspend fun prepareFromSlots(
        playerId: String, itemKey: String, quantity: Int, contractGroup: String, slots: Set<Int>,
    ): PreparedContractInventory? = null

    suspend fun prepare(
        playerId: String,
        itemKey: String,
        quantity: Int,
    ): PreparedContractInventory?

    /** Contract submissions pass the group so the final inventory recheck cannot downgrade the gate. */
    suspend fun prepare(
        playerId: String,
        itemKey: String,
        quantity: Int,
        contractGroup: String,
    ): PreparedContractInventory? = prepare(playerId, itemKey, quantity)
}

sealed interface ContractInventoryMutation {
    data object Confirmed : ContractInventoryMutation

    /** Adapter proved that no slot was changed. */
    data class NotPerformed(val code: String) : ContractInventoryMutation

    /** Adapter cannot prove whether the complete mutation happened. */
    data object Ambiguous : ContractInventoryMutation
}

interface ContractPaymentGateway {
    suspend fun balanceMinor(playerId: String): Long?

    /** Must make at most one provider deposit call and confirm its remote result before reporting success. */
    suspend fun deposit(
        playerId: String,
        amountMinor: Long,
        reason: String,
    ): ContractPaymentEvidence
}

data class ContractPaymentEvidence(
    /** true=confirmed provider success, false=explicit provider failure, null=ambiguous call outcome. */
    val providerAccepted: Boolean?,
    val balanceAfterMinor: Long?,
    val failureCode: String? = null,
    val transactionId: String? = null,
)

sealed interface ContractSubmissionOutcome {
    data class Committed(val receipt: ContractSubmissionReceipt) : ContractSubmissionOutcome

    data class Duplicate(val receipt: ContractSubmissionReceipt) : ContractSubmissionOutcome

    data class Rejected(val reason: SubmissionRejection) : ContractSubmissionOutcome

    data class Cancelled(val submissionId: String, val code: String) : ContractSubmissionOutcome

    data class Refunded(val submissionId: String, val code: String) : ContractSubmissionOutcome

    data class ManualReview(val submissionId: String) : ContractSubmissionOutcome

    data class Unavailable(val submissionId: String) : ContractSubmissionOutcome
}

data class ContractPaidRecovery(
    val commit: ContractCommitResult,
    val journal: ContractSubmissionJournalRecord,
)

/** Restart-safe completion of the idempotent state commit after a proven payout. */
object ContractSubmissionRecoveryEngine {
    fun recoverPaid(
        definition: ResourceContractDefinition,
        state: ResourceContractState,
        paid: ContractSubmissionJournalRecord,
        now: Long,
    ): ContractPaidRecovery {
        require(paid.status == ContractSubmissionJournalStatus.PAID) { "Journal does not contain a proven payout" }
        require(paid.contractId == definition.id && paid.contractWindowStartsAt == definition.windowStartsAt) {
            "Paid journal does not match contract policy"
        }
        val commit =
            ResourceContractEngine.commitReserved(
                definition,
                state.validatedAgainst(definition),
                requireNotNull(paid.quotaReservation()),
                now,
            )
        return ContractPaidRecovery(
            commit,
            ContractSubmissionJournalEngine.confirmContractCommitted(paid, now),
        )
    }
}

/**
 * Serial submission state machine. The caller owns serialization and thread
 * switching; this coordinator owns durable-before-side-effect ordering.
 */
class ContractSubmissionCoordinator(
    private val persistence: ContractSubmissionPersistence,
    private val inventory: ContractInventoryGateway,
    private val payment: ContractPaymentGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun submit(
        definition: ResourceContractDefinition,
        submissionId: String,
        playerId: String,
        requestedQuantity: Int,
        policy: ContractRankPolicy = ContractRankPolicy.IDENTITY,
        quote: ContractSubmissionQuote? = null,
        availableNetworkBudgetMinor: Long = Long.MAX_VALUE,
        inventorySlots: Set<Int>? = null,
        offeredInventory: PreparedContractInventory? = null,
    ): ContractSubmissionOutcome {
        // Food orders accept only the native slots explicitly offered at the desk.
        if (definition.group == "food_orders" && inventorySlots.isNullOrEmpty() && offeredInventory == null) {
            return ContractSubmissionOutcome.Rejected(SubmissionRejection.INVENTORY_UNAVAILABLE)
        }
        val records =
            try {
                persistence.journalRecords().onEach { it.validated() }
            } catch (_: Throwable) {
                return ContractSubmissionOutcome.Unavailable(submissionId)
            }
        records.firstOrNull { it.submissionId == submissionId }?.let { existing ->
            val receipt =
                runCatching { persistence.contractState(definition).recentReceipts[submissionId] }
                    .getOrNull()
            return if (receipt != null && existing.status == ContractSubmissionJournalStatus.CONTRACT_COMMITTED) {
                ContractSubmissionOutcome.Duplicate(receipt)
            } else {
                ContractSubmissionOutcome.Unavailable(submissionId)
            }
        }
        if (records.size >= ContractSubmissionJournalAudit.MAX_NETWORK_RECORDS) {
            return ContractSubmissionOutcome.Rejected(SubmissionRejection.JOURNAL_CAPACITY_REACHED)
        }

        val state =
            try {
                persistence.contractState(definition).validatedAgainst(definition)
            } catch (_: Throwable) {
                return ContractSubmissionOutcome.Unavailable(submissionId)
            }
        val reservations =
            records.asSequence()
                .filter { it.contractId == definition.id && it.contractWindowStartsAt == definition.windowStartsAt }
                .mapNotNull { it.quotaReservation() }
                .toList()
        val plan =
            try {
                ResourceContractEngine.plan(
                    definition = definition,
                    state = state,
                    submissionId = submissionId,
                    playerId = playerId,
                    requestedQuantity = requestedQuantity,
                    now = quote?.quotedAt ?: clock(),
                    reservations = reservations,
                    policy = policy,
                )
            } catch (_: Throwable) {
                return ContractSubmissionOutcome.Unavailable(submissionId)
            }
        when (plan) {
            is ContractSubmissionPlan.Duplicate -> return ContractSubmissionOutcome.Duplicate(plan.receipt)
            is ContractSubmissionPlan.Rejected -> return ContractSubmissionOutcome.Rejected(plan.reason)
            is ContractSubmissionPlan.Accepted -> Unit
        }
        if (quote != null && !quote.matches(definition, plan, clock())) {
            return ContractSubmissionOutcome.Rejected(SubmissionRejection.STALE_STATE)
        }
        if (plan.payoutMinor > availableNetworkBudgetMinor) {
            return ContractSubmissionOutcome.Rejected(SubmissionRejection.BUDGET_EXHAUSTED)
        }

        val preparedInventory =
            try {
                if (offeredInventory != null) {
                    offeredInventory
                } else if (inventorySlots == null) {
                    inventory.prepare(playerId, definition.itemKey, plan.acceptedQuantity.toInt(), definition.group)
                } else {
                    inventory.prepareFromSlots(playerId, definition.itemKey, plan.acceptedQuantity.toInt(), definition.group, inventorySlots)
                }
            } catch (_: Throwable) {
                null
            } ?: return ContractSubmissionOutcome.Rejected(SubmissionRejection.INVENTORY_UNAVAILABLE)
        val prepared =
            try {
                ContractSubmissionJournalEngine.prepare(definition, plan, preparedInventory.payloads, clock())
            } catch (_: Throwable) {
                return ContractSubmissionOutcome.Rejected(SubmissionRejection.INVENTORY_UNAVAILABLE)
            }
        if (!persistJournal(prepared)) return ContractSubmissionOutcome.Unavailable(submissionId)

        val removalStarted = ContractSubmissionJournalEngine.beginItemRemoval(prepared, clock())
        if (!persistJournal(removalStarted)) return ContractSubmissionOutcome.Unavailable(submissionId)
        when (val removal = safeInventoryMutation {
            preparedInventory.removeExact {
                val now = clock()
                quote?.matches(definition, plan, now) ?: definition.isOpenAt(now)
            }
        }) {
            ContractInventoryMutation.Confirmed -> Unit
            is ContractInventoryMutation.NotPerformed -> {
                val cancelled =
                    ContractSubmissionJournalEngine.confirmNoItemsRemoved(
                        removalStarted,
                        if (removal.code == "submission_expired") "quote_expired_before_remove" else "inventory_changed_before_remove",
                        clock(),
                    )
                return if (!persistJournal(cancelled)) {
                    ContractSubmissionOutcome.Unavailable(submissionId)
                } else if (removal.code == "submission_expired") {
                    ContractSubmissionOutcome.Rejected(SubmissionRejection.STALE_STATE)
                } else {
                    ContractSubmissionOutcome.Cancelled(submissionId, cancelled.cancellationCode!!)
                }
            }
            ContractInventoryMutation.Ambiguous -> {
                val review = ContractSubmissionJournalEngine.haltAmbiguousItemRemoval(removalStarted, clock())
                return if (persistJournal(review)) {
                    ContractSubmissionOutcome.ManualReview(submissionId)
                } else {
                    ContractSubmissionOutcome.Unavailable(submissionId)
                }
            }
        }

        val escrowed = ContractSubmissionJournalEngine.confirmItemsEscrowed(removalStarted, clock())
        if (!persistJournal(escrowed)) return ContractSubmissionOutcome.ManualReview(submissionId)

        val balanceBefore = runCatching { payment.balanceMinor(playerId) }.getOrNull()
        if (balanceBefore == null) {
            return refund(preparedInventory, escrowed, "provider_balance_unavailable")
        }
        val paymentStarted = ContractSubmissionJournalEngine.beginPayment(escrowed, balanceBefore, clock())
        if (!persistJournal(paymentStarted)) return ContractSubmissionOutcome.ManualReview(submissionId)

        val evidence =
            try {
                payment.deposit(playerId, plan.payoutMinor, paymentStarted.payoutReason)
            } catch (_: Throwable) {
                ContractPaymentEvidence(providerAccepted = null, balanceAfterMinor = null)
            }
        val exactPaidBalance = runCatching { Math.addExact(balanceBefore, plan.payoutMinor) }.getOrNull()
        if (evidence.providerAccepted == true && exactPaidBalance != null && evidence.balanceAfterMinor == exactPaidBalance) {
            val paid =
                ContractSubmissionJournalEngine.confirmPaid(
                    paymentStarted,
                    exactPaidBalance,
                    evidence.transactionId,
                    clock(),
                )
            if (!persistJournal(paid)) return ContractSubmissionOutcome.ManualReview(submissionId)
            return commit(definition, paid)
        }
        if (evidence.providerAccepted == false && evidence.balanceAfterMinor == balanceBefore) {
            val failed =
                ContractSubmissionJournalEngine.confirmPaymentFailed(
                    paymentStarted,
                    balanceBefore,
                    stableFailureCode(evidence.failureCode),
                    clock(),
                )
            if (!persistJournal(failed)) return ContractSubmissionOutcome.ManualReview(submissionId)
            return refund(preparedInventory, failed, "provider_rejected")
        }

        val review =
            ContractSubmissionJournalEngine.haltAmbiguousPayment(
                paymentStarted,
                evidence.balanceAfterMinor,
                clock(),
            )
        return if (persistJournal(review)) {
            ContractSubmissionOutcome.ManualReview(submissionId)
        } else {
            ContractSubmissionOutcome.Unavailable(submissionId)
        }
    }

    private suspend fun commit(
        definition: ResourceContractDefinition,
        paid: ContractSubmissionJournalRecord,
    ): ContractSubmissionOutcome {
        val state =
            try {
                persistence.contractState(definition).validatedAgainst(definition)
            } catch (_: Throwable) {
                return ContractSubmissionOutcome.ManualReview(paid.submissionId)
            }
        val committed =
            try {
                ResourceContractEngine.commitReserved(
                    definition,
                    state,
                    requireNotNull(paid.quotaReservation()),
                    clock(),
                )
            } catch (_: Throwable) {
                return ContractSubmissionOutcome.ManualReview(paid.submissionId)
            }
        try {
            persistence.persistContract(definition, committed.state)
        } catch (_: Throwable) {
            return ContractSubmissionOutcome.ManualReview(paid.submissionId)
        }
        val journalCommitted = ContractSubmissionJournalEngine.confirmContractCommitted(paid, clock())
        return if (persistJournal(journalCommitted)) {
            ContractSubmissionOutcome.Committed(committed.receipt)
        } else {
            ContractSubmissionOutcome.ManualReview(paid.submissionId)
        }
    }

    private suspend fun refund(
        preparedInventory: PreparedContractInventory,
        record: ContractSubmissionJournalRecord,
        code: String,
    ): ContractSubmissionOutcome {
        val refundStarted = ContractSubmissionJournalEngine.beginRefund(record, clock())
        if (!persistJournal(refundStarted)) return ContractSubmissionOutcome.ManualReview(record.submissionId)
        return when (safeInventoryMutation { preparedInventory.restoreExact() }) {
            ContractInventoryMutation.Confirmed -> {
                val refunded = ContractSubmissionJournalEngine.confirmRefunded(refundStarted, clock())
                if (persistJournal(refunded)) {
                    ContractSubmissionOutcome.Refunded(record.submissionId, code)
                } else {
                    ContractSubmissionOutcome.ManualReview(record.submissionId)
                }
            }
            is ContractInventoryMutation.NotPerformed,
            ContractInventoryMutation.Ambiguous,
            -> {
                val review = ContractSubmissionJournalEngine.haltAmbiguousRefund(refundStarted, clock())
                if (persistJournal(review)) {
                    ContractSubmissionOutcome.ManualReview(record.submissionId)
                } else {
                    ContractSubmissionOutcome.Unavailable(record.submissionId)
                }
            }
        }
    }

    private suspend fun persistJournal(record: ContractSubmissionJournalRecord): Boolean =
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

    private fun stableFailureCode(raw: String?): String =
        raw?.lowercase()
            ?.replace(Regex("[^a-z0-9._-]+"), "_")
            ?.trim('_')
            ?.take(64)
            ?.takeIf { it.isNotEmpty() }
            ?: "provider_rejected"
}

package ru.arc.contracts

import ru.arc.repository.Entity
import java.security.MessageDigest
import java.util.Base64

enum class ContractSubmissionJournalStatus(val label: String) {
    PREPARED("prepared"),
    CANCELLED("cancelled"),
    ITEM_REMOVAL_STARTED("item_removal_started"),
    ITEMS_ESCROWED("items_escrowed"),
    PAYMENT_STARTED("payment_started"),
    PAYMENT_FAILED("payment_failed"),
    PAID("paid"),
    CONTRACT_COMMITTED("contract_committed"),
    REFUND_STARTED("refund_started"),
    REFUNDED("refunded"),
    MANUAL_REVIEW("manual_review"),
}

enum class ContractSubmissionReviewReason(val label: String) {
    INTERRUPTED_ITEM_REMOVAL("interrupted_item_removal"),
    INTERRUPTED_ESCROW("interrupted_escrow"),
    INTERRUPTED_PAYMENT("interrupted_payment"),
    INTERRUPTED_PAYMENT_FAILED("interrupted_payment_failed"),
    INTERRUPTED_REFUND("interrupted_refund"),
    INVENTORY_EVIDENCE_CONFLICT("inventory_evidence_conflict"),
    REFUND_EVIDENCE_CONFLICT("refund_evidence_conflict"),
    PROVIDER_EVIDENCE_CONFLICT("provider_evidence_conflict"),
}

/**
 * Opaque, restorable Paper item payload. The caller verifies the canonical
 * item identity before capture; the journal additionally protects the exact
 * serialized bytes with a SHA-256 digest and strict size limits.
 */
data class EscrowedItemPayload(
    val itemKey: String,
    val quantity: Int,
    val serializedBase64: String,
    val serializedSha256: String,
) {
    init {
        validated()
    }

    fun validated(): EscrowedItemPayload {
        require(ResourceContractDefinition.normalizeItemKey(itemKey) == itemKey) {
            "Escrow item key must be normalized"
        }
        require(ITEM_KEY_PATTERN.matches(itemKey) && itemKey.length <= MAX_ITEM_KEY_LENGTH) {
            "Invalid escrow item key"
        }
        require(quantity in 1..MAX_ITEM_QUANTITY) { "Invalid escrow item quantity" }
        require(serializedBase64.isNotEmpty() && serializedBase64.length <= MAX_BASE64_LENGTH) {
            "Escrow item payload is empty or too large"
        }
        require(SHA256_PATTERN.matches(serializedSha256)) { "Invalid escrow item payload digest" }
        val bytes = decodeBounded(serializedBase64)
        require(sha256(bytes) == serializedSha256) { "Escrow item payload digest mismatch" }
        return this
    }

    fun decodedBytes(): ByteArray {
        validated()
        return Base64.getDecoder().decode(serializedBase64)
    }

    companion object {
        const val MAX_SERIALIZED_BYTES = 65_536
        const val MAX_ITEM_QUANTITY = 2_304
        private const val MAX_ITEM_KEY_LENGTH = 128
        private const val MAX_BASE64_LENGTH = ((MAX_SERIALIZED_BYTES + 2) / 3) * 4
        private val ITEM_KEY_PATTERN = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        private val SHA256_PATTERN = Regex("[a-f0-9]{64}")

        fun capture(
            itemKey: String,
            quantity: Int,
            serializedBytes: ByteArray,
        ): EscrowedItemPayload {
            require(serializedBytes.isNotEmpty() && serializedBytes.size <= MAX_SERIALIZED_BYTES) {
                "Escrow item payload is empty or too large"
            }
            return EscrowedItemPayload(
                itemKey = ResourceContractDefinition.normalizeItemKey(itemKey),
                quantity = quantity,
                serializedBase64 = Base64.getEncoder().encodeToString(serializedBytes),
                serializedSha256 = sha256(serializedBytes),
            )
        }

        private fun decodeBounded(value: String): ByteArray {
            val bytes =
                try {
                    Base64.getDecoder().decode(value)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid escrow item payload encoding")
                }
            require(bytes.isNotEmpty() && bytes.size <= MAX_SERIALIZED_BYTES) {
                "Escrow item payload is empty or too large"
            }
            return bytes
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Write-ahead record for one player submission.
 *
 * Every non-idempotent inventory or provider mutation has an explicit
 * *_STARTED state that must be persisted before the external call. A restart
 * in such a state is never retried automatically: it is moved to
 * MANUAL_REVIEW, preventing duplicate item removal, payout, or refund.
 */
data class ContractSubmissionJournalRecord(
    val submissionId: String,
    val contractId: String,
    val contractWindowStartsAt: Long,
    val itemKey: String,
    val playerId: String,
    val requestedQuantity: Int,
    val acceptedQuantity: Long,
    val payoutMinor: Long,
    val expectedContractRevision: Long,
    val payoutReason: String,
    val itemPayloads: List<EscrowedItemPayload>,
    val status: ContractSubmissionJournalStatus = ContractSubmissionJournalStatus.PREPARED,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val cancelledAt: Long? = null,
    val cancellationCode: String? = null,
    val itemRemovalStartedAt: Long? = null,
    val itemsEscrowedAt: Long? = null,
    val paymentStartedAt: Long? = null,
    val providerBalanceBeforeMinor: Long? = null,
    val providerBalanceAfterMinor: Long? = null,
    val providerTransactionId: String? = null,
    val paymentFailureCode: String? = null,
    val paidAt: Long? = null,
    val contractCommittedAt: Long? = null,
    val refundStartedAt: Long? = null,
    val refundedAt: Long? = null,
    val reviewFromStatus: ContractSubmissionJournalStatus? = null,
    val reviewReason: ContractSubmissionReviewReason? = null,
    val reviewEvidence: String? = null,
    val reconciliation: ContractSubmissionReconciliation? = null,
    val revision: Long = 0L,
    val definitionSnapshot: ResourceContractDefinition? = null,
    val quoteSupplyBefore: Long? = null,
    val quotePlannedAt: Long? = null,
    val quotePayoutBasisPoints: Int? = null,
) : Entity {
    init {
        validated()
    }

    override fun id(): String = submissionId

    fun validated(): ContractSubmissionJournalRecord {
        require(ID_PATTERN.matches(submissionId)) { "Invalid journal submission id" }
        require(CONTRACT_ID_PATTERN.matches(contractId)) { "Invalid journal contract id" }
        require(contractWindowStartsAt >= 0L) { "Invalid journal contract window" }
        require(ResourceContractDefinition.normalizeItemKey(itemKey) == itemKey) {
            "Journal item key must be normalized"
        }
        require(playerId.isNotBlank() && playerId.length <= ResourceContractState.MAX_PLAYER_ID_LENGTH) {
            "Invalid journal player id"
        }
        require(playerId.none(Char::isISOControl)) { "Invalid journal player id" }
        require(requestedQuantity > 0 && acceptedQuantity in 1..requestedQuantity.toLong()) {
            "Invalid journal submission quantity"
        }
        require(payoutMinor > 0L && expectedContractRevision >= 0L) { "Invalid journal payout plan" }
        require(
            payoutReason == payoutReason(submissionId) &&
                payoutReason.length <= MAX_PAYOUT_REASON_LENGTH && payoutReason.none(Char::isISOControl),
        ) { "Invalid journal payout correlation reason" }
        require(itemPayloads.isNotEmpty() && itemPayloads.size <= MAX_ITEM_PAYLOADS) {
            "Invalid journal item payload count"
        }
        var totalQuantity = 0L
        var totalBytes = 0L
        itemPayloads.forEach { payload ->
            payload.validated()
            require(payload.itemKey == itemKey) { "Journal item payload identity mismatch" }
            totalQuantity = Math.addExact(totalQuantity, payload.quantity.toLong())
            totalBytes = Math.addExact(totalBytes, payload.decodedBytes().size.toLong())
        }
        require(totalQuantity == acceptedQuantity) { "Journal item payload quantity mismatch" }
        require(totalBytes <= MAX_TOTAL_SERIALIZED_BYTES) { "Journal item payloads exceed the total size limit" }
        require(createdAt >= 0L && updatedAt >= createdAt) { "Invalid journal timestamps" }
        require(revision >= 0L) { "Invalid journal revision" }
        validateOptionalText(providerTransactionId, "provider transaction id", MAX_PROVIDER_ID_LENGTH)
        validateOptionalText(paymentFailureCode, "payment failure code", MAX_EVIDENCE_LENGTH)
        validateOptionalText(reviewEvidence, "review evidence", MAX_EVIDENCE_LENGTH)
        reconciliation?.validated()
        validateTimestampOrder()
        validateStatusShape()
        validateReconciliationShape()
        definitionSnapshot?.let { definition ->
            require(definition.id == contractId && definition.windowStartsAt == contractWindowStartsAt && definition.itemKey == itemKey) {
                "Journal contract snapshot does not match submission"
            }
            require(ContractMarketPricing.payoutAllowed(definition, acceptedQuantity, payoutMinor)) {
                "Journal payout exceeds its immutable contract policy"
            }
            if (definition.dynamicPricing || quoteSupplyBefore != null || quotePlannedAt != null || quotePayoutBasisPoints != null) {
                val supply = requireNotNull(quoteSupplyBefore) { "Missing quoted supply" }
                val at = requireNotNull(quotePlannedAt) { "Missing quote timestamp" }
                val policy = ContractRankPolicy(payoutBasisPoints = requireNotNull(quotePayoutBasisPoints))
                require(definition.isOpenAt(at) && at <= createdAt) { "Quote is outside its contract window" }
                require(ContractMarketPricing.payoutMinor(definition, supply, acceptedQuantity, at, policy) == payoutMinor) {
                    "Journal payout does not equal its immutable marginal quote"
                }
            }
        }
        return this
    }

    fun isTerminal(): Boolean =
        status == ContractSubmissionJournalStatus.CANCELLED ||
            status == ContractSubmissionJournalStatus.CONTRACT_COMMITTED ||
            status == ContractSubmissionJournalStatus.REFUNDED

    fun hasConfirmedItemEscrow(): Boolean = itemsEscrowedAt != null

    fun quotaReservation(): ContractQuotaReservation? =
        if (isTerminal()) {
            null
        } else {
            ContractQuotaReservation(submissionId, playerId, acceptedQuantity, payoutMinor)
        }

    private fun validateTimestampOrder() {
        val timestamps =
            listOfNotNull(
                itemRemovalStartedAt,
                cancelledAt,
                itemsEscrowedAt,
                paymentStartedAt,
                paidAt,
                contractCommittedAt,
                refundStartedAt,
                refundedAt,
            )
        require(timestamps.all { it in createdAt..updatedAt }) { "Journal phase timestamp is out of bounds" }
        require(itemsEscrowedAt == null || itemRemovalStartedAt != null) { "Escrow confirmation lacks removal start" }
        require(cancelledAt == null || itemsEscrowedAt == null) { "Cancelled journal already escrowed items" }
        require(paymentStartedAt == null || itemsEscrowedAt != null) { "Payment start lacks confirmed item escrow" }
        require(paidAt == null || paymentStartedAt != null) { "Paid state lacks payment start" }
        require(contractCommittedAt == null || paidAt != null) { "Contract commit lacks confirmed payment" }
        require(refundStartedAt == null || itemsEscrowedAt != null) { "Refund start lacks confirmed item escrow" }
        require(
            refundedAt == null || refundStartedAt != null ||
                reconciliation?.resolution == ContractSubmissionReconciliationResolution.ITEMS_REFUNDED,
        ) { "Refund confirmation lacks refund start or reconciliation evidence" }
        require(paidAt == null || refundStartedAt == null) { "Paid submission cannot enter item refund" }
        require(providerBalanceBeforeMinor == null || paymentStartedAt != null) { "Provider balance evidence lacks payment start" }
        require(providerBalanceAfterMinor == null || paymentStartedAt != null) { "Provider result evidence lacks payment start" }
        require(paymentStartedAt != null || paymentFailureCode == null) { "Payment failure evidence lacks payment start" }
        require(paidAt != null || providerTransactionId == null) { "Provider transaction id lacks confirmed payment" }
        requireOrdered(itemRemovalStartedAt, itemsEscrowedAt, "Item escrow predates removal start")
        requireOrdered(itemsEscrowedAt, paymentStartedAt, "Payment predates item escrow")
        requireOrdered(paymentStartedAt, paidAt, "Payment confirmation predates payment start")
        requireOrdered(paidAt, contractCommittedAt, "Contract commit predates payment confirmation")
        requireOrdered(itemsEscrowedAt, refundStartedAt, "Refund predates item escrow")
        if (reconciliation?.resolution != ContractSubmissionReconciliationResolution.ITEMS_REFUNDED) {
            requireOrdered(refundStartedAt, refundedAt, "Refund confirmation predates refund start")
        }
    }

    private fun validateStatusShape() {
        when (status) {
            ContractSubmissionJournalStatus.PREPARED -> {
                require(itemRemovalStartedAt == null) { "Prepared journal already has a mutation start" }
            }
            ContractSubmissionJournalStatus.CANCELLED -> {
                require(cancelledAt != null && itemsEscrowedAt == null && paymentStartedAt == null && refundStartedAt == null) {
                    "Invalid cancelled journal state"
                }
                require(CANCELLATION_CODE_PATTERN.matches(cancellationCode.orEmpty())) {
                    "Cancelled journal lacks a stable cancellation code"
                }
            }
            ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED -> {
                require(itemRemovalStartedAt != null && itemsEscrowedAt == null) { "Invalid item-removal journal state" }
            }
            ContractSubmissionJournalStatus.ITEMS_ESCROWED ->
                require(itemsEscrowedAt != null && paymentStartedAt == null && refundStartedAt == null) {
                    "Invalid item-escrow journal state"
                }
            ContractSubmissionJournalStatus.PAYMENT_STARTED -> {
                require(paymentStartedAt != null && providerBalanceBeforeMinor != null) { "Invalid payment-start journal state" }
                require(
                    providerBalanceAfterMinor == null && providerTransactionId == null && paidAt == null &&
                        paymentFailureCode == null && refundStartedAt == null,
                ) {
                    "Payment-start journal already has an outcome"
                }
            }
            ContractSubmissionJournalStatus.PAYMENT_FAILED -> {
                validateDefinitePaymentFailure()
                require(refundStartedAt == null) { "Failed-payment journal already entered refund" }
            }
            ContractSubmissionJournalStatus.PAID -> {
                validatePaid()
                require(contractCommittedAt == null) { "Paid journal already contains a contract commit" }
            }
            ContractSubmissionJournalStatus.CONTRACT_COMMITTED -> {
                validatePaid()
                require(contractCommittedAt != null) { "Committed journal lacks commit timestamp" }
            }
            ContractSubmissionJournalStatus.REFUND_STARTED -> {
                require(refundStartedAt != null && refundedAt == null && paidAt == null) { "Invalid refund-start journal state" }
                validateOptionalPaymentFailure()
            }
            ContractSubmissionJournalStatus.REFUNDED -> {
                require(refundedAt != null && paidAt == null) { "Invalid refunded journal state" }
                validateOptionalPaymentFailure()
                require(
                    refundStartedAt != null ||
                        reconciliation?.resolution == ContractSubmissionReconciliationResolution.ITEMS_REFUNDED,
                ) { "Refunded journal lacks refund intent or reconciliation evidence" }
            }
            ContractSubmissionJournalStatus.MANUAL_REVIEW -> {
                require(reconciliation == null) { "Unresolved manual review already contains reconciliation evidence" }
                require(reviewFromStatus in AMBIGUOUS_STATES && reviewReason != null) {
                    "Manual review lacks an ambiguous source state"
                }
                require(!reviewEvidence.isNullOrBlank()) { "Manual review lacks bounded evidence" }
                when (reviewFromStatus) {
                    ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED ->
                        require(itemRemovalStartedAt != null && itemsEscrowedAt == null && cancelledAt == null) {
                            "Interrupted item-removal review contains a later phase"
                        }
                    ContractSubmissionJournalStatus.PAYMENT_STARTED ->
                        require(
                            paymentStartedAt != null && providerBalanceBeforeMinor != null &&
                                providerTransactionId == null && paidAt == null &&
                                paymentFailureCode == null && refundStartedAt == null,
                        ) { "Interrupted payment review contains an outcome" }
                    ContractSubmissionJournalStatus.ITEMS_ESCROWED ->
                        require(
                            itemsEscrowedAt != null && paymentStartedAt == null && refundStartedAt == null &&
                                providerBalanceBeforeMinor == null && providerBalanceAfterMinor == null &&
                                providerTransactionId == null && paidAt == null && paymentFailureCode == null,
                        ) { "Interrupted escrow review contains a later phase" }
                    ContractSubmissionJournalStatus.PAYMENT_FAILED -> {
                        require(refundStartedAt == null && paidAt == null) {
                            "Interrupted failed-payment review contains a later phase"
                        }
                        validateDefinitePaymentFailure()
                    }
                    ContractSubmissionJournalStatus.REFUND_STARTED -> {
                        require(refundStartedAt != null && refundedAt == null && paidAt == null) {
                            "Interrupted refund review contains an outcome"
                        }
                        validateOptionalPaymentFailure()
                    }
                    else -> error("Unreachable manual-review source state")
                }
                require(reviewReason in expectedReviewReasons(requireNotNull(reviewFromStatus))) {
                    "Manual review reason does not match its source state"
                }
            }
        }
        if (status != ContractSubmissionJournalStatus.MANUAL_REVIEW) {
            require(reviewFromStatus == null && reviewReason == null && reviewEvidence == null) {
                "Non-review journal contains manual-review fields"
            }
        }
        if (status != ContractSubmissionJournalStatus.CANCELLED) {
            require(cancelledAt == null && cancellationCode == null) {
                "Non-cancelled journal contains cancellation fields"
            }
        }
    }

    private fun validateReconciliationShape() {
        val resolved = reconciliation ?: return
        require(resolved.reconciledAt in createdAt..updatedAt) { "Reconciliation timestamp is out of bounds" }
        require(resolved.reviewedRevision < revision) { "Reconciliation does not precede the resolved journal revision" }
        require(resolved.reviewReason in expectedReviewReasons(resolved.reviewFromStatus)) {
            "Reconciliation reason does not match its source state"
        }
        when (resolved.resolution) {
            ContractSubmissionReconciliationResolution.NO_ITEMS_REMOVED -> {
                require(status == ContractSubmissionJournalStatus.CANCELLED) {
                    "No-items reconciliation is not cancelled"
                }
                require(resolved.reviewFromStatus == ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED) {
                    "No-items reconciliation has an invalid source state"
                }
                require(resolved.evidenceKind == ContractSubmissionReconciliationEvidenceKind.OPERATOR_INVENTORY_INSPECTION) {
                    "No-items reconciliation has an invalid evidence kind"
                }
            }
            ContractSubmissionReconciliationResolution.ITEMS_REFUNDED -> {
                require(status == ContractSubmissionJournalStatus.REFUNDED) {
                    "Item-refund reconciliation is not refunded"
                }
                val expectedKind =
                    if (resolved.reviewFromStatus == ContractSubmissionJournalStatus.PAYMENT_STARTED) {
                        ContractSubmissionReconciliationEvidenceKind.OPERATOR_INVENTORY_AND_PROVIDER
                    } else {
                        ContractSubmissionReconciliationEvidenceKind.OPERATOR_INVENTORY_INSPECTION
                    }
                require(resolved.evidenceKind == expectedKind) {
                    "Item-refund reconciliation has an invalid evidence kind"
                }
                if (resolved.reviewFromStatus == ContractSubmissionJournalStatus.PAYMENT_STARTED) {
                    require(providerBalanceAfterMinor == providerBalanceBeforeMinor && providerTransactionId == null) {
                        "Item-refund reconciliation did not prove an unchanged provider balance"
                    }
                }
            }
            ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED -> {
                require(
                    status == ContractSubmissionJournalStatus.PAID ||
                        status == ContractSubmissionJournalStatus.CONTRACT_COMMITTED,
                ) { "Payment reconciliation is not paid or committed" }
                require(resolved.reviewFromStatus == ContractSubmissionJournalStatus.PAYMENT_STARTED) {
                    "Payment reconciliation has an invalid source state"
                }
                require(
                    resolved.evidenceKind ==
                        ContractSubmissionReconciliationEvidenceKind.OPERATOR_PROVIDER_BALANCE_AND_HISTORY,
                ) { "Payment reconciliation has an invalid evidence kind" }
                require(!providerTransactionId.isNullOrBlank()) {
                    "Payment reconciliation lacks provider transaction evidence"
                }
            }
        }
    }

    private fun validatePaid() {
        require(paidAt != null && providerBalanceBeforeMinor != null && providerBalanceAfterMinor != null) {
            "Paid journal lacks provider evidence"
        }
        require(Math.addExact(providerBalanceBeforeMinor, payoutMinor) == providerBalanceAfterMinor) {
            "Paid journal balance evidence does not match payout"
        }
        require(paymentFailureCode == null && refundStartedAt == null) { "Paid journal contains failure or refund evidence" }
    }

    private fun validateDefinitePaymentFailure() {
        require(paymentStartedAt != null && providerBalanceBeforeMinor != null && providerBalanceAfterMinor != null) {
            "Failed payment journal lacks provider evidence"
        }
        require(providerBalanceAfterMinor == providerBalanceBeforeMinor && !paymentFailureCode.isNullOrBlank()) {
            "Failed payment is not proven unchanged"
        }
        require(providerTransactionId == null && paidAt == null) { "Failed payment journal is marked paid" }
    }

    private fun validateOptionalPaymentFailure() {
        if (paymentStartedAt == null) {
            require(providerBalanceBeforeMinor == null && providerBalanceAfterMinor == null && paymentFailureCode == null) {
                "Refund journal contains incomplete payment evidence"
            }
        } else {
            validateDefinitePaymentFailure()
        }
    }

    private fun requireOrdered(earlier: Long?, later: Long?, message: String) {
        require(later == null || earlier != null && earlier <= later) { message }
    }

    companion object {
        const val MAX_ITEM_PAYLOADS = 54
        const val MAX_TOTAL_SERIALIZED_BYTES = 262_144L
        private const val MAX_PROVIDER_ID_LENGTH = 128
        private const val MAX_PAYOUT_REASON_LENGTH = 128
        private const val MAX_EVIDENCE_LENGTH = 256
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,95}")
        private val CONTRACT_ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{2,47}")
        private val CANCELLATION_CODE_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val AMBIGUOUS_STATES =
            setOf(
                ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED,
                ContractSubmissionJournalStatus.ITEMS_ESCROWED,
                ContractSubmissionJournalStatus.PAYMENT_STARTED,
                ContractSubmissionJournalStatus.PAYMENT_FAILED,
                ContractSubmissionJournalStatus.REFUND_STARTED,
            )

        private fun expectedReviewReasons(status: ContractSubmissionJournalStatus): Set<ContractSubmissionReviewReason> =
            when (status) {
                ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED ->
                    setOf(
                        ContractSubmissionReviewReason.INTERRUPTED_ITEM_REMOVAL,
                        ContractSubmissionReviewReason.INVENTORY_EVIDENCE_CONFLICT,
                    )
                ContractSubmissionJournalStatus.PAYMENT_STARTED ->
                    setOf(
                        ContractSubmissionReviewReason.INTERRUPTED_PAYMENT,
                        ContractSubmissionReviewReason.PROVIDER_EVIDENCE_CONFLICT,
                    )
                ContractSubmissionJournalStatus.ITEMS_ESCROWED ->
                    setOf(ContractSubmissionReviewReason.INTERRUPTED_ESCROW)
                ContractSubmissionJournalStatus.PAYMENT_FAILED ->
                    setOf(ContractSubmissionReviewReason.INTERRUPTED_PAYMENT_FAILED)
                ContractSubmissionJournalStatus.REFUND_STARTED ->
                    setOf(
                        ContractSubmissionReviewReason.INTERRUPTED_REFUND,
                        ContractSubmissionReviewReason.REFUND_EVIDENCE_CONFLICT,
                    )
                else -> emptySet()
            }

        fun payoutReason(submissionId: String): String = "arc-contract:$submissionId"

        private fun validateOptionalText(value: String?, label: String, maxLength: Int) {
            require(value == null || value.isNotBlank() && value.length <= maxLength && value.none(Char::isISOControl)) {
                "Invalid journal $label"
            }
        }
    }
}

/** Pure journal transitions. Callers must durably persist each returned record before the named side effect. */
object ContractSubmissionJournalEngine {
    fun prepare(
        definition: ResourceContractDefinition,
        plan: ContractSubmissionPlan.Accepted,
        itemPayloads: List<EscrowedItemPayload>,
        now: Long,
    ): ContractSubmissionJournalRecord {
        require(plan.acceptedQuantity > 0L && plan.payoutMinor > 0L) { "Cannot journal an empty submission" }
        require(now >= plan.plannedAt) { "Journal timestamp predates its submission plan" }
        require(definition.isOpenAt(plan.plannedAt)) { "Journal plan is outside the contract window" }
        require(
            plan.acceptedQuantity >= definition.minSubmissionQuantity &&
                plan.acceptedQuantity <= plan.requestedQuantity.toLong() &&
                plan.acceptedQuantity <= definition.maxSubmissionQuantity.toLong() &&
                plan.acceptedQuantity <= definition.targetQuantity &&
                plan.acceptedQuantity <= ContractRankPolicy(plan.playerCapBasisPoints, plan.payoutBasisPoints)
                    .playerCap(definition.perPlayerQuantityCap),
        ) { "Journal plan quantity exceeds contract policy" }
        require(
            ContractMarketPricing.payoutMinor(
                definition, plan.priceSupplyBefore, plan.acceptedQuantity, plan.plannedAt,
                ContractRankPolicy(plan.playerCapBasisPoints, plan.payoutBasisPoints),
            ) == plan.payoutMinor,
        ) {
            "Journal plan payout does not match contract policy"
        }
        require(plan.payoutMinor <= definition.budgetMinor) { "Journal plan payout exceeds contract budget" }
        require(itemPayloads.all { it.itemKey == definition.itemKey }) { "Journal payload does not match contract item" }
        return ContractSubmissionJournalRecord(
            submissionId = plan.submissionId,
            contractId = definition.id,
            contractWindowStartsAt = definition.windowStartsAt,
            itemKey = definition.itemKey,
            playerId = plan.playerId,
            requestedQuantity = plan.requestedQuantity,
            acceptedQuantity = plan.acceptedQuantity,
            payoutMinor = plan.payoutMinor,
            expectedContractRevision = plan.expectedRevision,
            payoutReason = ContractSubmissionJournalRecord.payoutReason(plan.submissionId),
            itemPayloads = itemPayloads,
            createdAt = now,
            definitionSnapshot = definition,
            quoteSupplyBefore = plan.priceSupplyBefore,
            quotePlannedAt = plan.plannedAt,
            quotePayoutBasisPoints = plan.payoutBasisPoints,
        )
    }

    fun beginItemRemoval(record: ContractSubmissionJournalRecord, now: Long): ContractSubmissionJournalRecord =
        advance(record, ContractSubmissionJournalStatus.PREPARED, now) {
            copy(
                status = ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED,
                itemRemovalStartedAt = now,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }

    fun cancelPrepared(
        record: ContractSubmissionJournalRecord,
        cancellationCode: String,
        now: Long,
    ): ContractSubmissionJournalRecord = cancel(record, ContractSubmissionJournalStatus.PREPARED, cancellationCode, now)

    /** Safe only after the inventory adapter proved that it mutated no slot. */
    fun confirmNoItemsRemoved(
        record: ContractSubmissionJournalRecord,
        cancellationCode: String,
        now: Long,
    ): ContractSubmissionJournalRecord =
        cancel(record, ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED, cancellationCode, now)

    fun confirmItemsEscrowed(record: ContractSubmissionJournalRecord, now: Long): ContractSubmissionJournalRecord {
        if (record.status == ContractSubmissionJournalStatus.ITEMS_ESCROWED) return record.validated()
        return advance(record, ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED, now) {
            copy(
                status = ContractSubmissionJournalStatus.ITEMS_ESCROWED,
                itemsEscrowedAt = now,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }
    }

    fun beginPayment(
        record: ContractSubmissionJournalRecord,
        providerBalanceBeforeMinor: Long,
        now: Long,
    ): ContractSubmissionJournalRecord =
        advance(record, ContractSubmissionJournalStatus.ITEMS_ESCROWED, now) {
            copy(
                status = ContractSubmissionJournalStatus.PAYMENT_STARTED,
                paymentStartedAt = now,
                providerBalanceBeforeMinor = providerBalanceBeforeMinor,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }

    fun confirmPaid(
        record: ContractSubmissionJournalRecord,
        providerBalanceAfterMinor: Long,
        providerTransactionId: String?,
        now: Long,
    ): ContractSubmissionJournalRecord {
        if (record.status == ContractSubmissionJournalStatus.PAID) {
            require(record.providerBalanceAfterMinor == providerBalanceAfterMinor &&
                record.providerTransactionId == providerTransactionId
            ) { "Paid journal replay disagrees with persisted provider evidence" }
            return record.validated()
        }
        return advance(record, ContractSubmissionJournalStatus.PAYMENT_STARTED, now) {
            require(Math.addExact(requireNotNull(providerBalanceBeforeMinor), payoutMinor) == providerBalanceAfterMinor) {
                "Provider balance change does not match journal payout"
            }
            copy(
                status = ContractSubmissionJournalStatus.PAID,
                providerBalanceAfterMinor = providerBalanceAfterMinor,
                providerTransactionId = providerTransactionId,
                paidAt = now,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }
    }

    fun confirmPaymentFailed(
        record: ContractSubmissionJournalRecord,
        providerBalanceAfterMinor: Long,
        failureCode: String,
        now: Long,
    ): ContractSubmissionJournalRecord {
        if (record.status == ContractSubmissionJournalStatus.PAYMENT_FAILED) {
            require(record.providerBalanceAfterMinor == providerBalanceAfterMinor && record.paymentFailureCode == failureCode) {
                "Failed-payment replay disagrees with persisted provider evidence"
            }
            return record.validated()
        }
        return advance(record, ContractSubmissionJournalStatus.PAYMENT_STARTED, now) {
            require(providerBalanceAfterMinor == providerBalanceBeforeMinor) {
                "Payment failure cannot be proven because the provider balance changed"
            }
            copy(
                status = ContractSubmissionJournalStatus.PAYMENT_FAILED,
                providerBalanceAfterMinor = providerBalanceAfterMinor,
                paymentFailureCode = failureCode,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }
    }

    fun haltAmbiguousPayment(
        record: ContractSubmissionJournalRecord,
        observedProviderBalanceAfterMinor: Long?,
        now: Long,
    ): ContractSubmissionJournalRecord =
        manualReview(
            record,
            ContractSubmissionReviewReason.PROVIDER_EVIDENCE_CONFLICT,
            "Provider result did not prove an unchanged balance or the exact planned payout",
            now,
            providerBalanceAfterMinor = observedProviderBalanceAfterMinor,
        )

    fun haltAmbiguousItemRemoval(record: ContractSubmissionJournalRecord, now: Long): ContractSubmissionJournalRecord =
        manualReview(
            record,
            ContractSubmissionReviewReason.INVENTORY_EVIDENCE_CONFLICT,
            "Inventory adapter could not prove that item removal fully committed or did not start",
            now,
        )

    fun haltAmbiguousRefund(record: ContractSubmissionJournalRecord, now: Long): ContractSubmissionJournalRecord =
        manualReview(
            record,
            ContractSubmissionReviewReason.REFUND_EVIDENCE_CONFLICT,
            "Inventory adapter could not prove that the exact escrow payload was fully restored",
            now,
        )

    fun confirmContractCommitted(record: ContractSubmissionJournalRecord, now: Long): ContractSubmissionJournalRecord {
        if (record.status == ContractSubmissionJournalStatus.CONTRACT_COMMITTED) return record.validated()
        return advance(record, ContractSubmissionJournalStatus.PAID, now) {
            copy(
                status = ContractSubmissionJournalStatus.CONTRACT_COMMITTED,
                contractCommittedAt = now,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }
    }

    fun beginRefund(record: ContractSubmissionJournalRecord, now: Long): ContractSubmissionJournalRecord {
        require(
            record.status == ContractSubmissionJournalStatus.ITEMS_ESCROWED ||
                record.status == ContractSubmissionJournalStatus.PAYMENT_FAILED,
        ) { "Journal cannot begin refund from ${record.status.label}" }
        return advance(record, record.status, now) {
            copy(
                status = ContractSubmissionJournalStatus.REFUND_STARTED,
                refundStartedAt = now,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }
    }

    fun confirmRefunded(record: ContractSubmissionJournalRecord, now: Long): ContractSubmissionJournalRecord {
        if (record.status == ContractSubmissionJournalStatus.REFUNDED) return record.validated()
        return advance(record, ContractSubmissionJournalStatus.REFUND_STARTED, now) {
            copy(
                status = ContractSubmissionJournalStatus.REFUNDED,
                refundedAt = now,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }
    }

    fun recoverInterrupted(record: ContractSubmissionJournalRecord, now: Long): ContractSubmissionJournalRecord =
        when (record.validated().status) {
            ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED ->
                manualReview(
                    record,
                    ContractSubmissionReviewReason.INTERRUPTED_ITEM_REMOVAL,
                    "Restart after durable item-removal intent; verify inventory before any retry",
                    now,
                )
            ContractSubmissionJournalStatus.PAYMENT_STARTED ->
                manualReview(
                    record,
                    ContractSubmissionReviewReason.INTERRUPTED_PAYMENT,
                    "Restart after durable payout intent; reconcile balance/history and never auto-pay",
                    now,
                )
            ContractSubmissionJournalStatus.ITEMS_ESCROWED ->
                manualReview(
                    record,
                    ContractSubmissionReviewReason.INTERRUPTED_ESCROW,
                    "Restart after durable item escrow; verify inventory before any refund",
                    now,
                )
            ContractSubmissionJournalStatus.PAYMENT_FAILED ->
                manualReview(
                    record,
                    ContractSubmissionReviewReason.INTERRUPTED_PAYMENT_FAILED,
                    "Restart after proven payment rejection; verify inventory before any refund",
                    now,
                )
            ContractSubmissionJournalStatus.REFUND_STARTED ->
                manualReview(
                    record,
                    ContractSubmissionReviewReason.INTERRUPTED_REFUND,
                    "Restart after durable refund intent; verify inventory before any retry",
                    now,
                )
            else -> record
        }

    private fun manualReview(
        record: ContractSubmissionJournalRecord,
        reason: ContractSubmissionReviewReason,
        evidence: String,
        now: Long,
        providerBalanceAfterMinor: Long? = record.providerBalanceAfterMinor,
    ): ContractSubmissionJournalRecord =
        advance(record, record.status, now) {
            require(
                status == ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED ||
                    status == ContractSubmissionJournalStatus.ITEMS_ESCROWED ||
                    status == ContractSubmissionJournalStatus.PAYMENT_STARTED ||
                    status == ContractSubmissionJournalStatus.PAYMENT_FAILED ||
                    status == ContractSubmissionJournalStatus.REFUND_STARTED,
            ) { "Journal state is not an ambiguous external-mutation boundary" }
            copy(
                status = ContractSubmissionJournalStatus.MANUAL_REVIEW,
                reviewFromStatus = status,
                reviewReason = reason,
                reviewEvidence = evidence,
                providerBalanceAfterMinor = providerBalanceAfterMinor,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }

    private fun cancel(
        record: ContractSubmissionJournalRecord,
        expected: ContractSubmissionJournalStatus,
        cancellationCode: String,
        now: Long,
    ): ContractSubmissionJournalRecord =
        advance(record, expected, now) {
            require(CANCELLATION_CODE_PATTERN.matches(cancellationCode)) { "Invalid journal cancellation code" }
            copy(
                status = ContractSubmissionJournalStatus.CANCELLED,
                cancelledAt = now,
                cancellationCode = cancellationCode,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }

    private val CANCELLATION_CODE_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")

    private inline fun advance(
        record: ContractSubmissionJournalRecord,
        expected: ContractSubmissionJournalStatus,
        now: Long,
        update: ContractSubmissionJournalRecord.() -> ContractSubmissionJournalRecord,
    ): ContractSubmissionJournalRecord {
        record.validated()
        require(record.status == expected) { "Journal cannot advance from ${record.status.label}; expected ${expected.label}" }
        require(now >= record.updatedAt) { "Journal transition timestamp moved backwards" }
        val next = record.update()
        require(next.updatedAt == now && next.revision == Math.addExact(record.revision, 1L)) {
            "Journal transition did not persist its timestamp and revision"
        }
        return next.validated()
    }
}

data class ContractSubmissionJournalSummary(
    val available: Boolean,
    val totalRecords: Int,
    val stateCounts: Map<String, Int>,
    val heldItemQuantity: Long,
    val pendingPayoutMinor: Long,
    val ambiguousPayoutMinor: Long,
    val manualReviewCount: Int,
    val oldestAttentionAgeSeconds: Long,
    val capacityRemaining: Int,
    val manualReviewSubmissionIds: List<String>,
) {
    companion object {
        fun unavailable(): ContractSubmissionJournalSummary =
            ContractSubmissionJournalSummary(
                available = false,
                totalRecords = 0,
                stateCounts = ContractSubmissionJournalStatus.entries.associate { it.label to 0 },
                heldItemQuantity = 0L,
                pendingPayoutMinor = 0L,
                ambiguousPayoutMinor = 0L,
                manualReviewCount = 0,
                oldestAttentionAgeSeconds = 0L,
                capacityRemaining = 0,
                manualReviewSubmissionIds = emptyList(),
            )
    }
}

object ContractSubmissionJournalAudit {
    const val MAX_NETWORK_RECORDS = 4_096

    fun summarize(
        records: List<ContractSubmissionJournalRecord>,
        now: Long,
        available: Boolean = true,
    ): ContractSubmissionJournalSummary {
        require(now >= 0L) { "Journal audit timestamp must be non-negative" }
        require(records.size <= MAX_NETWORK_RECORDS) { "Contract submission journal exceeds its network record limit" }
        val valid = records.map { it.validated() }
        require(valid.map { it.submissionId }.toSet().size == valid.size) {
            "Contract submission journal contains duplicate ids"
        }
        val counts = ContractSubmissionJournalStatus.entries.associate { status -> status.label to valid.count { it.status == status } }
        val held =
            valid.filter { it.hasConfirmedItemEscrow() && !it.isTerminal() }
                .fold(0L) { sum, record -> Math.addExact(sum, record.acceptedQuantity) }
        val pending =
            valid.filter {
                it.status == ContractSubmissionJournalStatus.ITEMS_ESCROWED ||
                    it.status == ContractSubmissionJournalStatus.PAYMENT_FAILED
            }.fold(0L) { sum, record -> Math.addExact(sum, record.payoutMinor) }
        val ambiguous =
            valid.filter {
                it.status == ContractSubmissionJournalStatus.PAYMENT_STARTED ||
                    it.status == ContractSubmissionJournalStatus.MANUAL_REVIEW &&
                    it.reviewFromStatus == ContractSubmissionJournalStatus.PAYMENT_STARTED
            }.fold(0L) { sum, record -> Math.addExact(sum, record.payoutMinor) }
        val attention = valid.filterNot { it.isTerminal() }
        val oldestAge = attention.maxOfOrNull { ((now - it.createdAt).coerceAtLeast(0L)) / 1_000L } ?: 0L
        val manualIds =
            valid.asSequence()
                .filter { it.status == ContractSubmissionJournalStatus.MANUAL_REVIEW }
                .sortedByDescending { it.updatedAt }
                .map { it.submissionId }
                .take(MAX_REVIEW_IDS)
                .toList()
        return ContractSubmissionJournalSummary(
            available = available,
            totalRecords = valid.size,
            stateCounts = counts,
            heldItemQuantity = held,
            pendingPayoutMinor = pending,
            ambiguousPayoutMinor = ambiguous,
            manualReviewCount = counts[ContractSubmissionJournalStatus.MANUAL_REVIEW.label] ?: 0,
            oldestAttentionAgeSeconds = oldestAge,
            capacityRemaining = MAX_NETWORK_RECORDS - valid.size,
            manualReviewSubmissionIds = manualIds,
        )
    }

    private const val MAX_REVIEW_IDS = 20
}

package ru.arc.contracts

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class ContractSubmissionReconciliationResolution(val label: String) {
    NO_ITEMS_REMOVED("no_items_removed"),
    ITEMS_REFUNDED("items_refunded"),
    PAYMENT_CONFIRMED("payment_confirmed"),
}

enum class ContractSubmissionReconciliationEvidenceKind(val label: String) {
    OPERATOR_INVENTORY_INSPECTION("operator_inventory_inspection"),
    OPERATOR_INVENTORY_AND_PROVIDER("operator_inventory_and_provider"),
    OPERATOR_PROVIDER_BALANCE_AND_HISTORY("operator_provider_balance_and_history"),
}

/** Immutable authenticated-ops evidence attached to a resolved manual review. */
data class ContractSubmissionReconciliation(
    val resolution: ContractSubmissionReconciliationResolution,
    val evidenceKind: ContractSubmissionReconciliationEvidenceKind,
    val operatorId: String,
    val operatorEvidence: String,
    val idempotencyKey: String,
    val reviewDigest: String,
    val reviewedRevision: Long,
    val reviewFromStatus: ContractSubmissionJournalStatus,
    val reviewReason: ContractSubmissionReviewReason,
    val originalReviewEvidence: String,
    val reconciledAt: Long,
) {
    fun validated(): ContractSubmissionReconciliation {
        validateOperatorId(operatorId)
        validateOperatorEvidence(operatorEvidence)
        validateIdempotencyKey(idempotencyKey)
        require(SHA256_PATTERN.matches(reviewDigest)) { "Invalid reconciliation review digest" }
        require(reviewedRevision >= 0L && reconciledAt >= 0L) { "Invalid reconciliation revision or timestamp" }
        require(
            reviewFromStatus == ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED ||
                reviewFromStatus == ContractSubmissionJournalStatus.ITEMS_ESCROWED ||
                reviewFromStatus == ContractSubmissionJournalStatus.PAYMENT_STARTED ||
                reviewFromStatus == ContractSubmissionJournalStatus.PAYMENT_FAILED ||
                reviewFromStatus == ContractSubmissionJournalStatus.REFUND_STARTED,
        ) { "Invalid reconciliation source state" }
        require(originalReviewEvidence.isNotBlank() && originalReviewEvidence.length <= MAX_ORIGINAL_EVIDENCE_LENGTH) {
            "Invalid original reconciliation evidence"
        }
        require(originalReviewEvidence.none(Char::isISOControl)) { "Invalid original reconciliation evidence" }
        return this
    }

    companion object {
        const val MAX_OPERATOR_EVIDENCE_LENGTH = 512
        private const val MAX_ORIGINAL_EVIDENCE_LENGTH = 256
        private val OPERATOR_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._@:-]{1,63}")
        private val IDEMPOTENCY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,95}")
        private val SHA256_PATTERN = Regex("[a-f0-9]{64}")

        internal fun validateOperatorId(value: String) {
            require(OPERATOR_PATTERN.matches(value)) { "Invalid reconciliation operator id" }
        }

        internal fun validateOperatorEvidence(value: String) {
            require(value.isNotBlank() && value.length <= MAX_OPERATOR_EVIDENCE_LENGTH && value.none(Char::isISOControl)) {
                "Invalid reconciliation operator evidence"
            }
        }

        internal fun validateIdempotencyKey(value: String) {
            require(IDEMPOTENCY_PATTERN.matches(value)) { "Invalid reconciliation idempotency key" }
        }
    }
}

data class ContractSubmissionReconciliationRequest(
    val submissionId: String,
    val expectedRevision: Long,
    val resolution: ContractSubmissionReconciliationResolution,
    val operatorId: String,
    val operatorEvidence: String,
    val idempotencyKey: String,
    val providerBalanceAfterMinor: Long? = null,
    val providerTransactionId: String? = null,
    val providerTransactionReason: String? = null,
) {
    fun validated(): ContractSubmissionReconciliationRequest {
        require(SUBMISSION_PATTERN.matches(submissionId)) { "Invalid reconciliation submission id" }
        require(expectedRevision >= 0L) { "Invalid reconciliation expected revision" }
        ContractSubmissionReconciliation.validateOperatorId(operatorId)
        ContractSubmissionReconciliation.validateOperatorEvidence(operatorEvidence)
        ContractSubmissionReconciliation.validateIdempotencyKey(idempotencyKey)
        require(
            providerTransactionId == null ||
                providerTransactionId.isNotBlank() &&
                providerTransactionId.length <= MAX_PROVIDER_TRANSACTION_ID_LENGTH &&
                providerTransactionId.none(Char::isISOControl),
        ) { "Invalid reconciliation provider transaction id" }
        require(
            providerTransactionReason == null ||
                providerTransactionReason.isNotBlank() &&
                providerTransactionReason.length <= MAX_PROVIDER_TRANSACTION_REASON_LENGTH &&
                providerTransactionReason.none(Char::isISOControl),
        ) { "Invalid reconciliation provider transaction reason" }
        return this
    }

    companion object {
        private const val MAX_PROVIDER_TRANSACTION_ID_LENGTH = 128
        private const val MAX_PROVIDER_TRANSACTION_REASON_LENGTH = 128
        private val SUBMISSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,95}")
    }
}

data class ContractSubmissionReconciliationPreview(
    val submissionId: String,
    val reviewedRevision: Long,
    val reviewFromStatus: ContractSubmissionJournalStatus,
    val reviewReason: ContractSubmissionReviewReason,
    val resolution: ContractSubmissionReconciliationResolution,
    val evidenceKind: ContractSubmissionReconciliationEvidenceKind,
    val proposedStatus: ContractSubmissionJournalStatus,
    val reviewDigest: String,
    val alreadyApplied: Boolean,
    val commitsContractState: Boolean,
)

data class ContractSubmissionReconciliationApplyResult(
    val preview: ContractSubmissionReconciliationPreview,
    val record: ContractSubmissionJournalRecord,
    val receipt: ContractSubmissionReceipt?,
    val replayed: Boolean,
)

/** Pure, replay-safe adjudication. It never retries inventory or payment side effects. */
object ContractSubmissionReconciliationEngine {
    fun preview(
        record: ContractSubmissionJournalRecord,
        request: ContractSubmissionReconciliationRequest,
    ): ContractSubmissionReconciliationPreview {
        val valid = record.validated()
        request.validated()
        valid.reconciliation?.let { existing ->
            require(matches(existing, valid, request)) { "Reconciliation replay disagrees with persisted evidence" }
            return preview(valid, existing.evidenceKind, existing.reviewDigest, alreadyApplied = true)
        }
        require(valid.status == ContractSubmissionJournalStatus.MANUAL_REVIEW) {
            "Submission is not awaiting manual reconciliation"
        }
        require(valid.submissionId == request.submissionId) { "Reconciliation submission id mismatch" }
        require(valid.revision == request.expectedRevision) { "Reconciliation revision is stale" }
        val evidenceKind = validateResolution(valid, request)
        return preview(valid, request.resolution, evidenceKind, digest(valid, request), alreadyApplied = false)
    }

    fun apply(
        record: ContractSubmissionJournalRecord,
        request: ContractSubmissionReconciliationRequest,
        reviewDigest: String,
        now: Long,
    ): ContractSubmissionJournalRecord {
        val valid = record.validated()
        request.validated()
        require(SHA256_PATTERN.matches(reviewDigest)) { "Invalid reconciliation review digest" }
        valid.reconciliation?.let { existing ->
            require(matches(existing, valid, request) && constantTimeEquals(existing.reviewDigest, reviewDigest)) {
                "Reconciliation replay disagrees with persisted evidence"
            }
            return valid
        }
        val preview = preview(valid, request)
        require(constantTimeEquals(preview.reviewDigest, reviewDigest)) { "Reconciliation review digest is stale" }
        require(now >= valid.updatedAt) { "Reconciliation timestamp moved backwards" }
        val resolvedEvidence =
            ContractSubmissionReconciliation(
                resolution = request.resolution,
                evidenceKind = preview.evidenceKind,
                operatorId = request.operatorId,
                operatorEvidence = request.operatorEvidence,
                idempotencyKey = request.idempotencyKey,
                reviewDigest = preview.reviewDigest,
                reviewedRevision = valid.revision,
                reviewFromStatus = requireNotNull(valid.reviewFromStatus),
                reviewReason = requireNotNull(valid.reviewReason),
                originalReviewEvidence = requireNotNull(valid.reviewEvidence),
                reconciledAt = now,
            ).validated()
        val nextRevision = Math.addExact(valid.revision, 1L)
        return when (request.resolution) {
            ContractSubmissionReconciliationResolution.NO_ITEMS_REMOVED ->
                valid.copy(
                    status = ContractSubmissionJournalStatus.CANCELLED,
                    cancelledAt = now,
                    cancellationCode = "operator_no_items_removed",
                    reviewFromStatus = null,
                    reviewReason = null,
                    reviewEvidence = null,
                    reconciliation = resolvedEvidence,
                    updatedAt = now,
                    revision = nextRevision,
                )
            ContractSubmissionReconciliationResolution.ITEMS_REFUNDED ->
                valid.copy(
                    status = ContractSubmissionJournalStatus.REFUNDED,
                    providerBalanceAfterMinor =
                        if (valid.reviewFromStatus == ContractSubmissionJournalStatus.PAYMENT_STARTED) {
                            request.providerBalanceAfterMinor
                        } else {
                            valid.providerBalanceAfterMinor
                        },
                    providerTransactionId = null,
                    paymentFailureCode =
                        if (valid.reviewFromStatus == ContractSubmissionJournalStatus.PAYMENT_STARTED) {
                            "operator_proved_not_paid"
                        } else {
                            valid.paymentFailureCode
                        },
                    refundedAt = now,
                    reviewFromStatus = null,
                    reviewReason = null,
                    reviewEvidence = null,
                    reconciliation = resolvedEvidence,
                    updatedAt = now,
                    revision = nextRevision,
                )
            ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED ->
                valid.copy(
                    status = ContractSubmissionJournalStatus.PAID,
                    providerBalanceAfterMinor = request.providerBalanceAfterMinor,
                    providerTransactionId = request.providerTransactionId,
                    paymentFailureCode = null,
                    paidAt = now,
                    reviewFromStatus = null,
                    reviewReason = null,
                    reviewEvidence = null,
                    reconciliation = resolvedEvidence,
                    updatedAt = now,
                    revision = nextRevision,
                )
        }.validated()
    }

    private fun validateResolution(
        record: ContractSubmissionJournalRecord,
        request: ContractSubmissionReconciliationRequest,
    ): ContractSubmissionReconciliationEvidenceKind =
        when (request.resolution) {
            ContractSubmissionReconciliationResolution.NO_ITEMS_REMOVED -> {
                require(record.reviewFromStatus == ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED) {
                    "No-items resolution requires an item-removal review"
                }
                require(request.providerBalanceAfterMinor == null && request.providerTransactionId == null) {
                    "No-items resolution does not accept provider evidence"
                }
                require(request.providerTransactionReason == null) { "No-items resolution does not accept provider evidence" }
                ContractSubmissionReconciliationEvidenceKind.OPERATOR_INVENTORY_INSPECTION
            }
            ContractSubmissionReconciliationResolution.ITEMS_REFUNDED -> {
                require(record.reviewFromStatus != null) { "Item-refund resolution lacks a review source" }
                if (record.reviewFromStatus == ContractSubmissionJournalStatus.PAYMENT_STARTED) {
                    require(request.providerBalanceAfterMinor == record.providerBalanceBeforeMinor) {
                        "Payment review can be refunded only after an unchanged provider balance is proven"
                    }
                    require(request.providerTransactionId == null) {
                        "A not-paid reconciliation cannot contain a provider transaction id"
                    }
                    require(request.providerTransactionReason == null) {
                        "A not-paid reconciliation cannot contain a provider transaction reason"
                    }
                    ContractSubmissionReconciliationEvidenceKind.OPERATOR_INVENTORY_AND_PROVIDER
                } else {
                    require(
                        request.providerBalanceAfterMinor == null &&
                            request.providerTransactionId == null &&
                            request.providerTransactionReason == null,
                    ) {
                        "Inventory-only refund resolution does not accept provider evidence"
                    }
                    ContractSubmissionReconciliationEvidenceKind.OPERATOR_INVENTORY_INSPECTION
                }
            }
            ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED -> {
                require(record.reviewFromStatus == ContractSubmissionJournalStatus.PAYMENT_STARTED) {
                    "Payment resolution requires a payment review"
                }
                val expectedAfter = Math.addExact(requireNotNull(record.providerBalanceBeforeMinor), record.payoutMinor)
                require(request.providerBalanceAfterMinor == expectedAfter) {
                    "Payment reconciliation balance does not match the exact planned payout"
                }
                require(!request.providerTransactionId.isNullOrBlank()) {
                    "Payment reconciliation requires provider history transaction evidence"
                }
                require(request.providerTransactionReason == record.payoutReason) {
                    "Payment reconciliation history reason does not match the journal correlation reason"
                }
                ContractSubmissionReconciliationEvidenceKind.OPERATOR_PROVIDER_BALANCE_AND_HISTORY
            }
        }

    private fun preview(
        record: ContractSubmissionJournalRecord,
        evidenceKind: ContractSubmissionReconciliationEvidenceKind,
        reviewDigest: String,
        alreadyApplied: Boolean,
    ): ContractSubmissionReconciliationPreview {
        val resolution = record.reconciliation?.resolution ?: error("Resolution is required for replay preview")
        return preview(record, resolution, evidenceKind, reviewDigest, alreadyApplied)
    }

    private fun preview(
        record: ContractSubmissionJournalRecord,
        resolution: ContractSubmissionReconciliationResolution,
        evidenceKind: ContractSubmissionReconciliationEvidenceKind,
        reviewDigest: String,
        alreadyApplied: Boolean,
    ): ContractSubmissionReconciliationPreview =
        ContractSubmissionReconciliationPreview(
            submissionId = record.submissionId,
            reviewedRevision = record.reconciliation?.reviewedRevision ?: record.revision,
            reviewFromStatus = record.reconciliation?.reviewFromStatus ?: requireNotNull(record.reviewFromStatus),
            reviewReason = record.reconciliation?.reviewReason ?: requireNotNull(record.reviewReason),
            resolution = resolution,
            evidenceKind = evidenceKind,
            proposedStatus =
                when (resolution) {
                    ContractSubmissionReconciliationResolution.NO_ITEMS_REMOVED -> ContractSubmissionJournalStatus.CANCELLED
                    ContractSubmissionReconciliationResolution.ITEMS_REFUNDED -> ContractSubmissionJournalStatus.REFUNDED
                    ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED ->
                        if (alreadyApplied) record.status else ContractSubmissionJournalStatus.PAID
                },
            reviewDigest = reviewDigest,
            alreadyApplied = alreadyApplied,
            commitsContractState = resolution == ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED,
        )

    private fun digest(
        record: ContractSubmissionJournalRecord,
        request: ContractSubmissionReconciliationRequest,
    ): String {
        val fields =
            listOf(
                "contract-reconciliation-v1",
                record.submissionId,
                record.revision.toString(),
                requireNotNull(record.reviewFromStatus).label,
                requireNotNull(record.reviewReason).label,
                requireNotNull(record.reviewEvidence),
                request.resolution.label,
                request.operatorId,
                request.operatorEvidence,
                request.idempotencyKey,
                request.providerBalanceAfterMinor?.toString().orEmpty(),
                request.providerTransactionId.orEmpty(),
                request.providerTransactionReason.orEmpty(),
            )
        val canonical = fields.joinToString("") { "${it.toByteArray(StandardCharsets.UTF_8).size}:$it" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun matches(
        existing: ContractSubmissionReconciliation,
        record: ContractSubmissionJournalRecord,
        request: ContractSubmissionReconciliationRequest,
    ): Boolean =
        record.submissionId == request.submissionId &&
            existing.reviewedRevision == request.expectedRevision &&
            existing.resolution == request.resolution &&
            existing.operatorId == request.operatorId &&
            existing.operatorEvidence == request.operatorEvidence &&
            existing.idempotencyKey == request.idempotencyKey &&
            when (request.resolution) {
                ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED ->
                    record.providerBalanceAfterMinor == request.providerBalanceAfterMinor &&
                        record.providerTransactionId == request.providerTransactionId &&
                        request.providerTransactionReason == record.payoutReason
                ContractSubmissionReconciliationResolution.ITEMS_REFUNDED ->
                    if (existing.reviewFromStatus == ContractSubmissionJournalStatus.PAYMENT_STARTED) {
                        record.providerBalanceAfterMinor == request.providerBalanceAfterMinor &&
                            request.providerTransactionId == null &&
                            request.providerTransactionReason == null
                    } else {
                        request.providerBalanceAfterMinor == null &&
                            request.providerTransactionId == null &&
                            request.providerTransactionReason == null
                    }
                ContractSubmissionReconciliationResolution.NO_ITEMS_REMOVED ->
                    request.providerBalanceAfterMinor == null &&
                        request.providerTransactionId == null &&
                        request.providerTransactionReason == null
            }

    private fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            actual.toByteArray(StandardCharsets.US_ASCII),
        )

    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}

data class ContractSubmissionRetentionPlan(
    val deleteSubmissionIds: List<String>,
    val totalBefore: Int,
    val totalAfter: Int,
    val terminalBefore: Int,
    val nonTerminalBefore: Int,
)

/** Deterministic bounded retention; ambiguous and otherwise non-terminal evidence is never deleted. */
object ContractSubmissionRetentionPolicy {
    const val TARGET_NETWORK_RECORDS = 3_072
    const val MIN_RECENT_TERMINAL_RECORDS = 256
    const val TERMINAL_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L

    fun plan(
        records: List<ContractSubmissionJournalRecord>,
        now: Long,
    ): ContractSubmissionRetentionPlan {
        require(now >= 0L) { "Retention timestamp must be non-negative" }
        require(records.size <= ContractSubmissionJournalAudit.MAX_NETWORK_RECORDS) {
            "Contract submission journal exceeds its network record limit"
        }
        val valid = records.map { it.validated() }
        require(valid.map { it.submissionId }.toSet().size == valid.size) {
            "Contract submission journal contains duplicate ids"
        }
        val terminal = valid.filter { it.isTerminal() }
        val recentProtected =
            terminal.sortedWith(compareByDescending<ContractSubmissionJournalRecord> { it.updatedAt }.thenBy { it.submissionId })
                .take(MIN_RECENT_TERMINAL_RECORDS)
                .mapTo(mutableSetOf()) { it.submissionId }
        val candidates =
            terminal.asSequence()
                .filterNot { it.submissionId in recentProtected }
                .sortedWith(compareBy<ContractSubmissionJournalRecord> { it.updatedAt }.thenBy { it.submissionId })
                .toList()
        val cutoff = now - TERMINAL_RETENTION_MILLIS
        val selected = LinkedHashSet<String>()
        candidates.filter { it.updatedAt <= cutoff }.forEach { selected += it.submissionId }
        val requiredForTarget = (valid.size - selected.size - TARGET_NETWORK_RECORDS).coerceAtLeast(0)
        candidates.asSequence()
            .filterNot { it.submissionId in selected }
            .take(requiredForTarget)
            .forEach { selected += it.submissionId }
        return ContractSubmissionRetentionPlan(
            deleteSubmissionIds = selected.toList(),
            totalBefore = valid.size,
            totalAfter = valid.size - selected.size,
            terminalBefore = terminal.size,
            nonTerminalBefore = valid.size - terminal.size,
        )
    }
}

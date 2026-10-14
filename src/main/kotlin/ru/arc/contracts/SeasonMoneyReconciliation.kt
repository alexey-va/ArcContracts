package ru.arc.contracts

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class SeasonMoneyReconciliationResolution(val label: String) {
    WITHDRAWAL_NOT_APPLIED("withdrawal_not_applied"),
    WITHDRAWAL_CONFIRMED("withdrawal_confirmed"),
}

enum class SeasonMoneyReconciliationEvidenceKind(val label: String) {
    OPERATOR_PROVIDER_BALANCE_AND_HISTORY("operator_provider_balance_and_history"),
}

/** Immutable authenticated-ops evidence attached after a manual review is adjudicated. */
data class SeasonMoneyReconciliation(
    val resolution: SeasonMoneyReconciliationResolution,
    val evidenceKind: SeasonMoneyReconciliationEvidenceKind,
    val operatorId: String,
    val operatorEvidence: String,
    val idempotencyKey: String,
    val reviewDigest: String,
    val reviewedRevision: Long,
    val reviewFromStatus: SeasonMoneyJournalStatus,
    val reviewReason: SeasonMoneyReviewReason,
    val originalReviewEvidence: String,
    val providerHistoryCheckedAt: Long,
    val providerBalanceAfterMinor: Long,
    val providerTransactionId: String?,
    val reconciledAt: Long,
) {
    fun validated(): SeasonMoneyReconciliation {
        ContractSubmissionReconciliation.validateOperatorId(operatorId)
        ContractSubmissionReconciliation.validateOperatorEvidence(operatorEvidence)
        ContractSubmissionReconciliation.validateIdempotencyKey(idempotencyKey)
        require(SHA256_PATTERN.matches(reviewDigest)) { "Invalid season money reconciliation review digest" }
        require(
            reviewedRevision >= 0L &&
                providerHistoryCheckedAt >= 0L &&
                providerHistoryCheckedAt <= reconciledAt,
        ) {
            "Invalid season money reconciliation revision or timestamp"
        }
        require(
            reviewFromStatus == SeasonMoneyJournalStatus.WITHDRAWAL_STARTED ||
                reviewFromStatus == SeasonMoneyJournalStatus.FUNDS_WITHDRAWN,
        ) { "Invalid season money reconciliation source state" }
        require(
            originalReviewEvidence.isNotBlank() &&
                originalReviewEvidence.length <= SeasonMoneyJournalRecord.MAX_EVIDENCE_LENGTH &&
                originalReviewEvidence.none(Char::isISOControl),
        ) { "Invalid original season money review evidence" }
        require(
            providerTransactionId == null ||
                providerTransactionId.isNotBlank() &&
                providerTransactionId.length <= SeasonMoneyJournalRecord.MAX_PROVIDER_TRANSACTION_ID_LENGTH &&
                providerTransactionId.none(Char::isISOControl),
        ) { "Invalid season money reconciliation provider transaction id" }
        when (resolution) {
            SeasonMoneyReconciliationResolution.WITHDRAWAL_NOT_APPLIED ->
                require(providerTransactionId == null) {
                    "A not-applied season withdrawal cannot contain a provider transaction id"
                }
            SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED ->
                require(providerTransactionId != null) {
                    "A confirmed season withdrawal requires a provider transaction id"
                }
        }
        return this
    }

    companion object {
        private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
    }
}

data class SeasonMoneyReconciliationRequest(
    val actionId: String,
    val expectedRevision: Long,
    val resolution: SeasonMoneyReconciliationResolution,
    val operatorId: String,
    val operatorEvidence: String,
    val idempotencyKey: String,
    val providerHistoryCheckedAt: Long,
    val providerBalanceAfterMinor: Long,
    val providerTransactionId: String? = null,
    val providerTransactionReason: String? = null,
) {
    fun validated(): SeasonMoneyReconciliationRequest {
        require(SeasonRuntimeState.validActionId(actionId)) { "Invalid season money reconciliation action id" }
        require(expectedRevision >= 0L && providerHistoryCheckedAt >= 0L) {
            "Invalid season money reconciliation revision or history timestamp"
        }
        ContractSubmissionReconciliation.validateOperatorId(operatorId)
        ContractSubmissionReconciliation.validateOperatorEvidence(operatorEvidence)
        ContractSubmissionReconciliation.validateIdempotencyKey(idempotencyKey)
        require(
            providerTransactionId == null ||
                providerTransactionId.isNotBlank() &&
                providerTransactionId.length <= SeasonMoneyJournalRecord.MAX_PROVIDER_TRANSACTION_ID_LENGTH &&
                providerTransactionId.none(Char::isISOControl),
        ) { "Invalid season money reconciliation provider transaction id" }
        require(
            providerTransactionReason == null ||
                SeasonMoneyJournalRecord.validEvidence(providerTransactionReason),
        ) { "Invalid season money reconciliation provider transaction reason" }
        return this
    }
}

data class SeasonMoneyReconciliationPreview(
    val actionId: String,
    val reviewedRevision: Long,
    val reviewFromStatus: SeasonMoneyJournalStatus,
    val reviewReason: SeasonMoneyReviewReason,
    val resolution: SeasonMoneyReconciliationResolution,
    val evidenceKind: SeasonMoneyReconciliationEvidenceKind,
    val proposedStatus: SeasonMoneyJournalStatus,
    val reviewDigest: String,
    val alreadyApplied: Boolean,
    val commitsSeasonState: Boolean,
)

data class SeasonMoneyReconciliationApplyResult(
    val preview: SeasonMoneyReconciliationPreview,
    val record: SeasonMoneyJournalRecord,
    val receipt: SeasonMoneyActionReceipt?,
    val replayed: Boolean,
)

/** Pure, replay-safe adjudication. It never calls the economy provider. */
object SeasonMoneyReconciliationEngine {
    fun preview(
        record: SeasonMoneyJournalRecord,
        request: SeasonMoneyReconciliationRequest,
    ): SeasonMoneyReconciliationPreview {
        val valid = record.validated()
        request.validated()
        valid.reconciliation?.let { existing ->
            require(matches(existing, valid, request)) {
                "Season money reconciliation replay disagrees with persisted evidence"
            }
            return preview(valid, existing.reviewDigest, alreadyApplied = true)
        }
        require(valid.status == SeasonMoneyJournalStatus.MANUAL_REVIEW) {
            "Season money action is not awaiting manual reconciliation"
        }
        require(valid.actionId == request.actionId) { "Season money reconciliation action id mismatch" }
        require(valid.revision == request.expectedRevision) { "Season money reconciliation revision is stale" }
        validateResolution(valid, request)
        return preview(valid, request.resolution, digest(valid, request), alreadyApplied = false)
    }

    fun apply(
        record: SeasonMoneyJournalRecord,
        request: SeasonMoneyReconciliationRequest,
        reviewDigest: String,
        now: Long,
    ): SeasonMoneyJournalRecord {
        val valid = record.validated()
        request.validated()
        require(SHA256_PATTERN.matches(reviewDigest)) { "Invalid season money reconciliation review digest" }
        valid.reconciliation?.let { existing ->
            require(matches(existing, valid, request) && constantTimeEquals(existing.reviewDigest, reviewDigest)) {
                "Season money reconciliation replay disagrees with persisted evidence"
            }
            return valid
        }
        val preview = preview(valid, request)
        require(constantTimeEquals(preview.reviewDigest, reviewDigest)) {
            "Season money reconciliation review digest is stale"
        }
        require(now >= valid.updatedAt && request.providerHistoryCheckedAt in valid.updatedAt..now) {
            "Season money reconciliation evidence timestamp is outside the review window"
        }
        val evidence =
            SeasonMoneyReconciliation(
                resolution = request.resolution,
                evidenceKind = SeasonMoneyReconciliationEvidenceKind.OPERATOR_PROVIDER_BALANCE_AND_HISTORY,
                operatorId = request.operatorId,
                operatorEvidence = request.operatorEvidence,
                idempotencyKey = request.idempotencyKey,
                reviewDigest = preview.reviewDigest,
                reviewedRevision = valid.revision,
                reviewFromStatus = requireNotNull(valid.reviewFromStatus),
                reviewReason = requireNotNull(valid.reviewReason),
                originalReviewEvidence = requireNotNull(valid.reviewEvidence),
                providerHistoryCheckedAt = request.providerHistoryCheckedAt,
                providerBalanceAfterMinor = request.providerBalanceAfterMinor,
                providerTransactionId = request.providerTransactionId,
                reconciledAt = now,
            ).validated()
        val nextRevision = Math.addExact(valid.revision, 1L)
        return when (request.resolution) {
            SeasonMoneyReconciliationResolution.WITHDRAWAL_NOT_APPLIED ->
                valid.copy(
                    status = SeasonMoneyJournalStatus.CANCELLED,
                    providerCallAttempted = valid.providerCallAttempted,
                    balanceAfterMinor = request.providerBalanceAfterMinor,
                    cancelledAt = now,
                    cancellationCode = "operator_proved_not_withdrawn",
                    reviewFromStatus = null,
                    reviewReason = null,
                    reviewEvidence = null,
                    providerTransactionId = null,
                    reconciliation = evidence,
                    updatedAt = now,
                    revision = nextRevision,
                )
            SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED ->
                valid.copy(
                    status = SeasonMoneyJournalStatus.FUNDS_WITHDRAWN,
                    providerCallAttempted = true,
                    balanceAfterMinor = request.providerBalanceAfterMinor,
                    fundsWithdrawnAt = valid.fundsWithdrawnAt ?: now,
                    reviewFromStatus = null,
                    reviewReason = null,
                    reviewEvidence = null,
                    providerTransactionId = request.providerTransactionId,
                    reconciliation = evidence,
                    updatedAt = now,
                    revision = nextRevision,
                )
        }.validated()
    }

    private fun validateResolution(
        record: SeasonMoneyJournalRecord,
        request: SeasonMoneyReconciliationRequest,
    ) {
        require(request.providerHistoryCheckedAt >= requireNotNull(record.withdrawalStartedAt)) {
            "Provider history evidence predates the season withdrawal intent"
        }
        if (record.reviewFromStatus == SeasonMoneyJournalStatus.FUNDS_WITHDRAWN) {
            require(request.resolution == SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED) {
                "Proven withdrawn funds cannot be reconciled as not applied"
            }
        }
        when (request.resolution) {
            SeasonMoneyReconciliationResolution.WITHDRAWAL_NOT_APPLIED -> {
                require(request.providerBalanceAfterMinor == record.balanceBeforeMinor) {
                    "Not-applied season withdrawal requires the unchanged provider balance"
                }
                require(request.providerTransactionId == null && request.providerTransactionReason == null) {
                    "Not-applied season withdrawal cannot contain provider transaction evidence"
                }
            }
            SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED -> {
                val expectedAfter = Math.subtractExact(requireNotNull(record.balanceBeforeMinor), record.amountMinor)
                require(request.providerBalanceAfterMinor == expectedAfter) {
                    "Confirmed season withdrawal balance does not match the exact planned burn"
                }
                require(!request.providerTransactionId.isNullOrBlank()) {
                    "Confirmed season withdrawal requires provider history transaction evidence"
                }
                require(request.providerTransactionReason == record.withdrawalReason) {
                    "Season withdrawal history reason does not match the journal correlation reason"
                }
            }
        }
    }

    private fun preview(
        record: SeasonMoneyJournalRecord,
        reviewDigest: String,
        alreadyApplied: Boolean,
    ): SeasonMoneyReconciliationPreview {
        val resolution = record.reconciliation?.resolution ?: error("Resolution is required for replay preview")
        return preview(record, resolution, reviewDigest, alreadyApplied)
    }

    private fun preview(
        record: SeasonMoneyJournalRecord,
        resolution: SeasonMoneyReconciliationResolution,
        reviewDigest: String,
        alreadyApplied: Boolean,
    ): SeasonMoneyReconciliationPreview =
        SeasonMoneyReconciliationPreview(
            actionId = record.actionId,
            reviewedRevision = record.reconciliation?.reviewedRevision ?: record.revision,
            reviewFromStatus = record.reconciliation?.reviewFromStatus ?: requireNotNull(record.reviewFromStatus),
            reviewReason = record.reconciliation?.reviewReason ?: requireNotNull(record.reviewReason),
            resolution = resolution,
            evidenceKind = SeasonMoneyReconciliationEvidenceKind.OPERATOR_PROVIDER_BALANCE_AND_HISTORY,
            proposedStatus =
                when (resolution) {
                    SeasonMoneyReconciliationResolution.WITHDRAWAL_NOT_APPLIED -> SeasonMoneyJournalStatus.CANCELLED
                    SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED ->
                        if (alreadyApplied) record.status else SeasonMoneyJournalStatus.FUNDS_WITHDRAWN
                },
            reviewDigest = reviewDigest,
            alreadyApplied = alreadyApplied,
            commitsSeasonState = resolution == SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED,
        )

    private fun digest(
        record: SeasonMoneyJournalRecord,
        request: SeasonMoneyReconciliationRequest,
    ): String {
        val fields =
            listOf(
                "season-money-reconciliation-v1",
                record.actionId,
                record.revision.toString(),
                requireNotNull(record.reviewFromStatus).label,
                requireNotNull(record.reviewReason).label,
                requireNotNull(record.reviewEvidence),
                request.resolution.label,
                request.operatorId,
                request.operatorEvidence,
                request.idempotencyKey,
                request.providerHistoryCheckedAt.toString(),
                request.providerBalanceAfterMinor.toString(),
                request.providerTransactionId.orEmpty(),
                request.providerTransactionReason.orEmpty(),
            )
        val canonical = fields.joinToString("") { "${it.toByteArray(StandardCharsets.UTF_8).size}:$it" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun matches(
        existing: SeasonMoneyReconciliation,
        record: SeasonMoneyJournalRecord,
        request: SeasonMoneyReconciliationRequest,
    ): Boolean =
        record.actionId == request.actionId &&
            existing.reviewedRevision == request.expectedRevision &&
            existing.resolution == request.resolution &&
            existing.operatorId == request.operatorId &&
            existing.operatorEvidence == request.operatorEvidence &&
            existing.idempotencyKey == request.idempotencyKey &&
            existing.providerHistoryCheckedAt == request.providerHistoryCheckedAt &&
            existing.providerBalanceAfterMinor == request.providerBalanceAfterMinor &&
            existing.providerTransactionId == request.providerTransactionId &&
            when (request.resolution) {
                SeasonMoneyReconciliationResolution.WITHDRAWAL_NOT_APPLIED ->
                    request.providerTransactionReason == null
                SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED ->
                    request.providerTransactionReason == record.withdrawalReason
            }

    private fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            actual.toByteArray(StandardCharsets.US_ASCII),
        )

    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}

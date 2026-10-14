package ru.arc.contracts

import ru.arc.repository.Entity

enum class SeasonMoneyJournalStatus(val label: String) {
    PREPARED("prepared"),
    WITHDRAWAL_STARTED("withdrawal_started"),
    FUNDS_WITHDRAWN("funds_withdrawn"),
    STATE_COMMITTED("state_committed"),
    CANCELLED("cancelled"),
    MANUAL_REVIEW("manual_review"),
}

enum class SeasonMoneyReviewReason(val label: String) {
    INTERRUPTED_WITHDRAWAL("interrupted_withdrawal"),
    PROVIDER_EVIDENCE_CONFLICT("provider_evidence_conflict"),
    STATE_EVIDENCE_CONFLICT("state_evidence_conflict"),
}

/**
 * Durable-before-side-effect evidence for a season money sink. The provider
 * withdrawal is never retried after WITHDRAWAL_STARTED unless an operator has
 * independently reconciled its exact outcome.
 */
data class SeasonMoneyJournalRecord(
    val actionId: String,
    val seasonId: String,
    val catalogDigest: String,
    val kind: SeasonMoneyActionKind,
    val targetId: String,
    val playerId: String,
    val amountMinor: Long,
    val expectedStateRevision: Long,
    val withdrawalReason: String,
    val status: SeasonMoneyJournalStatus = SeasonMoneyJournalStatus.PREPARED,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val withdrawalStartedAt: Long? = null,
    val providerCallAttempted: Boolean? = null,
    val balanceBeforeMinor: Long? = null,
    val balanceAfterMinor: Long? = null,
    val fundsWithdrawnAt: Long? = null,
    val stateCommittedAt: Long? = null,
    val cancelledAt: Long? = null,
    val cancellationCode: String? = null,
    val reviewFromStatus: SeasonMoneyJournalStatus? = null,
    val reviewReason: SeasonMoneyReviewReason? = null,
    val reviewEvidence: String? = null,
    val providerTransactionId: String? = null,
    val reconciliation: SeasonMoneyReconciliation? = null,
    val revision: Long = 0L,
) : Entity {
    init {
        validated()
    }

    override fun id(): String = actionId

    fun validated(): SeasonMoneyJournalRecord {
        require(SeasonRuntimeState.validActionId(actionId)) { "Invalid season money journal id" }
        require(SeasonRuntimeState.validTargetId(seasonId)) { "Invalid season money journal season" }
        require(SeasonRuntimeState.validDigest(catalogDigest)) { "Invalid season money journal catalog digest" }
        require(SeasonRuntimeState.validTargetId(targetId)) { "Invalid season money journal target" }
        require(SeasonRuntimeState.validPlayerId(playerId)) { "Invalid season money journal player" }
        require(amountMinor > 0L && expectedStateRevision >= 0L) { "Invalid season money journal plan" }
        require(withdrawalReason == withdrawalReason(kind, actionId)) { "Invalid season money withdrawal reason" }
        require(createdAt >= 0L && updatedAt >= createdAt && revision >= 0L) {
            "Invalid season money journal timestamps or revision"
        }
        require(
            providerTransactionId == null ||
                providerTransactionId.isNotBlank() &&
                providerTransactionId.length <= MAX_PROVIDER_TRANSACTION_ID_LENGTH &&
                providerTransactionId.none(Char::isISOControl),
        ) { "Invalid season money provider transaction id" }
        reconciliation?.validated()
        when (status) {
            SeasonMoneyJournalStatus.PREPARED -> {
                require(providerTransactionId == null && reconciliation == null) {
                    "Prepared season money journal contains reconciliation evidence"
                }
                requireNoMutationEvidence()
            }
            SeasonMoneyJournalStatus.WITHDRAWAL_STARTED -> {
                require(withdrawalStartedAt != null && balanceBeforeMinor != null) {
                    "Started season withdrawal requires a before balance"
                }
                require(balanceAfterMinor == null && fundsWithdrawnAt == null && stateCommittedAt == null) {
                    "Started season withdrawal contains later evidence"
                }
                require(providerCallAttempted == null) { "Started season withdrawal contains provider call evidence" }
                require(providerTransactionId == null && reconciliation == null) {
                    "Started season withdrawal contains reconciliation evidence"
                }
                requireNoTerminalEvidence()
            }
            SeasonMoneyJournalStatus.FUNDS_WITHDRAWN,
            SeasonMoneyJournalStatus.STATE_COMMITTED,
            -> {
                require(withdrawalStartedAt != null && balanceBeforeMinor != null && balanceAfterMinor != null) {
                    "Withdrawn season funds require exact balance evidence"
                }
                require(balanceAfterMinor == Math.subtractExact(balanceBeforeMinor, amountMinor)) {
                    "Season withdrawal balance delta does not match its amount"
                }
                require(providerCallAttempted == true) { "Proven season withdrawal must contain a provider call" }
                require(fundsWithdrawnAt != null && fundsWithdrawnAt >= withdrawalStartedAt) {
                    "Season withdrawal confirmation timestamp is invalid"
                }
                if (status == SeasonMoneyJournalStatus.STATE_COMMITTED) {
                    require(stateCommittedAt != null && stateCommittedAt >= fundsWithdrawnAt) {
                        "Season state commit timestamp is invalid"
                    }
                } else {
                    require(stateCommittedAt == null) { "Uncommitted season journal contains a commit timestamp" }
                }
                reconciliation?.let {
                    require(it.resolution == SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED) {
                        "Withdrawn season journal has the wrong reconciliation resolution"
                    }
                    require(
                        balanceAfterMinor == it.providerBalanceAfterMinor &&
                            providerTransactionId == it.providerTransactionId,
                    ) {
                        "Withdrawn season journal provider evidence disagrees with reconciliation"
                    }
                    require(it.reviewedRevision < revision && it.reconciledAt <= updatedAt) {
                        "Withdrawn season journal reconciliation revision is inconsistent"
                    }
                }
                requireNoTerminalEvidence()
            }
            SeasonMoneyJournalStatus.CANCELLED -> {
                require(cancelledAt != null && cancelledAt >= createdAt && validEvidence(cancellationCode)) {
                    "Cancelled season money journal requires bounded evidence"
                }
                require(fundsWithdrawnAt == null && stateCommittedAt == null) {
                    "Cancelled season money journal cannot contain a proven withdrawal or commit"
                }
                if (reconciliation?.resolution == SeasonMoneyReconciliationResolution.WITHDRAWAL_NOT_APPLIED) {
                    require(withdrawalStartedAt != null && balanceBeforeMinor != null && balanceAfterMinor == balanceBeforeMinor) {
                        "Reconciled season cancellation must prove an unchanged provider balance"
                    }
                    require(providerCallAttempted != false) {
                        "Reconciled season cancellation contradicts proven skipped-call evidence"
                    }
                } else if (withdrawalStartedAt == null) {
                    require(providerCallAttempted == null && balanceBeforeMinor == null && balanceAfterMinor == null) {
                        "Pre-withdrawal cancellation contains provider evidence"
                    }
                } else if (providerCallAttempted == false) {
                    require(balanceBeforeMinor != null) {
                        "Skipped provider call requires the durable before balance"
                    }
                } else {
                    require(providerCallAttempted == true && balanceBeforeMinor != null && balanceAfterMinor == balanceBeforeMinor) {
                        "Provider cancellation must prove an unchanged balance"
                    }
                }
                require(reviewFromStatus == null && reviewReason == null && reviewEvidence == null) {
                    "Cancelled season money journal contains review evidence"
                }
                reconciliation?.let {
                    require(it.resolution == SeasonMoneyReconciliationResolution.WITHDRAWAL_NOT_APPLIED) {
                        "Cancelled season journal has the wrong reconciliation resolution"
                    }
                    require(
                        providerTransactionId == null &&
                            balanceAfterMinor == balanceBeforeMinor &&
                            balanceAfterMinor == it.providerBalanceAfterMinor,
                    ) {
                        "Cancelled season reconciliation must prove no withdrawal"
                    }
                    require(it.reviewedRevision < revision && it.reconciledAt <= updatedAt) {
                        "Cancelled season journal reconciliation revision is inconsistent"
                    }
                } ?: require(providerTransactionId == null) {
                    "Cancelled season journal contains unbound provider transaction evidence"
                }
            }
            SeasonMoneyJournalStatus.MANUAL_REVIEW -> {
                require(reviewFromStatus != null && reviewFromStatus != SeasonMoneyJournalStatus.MANUAL_REVIEW) {
                    "Season manual review requires its previous state"
                }
                require(reviewReason != null && validEvidence(reviewEvidence)) {
                    "Season manual review requires bounded evidence"
                }
                require(cancelledAt == null && cancellationCode == null && stateCommittedAt == null) {
                    "Season manual review contains terminal evidence"
                }
                require(providerTransactionId == null && reconciliation == null) {
                    "Unresolved season manual review contains reconciliation evidence"
                }
            }
        }
        return this
    }

    private fun requireNoMutationEvidence() {
        require(
            withdrawalStartedAt == null && balanceBeforeMinor == null && balanceAfterMinor == null &&
                providerCallAttempted == null && fundsWithdrawnAt == null && stateCommittedAt == null,
        ) { "Prepared season money journal contains mutation evidence" }
        requireNoTerminalEvidence()
    }

    private fun requireNoTerminalEvidence() {
        require(
            cancelledAt == null && cancellationCode == null && reviewFromStatus == null &&
                reviewReason == null && reviewEvidence == null,
        ) { "Active season money journal contains terminal evidence" }
    }

    companion object {
        const val MAX_EVIDENCE_LENGTH = 128
        const val MAX_PROVIDER_TRANSACTION_ID_LENGTH = 128

        fun withdrawalReason(kind: SeasonMoneyActionKind, actionId: String): String =
            "arc-season:${kind.ledgerSource}:$actionId"

        fun validEvidence(value: String?): Boolean =
            value != null && value.isNotBlank() && value.length <= MAX_EVIDENCE_LENGTH && value.none(Char::isISOControl)
    }
}

object SeasonMoneyJournalEngine {
    fun prepare(
        catalog: ObserveSeasonCatalog,
        plan: SeasonMoneyActionPlan.Accepted,
        now: Long,
    ): SeasonMoneyJournalRecord {
        require(plan.catalogDigest == catalog.revisionDigest()) { "Season money plan catalog changed before prepare" }
        require(now >= plan.plannedAt) { "Season money journal precedes its plan" }
        return SeasonMoneyJournalRecord(
            actionId = plan.actionId,
            seasonId = catalog.id,
            catalogDigest = plan.catalogDigest,
            kind = plan.kind,
            targetId = plan.targetId,
            playerId = plan.playerId,
            amountMinor = plan.amountMinor,
            expectedStateRevision = plan.expectedStateRevision,
            withdrawalReason = SeasonMoneyJournalRecord.withdrawalReason(plan.kind, plan.actionId),
            createdAt = now,
        )
    }

    fun beginWithdrawal(
        prepared: SeasonMoneyJournalRecord,
        balanceBeforeMinor: Long,
        now: Long,
    ): SeasonMoneyJournalRecord {
        require(prepared.status == SeasonMoneyJournalStatus.PREPARED) { "Season money journal is not prepared" }
        require(now >= prepared.updatedAt) { "Season withdrawal start timestamp moved backwards" }
        require(balanceBeforeMinor >= prepared.amountMinor) { "Season withdrawal before balance is insufficient" }
        return prepared.copy(
            status = SeasonMoneyJournalStatus.WITHDRAWAL_STARTED,
            withdrawalStartedAt = now,
            balanceBeforeMinor = balanceBeforeMinor,
            updatedAt = now,
            revision = Math.addExact(prepared.revision, 1L),
        )
    }

    fun confirmFundsWithdrawn(
        started: SeasonMoneyJournalRecord,
        balanceAfterMinor: Long,
        now: Long,
    ): SeasonMoneyJournalRecord {
        require(started.status == SeasonMoneyJournalStatus.WITHDRAWAL_STARTED) {
            "Season money withdrawal is not in progress"
        }
        require(balanceAfterMinor == Math.subtractExact(requireNotNull(started.balanceBeforeMinor), started.amountMinor)) {
            "Season money withdrawal confirmation has the wrong balance"
        }
        return started.copy(
            status = SeasonMoneyJournalStatus.FUNDS_WITHDRAWN,
            providerCallAttempted = true,
            balanceAfterMinor = balanceAfterMinor,
            fundsWithdrawnAt = now,
            updatedAt = now,
            revision = Math.addExact(started.revision, 1L),
        )
    }

    fun confirmStateCommitted(
        withdrawn: SeasonMoneyJournalRecord,
        now: Long,
    ): SeasonMoneyJournalRecord {
        require(withdrawn.status == SeasonMoneyJournalStatus.FUNDS_WITHDRAWN) {
            "Season money journal does not contain proven funds"
        }
        return withdrawn.copy(
            status = SeasonMoneyJournalStatus.STATE_COMMITTED,
            stateCommittedAt = now,
            updatedAt = now,
            revision = Math.addExact(withdrawn.revision, 1L),
        )
    }

    fun cancelPrepared(
        prepared: SeasonMoneyJournalRecord,
        code: String,
        now: Long,
    ): SeasonMoneyJournalRecord {
        require(prepared.status == SeasonMoneyJournalStatus.PREPARED) { "Season money journal is not prepared" }
        require(SeasonMoneyJournalRecord.validEvidence(code)) { "Invalid season cancellation evidence" }
        return prepared.copy(
            status = SeasonMoneyJournalStatus.CANCELLED,
            cancelledAt = now,
            cancellationCode = code,
            updatedAt = now,
            revision = Math.addExact(prepared.revision, 1L),
        )
    }

    fun confirmWithdrawalFailed(
        started: SeasonMoneyJournalRecord,
        unchangedBalanceMinor: Long,
        code: String,
        now: Long,
    ): SeasonMoneyJournalRecord {
        require(started.status == SeasonMoneyJournalStatus.WITHDRAWAL_STARTED) {
            "Season money withdrawal is not in progress"
        }
        require(unchangedBalanceMinor == started.balanceBeforeMinor) {
            "Season withdrawal failure did not prove an unchanged balance"
        }
        require(SeasonMoneyJournalRecord.validEvidence(code)) { "Invalid season cancellation evidence" }
        return started.copy(
            status = SeasonMoneyJournalStatus.CANCELLED,
            providerCallAttempted = true,
            balanceAfterMinor = unchangedBalanceMinor,
            cancelledAt = now,
            cancellationCode = code,
            updatedAt = now,
            revision = Math.addExact(started.revision, 1L),
        )
    }

    fun confirmNoProviderCall(
        started: SeasonMoneyJournalRecord,
        observedBalanceMinor: Long?,
        code: String,
        now: Long,
    ): SeasonMoneyJournalRecord {
        require(started.status == SeasonMoneyJournalStatus.WITHDRAWAL_STARTED) {
            "Season money withdrawal is not in progress"
        }
        require(SeasonMoneyJournalRecord.validEvidence(code)) { "Invalid season cancellation evidence" }
        return started.copy(
            status = SeasonMoneyJournalStatus.CANCELLED,
            providerCallAttempted = false,
            balanceAfterMinor = observedBalanceMinor,
            cancelledAt = now,
            cancellationCode = code,
            updatedAt = now,
            revision = Math.addExact(started.revision, 1L),
        )
    }

    fun haltForReview(
        record: SeasonMoneyJournalRecord,
        reason: SeasonMoneyReviewReason,
        evidence: String,
        balanceAfterMinor: Long?,
        providerCallAttempted: Boolean? = record.providerCallAttempted,
        now: Long,
    ): SeasonMoneyJournalRecord {
        require(
            record.status == SeasonMoneyJournalStatus.WITHDRAWAL_STARTED ||
                record.status == SeasonMoneyJournalStatus.FUNDS_WITHDRAWN,
        ) { "Season money journal cannot enter review from ${record.status.label}" }
        require(SeasonMoneyJournalRecord.validEvidence(evidence)) { "Invalid season review evidence" }
        return record.copy(
            status = SeasonMoneyJournalStatus.MANUAL_REVIEW,
            providerCallAttempted = providerCallAttempted,
            balanceAfterMinor = balanceAfterMinor ?: record.balanceAfterMinor,
            reviewFromStatus = record.status,
            reviewReason = reason,
            reviewEvidence = evidence,
            updatedAt = now,
            revision = Math.addExact(record.revision, 1L),
        )
    }
}

data class SeasonMoneyJournalSummary(
    val available: Boolean,
    val records: Int,
    val statusCounts: Map<String, Int>,
    val pendingBurnMinor: Long,
    val ambiguousBurnMinor: Long,
    val manualReviewCount: Int,
)

object SeasonMoneyJournalAudit {
    const val MAX_NETWORK_RECORDS = 4_096

    fun summarize(records: Collection<SeasonMoneyJournalRecord>): SeasonMoneyJournalSummary {
        require(records.size <= MAX_NETWORK_RECORDS) { "Season money journal capacity exceeded" }
        val validated = records.onEach { it.validated() }
        require(validated.map { it.actionId }.toSet().size == validated.size) {
            "Season money journal contains duplicate action ids"
        }
        val counts = SeasonMoneyJournalStatus.entries.associate { status ->
            status.label to validated.count { it.status == status }
        }
        return SeasonMoneyJournalSummary(
            available = true,
            records = validated.size,
            statusCounts = counts,
            pendingBurnMinor =
                validated.filter { it.status == SeasonMoneyJournalStatus.WITHDRAWAL_STARTED }
                    .fold(0L) { total, record -> Math.addExact(total, record.amountMinor) },
            ambiguousBurnMinor =
                validated.filter { it.status == SeasonMoneyJournalStatus.MANUAL_REVIEW }
                    .fold(0L) { total, record -> Math.addExact(total, record.amountMinor) },
            manualReviewCount = counts.getValue(SeasonMoneyJournalStatus.MANUAL_REVIEW.label),
        )
    }

    fun unavailable(): SeasonMoneyJournalSummary =
        SeasonMoneyJournalSummary(
            available = false,
            records = 0,
            statusCounts = SeasonMoneyJournalStatus.entries.associate { it.label to 0 },
            pendingBurnMinor = 0L,
            ambiguousBurnMinor = 0L,
            manualReviewCount = 0,
        )
}

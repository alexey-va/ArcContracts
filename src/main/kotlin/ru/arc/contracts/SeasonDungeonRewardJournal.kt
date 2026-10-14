package ru.arc.contracts

import ru.arc.repository.Entity

enum class SeasonDungeonRewardJournalStatus(val label: String) {
    PREPARED("prepared"),
    CANCELLED("cancelled"),
    PAYMENT_STARTED("payment_started"),
    PAID("paid"),
    TROPHY_DELIVERY_STARTED("trophy_delivery_started"),
    TROPHY_DELIVERED("trophy_delivered"),
    STATE_COMMITTED("state_committed"),
    MANUAL_REVIEW("manual_review"),
}

enum class SeasonDungeonRewardReviewReason(val label: String) {
    INTERRUPTED_PAYMENT("interrupted_payment"),
    PROVIDER_EVIDENCE_CONFLICT("provider_evidence_conflict"),
    PROVIDER_REJECTED("provider_rejected"),
    INTERRUPTED_TROPHY_DELIVERY("interrupted_trophy_delivery"),
    TROPHY_DELIVERY_AMBIGUOUS("trophy_delivery_ambiguous"),
    TROPHY_DELIVERY_ATTEMPT_LIMIT("trophy_delivery_attempt_limit"),
    STATE_EVIDENCE_CONFLICT("state_evidence_conflict"),
}

data class SeasonDungeonRewardJournalRecord(
    val rewardId: String,
    val runId: String,
    val catalogDigest: String,
    val dungeonContractId: String,
    val instanceWorld: String,
    val playerId: String,
    val payoutMinor: Long,
    val trophyItemKey: String,
    val activeShare: Double,
    val expectedStateRevision: Long,
    val paymentReason: String,
    val trophyPayload: EscrowedItemPayload,
    val status: SeasonDungeonRewardJournalStatus = SeasonDungeonRewardJournalStatus.PREPARED,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val cancelledAt: Long? = null,
    val cancellationCode: String? = null,
    val paymentStartedAt: Long? = null,
    val providerBalanceBeforeMinor: Long? = null,
    val providerBalanceAfterMinor: Long? = null,
    val providerTransactionId: String? = null,
    val paidAt: Long? = null,
    val trophyDeliveryStartedAt: Long? = null,
    val trophyDeliveredAt: Long? = null,
    val trophyDeliveryAttempts: Int = 0,
    val lastTrophyDeliveryFailure: String? = null,
    val stateCommittedAt: Long? = null,
    val reviewFromStatus: SeasonDungeonRewardJournalStatus? = null,
    val reviewReason: SeasonDungeonRewardReviewReason? = null,
    val reviewEvidence: String? = null,
    val revision: Long = 0L,
) : Entity {
    init {
        validated()
    }

    override fun id(): String = rewardId

    fun validated(): SeasonDungeonRewardJournalRecord {
        require(SeasonRuntimeState.validActionId(rewardId)) { "Invalid dungeon reward journal id" }
        require(DungeonAdmissionPass.validRunId(runId)) { "Invalid dungeon reward journal run id" }
        require(SeasonRuntimeState.validDigest(catalogDigest)) { "Invalid dungeon reward journal catalog digest" }
        require(SeasonRuntimeState.validTargetId(dungeonContractId)) { "Invalid dungeon reward journal contract id" }
        require(SeasonDungeonLaunchToken.validWorldName(instanceWorld)) { "Invalid dungeon reward journal instance world" }
        require(SeasonRuntimeState.validPlayerId(playerId)) { "Invalid dungeon reward journal player id" }
        require(payoutMinor > 0L && expectedStateRevision >= 0L) { "Invalid dungeon reward journal payout plan" }
        require(activeShare.isFinite() && activeShare in 0.0..1.0) { "Invalid dungeon reward journal active share" }
        require(ResourceContractDefinition.normalizeItemKey(trophyItemKey) == trophyItemKey) {
            "Dungeon reward journal trophy key must be normalized"
        }
        require(paymentReason == paymentReason(rewardId)) { "Invalid dungeon reward payment reason" }
        trophyPayload.validated()
        require(trophyPayload.itemKey == trophyItemKey && trophyPayload.quantity == 1) {
            "Dungeon reward trophy payload does not match its plan"
        }
        require(createdAt >= 0L && updatedAt >= createdAt && revision >= 0L) {
            "Invalid dungeon reward journal timestamps or revision"
        }
        require(trophyDeliveryAttempts in 0..MAX_DELIVERY_ATTEMPTS) { "Invalid dungeon reward delivery attempt count" }
        listOfNotNull(
            cancelledAt,
            paymentStartedAt,
            paidAt,
            trophyDeliveryStartedAt,
            trophyDeliveredAt,
            stateCommittedAt,
        ).forEach { require(it in createdAt..updatedAt) { "Dungeon reward journal phase timestamp is out of bounds" } }
        validateText(cancellationCode, "cancellation code")
        validateText(providerTransactionId, "provider transaction id")
        validateText(lastTrophyDeliveryFailure, "trophy delivery failure")
        validateText(reviewEvidence, "review evidence")
        require(providerBalanceBeforeMinor == null || paymentStartedAt != null) {
            "Dungeon reward balance evidence lacks payment start"
        }
        require(providerBalanceAfterMinor == null || paymentStartedAt != null) {
            "Dungeon reward result evidence lacks payment start"
        }
        require(paidAt == null || paymentStartedAt != null) { "Dungeon reward payment confirmation lacks start" }
        require(trophyDeliveryStartedAt == null || paidAt != null) { "Dungeon reward trophy delivery lacks payment" }
        require(trophyDeliveredAt == null || trophyDeliveryStartedAt != null) {
            "Dungeon reward trophy confirmation lacks delivery start"
        }
        require(stateCommittedAt == null || trophyDeliveredAt != null) {
            "Dungeon reward state commit lacks trophy confirmation"
        }
        validateStatusShape()
        return this
    }

    fun toPlan(expectedRevision: Long = expectedStateRevision): SeasonDungeonRewardPlan.Accepted =
        SeasonDungeonRewardPlan.Accepted(
            rewardId = rewardId,
            runId = runId,
            catalogDigest = catalogDigest,
            dungeonContractId = dungeonContractId,
            instanceWorld = instanceWorld,
            playerId = playerId,
            payoutMinor = payoutMinor,
            trophyItemKey = trophyItemKey,
            activeShare = activeShare,
            expectedStateRevision = expectedRevision,
            plannedAt = createdAt,
        )

    private fun validateStatusShape() {
        when (status) {
            SeasonDungeonRewardJournalStatus.PREPARED ->
                require(paymentStartedAt == null && cancelledAt == null) { "Prepared dungeon reward already mutated" }
            SeasonDungeonRewardJournalStatus.CANCELLED ->
                require(cancelledAt != null && paymentStartedAt == null && !cancellationCode.isNullOrBlank()) {
                    "Invalid cancelled dungeon reward"
                }
            SeasonDungeonRewardJournalStatus.PAYMENT_STARTED ->
                require(paymentStartedAt != null && providerBalanceBeforeMinor != null && paidAt == null) {
                    "Invalid dungeon reward payment-start state"
                }
            SeasonDungeonRewardJournalStatus.PAID ->
                require(paidAt != null && providerBalanceAfterMinor != null && trophyDeliveredAt == null) {
                    "Invalid paid dungeon reward state"
                }
            SeasonDungeonRewardJournalStatus.TROPHY_DELIVERY_STARTED ->
                require(paidAt != null && trophyDeliveryStartedAt != null && trophyDeliveredAt == null) {
                    "Invalid dungeon reward trophy-start state"
                }
            SeasonDungeonRewardJournalStatus.TROPHY_DELIVERED ->
                require(trophyDeliveredAt != null && stateCommittedAt == null) { "Invalid delivered dungeon reward state" }
            SeasonDungeonRewardJournalStatus.STATE_COMMITTED ->
                require(stateCommittedAt != null) { "Invalid committed dungeon reward state" }
            SeasonDungeonRewardJournalStatus.MANUAL_REVIEW ->
                require(reviewFromStatus != null && reviewReason != null && !reviewEvidence.isNullOrBlank()) {
                    "Dungeon reward manual review lacks evidence"
                }
        }
        if (status != SeasonDungeonRewardJournalStatus.MANUAL_REVIEW) {
            require(reviewFromStatus == null && reviewReason == null && reviewEvidence == null) {
                "Non-review dungeon reward contains review evidence"
            }
        }
    }

    private fun validateText(value: String?, label: String) {
        if (value == null) return
        require(value.isNotBlank() && value.length <= MAX_EVIDENCE_LENGTH && value.none(Char::isISOControl)) {
            "Invalid dungeon reward $label"
        }
    }

    companion object {
        const val MAX_EVIDENCE_LENGTH = 256
        const val MAX_DELIVERY_ATTEMPTS = 16

        fun paymentReason(rewardId: String): String = "arc-season:dungeon_reward:$rewardId"
    }
}

data class SeasonDungeonRewardJournalSummary(
    val available: Boolean,
    val records: Long,
    val statusCounts: Map<String, Long>,
    val pendingPayoutMinor: Long,
    val manualReviewPayoutMinor: Long,
) {
    companion object {
        fun unavailable() = SeasonDungeonRewardJournalSummary(false, 0L, emptyMap(), 0L, 0L)
    }
}

object SeasonDungeonRewardJournalAudit {
    const val MAX_NETWORK_RECORDS = 512

    fun summarize(records: List<SeasonDungeonRewardJournalRecord>): SeasonDungeonRewardJournalSummary {
        require(records.size <= MAX_NETWORK_RECORDS) { "Season dungeon reward journal capacity exceeded" }
        val valid = records.onEach { it.validated() }
        require(valid.map { it.rewardId }.toSet().size == valid.size) { "Duplicate season dungeon reward journal id" }
        val pending =
            valid.filter {
                it.status == SeasonDungeonRewardJournalStatus.PAID ||
                    it.status == SeasonDungeonRewardJournalStatus.TROPHY_DELIVERED
            }.sumOf { it.payoutMinor }
        val review = valid.filter { it.status == SeasonDungeonRewardJournalStatus.MANUAL_REVIEW }.sumOf { it.payoutMinor }
        return SeasonDungeonRewardJournalSummary(
            available = true,
            records = valid.size.toLong(),
            statusCounts = valid.groupingBy { it.status.label }.eachCount().mapValues { it.value.toLong() },
            pendingPayoutMinor = pending,
            manualReviewPayoutMinor = review,
        )
    }
}

object SeasonDungeonRewardJournalEngine {
    fun prepare(
        plan: SeasonDungeonRewardPlan.Accepted,
        trophyPayload: EscrowedItemPayload,
        now: Long,
    ): SeasonDungeonRewardJournalRecord =
        SeasonDungeonRewardJournalRecord(
            rewardId = plan.rewardId,
            runId = plan.runId,
            catalogDigest = plan.catalogDigest,
            dungeonContractId = plan.dungeonContractId,
            instanceWorld = plan.instanceWorld,
            playerId = plan.playerId,
            payoutMinor = plan.payoutMinor,
            trophyItemKey = plan.trophyItemKey,
            activeShare = plan.activeShare,
            expectedStateRevision = plan.expectedStateRevision,
            paymentReason = SeasonDungeonRewardJournalRecord.paymentReason(plan.rewardId),
            trophyPayload = trophyPayload.validated(),
            createdAt = now,
        ).validated()

    fun beginPayment(record: SeasonDungeonRewardJournalRecord, balanceBeforeMinor: Long, now: Long) =
        advance(record, SeasonDungeonRewardJournalStatus.PREPARED, now) {
            copy(
                status = SeasonDungeonRewardJournalStatus.PAYMENT_STARTED,
                paymentStartedAt = now,
                providerBalanceBeforeMinor = balanceBeforeMinor,
            )
        }

    fun confirmPaid(
        record: SeasonDungeonRewardJournalRecord,
        balanceAfterMinor: Long,
        transactionId: String?,
        now: Long,
    ) = advance(record, SeasonDungeonRewardJournalStatus.PAYMENT_STARTED, now) {
        require(balanceAfterMinor == Math.addExact(requireNotNull(providerBalanceBeforeMinor), payoutMinor)) {
            "Dungeon reward payout evidence does not match its amount"
        }
        copy(
            status = SeasonDungeonRewardJournalStatus.PAID,
            providerBalanceAfterMinor = balanceAfterMinor,
            providerTransactionId = transactionId,
            paidAt = now,
        )
    }

    fun beginTrophyDelivery(record: SeasonDungeonRewardJournalRecord, now: Long) =
        advance(record, SeasonDungeonRewardJournalStatus.PAID, now) {
            require(trophyDeliveryAttempts < SeasonDungeonRewardJournalRecord.MAX_DELIVERY_ATTEMPTS) {
                "Dungeon reward trophy delivery attempt capacity reached"
            }
            copy(
                status = SeasonDungeonRewardJournalStatus.TROPHY_DELIVERY_STARTED,
                trophyDeliveryStartedAt = now,
                trophyDeliveryAttempts = trophyDeliveryAttempts + 1,
                lastTrophyDeliveryFailure = null,
            )
        }

    /** The adapter proved no slot changed, so the exact delivery may be retried later. */
    fun confirmTrophyNotDelivered(record: SeasonDungeonRewardJournalRecord, code: String, now: Long) =
        advance(record, SeasonDungeonRewardJournalStatus.TROPHY_DELIVERY_STARTED, now) {
            copy(
                status = SeasonDungeonRewardJournalStatus.PAID,
                trophyDeliveryStartedAt = null,
                lastTrophyDeliveryFailure = stableCode(code),
            )
        }

    fun confirmTrophyDelivered(record: SeasonDungeonRewardJournalRecord, now: Long) =
        advance(record, SeasonDungeonRewardJournalStatus.TROPHY_DELIVERY_STARTED, now) {
            copy(status = SeasonDungeonRewardJournalStatus.TROPHY_DELIVERED, trophyDeliveredAt = now)
        }

    fun confirmStateCommitted(record: SeasonDungeonRewardJournalRecord, now: Long): SeasonDungeonRewardJournalRecord {
        if (record.status == SeasonDungeonRewardJournalStatus.STATE_COMMITTED) return record.validated()
        return advance(record, SeasonDungeonRewardJournalStatus.TROPHY_DELIVERED, now) {
            copy(status = SeasonDungeonRewardJournalStatus.STATE_COMMITTED, stateCommittedAt = now)
        }
    }

    fun cancelPrepared(record: SeasonDungeonRewardJournalRecord, code: String, now: Long) =
        advance(record, SeasonDungeonRewardJournalStatus.PREPARED, now) {
            copy(
                status = SeasonDungeonRewardJournalStatus.CANCELLED,
                cancelledAt = now,
                cancellationCode = stableCode(code),
            )
        }

    fun haltPayment(
        record: SeasonDungeonRewardJournalRecord,
        reason: SeasonDungeonRewardReviewReason,
        evidence: String,
        balanceAfterMinor: Long?,
        now: Long,
    ) = halt(record, SeasonDungeonRewardJournalStatus.PAYMENT_STARTED, reason, evidence, now) {
        copy(providerBalanceAfterMinor = balanceAfterMinor)
    }

    fun haltTrophyDelivery(
        record: SeasonDungeonRewardJournalRecord,
        reason: SeasonDungeonRewardReviewReason,
        evidence: String,
        now: Long,
    ) = halt(record, SeasonDungeonRewardJournalStatus.TROPHY_DELIVERY_STARTED, reason, evidence, now)

    fun haltPaid(
        record: SeasonDungeonRewardJournalRecord,
        reason: SeasonDungeonRewardReviewReason,
        evidence: String,
        now: Long,
    ) = halt(record, SeasonDungeonRewardJournalStatus.PAID, reason, evidence, now)

    fun haltStateCommit(record: SeasonDungeonRewardJournalRecord, evidence: String, now: Long) =
        halt(
            record,
            SeasonDungeonRewardJournalStatus.TROPHY_DELIVERED,
            SeasonDungeonRewardReviewReason.STATE_EVIDENCE_CONFLICT,
            evidence,
            now,
        )

    fun recoverInterrupted(record: SeasonDungeonRewardJournalRecord, now: Long): SeasonDungeonRewardJournalRecord =
        when (record.validated().status) {
            SeasonDungeonRewardJournalStatus.PAYMENT_STARTED ->
                haltPayment(
                    record,
                    SeasonDungeonRewardReviewReason.INTERRUPTED_PAYMENT,
                    "restart_after_payment_intent",
                    record.providerBalanceAfterMinor,
                    now,
                )
            SeasonDungeonRewardJournalStatus.TROPHY_DELIVERY_STARTED ->
                haltTrophyDelivery(
                    record,
                    SeasonDungeonRewardReviewReason.INTERRUPTED_TROPHY_DELIVERY,
                    "restart_after_trophy_delivery_intent",
                    now,
                )
            else -> record
        }

    private fun advance(
        record: SeasonDungeonRewardJournalRecord,
        expected: SeasonDungeonRewardJournalStatus,
        now: Long,
        update: SeasonDungeonRewardJournalRecord.() -> SeasonDungeonRewardJournalRecord,
    ): SeasonDungeonRewardJournalRecord {
        val current = record.validated()
        require(current.status == expected) { "Expected dungeon reward status ${expected.label}, got ${current.status.label}" }
        require(now >= current.updatedAt) { "Dungeon reward journal time moved backwards" }
        val versioned = current.copy(updatedAt = now, revision = Math.addExact(current.revision, 1L))
        return versioned.update().validated()
    }

    private fun halt(
        record: SeasonDungeonRewardJournalRecord,
        expected: SeasonDungeonRewardJournalStatus,
        reason: SeasonDungeonRewardReviewReason,
        evidence: String,
        now: Long,
        update: SeasonDungeonRewardJournalRecord.() -> SeasonDungeonRewardJournalRecord = { this },
    ): SeasonDungeonRewardJournalRecord =
        advance(record, expected, now) {
            update().copy(
                status = SeasonDungeonRewardJournalStatus.MANUAL_REVIEW,
                reviewFromStatus = expected,
                reviewReason = reason,
                reviewEvidence = evidence.take(SeasonDungeonRewardJournalRecord.MAX_EVIDENCE_LENGTH),
            )
        }

    private fun stableCode(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').take(80).ifBlank { "unknown" }
}

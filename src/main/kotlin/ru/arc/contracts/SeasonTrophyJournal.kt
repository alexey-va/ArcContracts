package ru.arc.contracts

import ru.arc.repository.Entity

enum class SeasonTrophyJournalStatus(val label: String) {
    PREPARED("prepared"),
    CANCELLED("cancelled"),
    ITEM_REMOVAL_STARTED("item_removal_started"),
    ITEMS_REMOVED("items_removed"),
    STATE_COMMITTED("state_committed"),
    MANUAL_REVIEW("manual_review"),
}

enum class SeasonTrophyReviewReason(val label: String) {
    INTERRUPTED_ITEM_REMOVAL("interrupted_item_removal"),
    INVENTORY_EVIDENCE_CONFLICT("inventory_evidence_conflict"),
    STATE_EVIDENCE_CONFLICT("state_evidence_conflict"),
}

data class SeasonTrophyJournalRecord(
    val contributionId: String,
    val seasonId: String,
    val catalogDigest: String,
    val stageId: String,
    val itemKey: String,
    val playerId: String,
    val requestedQuantity: Int,
    val acceptedQuantity: Int,
    val expectedStateRevision: Long,
    val itemPayloads: List<EscrowedItemPayload>,
    val status: SeasonTrophyJournalStatus = SeasonTrophyJournalStatus.PREPARED,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val cancelledAt: Long? = null,
    val cancellationCode: String? = null,
    val itemRemovalStartedAt: Long? = null,
    val itemsRemovedAt: Long? = null,
    val stateCommittedAt: Long? = null,
    val reviewFromStatus: SeasonTrophyJournalStatus? = null,
    val reviewReason: SeasonTrophyReviewReason? = null,
    val reviewEvidence: String? = null,
    val revision: Long = 0L,
) : Entity {
    init {
        validated()
    }

    override fun id(): String = contributionId

    fun validated(): SeasonTrophyJournalRecord {
        require(SeasonRuntimeState.validActionId(contributionId)) { "Invalid season trophy journal id" }
        require(SeasonRuntimeState.validTargetId(seasonId)) { "Invalid season trophy journal season" }
        require(SeasonRuntimeState.validDigest(catalogDigest)) { "Invalid season trophy journal catalog digest" }
        require(SeasonRuntimeState.validTargetId(stageId)) { "Invalid season trophy journal stage" }
        require(ResourceContractDefinition.normalizeItemKey(itemKey) == itemKey) {
            "Invalid season trophy journal item"
        }
        require(SeasonRuntimeState.validPlayerId(playerId)) { "Invalid season trophy journal player" }
        require(requestedQuantity in 1..EscrowedItemPayload.MAX_ITEM_QUANTITY &&
            acceptedQuantity in 1..requestedQuantity
        ) { "Invalid season trophy journal quantity" }
        require(expectedStateRevision >= 0L) { "Invalid season trophy journal state revision" }
        require(itemPayloads.isNotEmpty() && itemPayloads.size <= MAX_ITEM_PAYLOADS) {
            "Invalid season trophy journal payload count"
        }
        var quantity = 0L
        var bytes = 0L
        itemPayloads.forEach { payload ->
            payload.validated()
            require(payload.itemKey == itemKey) { "Season trophy journal payload identity mismatch" }
            quantity = Math.addExact(quantity, payload.quantity.toLong())
            bytes = Math.addExact(bytes, payload.decodedBytes().size.toLong())
        }
        require(quantity == acceptedQuantity.toLong()) { "Season trophy journal payload quantity mismatch" }
        require(bytes <= MAX_TOTAL_SERIALIZED_BYTES) { "Season trophy journal payloads exceed size limit" }
        require(createdAt >= 0L && updatedAt >= createdAt && revision >= 0L) {
            "Invalid season trophy journal timestamps or revision"
        }
        listOfNotNull(cancelledAt, itemRemovalStartedAt, itemsRemovedAt, stateCommittedAt).forEach { timestamp ->
            require(timestamp in createdAt..updatedAt) { "Season trophy journal phase timestamp is out of bounds" }
        }
        require(itemsRemovedAt == null || itemRemovalStartedAt != null) {
            "Season trophy removal confirmation lacks intent"
        }
        require(stateCommittedAt == null || itemsRemovedAt != null) {
            "Season trophy state commit lacks removed items"
        }
        require(reviewEvidence == null || validEvidence(reviewEvidence)) { "Invalid season trophy review evidence" }
        when (status) {
            SeasonTrophyJournalStatus.PREPARED ->
                require(itemRemovalStartedAt == null && cancelledAt == null) {
                    "Prepared season trophy journal contains mutation evidence"
                }
            SeasonTrophyJournalStatus.CANCELLED ->
                require(cancelledAt != null && validCode(cancellationCode) && itemsRemovedAt == null) {
                    "Invalid cancelled season trophy journal"
                }
            SeasonTrophyJournalStatus.ITEM_REMOVAL_STARTED ->
                require(itemRemovalStartedAt != null && itemsRemovedAt == null) {
                    "Invalid season trophy item-removal intent"
                }
            SeasonTrophyJournalStatus.ITEMS_REMOVED ->
                require(itemsRemovedAt != null && stateCommittedAt == null) {
                    "Invalid season trophy removal confirmation"
                }
            SeasonTrophyJournalStatus.STATE_COMMITTED ->
                require(itemsRemovedAt != null && stateCommittedAt != null) {
                    "Invalid committed season trophy journal"
                }
            SeasonTrophyJournalStatus.MANUAL_REVIEW -> {
                require(reviewFromStatus in REVIEWABLE_STATUSES && reviewReason != null && validEvidence(reviewEvidence)) {
                    "Season trophy manual review lacks bounded evidence"
                }
                when (reviewFromStatus) {
                    SeasonTrophyJournalStatus.ITEM_REMOVAL_STARTED ->
                        require(itemRemovalStartedAt != null && itemsRemovedAt == null) {
                            "Season trophy removal review contains a later phase"
                        }
                    SeasonTrophyJournalStatus.ITEMS_REMOVED ->
                        require(itemsRemovedAt != null && stateCommittedAt == null) {
                            "Season trophy state review lacks removed items"
                        }
                    else -> error("Unreachable season trophy review state")
                }
            }
        }
        if (status != SeasonTrophyJournalStatus.CANCELLED) {
            require(cancelledAt == null && cancellationCode == null) {
                "Non-cancelled season trophy journal contains cancellation evidence"
            }
        }
        if (status != SeasonTrophyJournalStatus.MANUAL_REVIEW) {
            require(reviewFromStatus == null && reviewReason == null && reviewEvidence == null) {
                "Resolved season trophy journal contains review evidence"
            }
        }
        return this
    }

    fun toPlan(expectedRevision: Long = expectedStateRevision): SeasonTrophyContributionPlan.Accepted =
        SeasonTrophyContributionPlan.Accepted(
            contributionId = contributionId,
            stageId = stageId,
            itemKey = itemKey,
            playerId = playerId,
            requestedQuantity = requestedQuantity,
            acceptedQuantity = acceptedQuantity,
            expectedStateRevision = expectedRevision,
            catalogDigest = catalogDigest,
            plannedAt = createdAt,
        )

    companion object {
        const val MAX_ITEM_PAYLOADS = 54
        const val MAX_TOTAL_SERIALIZED_BYTES = 262_144L
        private val CODE_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val REVIEWABLE_STATUSES =
            setOf(SeasonTrophyJournalStatus.ITEM_REMOVAL_STARTED, SeasonTrophyJournalStatus.ITEMS_REMOVED)

        fun validCode(value: String?): Boolean = value != null && CODE_PATTERN.matches(value)

        fun validEvidence(value: String?): Boolean =
            value != null && value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)
    }
}

object SeasonTrophyJournalEngine {
    fun prepare(
        catalog: ObserveSeasonCatalog,
        plan: SeasonTrophyContributionPlan.Accepted,
        payloads: List<EscrowedItemPayload>,
        now: Long,
    ): SeasonTrophyJournalRecord {
        require(now >= plan.plannedAt) { "Season trophy journal predates its plan" }
        require(plan.catalogDigest == catalog.revisionDigest()) { "Season trophy plan uses another catalog" }
        require(payloads.all { it.itemKey == plan.itemKey }) { "Season trophy payload does not match plan" }
        return SeasonTrophyJournalRecord(
            contributionId = plan.contributionId,
            seasonId = catalog.id,
            catalogDigest = plan.catalogDigest,
            stageId = plan.stageId,
            itemKey = plan.itemKey,
            playerId = plan.playerId,
            requestedQuantity = plan.requestedQuantity,
            acceptedQuantity = plan.acceptedQuantity,
            expectedStateRevision = plan.expectedStateRevision,
            itemPayloads = payloads,
            createdAt = now,
        )
    }

    fun beginItemRemoval(record: SeasonTrophyJournalRecord, now: Long): SeasonTrophyJournalRecord =
        advance(record, SeasonTrophyJournalStatus.PREPARED, now) {
            copy(
                status = SeasonTrophyJournalStatus.ITEM_REMOVAL_STARTED,
                itemRemovalStartedAt = now,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }

    fun cancelPrepared(record: SeasonTrophyJournalRecord, code: String, now: Long): SeasonTrophyJournalRecord =
        cancel(record, SeasonTrophyJournalStatus.PREPARED, code, now)

    fun confirmNoItemsRemoved(record: SeasonTrophyJournalRecord, code: String, now: Long): SeasonTrophyJournalRecord =
        cancel(record, SeasonTrophyJournalStatus.ITEM_REMOVAL_STARTED, code, now)

    fun confirmItemsRemoved(record: SeasonTrophyJournalRecord, now: Long): SeasonTrophyJournalRecord {
        if (record.status == SeasonTrophyJournalStatus.ITEMS_REMOVED) return record.validated()
        return advance(record, SeasonTrophyJournalStatus.ITEM_REMOVAL_STARTED, now) {
            copy(
                status = SeasonTrophyJournalStatus.ITEMS_REMOVED,
                itemsRemovedAt = now,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }
    }

    fun confirmStateCommitted(record: SeasonTrophyJournalRecord, now: Long): SeasonTrophyJournalRecord {
        if (record.status == SeasonTrophyJournalStatus.STATE_COMMITTED) return record.validated()
        return advance(record, SeasonTrophyJournalStatus.ITEMS_REMOVED, now) {
            copy(
                status = SeasonTrophyJournalStatus.STATE_COMMITTED,
                stateCommittedAt = now,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }
    }

    fun haltItemRemoval(
        record: SeasonTrophyJournalRecord,
        reason: SeasonTrophyReviewReason,
        evidence: String,
        now: Long,
    ): SeasonTrophyJournalRecord =
        advance(record, SeasonTrophyJournalStatus.ITEM_REMOVAL_STARTED, now) {
            require(
                reason == SeasonTrophyReviewReason.INTERRUPTED_ITEM_REMOVAL ||
                    reason == SeasonTrophyReviewReason.INVENTORY_EVIDENCE_CONFLICT,
            ) { "Invalid season trophy removal review reason" }
            copy(
                status = SeasonTrophyJournalStatus.MANUAL_REVIEW,
                reviewFromStatus = status,
                reviewReason = reason,
                reviewEvidence = evidence,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }

    fun haltStateCommit(record: SeasonTrophyJournalRecord, evidence: String, now: Long): SeasonTrophyJournalRecord =
        advance(record, SeasonTrophyJournalStatus.ITEMS_REMOVED, now) {
            copy(
                status = SeasonTrophyJournalStatus.MANUAL_REVIEW,
                reviewFromStatus = status,
                reviewReason = SeasonTrophyReviewReason.STATE_EVIDENCE_CONFLICT,
                reviewEvidence = evidence,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }

    private fun cancel(
        record: SeasonTrophyJournalRecord,
        expected: SeasonTrophyJournalStatus,
        code: String,
        now: Long,
    ): SeasonTrophyJournalRecord =
        advance(record, expected, now) {
            require(SeasonTrophyJournalRecord.validCode(code)) { "Invalid season trophy cancellation code" }
            copy(
                status = SeasonTrophyJournalStatus.CANCELLED,
                cancelledAt = now,
                cancellationCode = code,
                updatedAt = now,
                revision = Math.addExact(revision, 1L),
            )
        }

    private inline fun advance(
        record: SeasonTrophyJournalRecord,
        expected: SeasonTrophyJournalStatus,
        now: Long,
        transition: SeasonTrophyJournalRecord.() -> SeasonTrophyJournalRecord,
    ): SeasonTrophyJournalRecord {
        val valid = record.validated()
        require(valid.status == expected) { "Season trophy journal expected ${expected.label}, found ${valid.status.label}" }
        require(now >= valid.updatedAt) { "Season trophy journal timestamp moved backwards" }
        return valid.transition().validated()
    }
}

data class SeasonTrophyJournalSummary(
    val available: Boolean,
    val records: Int,
    val statusCounts: Map<String, Int>,
    val removedPendingCommitQuantity: Long,
    val manualReviewQuantity: Long,
) {
    companion object {
        fun unavailable() = SeasonTrophyJournalSummary(false, 0, emptyMap(), 0L, 0L)
    }
}

object SeasonTrophyJournalAudit {
    const val MAX_NETWORK_RECORDS = 4_096

    fun summarize(records: List<SeasonTrophyJournalRecord>): SeasonTrophyJournalSummary {
        require(records.size <= MAX_NETWORK_RECORDS) { "Season trophy journal capacity exceeded" }
        val valid = records.map { it.validated() }
        return SeasonTrophyJournalSummary(
            available = true,
            records = valid.size,
            statusCounts = valid.groupingBy { it.status.label }.eachCount().toSortedMap(),
            removedPendingCommitQuantity =
                valid.filter { it.status == SeasonTrophyJournalStatus.ITEMS_REMOVED }
                    .sumOf { it.acceptedQuantity.toLong() },
            manualReviewQuantity =
                valid.filter { it.status == SeasonTrophyJournalStatus.MANUAL_REVIEW }
                    .sumOf { it.acceptedQuantity.toLong() },
        )
    }
}

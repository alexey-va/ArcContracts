package ru.arc.contracts

data class SeasonTrophyContributionReceipt(
    val contributionId: String,
    val stageId: String,
    val itemKey: String,
    val playerId: String,
    val quantity: Int,
    val committedAt: Long,
) {
    fun validated(): SeasonTrophyContributionReceipt {
        require(SeasonRuntimeState.validActionId(contributionId)) { "Invalid season trophy contribution id" }
        require(SeasonRuntimeState.validTargetId(stageId)) { "Invalid season trophy contribution stage" }
        require(ResourceContractDefinition.normalizeItemKey(itemKey) == itemKey) {
            "Invalid season trophy contribution item"
        }
        require(SeasonRuntimeState.validPlayerId(playerId)) { "Invalid season trophy contribution player" }
        require(quantity in 1..EscrowedItemPayload.MAX_ITEM_QUANTITY && committedAt >= 0L) {
            "Invalid season trophy contribution quantity or timestamp"
        }
        return this
    }
}

enum class SeasonTrophyContributionRejection(val label: String) {
    INVALID_REQUEST("invalid_request"),
    SEASON_CLOSED("season_closed"),
    STAGE_LOCKED("stage_locked"),
    ITEM_NOT_REQUIRED("item_not_required"),
    REQUIREMENT_FILLED("requirement_filled"),
    CONTRIBUTOR_LIMIT_REACHED("contributor_limit_reached"),
    INVENTORY_UNAVAILABLE("inventory_unavailable"),
}

sealed interface SeasonTrophyContributionPlan {
    data class Accepted(
        val contributionId: String,
        val stageId: String,
        val itemKey: String,
        val playerId: String,
        val requestedQuantity: Int,
        val acceptedQuantity: Int,
        val expectedStateRevision: Long,
        val catalogDigest: String,
        val plannedAt: Long,
    ) : SeasonTrophyContributionPlan

    data class Duplicate(val receipt: SeasonTrophyContributionReceipt) : SeasonTrophyContributionPlan

    data class Rejected(val reason: SeasonTrophyContributionRejection) : SeasonTrophyContributionPlan
}

data class SeasonTrophyContributionCommitResult(
    val state: SeasonRuntimeState,
    val receipt: SeasonTrophyContributionReceipt,
    val changed: Boolean,
)

object SeasonTrophyContributionEngine {
    fun plan(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        contributionId: String,
        stageId: String,
        itemKey: String,
        playerId: String,
        requestedQuantity: Int,
        now: Long,
    ): SeasonTrophyContributionPlan {
        state.validatedAgainst(catalog)
        val normalizedItem = ResourceContractDefinition.normalizeItemKey(itemKey)
        if (!SeasonRuntimeState.validActionId(contributionId) ||
            !SeasonRuntimeState.validTargetId(stageId) ||
            !SeasonRuntimeState.validPlayerId(playerId) ||
            normalizedItem != itemKey ||
            requestedQuantity !in 1..EscrowedItemPayload.MAX_ITEM_QUANTITY ||
            now < 0L
        ) return SeasonTrophyContributionPlan.Rejected(SeasonTrophyContributionRejection.INVALID_REQUEST)
        state.recentTrophyReceipts[contributionId]?.let { return SeasonTrophyContributionPlan.Duplicate(it) }
        if (!catalog.isOpenAt(now)) {
            return SeasonTrophyContributionPlan.Rejected(SeasonTrophyContributionRejection.SEASON_CLOSED)
        }
        val definition = catalog.projectStages[stageId]
            ?: return SeasonTrophyContributionPlan.Rejected(SeasonTrophyContributionRejection.INVALID_REQUEST)
        if (SeasonProjectEngine.status(catalog, state.project, stageId) != SeasonProjectStageStatus.OPEN) {
            return SeasonTrophyContributionPlan.Rejected(SeasonTrophyContributionRejection.STAGE_LOCKED)
        }
        val required = definition.requiredBoundRewards[itemKey]
            ?: return SeasonTrophyContributionPlan.Rejected(SeasonTrophyContributionRejection.ITEM_NOT_REQUIRED)
        val already = state.project.stages[stageId]?.boundRewards?.get(itemKey) ?: 0L
        val remaining = required - already
        if (remaining <= 0L) {
            return SeasonTrophyContributionPlan.Rejected(SeasonTrophyContributionRejection.REQUIREMENT_FILLED)
        }
        if (playerId !in state.projectContributors &&
            state.projectContributors.size >= SeasonRuntimeState.MAX_PROJECT_CONTRIBUTORS
        ) {
            return SeasonTrophyContributionPlan.Rejected(SeasonTrophyContributionRejection.CONTRIBUTOR_LIMIT_REACHED)
        }
        return SeasonTrophyContributionPlan.Accepted(
            contributionId = contributionId,
            stageId = stageId,
            itemKey = itemKey,
            playerId = playerId,
            requestedQuantity = requestedQuantity,
            acceptedQuantity = minOf(requestedQuantity.toLong(), remaining).toInt(),
            expectedStateRevision = state.revision,
            catalogDigest = catalog.revisionDigest(),
            plannedAt = now,
        )
    }

    fun commit(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        plan: SeasonTrophyContributionPlan.Accepted,
        now: Long,
    ): SeasonTrophyContributionCommitResult {
        state.validatedAgainst(catalog)
        require(now >= plan.plannedAt) { "Season trophy commit precedes its plan" }
        require(plan.catalogDigest == catalog.revisionDigest()) { "Season trophy plan uses another catalog" }
        state.recentTrophyReceipts[plan.contributionId]?.let {
            require(
                it.stageId == plan.stageId && it.itemKey == plan.itemKey && it.playerId == plan.playerId &&
                    it.quantity == plan.acceptedQuantity,
            ) { "Season trophy duplicate receipt disagrees with plan" }
            return SeasonTrophyContributionCommitResult(state, it, false)
        }
        require(state.revision == plan.expectedStateRevision) { "Season trophy plan uses stale state" }
        val contribution =
            SeasonProjectEngine.contribute(
                catalog,
                state.project,
                plan.stageId,
                SeasonProjectContribution.BoundReward(plan.itemKey, plan.acceptedQuantity.toLong()),
            )
        require(contribution.acceptedAmount == plan.acceptedQuantity.toLong()) {
            "Season trophy plan no longer fits the project requirement"
        }
        val contributor = state.projectContributors[plan.playerId] ?: SeasonContributorProgress()
        val stage = contributor.stages[plan.stageId] ?: SeasonProjectStageProgress()
        val contributed = stage.boundRewards[plan.itemKey] ?: 0L
        val updatedStage =
            stage.copy(
                boundRewards = stage.boundRewards + (plan.itemKey to Math.addExact(contributed, plan.acceptedQuantity.toLong())),
            )
        val receipt =
            SeasonTrophyContributionReceipt(
                contributionId = plan.contributionId,
                stageId = plan.stageId,
                itemKey = plan.itemKey,
                playerId = plan.playerId,
                quantity = plan.acceptedQuantity,
                committedAt = now,
            ).validated()
        val receipts = LinkedHashMap(state.recentTrophyReceipts)
        receipts[receipt.contributionId] = receipt
        while (receipts.size > SeasonRuntimeState.MAX_RECENT_TROPHY_RECEIPTS) receipts.remove(receipts.keys.first())
        val next =
            state.copy(
                project = contribution.state,
                projectContributors =
                    state.projectContributors +
                        (plan.playerId to contributor.copy(stages = contributor.stages + (plan.stageId to updatedStage))),
                recentTrophyReceipts = receipts,
                revision = Math.addExact(state.revision, 1L),
            ).validatedAgainst(catalog)
        return SeasonTrophyContributionCommitResult(next, receipt, true)
    }
}

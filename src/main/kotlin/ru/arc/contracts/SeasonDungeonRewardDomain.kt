package ru.arc.contracts

import java.security.MessageDigest

enum class SeasonDungeonRewardRejection(val label: String) {
    NATIVE_COMPLETION_MISSING("native_completion_missing"),
    RUN_START_MISSING("run_start_missing"),
    ACTIVE_SHARE_BELOW_MINIMUM("active_share_below_minimum"),
    PROJECT_STAGE_LOCKED("project_stage_locked"),
    ENTRY_BURN_MISSING("entry_burn_missing"),
    REWARD_COOLDOWN_ACTIVE("reward_cooldown_active"),
    WEEKLY_CAP_REACHED("weekly_cap_reached"),
    PLAYER_NOT_AUTHORIZED("player_not_authorized"),
    RECEIPT_CAPACITY_REACHED("receipt_capacity_reached"),
}

data class SeasonDungeonRewardReceipt(
    val rewardId: String,
    val runId: String,
    val catalogDigest: String,
    val dungeonContractId: String,
    val playerId: String,
    val payoutMinor: Long,
    val trophyItemKey: String,
    val rewardedAt: Long,
) {
    fun validated(): SeasonDungeonRewardReceipt {
        require(SeasonRuntimeState.validActionId(rewardId)) { "Invalid season dungeon reward id" }
        require(DungeonAdmissionPass.validRunId(runId)) { "Invalid season dungeon reward run id" }
        require(SeasonRuntimeState.validDigest(catalogDigest)) { "Invalid season dungeon reward catalog digest" }
        require(SeasonRuntimeState.validTargetId(dungeonContractId)) { "Invalid season dungeon reward contract id" }
        require(SeasonRuntimeState.validPlayerId(playerId)) { "Invalid season dungeon reward player id" }
        require(payoutMinor > 0L && rewardedAt >= 0L) { "Invalid season dungeon reward amount or timestamp" }
        require(ResourceContractDefinition.normalizeItemKey(trophyItemKey) == trophyItemKey) {
            "Season dungeon reward trophy key must be normalized"
        }
        return this
    }
}

sealed interface SeasonDungeonRewardPlan {
    data class Accepted(
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
        val plannedAt: Long,
    ) : SeasonDungeonRewardPlan

    data class Duplicate(val receipt: SeasonDungeonRewardReceipt) : SeasonDungeonRewardPlan

    data class Rejected(val rejections: Set<SeasonDungeonRewardRejection>) : SeasonDungeonRewardPlan
}

data class SeasonDungeonRewardCommitResult(
    val state: SeasonRuntimeState,
    val receipt: SeasonDungeonRewardReceipt,
    val changed: Boolean,
)

object SeasonDungeonRewardEngine {
    private const val MILLIS_PER_WEEK = 7L * 24L * 60L * 60L * 1_000L

    fun plan(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        authorization: SeasonDungeonRunAuthorization,
        playerId: String,
        activeShare: Double,
        now: Long,
    ): SeasonDungeonRewardPlan {
        val current = state.validatedAgainst(catalog)
        val run = authorization.validated()
        require(activeShare.isFinite() && activeShare in 0.0..1.0) { "Invalid season dungeon active share" }
        require(now >= 0L) { "Invalid season dungeon reward timestamp" }
        val definition = requireNotNull(catalog.dungeonContracts[run.dungeonContractId]) {
            "Season dungeon reward references an unknown contract"
        }
        val rewardId = rewardId(run.catalogDigest, run.runId, run.dungeonContractId, playerId)
        current.recentDungeonRewardReceipts[rewardId]?.let { return SeasonDungeonRewardPlan.Duplicate(it.validated()) }

        val pass = current.admissionPasses[SeasonRuntimeState.admissionKey(playerId, definition.id)]
        val sameAuthorization =
            current.authorizedDungeonRuns[run.instanceWorld] == run &&
                run.catalogDigest == catalog.revisionDigest() && playerId in run.participantIds
        val weekStartsAt = weekStartsAt(catalog, now)
        val matchingReceipts =
            current.recentDungeonRewardReceipts.values.filter {
                it.playerId == playerId && it.dungeonContractId == definition.id
            }
        val decision =
            DungeonQualificationEngine.evaluate(
                definition,
                DungeonQualificationContext(
                    nativeCompletion = sameAuthorization,
                    runStartObserved = sameAuthorization && pass?.status == DungeonAdmissionPassStatus.CONSUMED,
                    activeShare = activeShare,
                    completedProjectStages = SeasonProjectEngine.completedStages(catalog, current.project),
                    entryBurnRecorded =
                        pass?.status == DungeonAdmissionPassStatus.CONSUMED &&
                            pass.boundRunId == run.runId && pass.entryBurnMinor == definition.entryBurnMinorPerPlayer,
                    lastRewardedAt = matchingReceipts.maxOfOrNull { it.rewardedAt },
                    qualifyingRewardsThisWeek =
                        current.recentDungeonRewardReceipts.values.count {
                            it.dungeonContractId == definition.id && it.rewardedAt >= weekStartsAt
                        },
                    now = now,
                ),
            )
        val rejections = decision.rejections.mapTo(linkedSetOf()) { SeasonDungeonRewardRejection.valueOf(it.name) }
        if (playerId !in run.participantIds) rejections += SeasonDungeonRewardRejection.PLAYER_NOT_AUTHORIZED
        if (current.recentDungeonRewardReceipts.size >= SeasonRuntimeState.MAX_RECENT_DUNGEON_REWARD_RECEIPTS) {
            rejections += SeasonDungeonRewardRejection.RECEIPT_CAPACITY_REACHED
        }
        if (rejections.isNotEmpty()) return SeasonDungeonRewardPlan.Rejected(rejections)

        return SeasonDungeonRewardPlan.Accepted(
            rewardId = rewardId,
            runId = run.runId,
            catalogDigest = run.catalogDigest,
            dungeonContractId = definition.id,
            instanceWorld = run.instanceWorld,
            playerId = playerId,
            payoutMinor = definition.payoutMinorPerPlayer,
            trophyItemKey = definition.plannedBoundReward,
            activeShare = activeShare,
            expectedStateRevision = current.revision,
            plannedAt = now,
        )
    }

    fun commit(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        plan: SeasonDungeonRewardPlan.Accepted,
        now: Long,
    ): SeasonDungeonRewardCommitResult {
        val current = state.validatedAgainst(catalog)
        current.recentDungeonRewardReceipts[plan.rewardId]?.let {
            return SeasonDungeonRewardCommitResult(current, it.validated(), false)
        }
        require(current.revision == plan.expectedStateRevision) { "Season dungeon reward state revision changed" }
        require(plan.catalogDigest == catalog.revisionDigest()) { "Season dungeon reward catalog changed" }
        val definition = requireNotNull(catalog.dungeonContracts[plan.dungeonContractId]) {
            "Season dungeon reward contract is unavailable"
        }
        require(plan.payoutMinor == definition.payoutMinorPerPlayer && plan.trophyItemKey == definition.plannedBoundReward) {
            "Season dungeon reward no longer matches its policy"
        }
        require(plan.rewardId == rewardId(plan.catalogDigest, plan.runId, plan.dungeonContractId, plan.playerId)) {
            "Season dungeon reward identity changed"
        }
        val receipt =
            SeasonDungeonRewardReceipt(
                rewardId = plan.rewardId,
                runId = plan.runId,
                catalogDigest = plan.catalogDigest,
                dungeonContractId = plan.dungeonContractId,
                playerId = plan.playerId,
                payoutMinor = plan.payoutMinor,
                trophyItemKey = plan.trophyItemKey,
                rewardedAt = now,
            ).validated()
        val next =
            current.copy(
                recentDungeonRewardReceipts = current.recentDungeonRewardReceipts + (receipt.rewardId to receipt),
                revision = Math.addExact(current.revision, 1L),
            ).validatedAgainst(catalog)
        return SeasonDungeonRewardCommitResult(next, receipt, true)
    }

    fun rewardId(catalogDigest: String, runId: String, dungeonContractId: String, playerId: String): String {
        val input = "$catalogDigest\u0000$runId\u0000$dungeonContractId\u0000$playerId"
        val digest =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return "reward-$digest"
    }

    private fun weekStartsAt(catalog: ObserveSeasonCatalog, now: Long): Long {
        if (now <= catalog.startsAt) return catalog.startsAt
        val elapsed = Math.subtractExact(now, catalog.startsAt)
        return Math.addExact(catalog.startsAt, (elapsed / MILLIS_PER_WEEK) * MILLIS_PER_WEEK)
    }
}

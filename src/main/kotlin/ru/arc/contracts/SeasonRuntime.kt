package ru.arc.contracts

import java.util.LinkedHashMap

enum class SeasonProjectStageStatus(val label: String) {
    LOCKED("locked"),
    OPEN("open"),
    COMPLETE("complete"),
}

data class SeasonProjectStageProgress(
    val cashMinor: Long = 0L,
    val resources: Map<String, Long> = emptyMap(),
    val boundRewards: Map<String, Long> = emptyMap(),
)

data class SeasonProjectState(
    val seasonId: String,
    val stages: Map<String, SeasonProjectStageProgress> = emptyMap(),
)

sealed interface SeasonProjectContribution {
    val amount: Long

    data class Cash(override val amount: Long) : SeasonProjectContribution

    data class Resource(val orderId: String, override val amount: Long) : SeasonProjectContribution

    data class BoundReward(val itemKey: String, override val amount: Long) : SeasonProjectContribution
}

data class SeasonProjectContributionResult(
    val state: SeasonProjectState,
    val acceptedAmount: Long,
    val remainderAmount: Long,
    val stageCompleted: Boolean,
)

/**
 * Pure contribution engine for the public season project. It does not touch
 * inventory, balances or persistence; a durable coordinator must commit those
 * side effects before this engine can be connected to player actions.
 */
object SeasonProjectEngine {
    fun initial(catalog: ObserveSeasonCatalog): SeasonProjectState = SeasonProjectState(catalog.id)

    fun validated(
        catalog: ObserveSeasonCatalog,
        state: SeasonProjectState,
    ): SeasonProjectState {
        require(state.seasonId == catalog.id) { "Season project state belongs to a different season" }
        require(state.stages.keys.all(catalog.projectStages::containsKey)) {
            "Season project state contains an unknown stage"
        }
        state.stages.forEach { (stageId, progress) ->
            val definition = catalog.projectStages.getValue(stageId)
            require(progress.cashMinor in 0L..definition.cashContributionMinor) {
                "Season project stage '$stageId' has invalid cash progress"
            }
            validateQuantities(stageId, "resource", progress.resources, definition.requiredResources)
            validateQuantities(stageId, "bound reward", progress.boundRewards, definition.requiredBoundRewards)
        }
        val completed = completedStagesUnchecked(catalog, state)
        state.stages.forEach { (stageId, progress) ->
            val definition = catalog.projectStages.getValue(stageId)
            require(
                !hasProgress(progress) || definition.requiresProjectStages.all(completed::contains),
            ) {
                "Season project stage '$stageId' has progress before its prerequisites are complete"
            }
        }
        return state
    }

    fun status(
        catalog: ObserveSeasonCatalog,
        state: SeasonProjectState,
        stageId: String,
    ): SeasonProjectStageStatus {
        validated(catalog, state)
        val definition = requireNotNull(catalog.projectStages[stageId]) { "Unknown season project stage '$stageId'" }
        val completed = completedStagesUnchecked(catalog, state)
        return when {
            stageId in completed -> SeasonProjectStageStatus.COMPLETE
            definition.requiresProjectStages.all(completed::contains) -> SeasonProjectStageStatus.OPEN
            else -> SeasonProjectStageStatus.LOCKED
        }
    }

    fun completedStages(
        catalog: ObserveSeasonCatalog,
        state: SeasonProjectState,
    ): Set<String> {
        validated(catalog, state)
        return completedStagesUnchecked(catalog, state)
    }

    fun contribute(
        catalog: ObserveSeasonCatalog,
        state: SeasonProjectState,
        stageId: String,
        contribution: SeasonProjectContribution,
    ): SeasonProjectContributionResult {
        validated(catalog, state)
        require(contribution.amount > 0L) { "Season project contribution must be positive" }
        require(status(catalog, state, stageId) == SeasonProjectStageStatus.OPEN) {
            "Season project stage '$stageId' is not open"
        }
        val definition = catalog.projectStages.getValue(stageId)
        val current = state.stages[stageId] ?: SeasonProjectStageProgress()
        val previousCompleted = requirementsMet(definition, current)
        check(!previousCompleted) { "Open season project stage '$stageId' is already complete" }

        val accepted: Long
        val updated =
            when (contribution) {
                is SeasonProjectContribution.Cash -> {
                    accepted = acceptedAmount(contribution.amount, definition.cashContributionMinor, current.cashMinor)
                    current.copy(cashMinor = Math.addExact(current.cashMinor, accepted))
                }
                is SeasonProjectContribution.Resource -> {
                    val required = requireNotNull(definition.requiredResources[contribution.orderId]) {
                        "Season project stage '$stageId' does not require resource '${contribution.orderId}'"
                    }
                    val already = current.resources[contribution.orderId] ?: 0L
                    accepted = acceptedAmount(contribution.amount, required, already)
                    current.copy(resources = current.resources + (contribution.orderId to Math.addExact(already, accepted)))
                }
                is SeasonProjectContribution.BoundReward -> {
                    val required = requireNotNull(definition.requiredBoundRewards[contribution.itemKey]) {
                        "Season project stage '$stageId' does not require bound reward '${contribution.itemKey}'"
                    }
                    val already = current.boundRewards[contribution.itemKey] ?: 0L
                    accepted = acceptedAmount(contribution.amount, required, already)
                    current.copy(boundRewards = current.boundRewards + (contribution.itemKey to Math.addExact(already, accepted)))
                }
            }
        require(accepted > 0L) { "Season project contribution requirement is already filled" }
        val nextState = state.copy(stages = state.stages + (stageId to updated))
        return SeasonProjectContributionResult(
            state = validated(catalog, nextState),
            acceptedAmount = accepted,
            remainderAmount = contribution.amount - accepted,
            stageCompleted = requirementsMet(definition, updated),
        )
    }

    private fun completedStagesUnchecked(
        catalog: ObserveSeasonCatalog,
        state: SeasonProjectState,
    ): Set<String> {
        val completed = linkedSetOf<String>()
        do {
            val newlyCompleted =
                catalog.projectStages.values.filter { definition ->
                    definition.id !in completed &&
                        definition.requiresProjectStages.all(completed::contains) &&
                        requirementsMet(definition, state.stages[definition.id] ?: SeasonProjectStageProgress())
                }
            completed += newlyCompleted.map { it.id }
        } while (newlyCompleted.isNotEmpty())
        return completed
    }

    private fun requirementsMet(
        definition: ObserveProjectStageDefinition,
        progress: SeasonProjectStageProgress,
    ): Boolean =
        progress.cashMinor == definition.cashContributionMinor &&
            definition.requiredResources.all { (id, required) -> progress.resources[id] == required } &&
            definition.requiredBoundRewards.all { (id, required) -> progress.boundRewards[id] == required }

    private fun hasProgress(progress: SeasonProjectStageProgress): Boolean =
        progress.cashMinor > 0L || progress.resources.values.any { it > 0L } || progress.boundRewards.values.any { it > 0L }

    private fun acceptedAmount(requested: Long, required: Long, already: Long): Long =
        minOf(requested, required - already)

    private fun validateQuantities(
        stageId: String,
        kind: String,
        progress: Map<String, Long>,
        requirements: Map<String, Long>,
    ) {
        require(progress.keys.all(requirements::containsKey)) {
            "Season project stage '$stageId' contains unknown $kind progress"
        }
        progress.forEach { (id, quantity) ->
            require(quantity in 0L..requirements.getValue(id)) {
                "Season project stage '$stageId' has invalid $kind progress for '$id'"
            }
        }
    }
}

enum class DungeonCompletionPlayerOutcome(val label: String) {
    START_TO_FINISH("start_to_finish"),
    LEFT_BEFORE_COMPLETION("left_before_completion"),
    START_NOT_OBSERVED("start_not_observed"),
    NOT_PRESENT_AT_START("not_present_at_start"),
}

enum class DungeonQualificationRejection(val label: String) {
    NATIVE_COMPLETION_MISSING("native_completion_missing"),
    RUN_START_MISSING("run_start_missing"),
    ACTIVE_SHARE_BELOW_MINIMUM("active_share_below_minimum"),
    PROJECT_STAGE_LOCKED("project_stage_locked"),
    ENTRY_BURN_MISSING("entry_burn_missing"),
    REWARD_COOLDOWN_ACTIVE("reward_cooldown_active"),
    WEEKLY_CAP_REACHED("weekly_cap_reached"),
}

data class DungeonQualificationContext(
    val nativeCompletion: Boolean,
    val runStartObserved: Boolean,
    val activeShare: Double,
    val completedProjectStages: Set<String>,
    val entryBurnRecorded: Boolean,
    val lastRewardedAt: Long?,
    val qualifyingRewardsThisWeek: Int,
    val now: Long,
)

data class DungeonQualificationDecision(
    val eligible: Boolean,
    val rejections: Set<DungeonQualificationRejection>,
)

/** Pure fail-closed policy evaluation. It never pays or grants the reward. */
object DungeonQualificationEngine {
    fun evaluate(
        definition: ObserveDungeonContractDefinition,
        context: DungeonQualificationContext,
    ): DungeonQualificationDecision {
        require(context.activeShare.isFinite() && context.activeShare in 0.0..1.0) {
            "Dungeon active share must be in [0, 1]"
        }
        require(context.qualifyingRewardsThisWeek >= 0) { "Dungeon weekly reward count must be non-negative" }
        require(context.now >= 0L) { "Dungeon qualification timestamp must be non-negative" }
        require(context.lastRewardedAt == null || context.lastRewardedAt in 0L..context.now) {
            "Dungeon last reward timestamp must not be in the future"
        }

        val rejections = linkedSetOf<DungeonQualificationRejection>()
        if (!context.nativeCompletion) rejections += DungeonQualificationRejection.NATIVE_COMPLETION_MISSING
        if (!context.runStartObserved) rejections += DungeonQualificationRejection.RUN_START_MISSING
        if (context.activeShare < definition.minimumActiveShare) {
            rejections += DungeonQualificationRejection.ACTIVE_SHARE_BELOW_MINIMUM
        }
        if (definition.requiresProjectStage !in context.completedProjectStages) {
            rejections += DungeonQualificationRejection.PROJECT_STAGE_LOCKED
        }
        if (!context.entryBurnRecorded) rejections += DungeonQualificationRejection.ENTRY_BURN_MISSING
        val cooldownMillis = Math.multiplyExact(definition.rewardCooldownMinutes.toLong(), 60_000L)
        if (context.lastRewardedAt != null && context.now - context.lastRewardedAt < cooldownMillis) {
            rejections += DungeonQualificationRejection.REWARD_COOLDOWN_ACTIVE
        }
        if (context.qualifyingRewardsThisWeek >= definition.weeklyQualifyingPlayerCap) {
            rejections += DungeonQualificationRejection.WEEKLY_CAP_REACHED
        }
        return DungeonQualificationDecision(rejections.isEmpty(), rejections)
    }
}

data class DungeonContractObservationStats(
    val startedRuns: Long = 0L,
    val nativeCompletedRuns: Long = 0L,
    val completionPlayers: Long = 0L,
    val nativeCompletionDurationSeconds: Long = 0L,
    val playerOutcomes: Map<DungeonCompletionPlayerOutcome, Long> = emptyMap(),
)

data class DungeonContractObservationSnapshot(
    val catalogAvailable: Boolean,
    val activeRunsByContract: Map<String, Int>,
    val statsByContract: Map<String, DungeonContractObservationStats>,
)

data class DungeonContractCompletionObservation(
    val contractId: String,
    val durationSeconds: Long?,
    val playerOutcomes: Map<String, DungeonCompletionPlayerOutcome>,
)

/**
 * Bounded, in-memory observation of EliteMobs' native dungeon completion
 * proof. Player identifiers are used only as ephemeral set members and are
 * never exported as metric labels or persisted by this class.
 */
class DungeonContractObserver(
    private val maximumActiveRuns: Int = 128,
    private val completedRunRetention: Int = 512,
    private val maximumRunAgeMillis: Long = 12 * 60 * 60 * 1_000L,
) {
    init {
        require(maximumActiveRuns > 0)
        require(completedRunRetention > 0)
        require(maximumRunAgeMillis > 0L)
    }

    private data class ActiveRun(
        val contractId: String,
        val startedAt: Long,
        val participants: Set<String>,
    )

    private var catalog: ObserveSeasonCatalog? = null
    private val activeRuns = LinkedHashMap<String, ActiveRun>()
    private val completedRuns = LinkedHashMap<String, Unit>()
    private val stats = linkedMapOf<String, DungeonContractObservationStats>()

    @Synchronized
    fun configure(nextCatalog: ObserveSeasonCatalog?) {
        if (catalog?.id == nextCatalog?.id && catalog?.dungeonContracts == nextCatalog?.dungeonContracts) return
        catalog = nextCatalog
        activeRuns.clear()
        completedRuns.clear()
        stats.clear()
        nextCatalog?.dungeonContracts?.keys?.forEach { stats[it] = DungeonContractObservationStats() }
    }

    @Synchronized
    fun started(
        runId: String,
        world: String,
        participants: Set<String>,
        now: Long,
    ): Boolean {
        require(runId.isNotBlank()) { "Dungeon observation run id must not be blank" }
        require(now >= 0L) { "Dungeon observation timestamp must be non-negative" }
        prune(now)
        val contract = contractForWorld(world) ?: return false
        if (runId in activeRuns || runId in completedRuns || activeRuns.size >= maximumActiveRuns) return false
        activeRuns[runId] = ActiveRun(contract.id, now, participants.toSet())
        stats[contract.id] = stats.getValue(contract.id).copy(startedRuns = Math.addExact(stats.getValue(contract.id).startedRuns, 1L))
        return true
    }

    @Synchronized
    fun completed(
        runId: String,
        world: String,
        participants: Set<String>,
        now: Long,
    ): DungeonContractCompletionObservation? {
        require(runId.isNotBlank()) { "Dungeon observation run id must not be blank" }
        require(now >= 0L) { "Dungeon observation timestamp must be non-negative" }
        prune(now)
        val contract = contractForWorld(world) ?: return null
        if (runId in completedRuns) return null
        val start = activeRuns.remove(runId)
        val matchingStart = start?.takeIf { it.contractId == contract.id }
        val observedPlayerIds = matchingStart?.participants?.plus(participants) ?: participants
        val outcomes =
            observedPlayerIds.associateWith { playerId ->
                when {
                    matchingStart == null -> DungeonCompletionPlayerOutcome.START_NOT_OBSERVED
                    playerId !in matchingStart.participants -> DungeonCompletionPlayerOutcome.NOT_PRESENT_AT_START
                    playerId !in participants -> DungeonCompletionPlayerOutcome.LEFT_BEFORE_COMPLETION
                    else -> DungeonCompletionPlayerOutcome.START_TO_FINISH
                }
            }
        completedRuns[runId] = Unit
        while (completedRuns.size > completedRunRetention) completedRuns.remove(completedRuns.keys.first())

        val current = stats.getValue(contract.id)
        val durationSeconds = matchingStart?.let { ((now - it.startedAt).coerceAtLeast(0L) / 1_000L) }
        val outcomeCounts = current.playerOutcomes.toMutableMap()
        outcomes.values.forEach { outcome ->
            outcomeCounts[outcome] = Math.addExact(outcomeCounts[outcome] ?: 0L, 1L)
        }
        stats[contract.id] =
            current.copy(
                nativeCompletedRuns = Math.addExact(current.nativeCompletedRuns, 1L),
                completionPlayers = Math.addExact(current.completionPlayers, participants.size.toLong()),
                nativeCompletionDurationSeconds =
                    Math.addExact(current.nativeCompletionDurationSeconds, durationSeconds ?: 0L),
                playerOutcomes = outcomeCounts.toMap(),
            )
        return DungeonContractCompletionObservation(
            contractId = contract.id,
            durationSeconds = durationSeconds,
            playerOutcomes = outcomes,
        )
    }

    @Synchronized
    fun snapshot(now: Long): DungeonContractObservationSnapshot {
        require(now >= 0L) { "Dungeon observation timestamp must be non-negative" }
        prune(now)
        val currentCatalog = catalog
        return DungeonContractObservationSnapshot(
            catalogAvailable = currentCatalog != null,
            activeRunsByContract =
                currentCatalog?.dungeonContracts?.keys?.associateWith { contractId ->
                    activeRuns.values.count { it.contractId == contractId }
                } ?: emptyMap(),
            statsByContract = stats.toMap(),
        )
    }

    private fun contractForWorld(world: String): ObserveDungeonContractDefinition? {
        val normalized = world.trim().lowercase()
        return catalog?.dungeonContracts?.values?.firstOrNull { it.world == normalized }
    }

    private fun prune(now: Long) {
        val oldestAllowed = now - maximumRunAgeMillis
        activeRuns.entries.removeIf { (_, run) -> run.startedAt < oldestAllowed }
    }
}

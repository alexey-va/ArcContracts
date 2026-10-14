package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class SeasonRuntimeTest : StringSpec({
    val catalog = testSeasonCatalog()

    "public project opens stages only after exact prerequisite completion" {
        val initial = SeasonProjectEngine.initial(catalog)
        SeasonProjectEngine.status(catalog, initial, "road_foundation") shouldBe SeasonProjectStageStatus.OPEN
        SeasonProjectEngine.status(catalog, initial, "station_frame") shouldBe SeasonProjectStageStatus.LOCKED

        var state = initial
        val foundation = catalog.projectStages.getValue("road_foundation")
        if (foundation.cashContributionMinor > 0L) {
            state =
                SeasonProjectEngine.contribute(
                    catalog,
                    state,
                    foundation.id,
                    SeasonProjectContribution.Cash(foundation.cashContributionMinor),
                ).state
        }
        foundation.requiredResources.forEach { (orderId, quantity) ->
            state =
                SeasonProjectEngine.contribute(
                    catalog,
                    state,
                    foundation.id,
                    SeasonProjectContribution.Resource(orderId, quantity),
                ).state
        }
        foundation.requiredBoundRewards.forEach { (itemKey, quantity) ->
            state =
                SeasonProjectEngine.contribute(
                    catalog,
                    state,
                    foundation.id,
                    SeasonProjectContribution.BoundReward(itemKey, quantity),
                ).state
        }

        SeasonProjectEngine.completedStages(catalog, state) shouldContainExactly setOf("road_foundation")
        SeasonProjectEngine.status(catalog, state, "station_frame") shouldBe SeasonProjectStageStatus.OPEN
    }

    "public project caps an accepted contribution at the remaining requirement" {
        val stage = catalog.projectStages.getValue("road_foundation")
        val (orderId, required) = stage.requiredResources.entries.first()
        val result =
            SeasonProjectEngine.contribute(
                catalog,
                SeasonProjectEngine.initial(catalog),
                stage.id,
                SeasonProjectContribution.Resource(orderId, required + 25L),
            )

        result.acceptedAmount shouldBe required
        result.remainderAmount shouldBe 25L
        result.state.stages.getValue(stage.id).resources.getValue(orderId) shouldBe required
    }

    "public project rejects locked stages and unknown requirement keys" {
        shouldThrow<IllegalArgumentException> {
            SeasonProjectEngine.contribute(
                catalog,
                SeasonProjectEngine.initial(catalog),
                "station_frame",
                SeasonProjectContribution.Cash(1L),
            )
        }.message shouldBe "Season project stage 'station_frame' is not open"

        shouldThrow<IllegalArgumentException> {
            SeasonProjectEngine.contribute(
                catalog,
                SeasonProjectEngine.initial(catalog),
                "road_foundation",
                SeasonProjectContribution.Resource("not_configured", 1L),
            )
        }.message shouldBe "Season project stage 'road_foundation' does not require resource 'not_configured'"
    }

    "public project rejects persisted progress that bypasses prerequisites" {
        shouldThrow<IllegalArgumentException> {
            SeasonProjectEngine.validated(
                catalog,
                SeasonProjectState(
                    seasonId = catalog.id,
                    stages = mapOf("station_frame" to SeasonProjectStageProgress(cashMinor = 1L)),
                ),
            )
        }.message shouldBe "Season project stage 'station_frame' has progress before its prerequisites are complete"
    }

    "dungeon qualification requires every burn, progression and bounded reward gate" {
        val dungeon = catalog.dungeonContracts.values.first()
        val eligible =
            DungeonQualificationEngine.evaluate(
                dungeon,
                DungeonQualificationContext(
                    nativeCompletion = true,
                    runStartObserved = true,
                    activeShare = dungeon.minimumActiveShare,
                    completedProjectStages = setOf(dungeon.requiresProjectStage),
                    entryBurnRecorded = true,
                    lastRewardedAt = null,
                    qualifyingRewardsThisWeek = 0,
                    now = 10_000L,
                ),
            )
        eligible.eligible shouldBe true
        eligible.rejections shouldBe emptySet()

        val rejected =
            DungeonQualificationEngine.evaluate(
                dungeon,
                DungeonQualificationContext(
                    nativeCompletion = false,
                    runStartObserved = false,
                    activeShare = 0.0,
                    completedProjectStages = emptySet(),
                    entryBurnRecorded = false,
                    lastRewardedAt = 9_000L,
                    qualifyingRewardsThisWeek = dungeon.weeklyQualifyingPlayerCap,
                    now = 10_000L,
                ),
            )
        rejected.eligible shouldBe false
        rejected.rejections shouldContainExactly DungeonQualificationRejection.entries.toSet()
    }

    "dungeon observer counts native completion once without exporting player identity" {
        val observer = DungeonContractObserver()
        observer.configure(catalog)
        val dungeon = catalog.dungeonContracts.values.first()

        observer.started("run-1", dungeon.world, setOf("player-a", "player-b"), 1_000L) shouldBe true
        val completion = observer.completed("run-1", dungeon.world, setOf("player-a", "player-c"), 61_000L)
        requireNotNull(completion)
        completion.durationSeconds shouldBe 60L
        completion.playerOutcomes shouldContainExactly
            mapOf(
                "player-a" to DungeonCompletionPlayerOutcome.START_TO_FINISH,
                "player-b" to DungeonCompletionPlayerOutcome.LEFT_BEFORE_COMPLETION,
                "player-c" to DungeonCompletionPlayerOutcome.NOT_PRESENT_AT_START,
            )
        observer.completed("run-1", dungeon.world, setOf("player-a"), 62_000L) shouldBe null

        val snapshot = observer.snapshot(62_000L)
        val stats = snapshot.statsByContract.getValue(dungeon.id)
        stats.startedRuns shouldBe 1L
        stats.nativeCompletedRuns shouldBe 1L
        stats.completionPlayers shouldBe 2L
        stats.nativeCompletionDurationSeconds shouldBe 60L
        stats.playerOutcomes shouldContainExactly
            mapOf(
                DungeonCompletionPlayerOutcome.START_TO_FINISH to 1L,
                DungeonCompletionPlayerOutcome.LEFT_BEFORE_COMPLETION to 1L,
                DungeonCompletionPlayerOutcome.NOT_PRESENT_AT_START to 1L,
            )
    }

    "dungeon observer ignores unknown worlds and expires abandoned runs" {
        val observer = DungeonContractObserver(maximumRunAgeMillis = 1_000L)
        observer.configure(catalog)
        val dungeon = catalog.dungeonContracts.values.first()

        observer.started("unknown", "not_a_contract_world", setOf("player-a"), 0L) shouldBe false
        observer.started("run-1", dungeon.world, setOf("player-a"), 0L) shouldBe true
        observer.snapshot(1_001L).activeRunsByContract.getValue(dungeon.id) shouldBe 0
        val completion = observer.completed("run-1", dungeon.world, setOf("player-a"), 1_001L)
        requireNotNull(completion)
        completion.playerOutcomes.values.single() shouldBe DungeonCompletionPlayerOutcome.START_NOT_OBSERVED
    }
})

internal fun testSeasonCatalog(): ObserveSeasonCatalog {
    val resource = requireNotNull(SeasonRuntimeTest::class.java.getResource("/contracts/modules/contracts.yml"))
    return requireNotNull(ContractsConfig.fromFile(Path.of(resource.toURI()).parent.parent).validated().observeSeasonCatalog())
}

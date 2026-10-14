package ru.arc.contracts

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SeasonMoneyDomainTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val now = catalog.startsAt + 1_000L

    "catalog revision is stable across map order and changes with policy" {
        val reordered =
            catalog.copy(
                dungeonContracts = catalog.dungeonContracts.entries.reversed().associate { it.toPair() },
                projectStages = catalog.projectStages.entries.reversed().associate { it.toPair() },
            )

        reordered.revisionDigest() shouldBe catalog.revisionDigest()
        catalog.copy(endsAt = catalog.endsAt + 1L).revisionDigest() shouldNotBe catalog.revisionDigest()
        catalog.isOpenAt(catalog.startsAt) shouldBe true
        catalog.isOpenAt(catalog.endsAt) shouldBe false
    }

    "project cash commit is capped, attributed and idempotent" {
        val initial = SeasonRuntimeState.empty(catalog)
        val plan =
            SeasonMoneyActionEngine.plan(
                catalog,
                initial,
                "action-project-1",
                playerId,
                SeasonMoneyActionRequest.ProjectCash("road_foundation", 100_000_00L),
                now,
            ) as SeasonMoneyActionPlan.Accepted
        plan.amountMinor shouldBe catalog.projectStages.getValue("road_foundation").cashContributionMinor

        val committed = SeasonMoneyActionEngine.commit(catalog, initial, plan, now + 1)
        committed.changed shouldBe true
        committed.state.project.stages.getValue("road_foundation").cashMinor shouldBe plan.amountMinor
        committed.state.projectContributors.getValue(playerId)
            .stages.getValue("road_foundation").cashMinor shouldBe plan.amountMinor
        committed.state.validatedAgainst(catalog) shouldBe committed.state

        val duplicate = SeasonMoneyActionEngine.commit(catalog, committed.state, plan, now + 2)
        duplicate.changed shouldBe false
        duplicate.receipt shouldBe committed.receipt
    }

    "admission requires project unlock then binds and consumes one prepaid pass" {
        val locked =
            SeasonMoneyActionEngine.plan(
                catalog,
                SeasonRuntimeState.empty(catalog),
                "action-pass-locked",
                playerId,
                SeasonMoneyActionRequest.DungeonAdmission("mines_recon"),
                now,
            )
        locked shouldBe SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.DUNGEON_LOCKED)

        val unlocked = completedRoadFoundation(catalog, playerId)
        val plan =
            SeasonMoneyActionEngine.plan(
                catalog,
                unlocked,
                "action-pass-1",
                playerId,
                SeasonMoneyActionRequest.DungeonAdmission("mines_recon"),
                now,
            ) as SeasonMoneyActionPlan.Accepted
        val purchased = SeasonMoneyActionEngine.commit(catalog, unlocked, plan, now + 1).state
        purchased.admissionPasses.getValue(SeasonRuntimeState.admissionKey(playerId, "mines_recon")).status shouldBe
            DungeonAdmissionPassStatus.AVAILABLE

        val bound =
            SeasonMoneyActionEngine.bindAvailableAdmissions(
                catalog,
                purchased,
                "mines_recon",
                "elite-runtime-1",
                setOf(playerId),
                now + 2,
            )
        bound.boundPlayerIds shouldContainExactly setOf(playerId)
        val rebound =
            SeasonMoneyActionEngine.bindAvailableAdmissions(
                catalog,
                bound.state,
                "mines_recon",
                "elite-runtime-1",
                setOf(playerId),
                now + 3,
            )
        rebound.boundPlayerIds shouldContainExactly setOf(playerId)
        rebound.state shouldBe bound.state
        val wrongRun =
            SeasonMoneyActionEngine.bindAvailableAdmissions(
                catalog,
                rebound.state,
                "mines_recon",
                "elite-runtime-2",
                setOf(playerId),
                now + 4,
            )
        wrongRun.boundPlayerIds shouldBe emptySet()
        wrongRun.state shouldBe bound.state
        val consumed =
            SeasonMoneyActionEngine.consumeBoundAdmissions(
                catalog,
                rebound.state,
                "mines_recon",
                "elite-runtime-1",
                setOf(playerId),
                now + 5,
            )
        consumed.boundPlayerIds shouldContainExactly setOf(playerId)
        consumed.state.admissionPasses.getValue(SeasonRuntimeState.admissionKey(playerId, "mines_recon")).status shouldBe
            DungeonAdmissionPassStatus.CONSUMED
    }

    "state rejects a changed catalog digest and unattributed project progress" {
        val state = SeasonRuntimeState.empty(catalog)
        shouldThrow<IllegalArgumentException> {
            state.validatedAgainst(catalog.copy(title = "Другой сезон"))
        }.message shouldBe "Season runtime state does not match the exact catalog revision"

        val project =
            SeasonProjectEngine.contribute(
                catalog,
                state.project,
                "road_foundation",
                SeasonProjectContribution.Cash(100L),
            ).state
        shouldThrow<IllegalArgumentException> {
            state.copy(project = project).validatedAgainst(catalog)
        }.message shouldBe "Season project progress does not match exact contributor totals"
    }

    "completion consumes an available pass even if async start binding is still queued" {
        val unlocked = completedRoadFoundation(catalog, playerId)
        val plan =
            SeasonMoneyActionEngine.plan(
                catalog,
                unlocked,
                "action-pass-race",
                playerId,
                SeasonMoneyActionRequest.DungeonAdmission("mines_recon"),
                now,
            ) as SeasonMoneyActionPlan.Accepted
        val purchased = SeasonMoneyActionEngine.commit(catalog, unlocked, plan, now + 1).state

        val completed =
            SeasonMoneyActionEngine.consumeBoundAdmissions(
                catalog,
                purchased,
                "mines_recon",
                "native-run-race",
                setOf(playerId),
                now + 2,
            )

        completed.boundPlayerIds shouldContainExactly setOf(playerId)
        val pass = completed.state.admissionPasses.values.single()
        pass.status shouldBe DungeonAdmissionPassStatus.CONSUMED
        pass.boundRunId shouldBe "native-run-race"
    }

    "legacy Redis JSON initializes collections added by newer runtime schemas" {
        val gson = Gson()
        val legacyJson = gson.toJsonTree(SeasonRuntimeState.empty(catalog)).asJsonObject
        legacyJson.remove("dungeonLaunchTokens")
        legacyJson.remove("authorizedDungeonRuns")
        legacyJson.remove("recentDungeonRewardReceipts")
        legacyJson.remove("recentTrophyReceipts")

        val decoded = gson.fromJson(legacyJson, SeasonRuntimeState::class.java)

        decoded.validatedAgainst(catalog) shouldBe SeasonRuntimeState.empty(catalog)
    }
})

internal fun completedRoadFoundation(
    catalog: ObserveSeasonCatalog,
    playerId: String,
): SeasonRuntimeState {
    val stage = catalog.projectStages.getValue("road_foundation")
    var project = SeasonProjectEngine.initial(catalog)
    if (stage.cashContributionMinor > 0L) {
        project =
            SeasonProjectEngine.contribute(
                catalog,
                project,
                stage.id,
                SeasonProjectContribution.Cash(stage.cashContributionMinor),
            ).state
    }
    stage.requiredResources.forEach { (orderId, quantity) ->
        project =
            SeasonProjectEngine.contribute(
                catalog,
                project,
                stage.id,
                SeasonProjectContribution.Resource(orderId, quantity),
            ).state
    }
    val exact = project.stages.getValue(stage.id)
    return SeasonRuntimeState.empty(catalog).copy(
        project = project,
        projectContributors = mapOf(playerId to SeasonContributorProgress(mapOf(stage.id to exact))),
    ).validatedAgainst(catalog)
}

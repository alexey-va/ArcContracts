package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SeasonTrophyDomainTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val now = catalog.startsAt + 10_000L
    val stageId = catalog.completionStage
    val itemKey = catalog.dungeonContracts.getValue("mines_recon").plannedBoundReward

    fun finalStageOpen(): SeasonRuntimeState {
        var state = SeasonRuntimeState.empty(catalog)
        catalog.projectStages.values.filter { it.id != stageId }.forEach { definition ->
            var project = state.project
            if (definition.cashContributionMinor > 0L) {
                project =
                    SeasonProjectEngine.contribute(
                        catalog,
                        project,
                        definition.id,
                        SeasonProjectContribution.Cash(definition.cashContributionMinor),
                    ).state
            }
            definition.requiredResources.forEach { (resource, quantity) ->
                project =
                    SeasonProjectEngine.contribute(
                        catalog,
                        project,
                        definition.id,
                        SeasonProjectContribution.Resource(resource, quantity),
                    ).state
            }
            state = state.copy(project = project)
        }
        return state.copy(
            projectContributors = mapOf(playerId to SeasonContributorProgress(state.project.stages)),
        ).validatedAgainst(catalog)
    }

    "bound trophy contribution is capped, attributed and idempotent" {
        val initial = finalStageOpen()
        val plan =
            SeasonTrophyContributionEngine.plan(
                catalog,
                initial,
                "trophy-contribution-1",
                stageId,
                itemKey,
                playerId,
                64,
                now,
            ) as SeasonTrophyContributionPlan.Accepted
        plan.acceptedQuantity shouldBe catalog.projectStages.getValue(stageId).requiredBoundRewards.getValue(itemKey).toInt()
        val committed = SeasonTrophyContributionEngine.commit(catalog, initial, plan, now + 1)
        committed.state.project.stages.getValue(stageId).boundRewards.getValue(itemKey) shouldBe plan.acceptedQuantity.toLong()
        committed.state.projectContributors.getValue(playerId).stages.getValue(stageId)
            .boundRewards.getValue(itemKey) shouldBe plan.acceptedQuantity.toLong()
        committed.state.recentTrophyReceipts.getValue(plan.contributionId) shouldBe committed.receipt

        val duplicate = SeasonTrophyContributionEngine.commit(catalog, committed.state, plan, now + 2)
        duplicate.changed shouldBe false
        duplicate.receipt shouldBe committed.receipt
    }

    "trophy plan rejects locked, unrelated and stale contributions" {
        SeasonTrophyContributionEngine.plan(
            catalog,
            SeasonRuntimeState.empty(catalog),
            "trophy-contribution-locked",
            stageId,
            itemKey,
            playerId,
            1,
            now,
        ) shouldBe SeasonTrophyContributionPlan.Rejected(SeasonTrophyContributionRejection.STAGE_LOCKED)

        val initial = finalStageOpen()
        SeasonTrophyContributionEngine.plan(
            catalog,
            initial,
            "trophy-contribution-wrong",
            stageId,
            "arc:road_revival/not_a_trophy",
            playerId,
            1,
            now,
        ) shouldBe SeasonTrophyContributionPlan.Rejected(SeasonTrophyContributionRejection.ITEM_NOT_REQUIRED)

        val plan =
            SeasonTrophyContributionEngine.plan(
                catalog,
                initial,
                "trophy-contribution-stale",
                stageId,
                itemKey,
                playerId,
                1,
                now,
            ) as SeasonTrophyContributionPlan.Accepted
        shouldThrow<IllegalArgumentException> {
            SeasonTrophyContributionEngine.commit(catalog, initial.copy(revision = initial.revision + 1), plan, now + 1)
        }.message shouldBe "Season trophy plan uses stale state"
    }
})

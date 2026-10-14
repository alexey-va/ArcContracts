package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class SeasonDungeonRewardDomainTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val now = catalog.startsAt + 10_000L

    fun authorizedState(consumeAtStart: Boolean): Pair<SeasonRuntimeState, SeasonDungeonRunAuthorization> {
        val unlocked = completedRoadFoundation(catalog, playerId)
        val admissionPlan =
            SeasonMoneyActionEngine.plan(
                catalog,
                unlocked,
                "action-reward-pass",
                playerId,
                SeasonMoneyActionRequest.DungeonAdmission("mines_recon"),
                now,
            ) as SeasonMoneyActionPlan.Accepted
        val purchased = SeasonMoneyActionEngine.commit(catalog, unlocked, admissionPlan, now + 1).state
        val gate =
            SeasonDungeonLaunchGate(
                tokenIdFactory = { "launch-reward-test" },
                runIdFactory = { "run-reward-test" },
            )
        val reserved = gate.reserve(catalog, purchased, "mines_recon", setOf(playerId), now + 2)
        val authorized =
            requireNotNull(
                gate.authorizeInstance(
                    catalog,
                    reserved.state,
                    "em_id_the_mines",
                    "em_id_the_mines_reward_test",
                    now + 3,
                ),
            )
        val state =
            if (consumeAtStart) {
                gate.consumeAuthorizedRunAdmissions(catalog, authorized.state, authorized.authorization.instanceWorld, now + 4)
            } else {
                authorized.state
            }
        return state to authorized.authorization
    }

    "qualifying completion creates one deterministic money and trophy receipt" {
        val (state, authorization) = authorizedState(consumeAtStart = true)
        val plan =
            SeasonDungeonRewardEngine.plan(catalog, state, authorization, playerId, 1.0, now + 5) as
                SeasonDungeonRewardPlan.Accepted
        plan.payoutMinor shouldBe catalog.dungeonContracts.getValue("mines_recon").payoutMinorPerPlayer
        plan.trophyItemKey shouldBe "arc:road_revival/mines_core"
        plan.rewardId shouldBe
            SeasonDungeonRewardEngine.rewardId(catalog.revisionDigest(), authorization.runId, "mines_recon", playerId)

        val committed = SeasonDungeonRewardEngine.commit(catalog, state, plan, now + 6)
        committed.changed shouldBe true
        committed.state.recentDungeonRewardReceipts.getValue(plan.rewardId) shouldBe committed.receipt
        SeasonDungeonRewardEngine.plan(
            catalog,
            committed.state,
            authorization,
            playerId,
            1.0,
            now + 7,
        ) shouldBe SeasonDungeonRewardPlan.Duplicate(committed.receipt)
    }

    "paid pass must be consumed at native start before reward qualification" {
        val (state, authorization) = authorizedState(consumeAtStart = false)
        val rejected =
            SeasonDungeonRewardEngine.plan(catalog, state, authorization, playerId, 1.0, now + 5) as
                SeasonDungeonRewardPlan.Rejected
        rejected.rejections shouldContain SeasonDungeonRewardRejection.RUN_START_MISSING
        rejected.rejections shouldContain SeasonDungeonRewardRejection.ENTRY_BURN_MISSING
    }
})

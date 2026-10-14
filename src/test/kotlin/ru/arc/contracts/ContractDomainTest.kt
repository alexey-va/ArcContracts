package ru.arc.contracts

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractDomainTest : StringSpec({
    val definition =
        ResourceContractDefinition(
            id = "road_stone",
            displayName = "Камень для тракта",
            itemKey = "minecraft:stone",
            funding = ContractFunding.SERVER_ENVELOPE,
            windowStartsAt = 1_000L,
            windowEndsAt = 2_000L,
            payoutMinorPerUnit = 250L,
            budgetMinor = 25_000L,
            targetQuantity = 100L,
            perPlayerQuantityCap = 40L,
            minSubmissionQuantity = 4,
            maxSubmissionQuantity = 32,
        )

    "normalizes bounded vanilla item keys" {
        ResourceContractDefinition.normalizeItemKey(" Stone ") shouldBe "minecraft:stone"
        ResourceContractDefinition.normalizeItemKey("slimefun:BASIC_MACHINE") shouldBe "slimefun:basic_machine"
    }

    "rejects blank, non-namespaced and malformed item identities at the domain boundary" {
        shouldThrow<IllegalArgumentException> {
            definition.copy(itemKey = "")
        }.message shouldBe "Invalid namespaced contract itemKey: "
        shouldThrow<IllegalArgumentException> {
            definition.copy(itemKey = "stone")
        }.message shouldBe "Contract itemKey must be normalized: stone"
        shouldThrow<IllegalArgumentException> {
            definition.copy(itemKey = "minecraft:stone?")
        }.message shouldBe "Invalid namespaced contract itemKey: minecraft:stone?"
    }

    "plans and commits only the bounded quantity and exact minor-unit payout" {
        val initial = ResourceContractState.empty(definition)
        val plan =
            ResourceContractEngine.plan(
                definition,
                initial,
                submissionId = "submission-1",
                playerId = "player-1",
                requestedQuantity = 64,
                now = 1_500L,
            ) as ContractSubmissionPlan.Accepted

        plan.acceptedQuantity shouldBe 32L
        plan.payoutMinor shouldBe 8_000L
        val committed = ResourceContractEngine.commit(definition, initial, plan, committedAt = 1_501L)
        committed.changed shouldBe true
        committed.state.acceptedQuantity shouldBe 32L
        committed.state.spentMinor shouldBe 8_000L
        committed.state.perPlayerQuantity["player-1"] shouldBe 32L
        committed.state.revision shouldBe 1L
    }

    "enforces the shared player cap across repeated submissions" {
        val initial = ResourceContractState.empty(definition)
        val first =
            ResourceContractEngine.plan(definition, initial, "one", "player-1", 32, 1_500L)
                as ContractSubmissionPlan.Accepted
        val afterFirst = ResourceContractEngine.commit(definition, initial, first, 1_501L).state
        val second =
            ResourceContractEngine.plan(definition, afterFirst, "two", "player-1", 32, 1_502L)
                as ContractSubmissionPlan.Accepted
        second.acceptedQuantity shouldBe 8L
        val completed = ResourceContractEngine.commit(definition, afterFirst, second, 1_503L).state
        completed.perPlayerQuantity["player-1"] shouldBe 40L

        ResourceContractEngine.plan(definition, completed, "three", "player-1", 4, 1_504L) shouldBe
            ContractSubmissionPlan.Rejected(SubmissionRejection.PLAYER_CAP_REACHED)
    }

    "never spends beyond the configured server envelope" {
        val constrained = definition.copy(budgetMinor = 3_000L, targetQuantity = 100L)
        val plan =
            ResourceContractEngine.plan(
                constrained,
                ResourceContractState.empty(constrained),
                "budgeted",
                "player-1",
                32,
                1_500L,
            ) as ContractSubmissionPlan.Accepted
        plan.acceptedQuantity shouldBe 12L
        plan.payoutMinor shouldBe 3_000L
        ResourceContractEngine.commit(constrained, ResourceContractState.empty(constrained), plan, 1_501L)
            .state.status shouldBe ContractStatus.COMPLETED
    }

    "rank policy raises personal cap and payout while consuming the same server budget" {
        val policy = ContractRankPolicy(playerCapBasisPoints = 15_000, payoutBasisPoints = 11_200)
        val initial = ResourceContractState.empty(definition)
        val first = ResourceContractEngine.plan(
            definition, initial, "ranked-1", "player-1", 32, 1_500L, policy = policy,
        ) as ContractSubmissionPlan.Accepted

        first.acceptedQuantity shouldBe 32L
        first.payoutMinor shouldBe 8_960L
        val afterFirst = ResourceContractEngine.commit(definition, initial, first, 1_501L).state
        val second = ResourceContractEngine.plan(
            definition, afterFirst, "ranked-2", "player-1", 32, 1_502L, policy = policy,
        ) as ContractSubmissionPlan.Accepted
        second.acceptedQuantity shouldBe 28L
        second.payoutMinor shouldBe 7_840L
        val afterSecond = ResourceContractEngine.commit(definition, afterFirst, second, 1_503L).state

        afterSecond.perPlayerQuantity["player-1"] shouldBe 60L
        afterSecond.spentMinor shouldBe 16_800L
        afterSecond.validatedAgainst(definition) shouldBe afterSecond
    }

    "boosted payout is bounded by remaining budget at the effective rate" {
        val constrained = definition.copy(budgetMinor = 3_000L, targetQuantity = 100L)
        val policy = ContractRankPolicy(playerCapBasisPoints = 20_000, payoutBasisPoints = 11_200)
        val plan = ResourceContractEngine.plan(
            constrained, ResourceContractState.empty(constrained), "rank-budget", "player-1", 32, 1_500L,
            policy = policy,
        ) as ContractSubmissionPlan.Accepted

        plan.acceptedQuantity shouldBe 10L
        plan.payoutMinor shouldBe 2_800L
        ResourceContractEngine.commit(constrained, ResourceContractState.empty(constrained), plan, 1_501L)
            .state.status shouldBe ContractStatus.COMPLETED
    }

    "replays a committed submission id without paying twice" {
        val initial = ResourceContractState.empty(definition)
        val plan =
            ResourceContractEngine.plan(definition, initial, "stable-id", "player-1", 8, 1_500L)
                as ContractSubmissionPlan.Accepted
        val first = ResourceContractEngine.commit(definition, initial, plan, 1_501L)

        ResourceContractEngine.plan(definition, first.state, "stable-id", "player-1", 8, 1_502L) shouldBe
            ContractSubmissionPlan.Duplicate(first.receipt)
        val replay = ResourceContractEngine.commit(definition, first.state, plan, 1_503L)
        replay.changed shouldBe false
        replay.state shouldBe first.state
    }

    "round-trips the network repository record without losing exact state" {
        val initial = ResourceContractState.empty(definition)
        val plan =
            ResourceContractEngine.plan(definition, initial, "persisted-id", "player-1", 8, 1_500L)
                as ContractSubmissionPlan.Accepted
        val record =
            ResourceContractRecord(
                ResourceContractRecord.stateId(definition.id, definition.windowStartsAt),
                ResourceContractEngine.commit(definition, initial, plan, 1_501L).state,
            )

        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(record), ResourceContractRecord::class.java)

        restored shouldBe record
        restored.id() shouldBe "road_stone:1000"
    }

    "rejects a corrupted Redis record even when Gson bypasses constructors" {
        val gson = Gson()
        val json = gson.toJsonTree(ResourceContractRecord.empty(definition)).asJsonObject
        json.getAsJsonObject("state").addProperty("acceptedQuantity", 1L)
        val corrupted = gson.fromJson(json, ResourceContractRecord::class.java)

        shouldThrow<IllegalArgumentException> {
            corrupted.state.validatedAgainst(definition)
        }.message shouldBe "Player quantities do not equal accepted quantity"
    }

    "rejects stale concurrent plans" {
        val initial = ResourceContractState.empty(definition)
        val first = ResourceContractEngine.plan(definition, initial, "one", "player-1", 8, 1_500L)
            as ContractSubmissionPlan.Accepted
        val stale = ResourceContractEngine.plan(definition, initial, "two", "player-2", 8, 1_500L)
            as ContractSubmissionPlan.Accepted
        val changed = ResourceContractEngine.commit(definition, initial, first, 1_501L).state

        shouldThrow<IllegalArgumentException> {
            ResourceContractEngine.commit(definition, changed, stale, 1_502L)
        }.message shouldBe SubmissionRejection.STALE_STATE.label
    }

    "durable journal reservations hold quantity budget and player quota" {
        val state = ResourceContractState.empty(definition)
        val first = ContractQuotaReservation("reserved-1", "player-1", 32L, 8_000L)

        val samePlayer =
            ResourceContractEngine.plan(
                definition,
                state,
                "reserved-2",
                "player-1",
                32,
                1_500L,
                reservations = listOf(first),
            ) as ContractSubmissionPlan.Accepted
        samePlayer.acceptedQuantity shouldBe 8L
        samePlayer.payoutMinor shouldBe 2_000L

        val otherPlayer =
            ResourceContractEngine.plan(
                definition,
                state,
                "reserved-3",
                "player-2",
                64,
                1_500L,
                reservations = listOf(first, ContractQuotaReservation("reserved-2", "player-1", 8L, 2_000L)),
            ) as ContractSubmissionPlan.Accepted
        otherPlayer.acceptedQuantity shouldBe 32L
        otherPlayer.payoutMinor shouldBe 8_000L
    }

    "journal-backed reservations commit safely after another reservation advances the revision" {
        val state = ResourceContractState.empty(definition)
        val first = ContractQuotaReservation("reserved-1", "player-1", 8L, 2_000L)
        val second = ContractQuotaReservation("reserved-2", "player-2", 8L, 2_000L)

        val afterFirst = ResourceContractEngine.commitReserved(definition, state, first, 1_501L).state
        val afterSecond = ResourceContractEngine.commitReserved(definition, afterFirst, second, 1_502L).state

        afterSecond.acceptedQuantity shouldBe 16L
        afterSecond.spentMinor shouldBe 4_000L
        afterSecond.revision shouldBe 2L
        ResourceContractEngine.commitReserved(definition, afterSecond, second, 1_503L).changed shouldBe false
    }

    "rejects a replay or reservation that disagrees with the committed receipt" {
        val initial = ResourceContractState.empty(definition)
        val plan = ResourceContractEngine.plan(definition, initial, "stable-receipt", "player-1", 8, 1_500L)
            as ContractSubmissionPlan.Accepted
        val committed = ResourceContractEngine.commit(definition, initial, plan, 1_501L).state

        shouldThrow<IllegalArgumentException> {
            ResourceContractEngine.commit(definition, committed, plan.copy(payoutMinor = 2_001L), 1_502L)
        }.message shouldBe "Committed submission replay disagrees with its receipt"
        shouldThrow<IllegalArgumentException> {
            ResourceContractEngine.plan(
                definition,
                committed,
                "another-id",
                "player-2",
                8,
                1_503L,
                reservations = listOf(ContractQuotaReservation("stable-receipt", "player-2", 8L, 2_000L)),
            )
        }.message shouldBe "Contract reservation disagrees with its committed receipt"
    }

    "rejects submissions outside the configured window and minimum" {
        val state = ResourceContractState.empty(definition)
        ResourceContractEngine.plan(definition, state, "early", "player-1", 8, 999L) shouldBe
            ContractSubmissionPlan.Rejected(SubmissionRejection.CONTRACT_NOT_OPEN)
        ResourceContractEngine.plan(definition, state, "small", "player-1", 3, 1_500L) shouldBe
            ContractSubmissionPlan.Rejected(SubmissionRejection.BELOW_MINIMUM)
    }

    "rejects impossible or oversized submission policies before runtime" {
        shouldThrow<IllegalArgumentException> {
            definition.copy(maxSubmissionQuantity = 2_305)
        }.message shouldBe "Contract maximum submission must not exceed 2304"
        shouldThrow<IllegalArgumentException> {
            definition.copy(perPlayerQuantityCap = 3L)
        }.message shouldBe "Contract player cap must accept at least one minimum submission"
        shouldThrow<IllegalArgumentException> {
            definition.copy(budgetMinor = 999L)
        }.message shouldBe "Contract budget must fund at least one minimum submission"
    }
})

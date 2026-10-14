package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractMarketPricingTest : StringSpec({
    val definition = ResourceContractDefinition(
        id = "market_stone",
        displayName = "Камень",
        itemKey = "minecraft:stone",
        funding = ContractFunding.SERVER_ENVELOPE,
        windowStartsAt = 0L,
        windowEndsAt = ContractMarketPricing.WEEK_MILLIS,
        payoutMinorPerUnit = 100L,
        budgetMinor = 100_000L,
        targetQuantity = 100L,
        perPlayerQuantityCap = 100L,
        dynamicPricing = true,
    )

    "bounds dynamic price and applies rank after the market floor" {
        ContractMarketPricing.unitPayoutMinor(definition, 0L, 0L) shouldBe 100L
        ContractMarketPricing.unitPayoutMinor(definition, 100L, ContractMarketPricing.WEEK_MILLIS) shouldBe 50L
        ContractMarketPricing.unitPayoutMinor(
            definition, 0L, ContractMarketPricing.WEEK_MILLIS,
            ContractRankPolicy(payoutBasisPoints = 12_500),
        ) shouldBe 156L
    }

    "prices every marginal item from the locked supply snapshot" {
        ContractMarketPricing.payoutMinor(definition, 0L, 2L, 0L) shouldBe 199L
        ContractMarketPricing.payoutMinor(definition, 99L, 2L, 0L) shouldBe 100L
    }

    "allows the exact floored lower bound for odd base prices" {
        val odd = definition.copy(payoutMinorPerUnit = 125L)
        ContractMarketPricing.unitPayoutMinor(odd, 100L, 0L) shouldBe 62L
        ContractMarketPricing.payoutAllowed(odd, 2L, 124L) shouldBe true
    }

    "keeps a quote invariant when one batch is split into smaller marginal batches" {
        val large = definition.copy(targetQuantity = 2_000L)
        listOf(32L, 512L).forEach { quantity ->
            val whole = ContractMarketPricing.payoutMinor(large, 7L, quantity, 0L)
            val split = ContractMarketPricing.payoutMinor(large, 7L, quantity / 2L, 0L) +
                ContractMarketPricing.payoutMinor(large, 7L + quantity / 2L, quantity - quantity / 2L, 0L)
            whole shouldBe split
        }
    }

    "plan chooses the largest affordable dynamic prefix" {
        val constrained = definition.copy(budgetMinor = 185L)
        val plan = ResourceContractEngine.plan(
            constrained,
            ResourceContractState.empty(constrained),
            "market-plan",
            "player-1",
            3,
            0L,
        ) as ContractSubmissionPlan.Accepted
        plan.acceptedQuantity shouldBe 1L
        plan.payoutMinor shouldBe 100L
        plan.priceSupplyBefore shouldBe 0L
    }

    "does not close a dynamic contract while its minimum lot can become affordable" {
        val constrained = definition.copy(budgetMinor = 185L)
        val plan = ResourceContractEngine.plan(
            constrained, ResourceContractState.empty(constrained), "status-plan", "player-1", 1, 0L,
        ) as ContractSubmissionPlan.Accepted
        val committed = ResourceContractEngine.commit(constrained, ResourceContractState.empty(constrained), plan, 1L)
        committed.state.status shouldBe ContractStatus.OPEN
    }

    "closes an order whose remaining quantity cannot accept another minimum lot" {
        val limited = definition.copy(targetQuantity = 3L, minSubmissionQuantity = 2)
        val state = ResourceContractState.empty(limited)
        val plan = ResourceContractEngine.plan(limited, state, "last-lot", "player-1", 2, 0L) as ContractSubmissionPlan.Accepted
        ResourceContractEngine.commit(limited, state, plan, 1L).state.status shouldBe ContractStatus.COMPLETED
    }
})

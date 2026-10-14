package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractDynamicPricingUpgradeTest : StringSpec({
    val week = ContractRotation.weekStart(1_791_147_600_000)
    val fixed = ResourceContractDefinition("food_any_fish", "Любая сырая рыба", "arc:any_raw_fish",
        ContractFunding.SERVER_ENVELOPE, week, week + ContractRotation.WEEK_MILLIS,
        120, 200_000, 1_333, 64, maxSubmissionQuantity = 64, group = "food_orders", weeklyRecurring = true)
    val dynamic = fixed.copy(dynamicPricing = true)
    val plan = ContractSelectionPlan(week, 25_000_000, listOf(fixed), mapOf(fixed.id to "existing-obligation"))
    val state = ResourceContractState.empty(fixed).copy(acceptedQuantity = 128, spentMinor = 19_200,
        perPlayerQuantity = mapOf("player" to 128L), revision = 2,
        recentReceipts = mapOf("paid" to ContractSubmissionReceipt("paid", "player", 64, 9_600, week + 1000)))
    val record = ResourceContractRecord(ResourceContractRecord.stateId(fixed.id, week), state, fixed)

    "flag-only enable keeps financial history and limits and invalidates old quotes" {
        val upgraded = ContractDynamicPricingUpgrade.plan(plan, listOf(dynamic), listOf(record), emptySet())
        upgraded.plan.orders shouldBe listOf(dynamic)
        upgraded.records.single().definitionSnapshot shouldBe dynamic
        upgraded.records.single().state.copy(revision = state.revision) shouldBe state
        upgraded.records.single().state.revision shouldBe 3
        upgraded.records.single().validatedAgainst(dynamic) shouldBe upgraded.records.single()
    }
    "every nonterminal journal blocks its own order and unrelated policy edits stay frozen" {
        ContractDynamicPricingUpgrade.plan(plan, listOf(dynamic), listOf(record), setOf(record.stateId))
            .plan shouldBe plan
        for (changed in listOf(dynamic.copy(payoutMinorPerUnit = 130), dynamic.copy(targetQuantity = 1300),
            dynamic.copy(budgetMinor = 300_000), dynamic.copy(perPlayerQuantityCap = 128))) {
            val result = ContractDynamicPricingUpgrade.plan(plan, listOf(changed), listOf(record), emptySet())
            result.plan shouldBe plan
            result.records shouldBe emptyList()
        }
    }
    "record committed before plan is retryable without changing state or replaying receipts" {
        val first = ContractDynamicPricingUpgrade.plan(plan, listOf(dynamic), listOf(record), emptySet())
        val retry = ContractDynamicPricingUpgrade.plan(plan, listOf(dynamic), first.records, emptySet())
        retry.plan shouldBe first.plan
        retry.records shouldBe emptyList()
        ContractDynamicPricingUpgrade.plan(first.plan, listOf(fixed), first.records, emptySet()).plan shouldBe first.plan
    }
    "legacy journal recovery permits only flag-compatible policies and keeps fixed-only history fixed" {
        ContractDynamicPricingUpgrade.legacyJournalSnapshot(listOf(fixed, fixed)) shouldBe fixed
        ContractDynamicPricingUpgrade.legacyJournalSnapshot(listOf(fixed, dynamic)) shouldBe dynamic
        runCatching { ContractDynamicPricingUpgrade.legacyJournalSnapshot(listOf(fixed, dynamic.copy(payoutMinorPerUnit = 130))) }
            .isFailure shouldBe true
    }
})

package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class FoodContractSelectionTest : StringSpec({
    val week = ContractRotation.weekStart(1_789_344_000_000)
    fun order(id: String, group: String) = ResourceContractDefinition(
        id, id, "minecraft:cod", ContractFunding.SERVER_ENVELOPE, week,
        week + ContractRotation.WEEK_MILLIS, 100, 1000, 100, 10, group = group, weeklyRecurring = true,
    )
    "all food varieties share the existing envelope and leave the old weekly selection unchanged" {
        val forge = order("forge_one", "forge_orders")
        val foods = (1..20).map { order("food_$it", "food_orders") }
        val policy = ContractSelectionPolicy(true, week, 1, mapOf("food_orders" to 20))
        val original = ContractSelectionPlanner.plan(week, listOf(forge), policy, 21_000, emptyList(), emptyList())
        var saved = original
        val publisher = ContractSelectionPublisher(object : ContractSelectionPersistence {
            override fun find(week: Long) = saved
            override suspend fun commit(plan: ContractSelectionPlan) { saved = plan }
        })
        publisher.refresh(week, true, listOf(forge) + foods, policy, 21_000, emptyList(), emptyList(), emptySet())
        saved.orders.first() shouldBe forge
        saved.orders.count { it.group == "food_orders" } shouldBe 20
        saved.orders.sumOf { it.budgetMinor } shouldBe 21_000L
        val first = saved
        publisher.refresh(week, true, listOf(forge) + foods.reversed(), policy, 21_000, emptyList(), emptyList(), emptySet())
        saved shouldBe first
    }
    "fish selector accepts the four raw species and rejects cooked fish buckets and custom keys" {
        PaperContractItems.rawFishKeys.forEach { PaperContractItems.matchesKey(PaperContractItems.ANY_RAW_FISH, it) shouldBe true }
        listOf("minecraft:cooked_cod", "minecraft:cooked_salmon", "minecraft:cod_bucket", "arc:cod", "minecraft:chicken")
            .forEach { PaperContractItems.matchesKey(PaperContractItems.ANY_RAW_FISH, it) shouldBe false }
        PaperContractItems.matchesKey("minecraft:salmon", "minecraft:cod") shouldBe false
    }
})

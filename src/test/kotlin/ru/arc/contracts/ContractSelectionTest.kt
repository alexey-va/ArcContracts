package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class ContractSelectionTest : StringSpec({
    val week = ContractRotation.weekStart(1_789_344_000_000)
    val policy = ContractSelectionPolicy(true, week, 1)
    fun order(id: String, group: String = "forge", budget: Long = 1000) = ResourceContractDefinition(
        id, id, "minecraft:stone", ContractFunding.SERVER_ENVELOPE, week, week + ContractRotation.WEEK_MILLIS,
        10, budget, 100, 50, group = group, weeklyRecurring = true,
    )
    val candidates = listOf(order("forge_one"), order("forge_two"), order("guild_one", "guild"), order("guild_two", "guild"))

    "failed durable publication stays closed and retries the exact cached plan" {
        val store = SelectionStore()
        val publisher = ContractSelectionPublisher(store)
        store.fail = true
        shouldThrow<IllegalStateException> {
            publisher.refresh(week, true, candidates, policy, 2000, emptyList(), emptyList(), emptySet())
        }
        publisher.current(week) shouldBe null
        val pending = store.plan
        store.fail = false
        publisher.refresh(week, true, emptyList(), policy, 2000, emptyList(), emptyList(), emptySet())
        publisher.current(week) shouldBe pending
        val restarted = ContractSelectionPublisher(store)
        restarted.refresh(week, true, emptyList(), policy, 2000, emptyList(), emptyList(), emptySet())
        restarted.current(week) shouldBe pending
        restarted.current(week + ContractRotation.WEEK_MILLIS) shouldBe null
    }
    "a follower never creates a plan or commits it" {
        val store = SelectionStore()
        val publisher = ContractSelectionPublisher(store)
        publisher.refresh(week, false, candidates, policy, 2000, emptyList(), emptyList(), emptySet())
        store.commits shouldBe 0
        publisher.current(week) shouldBe null
        store.plan = ContractSelectionPlanner.plan(week, candidates, policy, 2000, emptyList(), emptyList())
        publisher.refresh(week, false, candidates, policy, 2000, emptyList(), emptyList(), emptySet())
        publisher.current(week) shouldBe store.plan
        store.commits shouldBe 0
    }
    "lowered envelope withdraws an incompatible published plan" {
        val store = SelectionStore()
        val publisher = ContractSelectionPublisher(store)
        publisher.refresh(week, true, candidates, policy, 2000, emptyList(), emptyList(), emptySet())
        shouldThrow<IllegalArgumentException> {
            publisher.refresh(week, true, candidates, policy, 1000, emptyList(), emptyList(), emptySet())
        }
        publisher.current(week) shouldBe null
    }
    "legacy held records resolve and validate against a retained candidate" {
        val original = candidates.first()
        val legacy = ResourceContractRecord.empty(original).copy(definitionSnapshot = null)
        val plan = ContractSelectionPlanner.plan(week, candidates, policy, 2000, emptyList(), listOf(legacy), setOf(legacy.stateId))
        plan.orders.first() shouldBe original
        shouldThrow<IllegalArgumentException> {
            ContractSelectionPlanner.plan(week, emptyList(), policy, 2000, emptyList(), listOf(legacy), setOf(legacy.stateId))
        }
    }
    "serialized plans retain definitions reasons and window identity" {
        val plan = ContractSelectionPlanner.plan(week, candidates, policy, 2000, emptyList(), emptyList())
        val gson = com.google.gson.Gson()
        gson.fromJson(gson.toJson(plan), ContractSelectionPlan::class.java).validated() shouldBe plan
        shouldThrow<IllegalArgumentException> { plan.copy(budgetMinor = 1).validated() }
        shouldThrow<IllegalArgumentException> { ContractSelectionPolicy(true, week + 1, 1) }
    }
    "selection is deterministic under catalog reorder and respects each group" {
        val first = ContractSelectionPlanner.plan(week, candidates, policy, 2000, emptyList(), emptyList())
        first shouldBe ContractSelectionPlanner.plan(week, candidates.reversed(), policy, 2000, emptyList(), emptyList())
        first.orders.map { it.group }.toSet() shouldBe setOf("forge", "guild")
        first.orders.sumOf { it.budgetMinor } shouldBe 2000L
        first.orders.all { it.payoutMinorPerUnit == 10L && it.perPlayerQuantityCap == 50L } shouldBe true
    }
    "next week prioritizes candidates absent from the previous selection" {
        val first = ContractSelectionPlanner.plan(week, candidates, policy, 2000, emptyList(), emptyList())
        val next = ContractSelectionPlanner.plan(week + ContractRotation.WEEK_MILLIS, candidates, policy, 2000, listOf(first), emptyList())
        next.orders.map { it.id }.intersect(first.orders.map { it.id }.toSet()).isEmpty() shouldBe true
    }
    "budget remains bounded and future candidates never open early" {
        val future = order("future_one", "future").copy(windowStartsAt = week + ContractRotation.WEEK_MILLIS,
            windowEndsAt = week + 2 * ContractRotation.WEEK_MILLIS)
        val plan = ContractSelectionPlanner.plan(week, candidates + future, policy, 1500, emptyList(), emptyList())
        plan.orders.size shouldBe 1
        plan.orders.none { it.id == future.id } shouldBe true
    }
    "held obligations preserve their original definition even after catalog removal" {
        val original = order("removed_one")
        val record = ResourceContractRecord.empty(original)
        val plan = ContractSelectionPlanner.plan(week, candidates, policy, 2000, emptyList(), listOf(record), setOf(record.stateId))
        plan.orders.first() shouldBe original
        plan.reasons[original.id] shouldBe "existing-obligation"
        plan.orders.count { it.group == "forge" } shouldBe 1
        plan.orders.sumOf { it.budgetMinor } shouldBe 2000L
    }
})

private class SelectionStore : ContractSelectionPersistence {
    var plan: ContractSelectionPlan? = null
    var fail = false
    var commits = 0
    override fun find(week: Long) = plan?.takeIf { it.weekStartsAt == week }
    override suspend fun commit(plan: ContractSelectionPlan) {
        this.plan = plan
        commits++
        check(!fail) { "Simulated failed durable write" }
    }
}

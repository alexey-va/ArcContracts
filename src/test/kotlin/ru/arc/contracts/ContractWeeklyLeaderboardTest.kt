package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ContractWeeklyLeaderboardTest : StringSpec({
    val week = Instant.parse("2026-10-25T21:00:00Z").toEpochMilli()
    fun order(id: String, start: Long = week) = ResourceContractDefinition(
        id, id, "minecraft:stone", ContractFunding.SERVER_ENVELOPE,
        start, start + ContractRotation.WEEK_MILLIS, 100, 10_000_000, 100_000, 100_000,
        weeklyRecurring = true,
    )
    fun record(definition: ResourceContractDefinition, quantities: Map<String, Long>, completed: Boolean = false) =
        ResourceContractRecord.empty(definition).copy(state = ResourceContractState.empty(definition).copy(
            status = if (completed) ContractStatus.COMPLETED else ContractStatus.OPEN,
            acceptedQuantity = quantities.values.sum(), spentMinor = quantities.values.sum() * 100,
            perPlayerQuantity = quantities,
        ))

    "ranks all current weekly contributions including completed orders without relying on retained receipts" {
        val stone = record(order("stone"), mapOf("alice" to 600, "bob" to 900), completed = true)
        val wood = record(order("wood"), mapOf("alice" to 500, "charlie" to 1_100, "zero" to 0))
        val old = record(order("stone", week - ContractRotation.WEEK_MILLIS), mapOf("old-player" to 99_999))
        val future = record(order("wood", week + ContractRotation.WEEK_MILLIS), mapOf("future-player" to 99_999))
        val result = ContractWeeklyLeaderboard.calculate(listOf(wood, future, stone, old), emptyList(), week + 1)
        result.weekStartsAt shouldBe week
        result.entries shouldBe listOf(
            ContractWeeklyLeaderboard.Entry("alice", 1_100),
            ContractWeeklyLeaderboard.Entry("charlie", 1_100),
            ContractWeeklyLeaderboard.Entry("bob", 900),
        )
        result.byContract.getValue("stone") shouldBe listOf(
            ContractWeeklyLeaderboard.Entry("bob", 900), ContractWeeklyLeaderboard.Entry("alice", 600),
        )
        result.incompleteContracts shouldBe emptySet()
        ContractWeeklyLeaderboard.calculate(listOf(stone, wood), emptyList(), week + 1).entries shouldBe result.entries
    }

    "does not include a cached receipt whose durable submission still needs reconciliation" {
        val definition = order("stone")
        val plan = ResourceContractEngine.plan(definition, ResourceContractState.empty(definition),
            "pending-save", "alice", 8, week + 100) as ContractSubmissionPlan.Accepted
        val prepared = ContractSubmissionJournalEngine.prepare(definition, plan,
            listOf(EscrowedItemPayload.capture("minecraft:stone", 8, byteArrayOf(1, 2, 3))), week + 100)
        val paid = ContractSubmissionJournalEngine.confirmPaid(
            ContractSubmissionJournalEngine.beginPayment(
                ContractSubmissionJournalEngine.confirmItemsEscrowed(
                    ContractSubmissionJournalEngine.beginItemRemoval(prepared, week + 101), week + 102),
                10_000, week + 103), 10_800, "payment-1", week + 104)
        val cached = record(definition, mapOf("alice" to 18, "bob" to 20)).let { base -> base.copy(
            state = base.state.copy(recentReceipts = mapOf("pending-save" to
                ContractSubmissionReceipt("pending-save", "alice", 8, 800, week + 105)))) }
        ContractWeeklyLeaderboard.calculate(listOf(cached), listOf(paid), week + 106).entries shouldBe listOf(
            ContractWeeklyLeaderboard.Entry("bob", 20), ContractWeeklyLeaderboard.Entry("alice", 10),
        )
        val evicted = cached.copy(state = cached.state.copy(recentReceipts = emptyMap(), revision = 514))
        val unresolved = ContractWeeklyLeaderboard.calculate(listOf(evicted), listOf(paid), week + 106)
        unresolved.entries shouldBe listOf(
            ContractWeeklyLeaderboard.Entry("bob", 20),
        )
        unresolved.incompleteContracts shouldBe setOf("stone")
        val committed = ContractSubmissionJournalEngine.confirmContractCommitted(paid, week + 105)
        ContractWeeklyLeaderboard.calculate(listOf(cached), listOf(committed), week + 106).entries shouldBe listOf(
            ContractWeeklyLeaderboard.Entry("bob", 20), ContractWeeklyLeaderboard.Entry("alice", 18),
        )
    }

    "rolls over at Monday midnight Moscow and has an explicit empty current week" {
        val records = listOf(record(order("stone"), mapOf("alice" to 100)))
        ContractWeeklyLeaderboard.calculate(records, emptyList(), week + ContractRotation.WEEK_MILLIS - 1)
            .entries.single().quantity shouldBe 100
        val next = ContractWeeklyLeaderboard.calculate(records, emptyList(), week + ContractRotation.WEEK_MILLIS)
        next.weekStartsAt shouldBe week + ContractRotation.WEEK_MILLIS
        next.entries shouldBe emptyList()
        next.byContract shouldBe emptyMap()
    }
})

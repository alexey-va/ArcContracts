package ru.arc.contracts

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ContractRotationTest : StringSpec({
    val start = Instant.parse("2026-09-06T21:00:00Z").toEpochMilli()
    val definition = ResourceContractDefinition(
        "weekly_coal", "Уголь", "minecraft:coal", ContractFunding.SERVER_ENVELOPE,
        start, start + ContractRotation.WEEK_MILLIS, 125, 100_000, 1_000, 500,
        dynamicPricing = true, weeklyRecurring = true,
    )
    fun plan(id: String = "submission-one") = ResourceContractEngine.plan(
        definition, ResourceContractState.empty(definition), id, "player-one", 8, start + 1_000,
    ) as ContractSubmissionPlan.Accepted
    fun journal() = ContractSubmissionJournalEngine.prepare(
        definition, plan(), listOf(EscrowedItemPayload.capture("minecraft:coal", 8, byteArrayOf(1))), start + 2_000,
    )

    "weekly rotation is stable on reopen and changes at Monday midnight in Moscow" {
        ContractRotation.at(definition, start - 1) shouldBe definition
        ContractRotation.at(definition, start + ContractRotation.WEEK_MILLIS - 1) shouldBe definition
        val next = ContractRotation.at(definition, start + ContractRotation.WEEK_MILLIS)
        next.windowStartsAt shouldBe start + ContractRotation.WEEK_MILLIS
        next.windowEndsAt shouldBe start + 2 * ContractRotation.WEEK_MILLIS
        ContractRotation.at(definition, start + 20 * ContractRotation.WEEK_MILLIS + 123).windowStartsAt shouldBe
            start + 20 * ContractRotation.WEEK_MILLIS
    }

    "Moscow week starts after the final Sunday minute" {
        ContractRotation.weekStart(Instant.parse("2026-09-13T20:59:59.999Z").toEpochMilli()) shouldBe start
        ContractRotation.weekStart(Instant.parse("2026-09-13T21:00:00Z").toEpochMilli()) shouldBe
            start + ContractRotation.WEEK_MILLIS
    }

    "quote binds player quantity total revision age and week" {
        val plan = plan()
        val quote = ContractSubmissionQuote(definition.id, start, plan.playerId, 8, plan.payoutMinor, 0, plan.plannedAt)
        quote.matches(definition, plan, plan.plannedAt + 30_000) shouldBe true
        quote.matches(definition, plan, plan.plannedAt + 30_001) shouldBe false
        quote.matches(definition, plan, plan.plannedAt - 1) shouldBe false
        quote.matches(definition, plan.copy(playerId = "another"), plan.plannedAt) shouldBe false
        quote.matches(definition, plan.copy(acceptedQuantity = 7), plan.plannedAt) shouldBe false
        quote.matches(definition, plan.copy(payoutMinor = plan.payoutMinor + 1), plan.plannedAt) shouldBe false
        quote.matches(definition, plan.copy(expectedRevision = 1), plan.plannedAt) shouldBe false
        quote.matches(definition, plan, definition.windowEndsAt) shouldBe false
    }

    "journal retains exact quote inputs and rejects a tampered total even within price bounds" {
        val record = journal()
        val restored = Gson().fromJson(Gson().toJson(record), ContractSubmissionJournalRecord::class.java).validated()
        restored shouldBe record
        shouldThrow<IllegalArgumentException> { record.copy(payoutMinor = record.payoutMinor + 1) }
        shouldThrow<IllegalArgumentException> { record.copy(quoteSupplyBefore = null) }
        restored.definitionSnapshot shouldBe definition
        ResourceContractRecord.empty(definition).definitionSnapshot shouldBe definition
    }

    "network weekly budget counts retired orders and held payouts once" {
        val prepared = journal()
        val held = ContractSubmissionJournalEngine.beginItemRemoval(prepared, start + 3_000)
        val committed = ResourceContractEngine.commit(definition, ResourceContractState.empty(definition), plan(), start + 4_000)
        val state = ResourceContractRecord(ResourceContractRecord.stateId(definition.id, start), committed.state, definition)
        ContractRotation.remainingBudget(100_000, emptyList(), listOf(held), start + 5_000) shouldBe 100_000 - prepared.payoutMinor
        ContractRotation.remainingBudget(100_000, listOf(state), listOf(held), start + 5_000) shouldBe 100_000 - prepared.payoutMinor
        ContractRotation.remainingBudget(100_000, listOf(state), emptyList(), start + 5_000) shouldBe 100_000 - prepared.payoutMinor
        ContractRotation.remainingBudget(100_000, listOf(state), emptyList(), start + ContractRotation.WEEK_MILLIS) shouldBe 100_000
        ContractRotation.remainingBudget(1, listOf(state), emptyList(), start + 5_000) shouldBe 0
    }

    "network weekly budget aggregates distinct orders and dedupes only matching receipts" {
        val secondDefinition = definition.copy(
            id = "weekly_iron",
            itemKey = "minecraft:iron_ingot",
            payoutMinorPerUnit = 200,
        )
        val secondPlan = ResourceContractEngine.plan(
            secondDefinition,
            ResourceContractState.empty(secondDefinition),
            "submission-two",
            "player-two",
            8,
            start + 1_000,
        ) as ContractSubmissionPlan.Accepted
        val secondCommitted = ResourceContractEngine.commit(
            secondDefinition,
            ResourceContractState.empty(secondDefinition),
            secondPlan,
            start + 4_000,
        )
        val secondState = ResourceContractRecord(
            ResourceContractRecord.stateId(secondDefinition.id, start),
            secondCommitted.state,
            secondDefinition,
        )

        val firstPlan = plan("submission-one")
        val firstCommitted = ResourceContractEngine.commit(
            definition,
            ResourceContractState.empty(definition),
            firstPlan,
            start + 4_000,
        )
        val firstState = ResourceContractRecord(
            ResourceContractRecord.stateId(definition.id, start),
            firstCommitted.state,
            definition,
        )
        val matchingHeld = ContractSubmissionJournalEngine.beginItemRemoval(
            ContractSubmissionJournalEngine.prepare(
                definition,
                firstPlan,
                listOf(EscrowedItemPayload.capture(definition.itemKey, 8, byteArrayOf(2))),
                start + 2_000,
            ),
            start + 3_000,
        )
        val firstHeld = ContractSubmissionJournalEngine.beginItemRemoval(
            ContractSubmissionJournalEngine.prepare(
                definition,
                firstPlan.copy(submissionId = "submission-one-held"),
                listOf(EscrowedItemPayload.capture(definition.itemKey, 8, byteArrayOf(4))),
                start + 2_000,
            ),
            start + 3_000,
        )
        val secondHeld = ContractSubmissionJournalEngine.beginItemRemoval(
            ContractSubmissionJournalEngine.prepare(
                secondDefinition,
                secondPlan.copy(submissionId = "submission-one"),
                listOf(EscrowedItemPayload.capture(secondDefinition.itemKey, 8, byteArrayOf(3))),
                start + 2_000,
            ),
            start + 3_000,
        )
        val oldDefinition = definition.copy(
            windowStartsAt = start - ContractRotation.WEEK_MILLIS,
            windowEndsAt = start,
            weeklyRecurring = false,
        )
        val oldPlan = ResourceContractEngine.plan(
            oldDefinition,
            ResourceContractState.empty(oldDefinition),
            "submission-one",
            "player-old",
            8,
            start - 1_000,
        ) as ContractSubmissionPlan.Accepted
        val oldCommitted = ResourceContractEngine.commit(
            oldDefinition,
            ResourceContractState.empty(oldDefinition),
            oldPlan,
            start - 500,
        )
        val oldState = ResourceContractRecord(
            ResourceContractRecord.stateId(oldDefinition.id, oldDefinition.windowStartsAt),
            oldCommitted.state,
            oldDefinition,
        )

        val limit = 100_000L
        ContractRotation.remainingBudget(
            limit,
            listOf(firstState, secondState, oldState),
            listOf(matchingHeld, firstHeld, secondHeld),
            start + 5_000,
        ) shouldBe limit - firstCommitted.receipt.payoutMinor - secondCommitted.receipt.payoutMinor - firstHeld.payoutMinor - secondHeld.payoutMinor
    }
})

package ru.arc.contracts

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify

class ContractSubmissionJournalAuditCacheTest : StringSpec({
    val definition = ResourceContractDefinition(
        id = "audit_stone",
        displayName = "Audit stone",
        itemKey = "minecraft:stone",
        funding = ContractFunding.SERVER_ENVELOPE,
        windowStartsAt = 1_000L,
        windowEndsAt = 20_000L,
        payoutMinorPerUnit = 250L,
        budgetMinor = 25_000L,
        targetQuantity = 100L,
        perPlayerQuantityCap = 40L,
        dynamicPricing = true,
    )
    val gson = Gson()

    fun payload(): EscrowedItemPayload =
        EscrowedItemPayload.capture(definition.itemKey, 8, byteArrayOf(1, 2, 3))

    fun prepared(payloads: List<EscrowedItemPayload> = listOf(payload())): ContractSubmissionJournalRecord {
        val plan = ResourceContractEngine.plan(
            definition, ResourceContractState.empty(definition), "audit-submission", "player-1", 8, 1_500L,
        ) as ContractSubmissionPlan.Accepted
        return ContractSubmissionJournalEngine.prepare(definition, plan, payloads, 1_500L)
    }

    "unchanged refresh skips validation payload decode and historical pricing while advancing age" {
        val payload = spyk(payload())
        val record = spyk(prepared(listOf(payload)))
        val cache = ContractSubmissionJournalAudit.Cache()
        clearMocks(payload, record)
        mockkObject(ContractMarketPricing)
        try {
            val first = cache.summarize(listOf(record), now = 3_500L)
            val later = cache.summarize(listOf(record), now = 6_500L, available = false)

            first.oldestAttentionAgeSeconds shouldBe 2L
            later shouldBe first.copy(available = false, oldestAttentionAgeSeconds = 5L)
            verify(exactly = 1) { record.validated() }
            verify(exactly = 1) { payload.decodedBytes() }
            verify(exactly = 2) { payload.validated() }
            verify(exactly = 1) { ContractMarketPricing.payoutMinor(any(), any(), any(), any(), any()) }
        } finally {
            unmockkObject(ContractMarketPricing)
        }
    }

    "validates Redis replacements with the same id and revision and never caches failed checks" {
        val original = prepared()
        val cache = ContractSubmissionJournalAudit.Cache()
        cache.summarize(listOf(original), 2_000L)
        val replacement = spyk(gson.fromJson(gson.toJson(original), ContractSubmissionJournalRecord::class.java))
        cache.summarize(listOf(replacement), 2_000L)
        cache.summarize(listOf(replacement), 3_000L)
        verify(exactly = 1) { replacement.validated() }

        val badPayloadJson = gson.toJsonTree(original).asJsonObject
        badPayloadJson.getAsJsonArray("itemPayloads")[0].asJsonObject.addProperty("serializedSha256", "0".repeat(64))
        val badPayload = gson.fromJson(badPayloadJson, ContractSubmissionJournalRecord::class.java)
        val badQuoteJson = gson.toJsonTree(original).asJsonObject
        badQuoteJson.addProperty("payoutMinor", original.payoutMinor + 1L)
        val badQuote = gson.fromJson(badQuoteJson, ContractSubmissionJournalRecord::class.java)

        listOf(
            badPayload to "Escrow item payload digest mismatch",
            badQuote to "Journal payout does not equal its immutable marginal quote",
        ).forEach { (invalid, message) ->
            invalid.submissionId shouldBe original.submissionId
            invalid.revision shouldBe original.revision
            shouldThrow<IllegalArgumentException> {
                ContractSubmissionJournalAudit.Cache().summarize(listOf(invalid), 3_000L)
            }.message shouldBe message
            repeat(2) {
                shouldThrow<IllegalArgumentException> { cache.summarize(listOf(invalid), 3_000L) }
                    .message shouldBe message
            }
        }
        cache.summarize(listOf(replacement), 4_000L).totalRecords shouldBe 1
        verify(exactly = 1) { replacement.validated() }
    }

    "detects in-place payload list replacement on a previously validated record" {
        val payloads = mutableListOf(payload())
        val record = prepared(payloads)
        val cache = ContractSubmissionJournalAudit.Cache()
        cache.summarize(listOf(record), 2_000L)
        val invalidJson = gson.toJsonTree(payloads.single()).asJsonObject
        invalidJson.addProperty("serializedSha256", "0".repeat(64))
        payloads[0] = gson.fromJson(invalidJson, EscrowedItemPayload::class.java)

        repeat(2) {
            shouldThrow<IllegalArgumentException> { cache.summarize(listOf(record), 3_000L) }
                .message shouldBe "Escrow item payload digest mismatch"
        }
    }

    "evicts removed records and clears validation at the runtime boundary" {
        val record = spyk(prepared())
        val cache = ContractSubmissionJournalAudit.Cache()
        cache.summarize(listOf(record), 2_000L)
        cache.summarize(emptyList(), 3_000L).totalRecords shouldBe 0
        cache.summarize(listOf(record), 4_000L).totalRecords shouldBe 1
        verify(exactly = 2) { record.validated() }
        cache.clear()
        cache.summarize(listOf(record), 5_000L)
        verify(exactly = 3) { record.validated() }
    }

    "recomputes status exposure and preserves audit bounds after cache hits" {
        val record = prepared()
        val cache = ContractSubmissionJournalAudit.Cache()
        cache.summarize(listOf(record), 2_000L)
        val escrowed = ContractSubmissionJournalEngine.confirmItemsEscrowed(
            ContractSubmissionJournalEngine.beginItemRemoval(record, 2_100L), 2_200L,
        )
        val review = ContractSubmissionJournalEngine.recoverInterrupted(
            ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 2_300L), 2_400L,
        )
        cache.summarize(listOf(review), 5_000L) shouldBe
            ContractSubmissionJournalAudit.summarize(listOf(review), 5_000L)
        cache.summarize(listOf(review), 8_000L) shouldBe
            ContractSubmissionJournalAudit.summarize(listOf(review), 8_000L)
        shouldThrow<IllegalArgumentException> { cache.summarize(listOf(review, review), 8_000L) }
            .message shouldBe "Contract submission journal contains duplicate ids"
        shouldThrow<IllegalArgumentException> { cache.summarize(listOf(review), -1L) }
            .message shouldBe "Journal audit timestamp must be non-negative"
        shouldThrow<IllegalArgumentException> {
            cache.summarize(List(ContractSubmissionJournalAudit.MAX_NETWORK_RECORDS + 1) { review }, 8_000L)
        }.message shouldBe "Contract submission journal exceeds its network record limit"
    }
})

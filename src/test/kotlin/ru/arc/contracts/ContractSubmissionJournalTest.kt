package ru.arc.contracts

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractSubmissionJournalTest : StringSpec({
    val definition =
        ResourceContractDefinition(
            id = "road_stone",
            displayName = "Камень для тракта",
            itemKey = "minecraft:stone",
            funding = ContractFunding.SERVER_ENVELOPE,
            windowStartsAt = 1_000L,
            windowEndsAt = 20_000L,
            payoutMinorPerUnit = 250L,
            budgetMinor = 25_000L,
            targetQuantity = 100L,
            perPlayerQuantityCap = 40L,
            minSubmissionQuantity = 4,
            maxSubmissionQuantity = 32,
        )

    fun plan(id: String = "submission-1"): ContractSubmissionPlan.Accepted =
        ResourceContractEngine.plan(
            definition,
            ResourceContractState.empty(definition),
            id,
            "player-1",
            8,
            1_500L,
        ) as ContractSubmissionPlan.Accepted

    fun payload(quantity: Int = 8): EscrowedItemPayload =
        EscrowedItemPayload.capture(
            itemKey = definition.itemKey,
            quantity = quantity,
            serializedBytes = byteArrayOf(10, 0, 3, 1, 2, 3, quantity.toByte()),
        )

    fun prepared(id: String = "submission-1"): ContractSubmissionJournalRecord =
        ContractSubmissionJournalEngine.prepare(definition, plan(id), listOf(payload()), 1_500L)

    "persists every intent before completing a successful payout" {
        val prepared = prepared()
        prepared.status shouldBe ContractSubmissionJournalStatus.PREPARED
        prepared.payoutReason shouldBe "arc-contract:submission-1"
        prepared.itemPayloads.single().decodedBytes() shouldBe byteArrayOf(10, 0, 3, 1, 2, 3, 8)

        val removalStarted = ContractSubmissionJournalEngine.beginItemRemoval(prepared, 1_501L)
        val escrowed = ContractSubmissionJournalEngine.confirmItemsEscrowed(removalStarted, 1_502L)
        val paymentStarted = ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 1_503L)
        val paid = ContractSubmissionJournalEngine.confirmPaid(paymentStarted, 12_000L, "provider-tx-1", 1_504L)
        val committed = ContractSubmissionJournalEngine.confirmContractCommitted(paid, 1_505L)

        committed.status shouldBe ContractSubmissionJournalStatus.CONTRACT_COMMITTED
        committed.providerBalanceBeforeMinor shouldBe 10_000L
        committed.providerBalanceAfterMinor shouldBe 12_000L
        committed.revision shouldBe 5L
        committed.isTerminal() shouldBe true
    }

    "does not allow an ambiguous external mutation to be started twice" {
        val removalStarted = ContractSubmissionJournalEngine.beginItemRemoval(prepared(), 1_501L)
        shouldThrow<IllegalArgumentException> {
            ContractSubmissionJournalEngine.beginItemRemoval(removalStarted, 1_502L)
        }.message shouldBe "Journal cannot advance from item_removal_started; expected prepared"

        val escrowed = ContractSubmissionJournalEngine.confirmItemsEscrowed(removalStarted, 1_502L)
        val paymentStarted = ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 1_503L)
        shouldThrow<IllegalArgumentException> {
            ContractSubmissionJournalEngine.beginPayment(paymentStarted, 10_000L, 1_504L)
        }.message shouldBe "Journal cannot advance from payment_started; expected items_escrowed"
    }

    "uses every unfinished journal as the only durable quota reservation" {
        val prepared = prepared("quota-one")
        prepared.quotaReservation() shouldBe ContractQuotaReservation("quota-one", "player-1", 8L, 2_000L)

        val cancelled = ContractSubmissionJournalEngine.cancelPrepared(prepared, "inventory_missing", 1_501L)
        cancelled.status shouldBe ContractSubmissionJournalStatus.CANCELLED
        cancelled.cancellationCode shouldBe "inventory_missing"
        cancelled.isTerminal() shouldBe true
        cancelled.quotaReservation() shouldBe null
    }

    "journals a bounded rank-boosted payout as the exact durable reservation" {
        val boostedPlan = ResourceContractEngine.plan(
            definition,
            ResourceContractState.empty(definition),
            "boosted-journal",
            "player-1",
            8,
            1_500L,
            policy = ContractRankPolicy(15_000, 11_200),
        ) as ContractSubmissionPlan.Accepted
        val boostedPayload = payload(8)

        val record = ContractSubmissionJournalEngine.prepare(definition, boostedPlan, listOf(boostedPayload), 1_500L)

        record.payoutMinor shouldBe 2_240L
        record.quotaReservation() shouldBe ContractQuotaReservation("boosted-journal", "player-1", 8L, 2_240L)
    }

    "cancels an item-removal intent only when the adapter proved no mutation" {
        val started = ContractSubmissionJournalEngine.beginItemRemoval(prepared("no-removal"), 1_501L)
        val cancelled = ContractSubmissionJournalEngine.confirmNoItemsRemoved(started, "inventory_changed_before_remove", 1_502L)

        cancelled.status shouldBe ContractSubmissionJournalStatus.CANCELLED
        cancelled.itemRemovalStartedAt shouldBe 1_501L
        cancelled.cancelledAt shouldBe 1_502L
        cancelled.hasConfirmedItemEscrow() shouldBe false
    }

    "halts ambiguous inventory removal and refund evidence for manual review" {
        val started = ContractSubmissionJournalEngine.beginItemRemoval(prepared("ambiguous-remove"), 1_501L)
        val removalReview = ContractSubmissionJournalEngine.haltAmbiguousItemRemoval(started, 1_502L)
        removalReview.reviewReason shouldBe ContractSubmissionReviewReason.INVENTORY_EVIDENCE_CONFLICT

        val escrowed =
            ContractSubmissionJournalEngine.confirmItemsEscrowed(
                ContractSubmissionJournalEngine.beginItemRemoval(prepared("ambiguous-refund"), 1_501L),
                1_502L,
            )
        val refundStarted = ContractSubmissionJournalEngine.beginRefund(escrowed, 1_503L)
        val refundReview = ContractSubmissionJournalEngine.haltAmbiguousRefund(refundStarted, 1_504L)
        refundReview.reviewReason shouldBe ContractSubmissionReviewReason.REFUND_EVIDENCE_CONFLICT
    }

    "moves every interrupted non-idempotent boundary to manual review" {
        val removalStarted = ContractSubmissionJournalEngine.beginItemRemoval(prepared("remove-crash"), 1_501L)
        val removalReview = ContractSubmissionJournalEngine.recoverInterrupted(removalStarted, 2_000L)
        removalReview.status shouldBe ContractSubmissionJournalStatus.MANUAL_REVIEW
        removalReview.reviewReason shouldBe ContractSubmissionReviewReason.INTERRUPTED_ITEM_REMOVAL

        val escrowed =
            ContractSubmissionJournalEngine.confirmItemsEscrowed(
                ContractSubmissionJournalEngine.beginItemRemoval(prepared("pay-crash"), 1_501L),
                1_502L,
            )
        val paymentStarted = ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 1_503L)
        val paymentReview = ContractSubmissionJournalEngine.recoverInterrupted(paymentStarted, 2_000L)
        paymentReview.status shouldBe ContractSubmissionJournalStatus.MANUAL_REVIEW
        paymentReview.reviewFromStatus shouldBe ContractSubmissionJournalStatus.PAYMENT_STARTED
        paymentReview.reviewReason shouldBe ContractSubmissionReviewReason.INTERRUPTED_PAYMENT

        val escrowReview = ContractSubmissionJournalEngine.recoverInterrupted(escrowed, 2_000L)
        escrowReview.status shouldBe ContractSubmissionJournalStatus.MANUAL_REVIEW
        escrowReview.reviewFromStatus shouldBe ContractSubmissionJournalStatus.ITEMS_ESCROWED
        escrowReview.reviewReason shouldBe ContractSubmissionReviewReason.INTERRUPTED_ESCROW

        val failedPayment = ContractSubmissionJournalEngine.confirmPaymentFailed(paymentStarted, 10_000L, "provider_rejected", 1_504L)
        val failedPaymentReview = ContractSubmissionJournalEngine.recoverInterrupted(failedPayment, 2_000L)
        failedPaymentReview.status shouldBe ContractSubmissionJournalStatus.MANUAL_REVIEW
        failedPaymentReview.reviewFromStatus shouldBe ContractSubmissionJournalStatus.PAYMENT_FAILED
        failedPaymentReview.reviewReason shouldBe ContractSubmissionReviewReason.INTERRUPTED_PAYMENT_FAILED

        val refundStarted = ContractSubmissionJournalEngine.beginRefund(escrowed, 1_504L)
        val refundReview = ContractSubmissionJournalEngine.recoverInterrupted(refundStarted, 2_000L)
        refundReview.status shouldBe ContractSubmissionJournalStatus.MANUAL_REVIEW
        refundReview.reviewReason shouldBe ContractSubmissionReviewReason.INTERRUPTED_REFUND
    }

    "leaves prepared and completed phases unchanged and makes confirmations idempotent" {
        val prepared = prepared("safe-recovery")
        ContractSubmissionJournalEngine.recoverInterrupted(prepared, 2_000L) shouldBe prepared
        val escrowed =
            ContractSubmissionJournalEngine.confirmItemsEscrowed(
                ContractSubmissionJournalEngine.beginItemRemoval(prepared, 1_501L),
                1_502L,
            )
        ContractSubmissionJournalEngine.confirmItemsEscrowed(escrowed, 1_600L) shouldBe escrowed
        val paid =
            ContractSubmissionJournalEngine.confirmPaid(
                ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 1_503L),
                12_000L,
                "provider-stable",
                1_504L,
            )
        ContractSubmissionJournalEngine.confirmPaid(paid, 12_000L, "provider-stable", 1_600L) shouldBe paid
        ContractSubmissionJournalEngine.recoverInterrupted(paid, 2_000L) shouldBe paid
        val committed = ContractSubmissionJournalEngine.confirmContractCommitted(paid, 1_505L)
        ContractSubmissionJournalEngine.confirmContractCommitted(committed, 1_600L) shouldBe committed
        ContractSubmissionJournalEngine.recoverInterrupted(committed, 2_000L) shouldBe committed
    }

    "only accepts a failed payment when unchanged balance is proven" {
        val escrowed =
            ContractSubmissionJournalEngine.confirmItemsEscrowed(
                ContractSubmissionJournalEngine.beginItemRemoval(prepared(), 1_501L),
                1_502L,
            )
        val paymentStarted = ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 1_503L)
        shouldThrow<IllegalArgumentException> {
            ContractSubmissionJournalEngine.confirmPaymentFailed(paymentStarted, 10_001L, "provider_error", 1_504L)
        }.message shouldBe "Payment failure cannot be proven because the provider balance changed"

        val failed =
            ContractSubmissionJournalEngine.confirmPaymentFailed(
                paymentStarted,
                10_000L,
                "provider_rejected",
                1_504L,
            )
        val refundStarted = ContractSubmissionJournalEngine.beginRefund(failed, 1_505L)
        val refunded = ContractSubmissionJournalEngine.confirmRefunded(refundStarted, 1_506L)
        refunded.status shouldBe ContractSubmissionJournalStatus.REFUNDED
        refunded.isTerminal() shouldBe true
    }

    "halts a conflicting provider outcome without retrying the payout" {
        val escrowed =
            ContractSubmissionJournalEngine.confirmItemsEscrowed(
                ContractSubmissionJournalEngine.beginItemRemoval(prepared("provider-conflict"), 1_501L),
                1_502L,
            )
        val paymentStarted = ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 1_503L)
        val review = ContractSubmissionJournalEngine.haltAmbiguousPayment(paymentStarted, 10_500L, 1_504L)

        review.status shouldBe ContractSubmissionJournalStatus.MANUAL_REVIEW
        review.reviewFromStatus shouldBe ContractSubmissionJournalStatus.PAYMENT_STARTED
        review.reviewReason shouldBe ContractSubmissionReviewReason.PROVIDER_EVIDENCE_CONFLICT
        review.providerBalanceAfterMinor shouldBe 10_500L
        shouldThrow<IllegalArgumentException> {
            ContractSubmissionJournalEngine.beginPayment(review, 10_500L, 1_505L)
        }
    }

    "rejects corrupt payloads and Gson records that bypass constructors" {
        val gson = Gson()
        val payloadJson = gson.toJsonTree(payload()).asJsonObject
        payloadJson.addProperty("serializedSha256", "0".repeat(64))
        val corruptPayload = gson.fromJson(payloadJson, EscrowedItemPayload::class.java)
        shouldThrow<IllegalArgumentException> { corruptPayload.validated() }
            .message shouldBe "Escrow item payload digest mismatch"

        val recordJson = gson.toJsonTree(prepared()).asJsonObject
        recordJson.addProperty("acceptedQuantity", 7L)
        val corruptRecord = gson.fromJson(recordJson, ContractSubmissionJournalRecord::class.java)
        shouldThrow<IllegalArgumentException> { corruptRecord.validated() }
            .message shouldBe "Journal item payload quantity mismatch"

        val escrowed =
            ContractSubmissionJournalEngine.confirmItemsEscrowed(
                ContractSubmissionJournalEngine.beginItemRemoval(prepared("bad-order"), 1_501L),
                1_502L,
            )
        val paid =
            ContractSubmissionJournalEngine.confirmPaid(
                ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 1_503L),
                12_000L,
                null,
                1_504L,
            )
        val timestampJson = gson.toJsonTree(paid).asJsonObject
        timestampJson.addProperty("paymentStartedAt", 1_504L)
        timestampJson.addProperty("paidAt", 1_503L)
        val badOrder = gson.fromJson(timestampJson, ContractSubmissionJournalRecord::class.java)
        shouldThrow<IllegalArgumentException> { badOrder.validated() }
            .message shouldBe "Payment confirmation predates payment start"
    }

    "enforces payload entry and aggregate byte limits before inventory mutation" {
        shouldThrow<IllegalArgumentException> {
            EscrowedItemPayload.capture(
                definition.itemKey,
                1,
                ByteArray(EscrowedItemPayload.MAX_SERIALIZED_BYTES + 1),
            )
        }.message shouldBe "Escrow item payload is empty or too large"

        val manyPayloads =
            (1..ContractSubmissionJournalRecord.MAX_ITEM_PAYLOADS + 1).map {
                EscrowedItemPayload.capture(definition.itemKey, 1, byteArrayOf(it.toByte()))
            }
        val oversizedDefinition = definition.copy(maxSubmissionQuantity = 64, perPlayerQuantityCap = 64L)
        val oversizedPlan = plan().copy(requestedQuantity = 64, acceptedQuantity = manyPayloads.size.toLong(), payoutMinor = 13_750L)
        shouldThrow<IllegalArgumentException> {
            ContractSubmissionJournalEngine.prepare(oversizedDefinition, oversizedPlan, manyPayloads, 1_500L)
        }.message shouldBe "Invalid journal item payload count"

        val aggregatePayloads =
            (1..5).map {
                EscrowedItemPayload.capture(definition.itemKey, 1, ByteArray(60_000) { it.toByte() })
            }
        val aggregatePlan = plan().copy(requestedQuantity = 5, acceptedQuantity = 5L, payoutMinor = 1_250L)
        shouldThrow<IllegalArgumentException> {
            ContractSubmissionJournalEngine.prepare(definition, aggregatePlan, aggregatePayloads, 1_500L)
        }.message shouldBe "Journal item payloads exceed the total size limit"
    }

    "summarizes held items payout exposure and bounded manual review ids" {
        val escrowed =
            ContractSubmissionJournalEngine.confirmItemsEscrowed(
                ContractSubmissionJournalEngine.beginItemRemoval(prepared("escrowed-one"), 2_000L),
                2_100L,
            )
        val paymentStarted = ContractSubmissionJournalEngine.beginPayment(escrowed.copy(submissionId = "payment-one", payoutReason = "arc-contract:payment-one"), 10_000L, 2_200L)
        val review = ContractSubmissionJournalEngine.recoverInterrupted(paymentStarted, 2_300L)
        val summary = ContractSubmissionJournalAudit.summarize(listOf(escrowed, review), now = 12_100L)

        summary.totalRecords shouldBe 2
        summary.heldItemQuantity shouldBe 16L
        summary.pendingPayoutMinor shouldBe 2_000L
        summary.ambiguousPayoutMinor shouldBe 2_000L
        summary.manualReviewCount shouldBe 1
        summary.oldestAttentionAgeSeconds shouldBe 10L
        summary.capacityRemaining shouldBe ContractSubmissionJournalAudit.MAX_NETWORK_RECORDS - 2
        summary.manualReviewSubmissionIds shouldBe listOf("payment-one")
    }
})

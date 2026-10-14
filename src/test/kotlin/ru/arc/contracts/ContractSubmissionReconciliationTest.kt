package ru.arc.contracts

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ContractSubmissionReconciliationTest : StringSpec({
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

    fun prepared(
        id: String,
        createdAt: Long = 1_500L,
    ): ContractSubmissionJournalRecord {
        val plan =
            ResourceContractEngine.plan(
                definition,
                ResourceContractState.empty(definition),
                id,
                "player-1",
                8,
                1_500L,
            ) as ContractSubmissionPlan.Accepted
        val payload =
            EscrowedItemPayload.capture(
                definition.itemKey,
                8,
                byteArrayOf(10, 0, 3, 1, 2, 3, 8),
            )
        return ContractSubmissionJournalEngine.prepare(definition, plan, listOf(payload), createdAt)
    }

    fun removalReview(id: String = "review-removal"): ContractSubmissionJournalRecord {
        val record = prepared(id)
        return ContractSubmissionJournalEngine.recoverInterrupted(
            ContractSubmissionJournalEngine.beginItemRemoval(record, 1_501L),
            2_000L,
        )
    }

    fun paymentReview(id: String = "review-payment"): ContractSubmissionJournalRecord {
        val removal = ContractSubmissionJournalEngine.beginItemRemoval(prepared(id), 1_501L)
        val escrowed = ContractSubmissionJournalEngine.confirmItemsEscrowed(removal, 1_502L)
        val payment = ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 1_503L)
        return ContractSubmissionJournalEngine.recoverInterrupted(payment, 2_000L)
    }

    fun escrowReview(id: String = "review-escrow"): ContractSubmissionJournalRecord {
        val removal = ContractSubmissionJournalEngine.beginItemRemoval(prepared(id), 1_501L)
        val escrowed = ContractSubmissionJournalEngine.confirmItemsEscrowed(removal, 1_502L)
        return ContractSubmissionJournalEngine.recoverInterrupted(escrowed, 2_000L)
    }

    fun failedPaymentReview(id: String = "review-failed-payment"): ContractSubmissionJournalRecord {
        val removal = ContractSubmissionJournalEngine.beginItemRemoval(prepared(id), 1_501L)
        val escrowed = ContractSubmissionJournalEngine.confirmItemsEscrowed(removal, 1_502L)
        val payment = ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 1_503L)
        val failed = ContractSubmissionJournalEngine.confirmPaymentFailed(payment, 10_000L, "provider_rejected", 1_504L)
        return ContractSubmissionJournalEngine.recoverInterrupted(failed, 2_000L)
    }

    fun request(
        record: ContractSubmissionJournalRecord,
        resolution: ContractSubmissionReconciliationResolution,
        providerBalanceAfterMinor: Long? = null,
        providerTransactionId: String? = null,
        providerTransactionReason: String? = null,
        evidence: String = "Compared the exact player inventory snapshot and provider history entry.",
    ): ContractSubmissionReconciliationRequest =
        ContractSubmissionReconciliationRequest(
            submissionId = record.submissionId,
            expectedRevision = record.revision,
            resolution = resolution,
            operatorId = "codex.ops",
            operatorEvidence = evidence,
            idempotencyKey = "reconcile-${record.submissionId}",
            providerBalanceAfterMinor = providerBalanceAfterMinor,
            providerTransactionId = providerTransactionId,
            providerTransactionReason = providerTransactionReason,
        )

    "requires preview digest and cancels only a proven no-removal review" {
        val review = removalReview()
        val command = request(review, ContractSubmissionReconciliationResolution.NO_ITEMS_REMOVED)
        val preview = ContractSubmissionReconciliationEngine.preview(review, command)

        preview.proposedStatus shouldBe ContractSubmissionJournalStatus.CANCELLED
        preview.evidenceKind shouldBe ContractSubmissionReconciliationEvidenceKind.OPERATOR_INVENTORY_INSPECTION
        preview.commitsContractState shouldBe false
        shouldThrow<IllegalArgumentException> {
            ContractSubmissionReconciliationEngine.apply(review, command, "0".repeat(64), 2_100L)
        }.message shouldBe "Reconciliation review digest is stale"

        val cancelled = ContractSubmissionReconciliationEngine.apply(review, command, preview.reviewDigest, 2_100L)
        cancelled.status shouldBe ContractSubmissionJournalStatus.CANCELLED
        cancelled.cancellationCode shouldBe "operator_no_items_removed"
        cancelled.reconciliation?.reviewReason shouldBe ContractSubmissionReviewReason.INTERRUPTED_ITEM_REMOVAL
        cancelled.reconciliation?.originalReviewEvidence shouldBe review.reviewEvidence
        cancelled.isTerminal() shouldBe true
    }

    "accepts an exact provider-history payout and preserves reconciliation through contract commit" {
        val review = paymentReview()
        val command =
            request(
                review,
                ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED,
                providerBalanceAfterMinor = 12_000L,
                providerTransactionId = "redis-history-123",
                providerTransactionReason = review.payoutReason,
            )
        val preview = ContractSubmissionReconciliationEngine.preview(review, command)
        preview.commitsContractState shouldBe true
        preview.evidenceKind shouldBe
            ContractSubmissionReconciliationEvidenceKind.OPERATOR_PROVIDER_BALANCE_AND_HISTORY

        val paid = ContractSubmissionReconciliationEngine.apply(review, command, preview.reviewDigest, 2_100L)
        paid.status shouldBe ContractSubmissionJournalStatus.PAID
        paid.providerBalanceAfterMinor shouldBe 12_000L
        paid.providerTransactionId shouldBe "redis-history-123"
        val recovery =
            ContractSubmissionRecoveryEngine.recoverPaid(
                definition,
                ResourceContractState.empty(definition),
                paid,
                2_200L,
            )
        recovery.commit.receipt.submissionId shouldBe review.submissionId
        recovery.journal.status shouldBe ContractSubmissionJournalStatus.CONTRACT_COMMITTED
        recovery.journal.reconciliation shouldBe paid.reconciliation

        ContractSubmissionReconciliationEngine.apply(
            recovery.journal,
            command,
            preview.reviewDigest,
            2_300L,
        ) shouldBe recovery.journal
        shouldThrow<IllegalArgumentException> {
            ContractSubmissionReconciliationEngine.apply(
                recovery.journal,
                command.copy(operatorEvidence = "Different evidence"),
                preview.reviewDigest,
                2_300L,
            )
        }.message shouldBe "Reconciliation replay disagrees with persisted evidence"
    }

    "rejects payment adjudication without exact amount and provider history identity" {
        val review = paymentReview("bad-payment-proof")
        shouldThrow<IllegalArgumentException> {
            ContractSubmissionReconciliationEngine.preview(
                review,
                request(
                    review,
                    ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED,
                    providerBalanceAfterMinor = 11_999L,
                    providerTransactionId = "history-1",
                    providerTransactionReason = review.payoutReason,
                ),
            )
        }.message shouldBe "Payment reconciliation balance does not match the exact planned payout"

        shouldThrow<IllegalArgumentException> {
            ContractSubmissionReconciliationEngine.preview(
                review,
                request(
                    review,
                    ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED,
                    providerBalanceAfterMinor = 12_000L,
                    providerTransactionReason = review.payoutReason,
                ),
            )
        }.message shouldBe "Payment reconciliation requires provider history transaction evidence"

        shouldThrow<IllegalArgumentException> {
            ContractSubmissionReconciliationEngine.preview(
                review,
                request(
                    review,
                    ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED,
                    providerBalanceAfterMinor = 12_000L,
                    providerTransactionId = "history-wrong-reason",
                    providerTransactionReason = "arc-contract:another-submission",
                ),
            )
        }.message shouldBe "Payment reconciliation history reason does not match the journal correlation reason"
    }

    "reconciles interrupted escrow and failed payment only as inventory refunds" {
        listOf(escrowReview(), failedPaymentReview()).forEach { review ->
            review.status shouldBe ContractSubmissionJournalStatus.MANUAL_REVIEW
            val refundRequest = request(review, ContractSubmissionReconciliationResolution.ITEMS_REFUNDED)
            val preview = ContractSubmissionReconciliationEngine.preview(review, refundRequest)
            preview.evidenceKind shouldBe ContractSubmissionReconciliationEvidenceKind.OPERATOR_INVENTORY_INSPECTION
            preview.commitsContractState shouldBe false
            shouldThrow<IllegalArgumentException> {
                ContractSubmissionReconciliationEngine.preview(
                    review,
                    request(
                        review,
                        ContractSubmissionReconciliationResolution.ITEMS_REFUNDED,
                        providerBalanceAfterMinor = review.providerBalanceBeforeMinor ?: 1L,
                    ),
                )
            }.message shouldBe "Inventory-only refund resolution does not accept provider evidence"

            val refunded = ContractSubmissionReconciliationEngine.apply(review, refundRequest, preview.reviewDigest, 2_100L)
            refunded.status shouldBe ContractSubmissionJournalStatus.REFUNDED
            refunded.reconciliation?.reviewFromStatus shouldBe review.reviewFromStatus
            ContractSubmissionReconciliationEngine.apply(
                refunded,
                refundRequest,
                preview.reviewDigest,
                2_200L,
            ) shouldBe refunded

            shouldThrow<IllegalArgumentException> {
                ContractSubmissionReconciliationEngine.preview(
                    review,
                    request(
                        review,
                        ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED,
                        providerBalanceAfterMinor = 12_000L,
                        providerTransactionId = "history-unsupported",
                        providerTransactionReason = review.payoutReason,
                    ),
                )
            }.message shouldBe "Payment resolution requires a payment review"
        }
    }

    "requires unchanged balance before terminally confirming a manual item refund" {
        val review = paymentReview("not-paid-refund")
        val command =
            request(
                review,
                ContractSubmissionReconciliationResolution.ITEMS_REFUNDED,
                providerBalanceAfterMinor = 10_000L,
            )
        val preview = ContractSubmissionReconciliationEngine.preview(review, command)
        preview.evidenceKind shouldBe ContractSubmissionReconciliationEvidenceKind.OPERATOR_INVENTORY_AND_PROVIDER
        val refunded = ContractSubmissionReconciliationEngine.apply(review, command, preview.reviewDigest, 2_100L)

        refunded.status shouldBe ContractSubmissionJournalStatus.REFUNDED
        refunded.providerBalanceBeforeMinor shouldBe 10_000L
        refunded.providerBalanceAfterMinor shouldBe 10_000L
        refunded.paymentFailureCode shouldBe "operator_proved_not_paid"
        refunded.refundedAt shouldBe 2_100L
        refunded.isTerminal() shouldBe true

        shouldThrow<IllegalArgumentException> {
            ContractSubmissionReconciliationEngine.preview(
                paymentReview("changed-balance-refund"),
                request(
                    paymentReview("changed-balance-refund"),
                    ContractSubmissionReconciliationResolution.ITEMS_REFUNDED,
                    providerBalanceAfterMinor = 10_001L,
                ),
            )
        }.message shouldBe "Payment review can be refunded only after an unchanged provider balance is proven"
    }

    "records an operator-proven refund without inventing escrow or refund-start timestamps" {
        val review = removalReview("operator-refund")
        val command = request(review, ContractSubmissionReconciliationResolution.ITEMS_REFUNDED)
        val preview = ContractSubmissionReconciliationEngine.preview(review, command)
        val refunded = ContractSubmissionReconciliationEngine.apply(review, command, preview.reviewDigest, 2_100L)

        refunded.status shouldBe ContractSubmissionJournalStatus.REFUNDED
        refunded.itemsEscrowedAt shouldBe null
        refunded.refundStartedAt shouldBe null
        refunded.refundedAt shouldBe 2_100L
        refunded.reconciliation?.evidenceKind shouldBe
            ContractSubmissionReconciliationEvidenceKind.OPERATOR_INVENTORY_INSPECTION
        refunded.validated() shouldBe refunded
    }

    fun cancelled(
        id: String,
        createdAt: Long,
    ): ContractSubmissionJournalRecord =
        ContractSubmissionJournalEngine.cancelPrepared(
            prepared(id, createdAt),
            "test_terminal",
            createdAt + 1,
        )

    "retention deletes only old terminal records and always preserves recent evidence" {
        val now = ContractSubmissionRetentionPolicy.TERMINAL_RETENTION_MILLIS + 100_000L
        val terminal = (0 until 300).map { cancelled("terminal-${it.toString().padStart(4, '0')}", 10_000L + it) }
        val attention = removalReview("manual-attention")
        val plan = ContractSubmissionRetentionPolicy.plan(terminal + attention, now)

        plan.deleteSubmissionIds.size shouldBe 44
        plan.deleteSubmissionIds shouldContainExactly (0 until 44).map { "terminal-${it.toString().padStart(4, '0')}" }
        ("manual-attention" in plan.deleteSubmissionIds) shouldBe false
        plan.totalAfter shouldBe 257
        plan.nonTerminalBefore shouldBe 1
    }

    "retention applies deterministic capacity pressure without deleting non-terminal records" {
        val now = 1_000_000L
        val terminal =
            (0 until 3_300).map {
                cancelled("pressure-${it.toString().padStart(4, '0')}", now - 10_000L + it)
            }
        val attention = removalReview("pressure-manual")
        val plan = ContractSubmissionRetentionPolicy.plan(terminal + attention, now)

        plan.totalAfter shouldBe ContractSubmissionRetentionPolicy.TARGET_NETWORK_RECORDS
        plan.deleteSubmissionIds.size shouldBe 229
        plan.deleteSubmissionIds.first() shouldBe "pressure-0000"
        plan.deleteSubmissionIds.last() shouldBe "pressure-0228"
        ("pressure-manual" in plan.deleteSubmissionIds) shouldBe false
    }

    "loads legacy journal JSON without reconciliation and round trips resolved evidence" {
        val gson = Gson()
        val legacyJson = gson.toJsonTree(paymentReview("legacy-review")).asJsonObject
        legacyJson.remove("reconciliation")
        val legacy = gson.fromJson(legacyJson, ContractSubmissionJournalRecord::class.java).validated()
        legacy.reconciliation shouldBe null
        legacy.status shouldBe ContractSubmissionJournalStatus.MANUAL_REVIEW

        val command =
            request(
                legacy,
                ContractSubmissionReconciliationResolution.PAYMENT_CONFIRMED,
                providerBalanceAfterMinor = 12_000L,
                providerTransactionId = "history-round-trip",
                providerTransactionReason = legacy.payoutReason,
            )
        val preview = ContractSubmissionReconciliationEngine.preview(legacy, command)
        val resolved = ContractSubmissionReconciliationEngine.apply(legacy, command, preview.reviewDigest, 2_100L)
        val roundTrip = gson.fromJson(gson.toJson(resolved), ContractSubmissionJournalRecord::class.java).validated()

        roundTrip shouldBe resolved
        roundTrip.reconciliation?.evidenceKind shouldBe
            ContractSubmissionReconciliationEvidenceKind.OPERATOR_PROVIDER_BALANCE_AND_HISTORY
    }
})

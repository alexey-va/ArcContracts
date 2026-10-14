package ru.arc.contracts

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SeasonMoneyReconciliationTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val now = catalog.startsAt + 10_000L

    fun review(actionId: String): SeasonMoneyJournalRecord {
        val plan = acceptedProjectPlan(catalog, playerId, now, actionId)
        val started =
            SeasonMoneyJournalEngine.beginWithdrawal(
                SeasonMoneyJournalEngine.prepare(catalog, plan, now),
                100_000_00L,
                now + 1,
            )
        return SeasonMoneyJournalEngine.haltForReview(
            started,
            SeasonMoneyReviewReason.PROVIDER_EVIDENCE_CONFLICT,
            "withdrawal_outcome_ambiguous",
            null,
            true,
            now + 2,
        )
    }

    "confirms an exact provider-history withdrawal without retrying it" {
        val record = review("action-season-reconcile-paid")
        val request =
            SeasonMoneyReconciliationRequest(
                actionId = record.actionId,
                expectedRevision = record.revision,
                resolution = SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED,
                operatorId = "ops-primary",
                operatorEvidence = "RedisEconomy history and exact balance checked",
                idempotencyKey = "season-reconcile-paid-1",
                providerHistoryCheckedAt = now + 3,
                providerBalanceAfterMinor = 99_000_00L,
                providerTransactionId = "redis-transaction-1",
                providerTransactionReason = record.withdrawalReason,
            )

        val preview = SeasonMoneyReconciliationEngine.preview(record, request)
        preview.proposedStatus shouldBe SeasonMoneyJournalStatus.FUNDS_WITHDRAWN
        preview.commitsSeasonState shouldBe true
        val resolved = SeasonMoneyReconciliationEngine.apply(record, request, preview.reviewDigest, now + 4)

        resolved.status shouldBe SeasonMoneyJournalStatus.FUNDS_WITHDRAWN
        resolved.balanceAfterMinor shouldBe 99_000_00L
        resolved.providerTransactionId shouldBe "redis-transaction-1"
        resolved.reconciliation?.resolution shouldBe SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED
        SeasonMoneyReconciliationEngine.preview(resolved, request).alreadyApplied shouldBe true
        shouldThrow<IllegalArgumentException> {
            resolved.copy(
                reconciliation = resolved.reconciliation?.copy(providerBalanceAfterMinor = 1L),
            )
        }.message shouldBe "Withdrawn season journal provider evidence disagrees with reconciliation"
    }

    "cancels only after unchanged balance and checked history prove no withdrawal" {
        val plan = acceptedProjectPlan(catalog, playerId, now, "action-season-reconcile-empty")
        val started =
            SeasonMoneyJournalEngine.beginWithdrawal(
                SeasonMoneyJournalEngine.prepare(catalog, plan, now),
                100_000_00L,
                now + 1,
            )
        val record =
            SeasonMoneyJournalEngine.haltForReview(
                started,
                SeasonMoneyReviewReason.INTERRUPTED_WITHDRAWAL,
                "restart_after_withdrawal_intent",
                null,
                now = now + 2,
            )
        val request =
            SeasonMoneyReconciliationRequest(
                actionId = record.actionId,
                expectedRevision = record.revision,
                resolution = SeasonMoneyReconciliationResolution.WITHDRAWAL_NOT_APPLIED,
                operatorId = "ops-primary",
                operatorEvidence = "No matching reason in provider history",
                idempotencyKey = "season-reconcile-empty-1",
                providerHistoryCheckedAt = now + 3,
                providerBalanceAfterMinor = 100_000_00L,
            )

        val preview = SeasonMoneyReconciliationEngine.preview(record, request)
        val resolved = SeasonMoneyReconciliationEngine.apply(record, request, preview.reviewDigest, now + 4)

        resolved.status shouldBe SeasonMoneyJournalStatus.CANCELLED
        resolved.cancellationCode shouldBe "operator_proved_not_withdrawn"
        resolved.providerCallAttempted shouldBe null
        resolved.reconciliation?.resolution shouldBe SeasonMoneyReconciliationResolution.WITHDRAWAL_NOT_APPLIED
        resolved.validated() shouldBe resolved
    }

    "rejects weak or stale provider evidence" {
        val record = review("action-season-reconcile-reject")
        val wrongBalance =
            SeasonMoneyReconciliationRequest(
                actionId = record.actionId,
                expectedRevision = record.revision,
                resolution = SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED,
                operatorId = "ops-primary",
                operatorEvidence = "Checked provider history",
                idempotencyKey = "season-reconcile-reject-1",
                providerHistoryCheckedAt = now + 3,
                providerBalanceAfterMinor = 99_500_00L,
                providerTransactionId = "redis-transaction-2",
                providerTransactionReason = record.withdrawalReason,
            )
        shouldThrow<IllegalArgumentException> {
            SeasonMoneyReconciliationEngine.preview(record, wrongBalance)
        }.message shouldBe "Confirmed season withdrawal balance does not match the exact planned burn"

        val valid = wrongBalance.copy(providerBalanceAfterMinor = 99_000_00L)
        val preview = SeasonMoneyReconciliationEngine.preview(record, valid)
        shouldThrow<IllegalArgumentException> {
            SeasonMoneyReconciliationEngine.apply(record, valid, "0".repeat(64), now + 4)
        }.message shouldBe "Season money reconciliation review digest is stale"
        SeasonMoneyReconciliationEngine.apply(record, valid, preview.reviewDigest, now + 4).validated()
    }

    "does not overturn already-proven withdrawn funds" {
        val plan = acceptedProjectPlan(catalog, playerId, now, "action-season-reconcile-state")
        val started =
            SeasonMoneyJournalEngine.beginWithdrawal(
                SeasonMoneyJournalEngine.prepare(catalog, plan, now),
                100_000_00L,
                now + 1,
            )
        val withdrawn = SeasonMoneyJournalEngine.confirmFundsWithdrawn(started, 99_000_00L, now + 2)
        val review =
            SeasonMoneyJournalEngine.haltForReview(
                withdrawn,
                SeasonMoneyReviewReason.STATE_EVIDENCE_CONFLICT,
                "state_commit_conflict",
                withdrawn.balanceAfterMinor,
                true,
                now + 3,
            )
        val request =
            SeasonMoneyReconciliationRequest(
                actionId = review.actionId,
                expectedRevision = review.revision,
                resolution = SeasonMoneyReconciliationResolution.WITHDRAWAL_NOT_APPLIED,
                operatorId = "ops-primary",
                operatorEvidence = "Attempted contradictory resolution",
                idempotencyKey = "season-reconcile-state-1",
                providerHistoryCheckedAt = now + 4,
                providerBalanceAfterMinor = 100_000_00L,
            )

        shouldThrow<IllegalArgumentException> {
            SeasonMoneyReconciliationEngine.preview(review, request)
        }.message shouldBe "Proven withdrawn funds cannot be reconciled as not applied"
    }

    "loads legacy journal JSON and round trips reconciliation evidence" {
        val gson = Gson()
        val record = review("action-season-reconcile-legacy")
        val legacyJson = gson.toJsonTree(record).asJsonObject
        legacyJson.remove("providerTransactionId")
        legacyJson.remove("reconciliation")
        val legacy = gson.fromJson(legacyJson, SeasonMoneyJournalRecord::class.java).validated()
        legacy.providerTransactionId shouldBe null
        legacy.reconciliation shouldBe null

        val request =
            SeasonMoneyReconciliationRequest(
                actionId = legacy.actionId,
                expectedRevision = legacy.revision,
                resolution = SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED,
                operatorId = "ops-primary",
                operatorEvidence = "Legacy record provider evidence checked",
                idempotencyKey = "season-reconcile-legacy-1",
                providerHistoryCheckedAt = now + 3,
                providerBalanceAfterMinor = 99_000_00L,
                providerTransactionId = "redis-transaction-legacy",
                providerTransactionReason = legacy.withdrawalReason,
            )
        val preview = SeasonMoneyReconciliationEngine.preview(legacy, request)
        val resolved = SeasonMoneyReconciliationEngine.apply(legacy, request, preview.reviewDigest, now + 4)
        val roundTrip = gson.fromJson(gson.toJson(resolved), SeasonMoneyJournalRecord::class.java).validated()

        roundTrip shouldBe resolved
        roundTrip.reconciliation?.providerTransactionId shouldBe "redis-transaction-legacy"
    }
})

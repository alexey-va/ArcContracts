package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SeasonDungeonRewardJournalTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val itemKey = "arc:road_revival/mines_core"
    val payload = EscrowedItemPayload.capture(itemKey, 1, byteArrayOf(1, 2, 3))
    val plan =
        SeasonDungeonRewardPlan.Accepted(
            rewardId = "reward-${"a".repeat(64)}",
            runId = "run-reward-journal",
            catalogDigest = catalog.revisionDigest(),
            dungeonContractId = "mines_recon",
            instanceWorld = "em_id_the_mines_journal",
            playerId = playerId,
            payoutMinor = catalog.dungeonContracts.getValue("mines_recon").payoutMinorPerPlayer,
            trophyItemKey = itemKey,
            activeShare = 1.0,
            expectedStateRevision = 4L,
            plannedAt = catalog.startsAt + 1_000L,
        )

    "journal brackets both non-idempotent side effects and final state commit" {
        val prepared = SeasonDungeonRewardJournalEngine.prepare(plan, payload, plan.plannedAt)
        val paying = SeasonDungeonRewardJournalEngine.beginPayment(prepared, 10_000L, plan.plannedAt + 1)
        val paid =
            SeasonDungeonRewardJournalEngine.confirmPaid(
                paying,
                10_000L + plan.payoutMinor,
                "provider-1",
                plan.plannedAt + 2,
            )
        val delivering = SeasonDungeonRewardJournalEngine.beginTrophyDelivery(paid, plan.plannedAt + 3)
        val delivered = SeasonDungeonRewardJournalEngine.confirmTrophyDelivered(delivering, plan.plannedAt + 4)
        val committed = SeasonDungeonRewardJournalEngine.confirmStateCommitted(delivered, plan.plannedAt + 5)

        committed.status shouldBe SeasonDungeonRewardJournalStatus.STATE_COMMITTED
        committed.trophyDeliveryAttempts shouldBe 1
        SeasonDungeonRewardJournalAudit.summarize(listOf(delivered)).pendingPayoutMinor shouldBe plan.payoutMinor
    }

    "restart never retries an ambiguous money or trophy intent" {
        val prepared = SeasonDungeonRewardJournalEngine.prepare(plan, payload, plan.plannedAt)
        val paying = SeasonDungeonRewardJournalEngine.beginPayment(prepared, 10_000L, plan.plannedAt + 1)
        val paymentReview = SeasonDungeonRewardJournalEngine.recoverInterrupted(paying, plan.plannedAt + 2)
        paymentReview.status shouldBe SeasonDungeonRewardJournalStatus.MANUAL_REVIEW
        paymentReview.reviewReason shouldBe SeasonDungeonRewardReviewReason.INTERRUPTED_PAYMENT

        val paid =
            SeasonDungeonRewardJournalEngine.confirmPaid(
                paying,
                10_000L + plan.payoutMinor,
                null,
                plan.plannedAt + 2,
            )
        val delivering = SeasonDungeonRewardJournalEngine.beginTrophyDelivery(paid, plan.plannedAt + 3)
        val trophyReview = SeasonDungeonRewardJournalEngine.recoverInterrupted(delivering, plan.plannedAt + 4)
        trophyReview.status shouldBe SeasonDungeonRewardJournalStatus.MANUAL_REVIEW
        trophyReview.reviewReason shouldBe SeasonDungeonRewardReviewReason.INTERRUPTED_TROPHY_DELIVERY
    }

    "proven no-op delivery returns to paid and remains safely retryable" {
        val prepared = SeasonDungeonRewardJournalEngine.prepare(plan, payload, plan.plannedAt)
        val paying = SeasonDungeonRewardJournalEngine.beginPayment(prepared, 10_000L, plan.plannedAt + 1)
        val paid =
            SeasonDungeonRewardJournalEngine.confirmPaid(
                paying,
                10_000L + plan.payoutMinor,
                null,
                plan.plannedAt + 2,
            )
        val started = SeasonDungeonRewardJournalEngine.beginTrophyDelivery(paid, plan.plannedAt + 3)
        val retryable =
            SeasonDungeonRewardJournalEngine.confirmTrophyNotDelivered(started, "slot_changed", plan.plannedAt + 4)
        retryable.status shouldBe SeasonDungeonRewardJournalStatus.PAID
        retryable.trophyDeliveryAttempts shouldBe 1
        retryable.lastTrophyDeliveryFailure shouldBe "slot_changed"
    }
})

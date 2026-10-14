package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SeasonTrophyJournalTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val stageId = catalog.completionStage
    val itemKey = catalog.dungeonContracts.getValue("mines_recon").plannedBoundReward
    val now = catalog.startsAt + 10_000L
    val plan =
        SeasonTrophyContributionPlan.Accepted(
            contributionId = "trophy-journal-1",
            stageId = stageId,
            itemKey = itemKey,
            playerId = playerId,
            requestedQuantity = 1,
            acceptedQuantity = 1,
            expectedStateRevision = 7,
            catalogDigest = catalog.revisionDigest(),
            plannedAt = now,
        )
    val payload = EscrowedItemPayload.capture(itemKey, 1, byteArrayOf(1, 2, 3))

    "journal orders durable intent, removal and state commit" {
        val prepared = SeasonTrophyJournalEngine.prepare(catalog, plan, listOf(payload), now)
        val started = SeasonTrophyJournalEngine.beginItemRemoval(prepared, now + 1)
        val removed = SeasonTrophyJournalEngine.confirmItemsRemoved(started, now + 2)
        val committed = SeasonTrophyJournalEngine.confirmStateCommitted(removed, now + 3)

        committed.status shouldBe SeasonTrophyJournalStatus.STATE_COMMITTED
        committed.revision shouldBe 3
        SeasonTrophyJournalEngine.confirmStateCommitted(committed, now + 4) shouldBe committed
        SeasonTrophyJournalAudit.summarize(listOf(removed, committed)).removedPendingCommitQuantity shouldBe 1
    }

    "unknown item removal stops for review and is never marked removed" {
        val started =
            SeasonTrophyJournalEngine.beginItemRemoval(
                SeasonTrophyJournalEngine.prepare(catalog, plan, listOf(payload), now),
                now + 1,
            )
        val review =
            SeasonTrophyJournalEngine.haltItemRemoval(
                started,
                SeasonTrophyReviewReason.INVENTORY_EVIDENCE_CONFLICT,
                "Exact inventory state could not be proven",
                now + 2,
            )
        review.status shouldBe SeasonTrophyJournalStatus.MANUAL_REVIEW
        review.itemsRemovedAt shouldBe null
        SeasonTrophyJournalAudit.summarize(listOf(review)).manualReviewQuantity shouldBe 1
        shouldThrow<IllegalArgumentException> {
            SeasonTrophyJournalEngine.confirmItemsRemoved(review, now + 3)
        }
    }

    "payload quantity and digest are mandatory evidence" {
        shouldThrow<IllegalArgumentException> {
            SeasonTrophyJournalEngine.prepare(
                catalog,
                plan.copy(acceptedQuantity = 2, requestedQuantity = 2),
                listOf(payload),
                now,
            )
        }.message shouldBe "Season trophy journal payload quantity mismatch"
    }
})

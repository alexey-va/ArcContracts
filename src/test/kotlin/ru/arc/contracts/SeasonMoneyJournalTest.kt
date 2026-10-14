package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SeasonMoneyJournalTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val now = catalog.startsAt + 1_000L

    "journal proves exact withdrawal before the state commit" {
        val plan = acceptedProjectPlan(catalog, playerId, now)
        val prepared = SeasonMoneyJournalEngine.prepare(catalog, plan, now)
        val started = SeasonMoneyJournalEngine.beginWithdrawal(prepared, 100_000_00L, now + 1)
        val withdrawn =
            SeasonMoneyJournalEngine.confirmFundsWithdrawn(
                started,
                100_000_00L - plan.amountMinor,
                now + 2,
            )
        val committed = SeasonMoneyJournalEngine.confirmStateCommitted(withdrawn, now + 3)

        committed.status shouldBe SeasonMoneyJournalStatus.STATE_COMMITTED
        committed.providerCallAttempted shouldBe true
        committed.validated() shouldBe committed
        shouldThrow<IllegalArgumentException> {
            SeasonMoneyJournalEngine.confirmFundsWithdrawn(started, 99_999_99L, now + 2)
        }.message shouldBe "Season money withdrawal confirmation has the wrong balance"
    }

    "journal distinguishes a skipped provider call from an unchanged rejected call" {
        val plan = acceptedProjectPlan(catalog, playerId, now)
        val prepared = SeasonMoneyJournalEngine.prepare(catalog, plan, now)
        val started = SeasonMoneyJournalEngine.beginWithdrawal(prepared, 100_000_00L, now + 1)

        val skipped =
            SeasonMoneyJournalEngine.confirmNoProviderCall(
                started,
                99_000_00L,
                "provider_balance_changed_before_call",
                now + 2,
            )
        skipped.status shouldBe SeasonMoneyJournalStatus.CANCELLED
        skipped.providerCallAttempted shouldBe false
        skipped.validated() shouldBe skipped
        SeasonMoneyJournalEngine.confirmNoProviderCall(
            started,
            null,
            "provider_unavailable",
            now + 2,
        ).validated().balanceAfterMinor shouldBe null

        val rejected =
            SeasonMoneyJournalEngine.confirmWithdrawalFailed(
                started,
                100_000_00L,
                "provider_rejected",
                now + 2,
            )
        rejected.providerCallAttempted shouldBe true
        rejected.validated() shouldBe rejected
    }

    "ambiguous provider evidence is retained for manual review" {
        val plan = acceptedProjectPlan(catalog, playerId, now)
        val started =
            SeasonMoneyJournalEngine.beginWithdrawal(
                SeasonMoneyJournalEngine.prepare(catalog, plan, now),
                100_000_00L,
                now + 1,
            )
        val review =
            SeasonMoneyJournalEngine.haltForReview(
                started,
                SeasonMoneyReviewReason.PROVIDER_EVIDENCE_CONFLICT,
                "withdrawal_outcome_ambiguous",
                99_500_00L,
                true,
                now + 2,
            )

        review.status shouldBe SeasonMoneyJournalStatus.MANUAL_REVIEW
        review.reviewFromStatus shouldBe SeasonMoneyJournalStatus.WITHDRAWAL_STARTED
        SeasonMoneyJournalAudit.summarize(listOf(review)).ambiguousBurnMinor shouldBe plan.amountMinor
    }
})

internal fun acceptedProjectPlan(
    catalog: ObserveSeasonCatalog,
    playerId: String,
    now: Long,
    actionId: String = "action-project-journal",
): SeasonMoneyActionPlan.Accepted =
    SeasonMoneyActionEngine.plan(
        catalog,
        SeasonRuntimeState.empty(catalog),
        actionId,
        playerId,
        SeasonMoneyActionRequest.ProjectCash("road_foundation", 1_000_00L),
        now,
    ) as SeasonMoneyActionPlan.Accepted

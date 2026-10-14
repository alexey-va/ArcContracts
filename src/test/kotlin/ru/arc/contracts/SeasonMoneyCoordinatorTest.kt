package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SeasonMoneyCoordinatorTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val now = catalog.startsAt + 1_000L

    "successful withdrawal commits project state exactly once" {
        val persistence = FakeSeasonMoneyPersistence(SeasonRuntimeState.empty(catalog))
        val gateway = FakeSeasonMoneyGateway(100_000_00L)
        var clock = now
        val coordinator = SeasonMoneyCoordinator(persistence, gateway) { clock++ }

        val outcome =
            coordinator.submit(
                catalog,
                "action-coordinator-1",
                playerId,
                SeasonMoneyActionRequest.ProjectCash("road_foundation", 1_000_00L),
            ) as SeasonMoneyActionOutcome.Committed

        gateway.withdrawCalls shouldBe 1
        gateway.balanceMinor shouldBe 99_000_00L
        persistence.current.recentReceipts.getValue("action-coordinator-1") shouldBe outcome.receipt
        persistence.journal.getValue("action-coordinator-1").status shouldBe SeasonMoneyJournalStatus.STATE_COMMITTED

        val duplicate =
            coordinator.submit(
                catalog,
                "action-coordinator-1",
                playerId,
                SeasonMoneyActionRequest.ProjectCash("road_foundation", 1_000_00L),
            )
        duplicate shouldBe SeasonMoneyActionOutcome.Duplicate(outcome.receipt)
        gateway.withdrawCalls shouldBe 1
    }

    "balance race skips provider call and cancels without changing state" {
        val initial = SeasonRuntimeState.empty(catalog)
        val persistence = FakeSeasonMoneyPersistence(initial)
        val gateway = FakeSeasonMoneyGateway(100_000_00L).apply { changeBeforeProviderCallTo = 99_500_00L }
        var clock = now
        val outcome =
            SeasonMoneyCoordinator(persistence, gateway) { clock++ }.submit(
                catalog,
                "action-coordinator-race",
                playerId,
                SeasonMoneyActionRequest.ProjectCash("road_foundation", 1_000_00L),
            )

        outcome shouldBe
            SeasonMoneyActionOutcome.Cancelled(
                "action-coordinator-race",
                "provider_balance_changed_before_call",
            )
        gateway.withdrawCalls shouldBe 0
        persistence.current shouldBe initial
        persistence.journal.getValue("action-coordinator-race").providerCallAttempted shouldBe false
    }

    "ambiguous provider outcome halts this and later season money actions" {
        val persistence = FakeSeasonMoneyPersistence(SeasonRuntimeState.empty(catalog))
        val gateway = FakeSeasonMoneyGateway(100_000_00L).apply { ambiguousAfterMinor = 99_500_00L }
        var clock = now
        val coordinator = SeasonMoneyCoordinator(persistence, gateway) { clock++ }

        coordinator.submit(
            catalog,
            "action-coordinator-ambiguous",
            playerId,
            SeasonMoneyActionRequest.ProjectCash("road_foundation", 1_000_00L),
        ) shouldBe SeasonMoneyActionOutcome.ManualReview("action-coordinator-ambiguous")
        persistence.journal.getValue("action-coordinator-ambiguous").status shouldBe
            SeasonMoneyJournalStatus.MANUAL_REVIEW

        coordinator.submit(
            catalog,
            "action-coordinator-blocked",
            playerId,
            SeasonMoneyActionRequest.ProjectCash("road_foundation", 1_000_00L),
        ) shouldBe SeasonMoneyActionOutcome.Unavailable("action-coordinator-blocked")
    }

    "restart recovery cancels pre-call intent and commits proven funds" {
        val initial = SeasonRuntimeState.empty(catalog)
        val persistence = FakeSeasonMoneyPersistence(initial)
        val gateway = FakeSeasonMoneyGateway(100_000_00L)
        var clock = now
        val plan =
            SeasonMoneyActionEngine.plan(
                catalog,
                initial,
                "action-recovery-funds",
                playerId,
                SeasonMoneyActionRequest.ProjectCash("road_foundation", 1_000_00L),
                clock++,
            ) as SeasonMoneyActionPlan.Accepted
        val prepared = SeasonMoneyJournalEngine.prepare(catalog, plan, clock++)
        val started = SeasonMoneyJournalEngine.beginWithdrawal(prepared, 100_000_00L, clock++)
        persistence.journal[plan.actionId] =
            SeasonMoneyJournalEngine.confirmFundsWithdrawn(started, 99_000_00L, clock++)

        val untouched = acceptedProjectPlan(catalog, playerId, clock++, "action-recovery-prepared")
        persistence.journal[untouched.actionId] = SeasonMoneyJournalEngine.prepare(catalog, untouched, clock++)

        val summary = SeasonMoneyCoordinator(persistence, gateway) { clock++ }.recover(catalog)

        summary.committedWithdrawals shouldBe 1
        summary.cancelledPrepared shouldBe 1
        summary.movedToManualReview shouldBe 0
        persistence.current.recentReceipts.containsKey(plan.actionId) shouldBe true
        persistence.journal.getValue(plan.actionId).status shouldBe SeasonMoneyJournalStatus.STATE_COMMITTED
        persistence.journal.getValue(untouched.actionId).status shouldBe SeasonMoneyJournalStatus.CANCELLED
        gateway.withdrawCalls shouldBe 0
    }

    "durably binds and consumes a prepaid pass on one native run" {
        val unlocked = completedRoadFoundation(catalog, playerId)
        val plan =
            SeasonMoneyActionEngine.plan(
                catalog,
                unlocked,
                "action-coordinator-pass",
                playerId,
                SeasonMoneyActionRequest.DungeonAdmission("mines_recon"),
                now,
            ) as SeasonMoneyActionPlan.Accepted
        val persistence = FakeSeasonMoneyPersistence(SeasonMoneyActionEngine.commit(catalog, unlocked, plan, now + 1).state)
        val coordinator = SeasonMoneyCoordinator(persistence, FakeSeasonMoneyGateway(0L))

        coordinator.bindAdmissions(catalog, "mines_recon", "native-run-1", setOf(playerId), now + 2)
            .boundPlayerIds shouldBe setOf(playerId)
        persistence.current.admissionPasses.values.single().status shouldBe DungeonAdmissionPassStatus.BOUND_TO_RUN

        coordinator.consumeAdmissions(catalog, "mines_recon", "native-run-1", setOf(playerId), now + 3)
            .boundPlayerIds shouldBe setOf(playerId)
        persistence.current.admissionPasses.values.single().status shouldBe DungeonAdmissionPassStatus.CONSUMED
    }

    "restart preserves one-time launch authorization and consumes it before native clone" {
        val unlocked = completedRoadFoundation(catalog, playerId)
        val plan =
            SeasonMoneyActionEngine.plan(
                catalog,
                unlocked,
                "action-coordinator-launch-pass",
                playerId,
                SeasonMoneyActionRequest.DungeonAdmission("mines_recon"),
                now,
            ) as SeasonMoneyActionPlan.Accepted
        val persistence =
            FakeSeasonMoneyPersistence(SeasonMoneyActionEngine.commit(catalog, unlocked, plan, now + 1).state)
        val firstProcess =
            SeasonMoneyCoordinator(
                persistence,
                FakeSeasonMoneyGateway(0L),
                SeasonDungeonLaunchGate(
                    tokenIdFactory = { "launch-coordinator-restart" },
                    runIdFactory = { "run-coordinator-restart" },
                ),
            )
        val reserved =
            firstProcess.reserveDungeonLaunch(catalog, "mines_recon", setOf(playerId), now + 2)
        persistence.current.dungeonLaunchTokens.keys shouldBe setOf(reserved.token.tokenId)

        val restarted = SeasonMoneyCoordinator(persistence, FakeSeasonMoneyGateway(0L))
        val authorized =
            requireNotNull(
                restarted.authorizeDungeonInstance(
                    catalog,
                    "em_id_the_mines",
                    "em_id_the_mines_restart",
                    now + 3,
                ),
            )
        authorized.authorization.runId shouldBe reserved.token.runId
        persistence.current.dungeonLaunchTokens shouldBe emptyMap()
        persistence.current.authorizedDungeonRuns.keys shouldBe setOf("em_id_the_mines_restart")
        restarted.authorizeDungeonInstance(
            catalog,
            "em_id_the_mines",
            "em_id_the_mines_replay",
            now + 4,
        ) shouldBe null

        restarted.consumeAdmissions(
            catalog,
            "mines_recon",
            reserved.token.runId,
            setOf(playerId),
            now + 5,
            "em_id_the_mines_restart",
        )
        persistence.current.authorizedDungeonRuns shouldBe emptyMap()
        persistence.current.admissionPasses.values.single().status shouldBe DungeonAdmissionPassStatus.CONSUMED
    }

    "restart releases an expired launch without authorizing a clone" {
        val unlocked = completedRoadFoundation(catalog, playerId)
        val plan =
            SeasonMoneyActionEngine.plan(
                catalog,
                unlocked,
                "action-coordinator-expired-pass",
                playerId,
                SeasonMoneyActionRequest.DungeonAdmission("mines_recon"),
                now,
            ) as SeasonMoneyActionPlan.Accepted
        val persistence =
            FakeSeasonMoneyPersistence(SeasonMoneyActionEngine.commit(catalog, unlocked, plan, now + 1).state)
        val firstProcess =
            SeasonMoneyCoordinator(
                persistence,
                FakeSeasonMoneyGateway(0L),
                SeasonDungeonLaunchGate(
                    tokenTtlMillis = 10L,
                    tokenIdFactory = { "launch-coordinator-expired" },
                    runIdFactory = { "run-coordinator-expired" },
                ),
            )
        firstProcess.reserveDungeonLaunch(catalog, "mines_recon", setOf(playerId), now + 2)

        val restarted = SeasonMoneyCoordinator(persistence, FakeSeasonMoneyGateway(0L))
        restarted.authorizeDungeonInstance(
            catalog,
            "em_id_the_mines",
            "em_id_the_mines_expired",
            now + 20_000,
        ) shouldBe null
        persistence.current.dungeonLaunchTokens shouldBe emptyMap()
        persistence.current.admissionPasses.values.single().status shouldBe DungeonAdmissionPassStatus.AVAILABLE
    }

    "commits reconciled funds without another provider call" {
        val persistence = FakeSeasonMoneyPersistence(SeasonRuntimeState.empty(catalog).copy(revision = 7L))
        val gateway = FakeSeasonMoneyGateway(99_000_00L)
        val plan = acceptedProjectPlan(catalog, playerId, now, "action-reconciled-commit")
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
                null,
                true,
                now + 2,
            )
        val request =
            SeasonMoneyReconciliationRequest(
                actionId = review.actionId,
                expectedRevision = review.revision,
                resolution = SeasonMoneyReconciliationResolution.WITHDRAWAL_CONFIRMED,
                operatorId = "ops-primary",
                operatorEvidence = "Exact provider history and balance checked",
                idempotencyKey = "season-reconciled-commit-1",
                providerHistoryCheckedAt = now + 3,
                providerBalanceAfterMinor = 99_000_00L,
                providerTransactionId = "redis-transaction-commit",
                providerTransactionReason = review.withdrawalReason,
            )
        val preview = SeasonMoneyReconciliationEngine.preview(review, request)
        val resolved = SeasonMoneyReconciliationEngine.apply(review, request, preview.reviewDigest, now + 4)
        persistence.journal[resolved.actionId] = resolved
        var clock = now + 5

        val outcome = SeasonMoneyCoordinator(persistence, gateway) { clock++ }.commitReconciled(catalog, resolved)

        outcome shouldBe SeasonMoneyActionOutcome.Committed(persistence.current.recentReceipts.getValue(resolved.actionId))
        persistence.current.revision shouldBe 8L
        gateway.withdrawCalls shouldBe 0
        persistence.journal.getValue(resolved.actionId).status shouldBe SeasonMoneyJournalStatus.STATE_COMMITTED
    }

    "prunes oldest terminal records before accepting another action" {
        val persistence = FakeSeasonMoneyPersistence(SeasonRuntimeState.empty(catalog))
        repeat(2_049) { index ->
            val plan = acceptedProjectPlan(catalog, playerId, now, "retained-action-$index")
            val prepared = SeasonMoneyJournalEngine.prepare(catalog, plan, now + index)
            persistence.journal[plan.actionId] =
                SeasonMoneyJournalEngine.cancelPrepared(prepared, "test_terminal", now + index + 1)
        }
        var clock = now + 3_000

        SeasonMoneyCoordinator(persistence, FakeSeasonMoneyGateway(100_000_00L)) { clock++ }.submit(
            catalog,
            "action-after-retention",
            playerId,
            SeasonMoneyActionRequest.ProjectCash("road_foundation", 1_000_00L),
        ) as SeasonMoneyActionOutcome.Committed

        persistence.journal.containsKey("retained-action-0") shouldBe false
        persistence.journal.containsKey("action-after-retention") shouldBe true
    }
})

private class FakeSeasonMoneyPersistence(
    var current: SeasonRuntimeState,
) : SeasonMoneyPersistence {
    val journal = linkedMapOf<String, SeasonMoneyJournalRecord>()

    override fun state(catalog: ObserveSeasonCatalog): SeasonRuntimeState = current.validatedAgainst(catalog)

    override fun journalRecords(): List<SeasonMoneyJournalRecord> = journal.values.toList()

    override suspend fun persistState(state: SeasonRuntimeState) {
        current = state
    }

    override suspend fun persistJournal(record: SeasonMoneyJournalRecord) {
        journal[record.actionId] = record
    }

    override suspend fun deleteJournal(actionId: String) {
        journal.remove(actionId)
    }
}

private class FakeSeasonMoneyGateway(
    var balanceMinor: Long,
) : SeasonMoneyGateway {
    var withdrawCalls = 0
    var changeBeforeProviderCallTo: Long? = null
    var ambiguousAfterMinor: Long? = null

    override suspend fun balanceMinor(playerId: String): Long = balanceMinor

    override suspend fun withdraw(
        playerId: String,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): SeasonMoneyEvidence {
        changeBeforeProviderCallTo?.let { changed ->
            balanceMinor = changed
            return SeasonMoneyEvidence(false, false, changed, "provider_balance_changed_before_call")
        }
        withdrawCalls += 1
        ambiguousAfterMinor?.let { ambiguous ->
            balanceMinor = ambiguous
            return SeasonMoneyEvidence(null, true, ambiguous)
        }
        balanceMinor -= amountMinor
        return SeasonMoneyEvidence(true, true, balanceMinor)
    }
}

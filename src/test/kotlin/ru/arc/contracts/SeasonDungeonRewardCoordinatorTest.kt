package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SeasonDungeonRewardCoordinatorTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val itemKey = "arc:road_revival/mines_core"
    val now = catalog.startsAt + 20_000L

    fun authorizedState(): Pair<SeasonRuntimeState, SeasonDungeonRunAuthorization> {
        val unlocked = completedRoadFoundation(catalog, playerId)
        val admission =
            SeasonMoneyActionEngine.plan(
                catalog,
                unlocked,
                "action-reward-coordinator-pass",
                playerId,
                SeasonMoneyActionRequest.DungeonAdmission("mines_recon"),
                now,
            ) as SeasonMoneyActionPlan.Accepted
        val purchased = SeasonMoneyActionEngine.commit(catalog, unlocked, admission, now + 1).state
        val gate =
            SeasonDungeonLaunchGate(
                tokenIdFactory = { "launch-reward-coordinator" },
                runIdFactory = { "run-reward-coordinator" },
            )
        val reserved = gate.reserve(catalog, purchased, "mines_recon", setOf(playerId), now + 2)
        val authorized =
            requireNotNull(
                gate.authorizeInstance(
                    catalog,
                    reserved.state,
                    "em_id_the_mines",
                    "em_id_the_mines_coordinator",
                    now + 3,
                ),
            )
        return gate.consumeAuthorizedRunAdmissions(
            catalog,
            authorized.state,
            authorized.authorization.instanceWorld,
            now + 4,
        ) to authorized.authorization
    }

    "money and exact bound trophy are each attempted once before receipt commit" {
        val (state, authorization) = authorizedState()
        val persistence = FakeDungeonRewardPersistence(state)
        val payment = FakeDungeonRewardPayment()
        val trophies = FakeDungeonRewardTrophies(itemKey)
        var clock = now + 10
        val coordinator = SeasonDungeonRewardCoordinator(persistence, payment, trophies) { clock++ }

        val outcome = coordinator.deliver(catalog, authorization, playerId, 1.0, now + 5) as
            SeasonDungeonRewardOutcome.Committed
        payment.depositCalls shouldBe 1
        trophies.deliveryCalls shouldBe 1
        persistence.current.recentDungeonRewardReceipts.getValue(outcome.receipt.rewardId) shouldBe outcome.receipt
        persistence.journal.getValue(outcome.receipt.rewardId).status shouldBe
            SeasonDungeonRewardJournalStatus.STATE_COMMITTED

        coordinator.deliver(catalog, authorization, playerId, 1.0, now + 6) shouldBe
            SeasonDungeonRewardOutcome.Duplicate(outcome.receipt)
        payment.depositCalls shouldBe 1
        trophies.deliveryCalls shouldBe 1
    }

    "restart after proven payment resumes only trophy delivery and state commit" {
        val (state, authorization) = authorizedState()
        val plan =
            SeasonDungeonRewardEngine.plan(catalog, state, authorization, playerId, 1.0, now + 5) as
                SeasonDungeonRewardPlan.Accepted
        val payload = EscrowedItemPayload.capture(itemKey, 1, byteArrayOf(9, 8, 7))
        val prepared = SeasonDungeonRewardJournalEngine.prepare(plan, payload, now + 5)
        val paying = SeasonDungeonRewardJournalEngine.beginPayment(prepared, 50_000L, now + 6)
        val paid = SeasonDungeonRewardJournalEngine.confirmPaid(paying, 50_000L + plan.payoutMinor, null, now + 7)
        val persistence = FakeDungeonRewardPersistence(state).also { it.journal[paid.rewardId] = paid }
        val payment = FakeDungeonRewardPayment()
        val trophies = FakeDungeonRewardTrophies(itemKey)
        var clock = now + 20

        val summary = SeasonDungeonRewardCoordinator(persistence, payment, trophies) { clock++ }.recover(catalog)
        summary.statusCounts.getValue(SeasonDungeonRewardJournalStatus.STATE_COMMITTED.label) shouldBe 1L
        payment.depositCalls shouldBe 0
        trophies.deliveryCalls shouldBe 1
        persistence.current.recentDungeonRewardReceipts.containsKey(plan.rewardId) shouldBe true
    }

    "ambiguous provider result is fail-stopped without trophy delivery" {
        val (state, authorization) = authorizedState()
        val persistence = FakeDungeonRewardPersistence(state)
        val payment =
            FakeDungeonRewardPayment(
                evidence = ContractPaymentEvidence(providerAccepted = null, balanceAfterMinor = null),
            )
        val trophies = FakeDungeonRewardTrophies(itemKey)
        var clock = now + 30
        val outcome =
            SeasonDungeonRewardCoordinator(persistence, payment, trophies) { clock++ }
                .deliver(catalog, authorization, playerId, 1.0, now + 5)

        outcome shouldBe SeasonDungeonRewardOutcome.ManualReview(
            SeasonDungeonRewardEngine.rewardId(catalog.revisionDigest(), authorization.runId, "mines_recon", playerId),
        )
        payment.depositCalls shouldBe 1
        trophies.deliveryCalls shouldBe 0
        persistence.journal.values.single().status shouldBe SeasonDungeonRewardJournalStatus.MANUAL_REVIEW
    }

    "crash after provider success never repeats the payout" {
        val (state, authorization) = authorizedState()
        val persistence =
            FakeDungeonRewardPersistence(
                state,
                failJournalStatusOnce = SeasonDungeonRewardJournalStatus.PAID,
            )
        val payment = FakeDungeonRewardPayment()
        val trophies = FakeDungeonRewardTrophies(itemKey)
        var clock = now + 40
        val coordinator = SeasonDungeonRewardCoordinator(persistence, payment, trophies) { clock++ }

        coordinator.deliver(catalog, authorization, playerId, 1.0, now + 5) shouldBe
            SeasonDungeonRewardOutcome.ManualReview(
                SeasonDungeonRewardEngine.rewardId(catalog.revisionDigest(), authorization.runId, "mines_recon", playerId),
            )
        persistence.journal.values.single().status shouldBe SeasonDungeonRewardJournalStatus.PAYMENT_STARTED
        coordinator.recover(catalog).statusCounts.getValue(SeasonDungeonRewardJournalStatus.MANUAL_REVIEW.label) shouldBe 1L
        payment.depositCalls shouldBe 1
        trophies.deliveryCalls shouldBe 0
    }

    "crash after trophy mutation never repeats the item delivery" {
        val (state, authorization) = authorizedState()
        val persistence =
            FakeDungeonRewardPersistence(
                state,
                failJournalStatusOnce = SeasonDungeonRewardJournalStatus.TROPHY_DELIVERED,
            )
        val payment = FakeDungeonRewardPayment()
        val trophies = FakeDungeonRewardTrophies(itemKey)
        var clock = now + 50
        val coordinator = SeasonDungeonRewardCoordinator(persistence, payment, trophies) { clock++ }

        (coordinator.deliver(catalog, authorization, playerId, 1.0, now + 5) is
            SeasonDungeonRewardOutcome.ManualReview) shouldBe true
        persistence.journal.values.single().status shouldBe SeasonDungeonRewardJournalStatus.TROPHY_DELIVERY_STARTED
        coordinator.recover(catalog).statusCounts.getValue(SeasonDungeonRewardJournalStatus.MANUAL_REVIEW.label) shouldBe 1L
        payment.depositCalls shouldBe 1
        trophies.deliveryCalls shouldBe 1
    }

    "proven trophy delivery survives a failed state save without another side effect" {
        val (state, authorization) = authorizedState()
        val persistence = FakeDungeonRewardPersistence(state, failStateOnce = true)
        val payment = FakeDungeonRewardPayment()
        val trophies = FakeDungeonRewardTrophies(itemKey)
        var clock = now + 60
        val coordinator = SeasonDungeonRewardCoordinator(persistence, payment, trophies) { clock++ }

        (coordinator.deliver(catalog, authorization, playerId, 1.0, now + 5) is
            SeasonDungeonRewardOutcome.ManualReview) shouldBe true
        persistence.journal.values.single().status shouldBe SeasonDungeonRewardJournalStatus.TROPHY_DELIVERED
        coordinator.recover(catalog).statusCounts.getValue(SeasonDungeonRewardJournalStatus.STATE_COMMITTED.label) shouldBe 1L
        payment.depositCalls shouldBe 1
        trophies.deliveryCalls shouldBe 1
    }
})

private class FakeDungeonRewardPersistence(
    var current: SeasonRuntimeState,
    private val failJournalStatusOnce: SeasonDungeonRewardJournalStatus? = null,
    private val failStateOnce: Boolean = false,
) : SeasonDungeonRewardPersistence {
    val journal = linkedMapOf<String, SeasonDungeonRewardJournalRecord>()
    private var journalFailureConsumed = false
    private var stateFailureConsumed = false

    override fun state(catalog: ObserveSeasonCatalog): SeasonRuntimeState = current

    override fun journalRecords(): List<SeasonDungeonRewardJournalRecord> = journal.values.toList()

    override suspend fun persistState(state: SeasonRuntimeState) {
        if (failStateOnce && !stateFailureConsumed) {
            stateFailureConsumed = true
            throw IllegalStateException("injected state persistence failure")
        }
        current = state
    }

    override suspend fun persistJournal(record: SeasonDungeonRewardJournalRecord) {
        if (record.status == failJournalStatusOnce && !journalFailureConsumed) {
            journalFailureConsumed = true
            throw IllegalStateException("injected journal persistence failure")
        }
        journal[record.rewardId] = record
    }
}

private class FakeDungeonRewardPayment(
    private val balance: Long = 100_000L,
    private val evidence: ContractPaymentEvidence? = null,
) : ContractPaymentGateway {
    var depositCalls = 0

    override suspend fun balanceMinor(playerId: String): Long = balance

    override suspend fun deposit(playerId: String, amountMinor: Long, reason: String): ContractPaymentEvidence {
        depositCalls += 1
        return evidence ?: ContractPaymentEvidence(providerAccepted = true, balanceAfterMinor = balance + amountMinor)
    }
}

private class FakeDungeonRewardTrophies(private val itemKey: String) : SeasonDungeonTrophyDeliveryGateway {
    var deliveryCalls = 0

    override suspend fun createPayload(playerId: String, itemKey: String): EscrowedItemPayload =
        EscrowedItemPayload.capture(this.itemKey, 1, byteArrayOf(3, 2, 1))

    override suspend fun prepareDelivery(
        playerId: String,
        payload: EscrowedItemPayload,
    ): PreparedSeasonDungeonTrophyDelivery =
        object : PreparedSeasonDungeonTrophyDelivery {
            override suspend fun deliverExact(): ContractInventoryMutation {
                deliveryCalls += 1
                return ContractInventoryMutation.Confirmed
            }
        }
}

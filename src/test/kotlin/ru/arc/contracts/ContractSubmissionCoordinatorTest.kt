package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class ContractSubmissionCoordinatorTest : StringSpec({
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

    "food orders reject generic inventory submission before escrow or payment" {
        runTest {
            val food = definition.copy(group = "food_orders", itemKey = "arc:any_raw_fish")
            val events = mutableListOf<String>()
            val persistence = FakePersistence(food, events)
            val inventory = object : ContractInventoryGateway {
                override suspend fun prepare(playerId: String, itemKey: String, quantity: Int): PreparedContractInventory? =
                    error("A food order must never scan unoffered inventory")
            }
            val payment = FakePayment(0L, ContractPaymentEvidence(true, 2_000L), events)
            val coordinator = ContractSubmissionCoordinator(persistence, inventory, payment, tickingClock())
            for (slots in listOf(null, emptySet<Int>())) {
                coordinator.submit(food, "unoffered", "player-1", 8, inventorySlots = slots) shouldBe
                    ContractSubmissionOutcome.Rejected(SubmissionRejection.INVENTORY_UNAVAILABLE)
            }
            persistence.journals shouldBe emptyMap()
            events shouldBe emptyList()
            payment.depositCalls shouldBe 0
        }
    }

    "explicit staged food is journalled before removal and paid once without scanning native inventory" {
        runTest {
            val food = definition.copy(group = "food_orders", itemKey = "arc:any_raw_fish")
            val events = mutableListOf<String>()
            val persistence = FakePersistence(food, events)
            val inventory = object : ContractInventoryGateway {
                override suspend fun prepare(playerId: String, itemKey: String, quantity: Int): PreparedContractInventory? =
                    error("Explicit desk input must not scan native inventory")
            }
            val payment = FakePayment(0L, ContractPaymentEvidence(true, 2_000L), events)
            val offered = FakePreparedInventory(food.itemKey, 8, events)
            val result = ContractSubmissionCoordinator(persistence, inventory, payment, tickingClock())
                .submit(food, "staged", "player-1", 8, offeredInventory = offered)
            (result is ContractSubmissionOutcome.Committed) shouldBe true
            payment.depositCalls shouldBe 1
            (events.indexOf("journal:item_removal_started") < events.indexOf("inventory:remove")) shouldBe true
            (events.indexOf("journal:items_escrowed") < events.indexOf("payment:deposit")) shouldBe true
            persistence.state.acceptedQuantity shouldBe 8L
        }
    }

    "rejects stale or changed GUI quotes before touching inventory or payment" {
        runTest {
            val longWindow = definition.copy(windowEndsAt = 100_000L)
            val initial = ContractSubmissionQuote(longWindow.id, longWindow.windowStartsAt, "player-1", 8, 2_000L, 0L, 1_500L)
            val cases = listOf(
                initial to 31_501L,
                initial.copy(payoutMinor = 2_001L) to 2_000L,
                initial.copy(playerId = "another-player") to 2_000L,
                initial.copy(expectedRevision = 1L) to 2_000L,
                initial.copy(quantity = 7) to 2_000L,
            )
            for ((quote, now) in cases) {
                val events = mutableListOf<String>()
                val persistence = FakePersistence(longWindow, events)
                val inventory = object : ContractInventoryGateway {
                    override suspend fun prepare(playerId: String, itemKey: String, quantity: Int): PreparedContractInventory? =
                        error("Invalid quote must not inspect or mutate inventory")
                }
                val payment = FakePayment(0L, ContractPaymentEvidence(true, 2_000L), events)
                ContractSubmissionCoordinator(persistence, inventory, payment) { now }
                    .submit(longWindow, "stale", "player-1", 8, quote = quote) shouldBe
                    ContractSubmissionOutcome.Rejected(SubmissionRejection.STALE_STATE)
                events shouldBe emptyList()
                persistence.journals shouldBe emptyMap()
                payment.depositCalls shouldBe 0
            }
        }
    }

    "quote expiry during journal persistence cannot remove items or pay" {
        runTest {
            for ((windowEnd, delayedNow) in listOf(100_000L to 31_501L, 20_000L to 20_000L)) {
                val order = definition.copy(windowEndsAt = windowEnd)
                val events = mutableListOf<String>()
                val storage = FakePersistence(order, events)
                var now = 1_500L
                val persistence = object : ContractSubmissionPersistence by storage {
                    override suspend fun persistJournal(record: ContractSubmissionJournalRecord) {
                        storage.persistJournal(record)
                        if (record.status == ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED) now = delayedNow
                    }
                }
                val prepared = FakePreparedInventory(order.itemKey, 8, events)
                val payment = FakePayment(0L, ContractPaymentEvidence(true, 2_000L), events)
                val quote = ContractSubmissionQuote(order.id, order.windowStartsAt, "player-1", 8, 2_000L, 0L, now)
                val outcome = ContractSubmissionCoordinator(persistence, FakeInventory(prepared), payment) { now }
                    .submit(order, "delayed", "player-1", 8, quote = quote)

                outcome shouldBe ContractSubmissionOutcome.Rejected(SubmissionRejection.STALE_STATE)
                payment.depositCalls shouldBe 0
                ("inventory:remove" in events) shouldBe false
                prepared.restoreCalls shouldBe 0
                storage.state.acceptedQuantity shouldBe 0L
                storage.journals.getValue("delayed").status shouldBe ContractSubmissionJournalStatus.CANCELLED
                storage.journals.getValue("delayed").quotaReservation() shouldBe null
            }
        }
    }

    "network envelope rejects payout before creating escrow" {
        runTest {
            val events = mutableListOf<String>()
            val persistence = FakePersistence(definition, events)
            val payment = FakePayment(0L, ContractPaymentEvidence(true, 2_000L), events)
            val inventory = object : ContractInventoryGateway {
                override suspend fun prepare(playerId: String, itemKey: String, quantity: Int): PreparedContractInventory? =
                    error("Exhausted network budget must not touch inventory")
            }
            ContractSubmissionCoordinator(persistence, inventory, payment, tickingClock())
                .submit(definition, "no-budget", "player-1", 8, availableNetworkBudgetMinor = 1_999L) shouldBe
                ContractSubmissionOutcome.Rejected(SubmissionRejection.BUDGET_EXHAUSTED)
            events shouldBe emptyList()
            persistence.journals shouldBe emptyMap()
        }
    }

    "persists every intent before removing items and paying exactly once" {
        runTest {
            val events = mutableListOf<String>()
            val persistence = FakePersistence(definition, events)
            val preparedInventory = FakePreparedInventory(definition.itemKey, 8, events)
            val payment =
                FakePayment(
                    balance = 10_000L,
                    evidence = ContractPaymentEvidence(true, 12_000L),
                    events = events,
                )
            val coordinator =
                ContractSubmissionCoordinator(
                    persistence,
                    FakeInventory(preparedInventory),
                    payment,
                    tickingClock(),
                )

            val outcome = coordinator.submit(definition, "submission-1", "player-1", 8)

            (outcome as ContractSubmissionOutcome.Committed).receipt.payoutMinor shouldBe 2_000L
            payment.depositCalls shouldBe 1
            persistence.state.acceptedQuantity shouldBe 8L
            persistence.journals.getValue("submission-1").status shouldBe
                ContractSubmissionJournalStatus.CONTRACT_COMMITTED
            events.shouldContainExactly(
                "journal:prepared",
                "journal:item_removal_started",
                "inventory:remove",
                "journal:items_escrowed",
                "payment:balance",
                "journal:payment_started",
                "payment:deposit",
                "journal:paid",
                "contract:1",
                "journal:contract_committed",
            )
        }
    }

    "refunds exact escrow after an explicit unchanged-balance provider failure" {
        runTest {
            val events = mutableListOf<String>()
            val persistence = FakePersistence(definition, events)
            val preparedInventory = FakePreparedInventory(definition.itemKey, 8, events)
            val payment =
                FakePayment(
                    balance = 10_000L,
                    evidence = ContractPaymentEvidence(false, 10_000L, "Maximum balance"),
                    events = events,
                )
            val coordinator =
                ContractSubmissionCoordinator(
                    persistence,
                    FakeInventory(preparedInventory),
                    payment,
                    tickingClock(),
                )

            val outcome = coordinator.submit(definition, "submission-2", "player-1", 8)

            outcome shouldBe ContractSubmissionOutcome.Refunded("submission-2", "provider_rejected")
            persistence.state shouldBe ResourceContractState.empty(definition)
            persistence.journals.getValue("submission-2").status shouldBe ContractSubmissionJournalStatus.REFUNDED
            preparedInventory.restoreCalls shouldBe 1
            events.shouldContainExactly(
                "journal:prepared",
                "journal:item_removal_started",
                "inventory:remove",
                "journal:items_escrowed",
                "payment:balance",
                "journal:payment_started",
                "payment:deposit",
                "journal:payment_failed",
                "journal:refund_started",
                "inventory:restore",
                "journal:refunded",
            )
        }
    }

    "halts an ambiguous provider outcome without retrying payout or releasing quota" {
        runTest {
            val events = mutableListOf<String>()
            val persistence = FakePersistence(definition, events)
            val preparedInventory = FakePreparedInventory(definition.itemKey, 8, events)
            val payment =
                FakePayment(
                    balance = 10_000L,
                    evidence = ContractPaymentEvidence(null, 10_500L),
                    events = events,
                )
            val coordinator =
                ContractSubmissionCoordinator(
                    persistence,
                    FakeInventory(preparedInventory),
                    payment,
                    tickingClock(),
                )

            coordinator.submit(definition, "submission-3", "player-1", 8) shouldBe
                ContractSubmissionOutcome.ManualReview("submission-3")

            payment.depositCalls shouldBe 1
            preparedInventory.restoreCalls shouldBe 0
            persistence.state shouldBe ResourceContractState.empty(definition)
            val review = persistence.journals.getValue("submission-3")
            review.status shouldBe ContractSubmissionJournalStatus.MANUAL_REVIEW
            review.quotaReservation() shouldBe ContractQuotaReservation("submission-3", "player-1", 8L, 2_000L)
        }
    }

    "reports unavailable when an ambiguous manual-review journal cannot be persisted" {
        runTest {
            data class Case(
                val id: String,
                val persistence: FakePersistence,
                val inventory: FakePreparedInventory,
                val payment: FakePayment,
                val expectedPreviousStatus: ContractSubmissionJournalStatus,
            )

            val itemCase = run {
                val events = mutableListOf<String>()
                val persistence = FakePersistence(
                    definition,
                    events,
                    failPersistJournalStatuses = setOf(ContractSubmissionJournalStatus.MANUAL_REVIEW),
                )
                val inventory = FakePreparedInventory(definition.itemKey, 8, events, removeResult = ContractInventoryMutation.Ambiguous)
                Case("persist-fail-remove", persistence, inventory, FakePayment(10_000L, ContractPaymentEvidence(true, 12_000L), events), ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED)
            }
            val paymentCase = run {
                val events = mutableListOf<String>()
                val persistence = FakePersistence(
                    definition,
                    events,
                    failPersistJournalStatuses = setOf(ContractSubmissionJournalStatus.MANUAL_REVIEW),
                )
                val inventory = FakePreparedInventory(definition.itemKey, 8, events)
                Case("persist-fail-payment", persistence, inventory, FakePayment(10_000L, ContractPaymentEvidence(null, 10_500L), events), ContractSubmissionJournalStatus.PAYMENT_STARTED)
            }
            val refundCase = run {
                val events = mutableListOf<String>()
                val persistence = FakePersistence(
                    definition,
                    events,
                    failPersistJournalStatuses = setOf(ContractSubmissionJournalStatus.MANUAL_REVIEW),
                )
                val inventory = FakePreparedInventory(definition.itemKey, 8, events, restoreResult = ContractInventoryMutation.Ambiguous)
                Case("persist-fail-refund", persistence, inventory, FakePayment(null, ContractPaymentEvidence(true, 12_000L), events), ContractSubmissionJournalStatus.REFUND_STARTED)
            }

            listOf(itemCase, paymentCase, refundCase).forEach { case ->
                val outcome =
                    ContractSubmissionCoordinator(case.persistence, FakeInventory(case.inventory), case.payment, tickingClock())
                        .submit(definition, case.id, "player-1", 8)
                outcome shouldBe ContractSubmissionOutcome.Unavailable(case.id)
                case.persistence.journals.getValue(case.id).status shouldBe case.expectedPreviousStatus
            }
        }
    }

    "cancels a durable intent when inventory prevalidation proves no slot changed" {
        runTest {
            val events = mutableListOf<String>()
            val persistence = FakePersistence(definition, events)
            val preparedInventory =
                FakePreparedInventory(
                    definition.itemKey,
                    8,
                    events,
                    removeResult = ContractInventoryMutation.NotPerformed("slot_changed"),
                )
            val payment = FakePayment(10_000L, ContractPaymentEvidence(true, 12_000L), events)
            val coordinator =
                ContractSubmissionCoordinator(
                    persistence,
                    FakeInventory(preparedInventory),
                    payment,
                    tickingClock(),
                )

            coordinator.submit(definition, "submission-4", "player-1", 8) shouldBe
                ContractSubmissionOutcome.Cancelled("submission-4", "inventory_changed_before_remove")

            payment.depositCalls shouldBe 0
            persistence.journals.getValue("submission-4").quotaReservation() shouldBe null
        }
    }

    "refunds without starting a payout when the provider balance is unavailable" {
        runTest {
            val events = mutableListOf<String>()
            val persistence = FakePersistence(definition, events)
            val preparedInventory = FakePreparedInventory(definition.itemKey, 8, events)
            val payment = FakePayment(null, ContractPaymentEvidence(true, 12_000L), events)
            val coordinator =
                ContractSubmissionCoordinator(
                    persistence,
                    FakeInventory(preparedInventory),
                    payment,
                    tickingClock(),
                )

            coordinator.submit(definition, "submission-5", "player-1", 8) shouldBe
                ContractSubmissionOutcome.Refunded("submission-5", "provider_balance_unavailable")

            payment.depositCalls shouldBe 0
            persistence.journals.getValue("submission-5").paymentStartedAt shouldBe null
            persistence.journals.getValue("submission-5").status shouldBe ContractSubmissionJournalStatus.REFUNDED
        }
    }

    "recovers a crash after proven payout by idempotently committing state" {
        val plan =
            ResourceContractEngine.plan(
                definition,
                ResourceContractState.empty(definition),
                "paid-crash",
                "player-1",
                8,
                1_500L,
            ) as ContractSubmissionPlan.Accepted
        val prepared =
            ContractSubmissionJournalEngine.prepare(
                definition,
                plan,
                listOf(EscrowedItemPayload.capture(definition.itemKey, 8, byteArrayOf(1, 2, 3))),
                1_500L,
            )
        val escrowed =
            ContractSubmissionJournalEngine.confirmItemsEscrowed(
                ContractSubmissionJournalEngine.beginItemRemoval(prepared, 1_501L),
                1_502L,
            )
        val paid =
            ContractSubmissionJournalEngine.confirmPaid(
                ContractSubmissionJournalEngine.beginPayment(escrowed, 10_000L, 1_503L),
                12_000L,
                null,
                1_504L,
            )

        val first =
            ContractSubmissionRecoveryEngine.recoverPaid(
                definition,
                ResourceContractState.empty(definition),
                paid,
                2_000L,
            )
        first.commit.changed shouldBe true
        first.commit.state.acceptedQuantity shouldBe 8L
        first.journal.status shouldBe ContractSubmissionJournalStatus.CONTRACT_COMMITTED

        val replay = ContractSubmissionRecoveryEngine.recoverPaid(definition, first.commit.state, paid, 2_001L)
        replay.commit.changed shouldBe false
        replay.commit.state shouldBe first.commit.state
        replay.journal.status shouldBe ContractSubmissionJournalStatus.CONTRACT_COMMITTED
    }
})

private class FakePersistence(
    definition: ResourceContractDefinition,
    private val events: MutableList<String>,
    private val failPersistJournalStatuses: Set<ContractSubmissionJournalStatus> = emptySet(),
) : ContractSubmissionPersistence {
    var state = ResourceContractState.empty(definition)
    val journals = linkedMapOf<String, ContractSubmissionJournalRecord>()

    override fun contractState(definition: ResourceContractDefinition): ResourceContractState = state

    override fun journalRecords(): List<ContractSubmissionJournalRecord> = journals.values.toList()

    override suspend fun persistJournal(record: ContractSubmissionJournalRecord) {
        if (record.status in failPersistJournalStatuses) error("Injected journal persistence failure")
        events += "journal:${record.status.label}"
        journals[record.submissionId] = record
    }

    override suspend fun persistContract(
        definition: ResourceContractDefinition,
        state: ResourceContractState,
    ) {
        state.validatedAgainst(definition)
        this.state = state
        events += "contract:${state.revision}"
    }
}

private class FakeInventory(
    private val prepared: PreparedContractInventory?,
) : ContractInventoryGateway {
    override suspend fun prepare(
        playerId: String,
        itemKey: String,
        quantity: Int,
    ): PreparedContractInventory? = prepared
}

private class FakePreparedInventory(
    itemKey: String,
    quantity: Int,
    private val events: MutableList<String>,
    private val removeResult: ContractInventoryMutation = ContractInventoryMutation.Confirmed,
    private val restoreResult: ContractInventoryMutation = ContractInventoryMutation.Confirmed,
) : PreparedContractInventory {
    override val payloads =
        listOf(
            EscrowedItemPayload.capture(
                itemKey,
                quantity,
                byteArrayOf(10, 0, 3, 1, 2, 3, quantity.toByte()),
            ),
        )
    var restoreCalls = 0

    override suspend fun removeExact(canRemove: () -> Boolean): ContractInventoryMutation {
        if (!canRemove()) return ContractInventoryMutation.NotPerformed("submission_expired")
        events += "inventory:remove"
        return removeResult
    }

    override suspend fun restoreExact(): ContractInventoryMutation {
        restoreCalls += 1
        events += "inventory:restore"
        return restoreResult
    }
}

private class FakePayment(
    private val balance: Long?,
    private val evidence: ContractPaymentEvidence,
    private val events: MutableList<String>,
) : ContractPaymentGateway {
    var depositCalls = 0

    override suspend fun balanceMinor(playerId: String): Long? {
        events += "payment:balance"
        return balance
    }

    override suspend fun deposit(
        playerId: String,
        amountMinor: Long,
        reason: String,
    ): ContractPaymentEvidence {
        depositCalls += 1
        events += "payment:deposit"
        return evidence
    }
}

private fun tickingClock(): () -> Long {
    var now = 1_500L
    return { now++ }
}

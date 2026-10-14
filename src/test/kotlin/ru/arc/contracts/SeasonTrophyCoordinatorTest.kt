package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SeasonTrophyCoordinatorTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val stageId = catalog.completionStage
    val itemKey = catalog.dungeonContracts.getValue("mines_recon").plannedBoundReward
    val now = catalog.startsAt + 10_000L

    fun openState(): SeasonRuntimeState {
        var state = SeasonRuntimeState.empty(catalog)
        catalog.projectStages.values.filter { it.id != stageId }.forEach { definition ->
            var project = state.project
            if (definition.cashContributionMinor > 0L) {
                project = SeasonProjectEngine.contribute(
                    catalog,
                    project,
                    definition.id,
                    SeasonProjectContribution.Cash(definition.cashContributionMinor),
                ).state
            }
            definition.requiredResources.forEach { (resource, quantity) ->
                project = SeasonProjectEngine.contribute(
                    catalog,
                    project,
                    definition.id,
                    SeasonProjectContribution.Resource(resource, quantity),
                ).state
            }
            state = state.copy(project = project)
        }
        return state.copy(
            projectContributors = mapOf(playerId to SeasonContributorProgress(state.project.stages)),
        ).validatedAgainst(catalog)
    }

    "submission removes exact trophy once then commits project state" {
        val persistence = FakeTrophyPersistence(openState())
        val inventory = FakeTrophyInventory(itemKey)
        var clock = now
        val coordinator = SeasonTrophyContributionCoordinator(persistence, inventory) { clock++ }

        val outcome = coordinator.submit(
            catalog,
            "trophy-coordinator-1",
            stageId,
            itemKey,
            playerId,
            1,
        ) as SeasonTrophyContributionOutcome.Committed
        inventory.removeCalls shouldBe 1
        persistence.current.recentTrophyReceipts.getValue("trophy-coordinator-1") shouldBe outcome.receipt
        persistence.journal.getValue("trophy-coordinator-1").status shouldBe SeasonTrophyJournalStatus.STATE_COMMITTED

        coordinator.submit(
            catalog,
            "trophy-coordinator-1",
            stageId,
            itemKey,
            playerId,
            1,
        ) shouldBe SeasonTrophyContributionOutcome.Duplicate(outcome.receipt)
        inventory.removeCalls shouldBe 1
    }

    "ambiguous removal moves to manual review and never retries" {
        val persistence = FakeTrophyPersistence(openState())
        val inventory = FakeTrophyInventory(itemKey, ContractInventoryMutation.Ambiguous)
        var clock = now
        val coordinator = SeasonTrophyContributionCoordinator(persistence, inventory) { clock++ }

        coordinator.submit(
            catalog,
            "trophy-coordinator-ambiguous",
            stageId,
            itemKey,
            playerId,
            1,
        ) shouldBe SeasonTrophyContributionOutcome.ManualReview("trophy-coordinator-ambiguous")
        inventory.removeCalls shouldBe 1
        persistence.journal.getValue("trophy-coordinator-ambiguous").status shouldBe
            SeasonTrophyJournalStatus.MANUAL_REVIEW

        coordinator.submit(
            catalog,
            "trophy-coordinator-retry",
            stageId,
            itemKey,
            playerId,
            1,
        ) shouldBe SeasonTrophyContributionOutcome.Unavailable("trophy-coordinator-retry")
        inventory.removeCalls shouldBe 1
    }

    "restart commits proven removed items without another inventory mutation" {
        val persistence = FakeTrophyPersistence(openState())
        val plan =
            SeasonTrophyContributionEngine.plan(
                catalog,
                persistence.current,
                "trophy-coordinator-recover",
                stageId,
                itemKey,
                playerId,
                1,
                now,
            ) as SeasonTrophyContributionPlan.Accepted
        val payload = EscrowedItemPayload.capture(itemKey, 1, byteArrayOf(1, 2, 3))
        persistence.journal[plan.contributionId] =
            SeasonTrophyJournalEngine.confirmItemsRemoved(
                SeasonTrophyJournalEngine.beginItemRemoval(
                    SeasonTrophyJournalEngine.prepare(catalog, plan, listOf(payload), now),
                    now + 1,
                ),
                now + 2,
            )
        val inventory = FakeTrophyInventory(itemKey)
        var clock = now + 3

        val summary = SeasonTrophyContributionCoordinator(persistence, inventory) { clock++ }.recover(catalog)
        summary.committedRemovedItems shouldBe 1
        inventory.removeCalls shouldBe 0
        persistence.current.recentTrophyReceipts.containsKey(plan.contributionId) shouldBe true
        persistence.journal.getValue(plan.contributionId).status shouldBe SeasonTrophyJournalStatus.STATE_COMMITTED
    }
})

private class FakeTrophyPersistence(var current: SeasonRuntimeState) : SeasonTrophyPersistence {
    val journal = linkedMapOf<String, SeasonTrophyJournalRecord>()

    override fun state(catalog: ObserveSeasonCatalog): SeasonRuntimeState = current.validatedAgainst(catalog)

    override fun journalRecords(): List<SeasonTrophyJournalRecord> = journal.values.toList()

    override suspend fun persistState(state: SeasonRuntimeState) {
        current = state
    }

    override suspend fun persistJournal(record: SeasonTrophyJournalRecord) {
        journal[record.contributionId] = record
    }
}

private class FakeTrophyInventory(
    private val itemKey: String,
    private val mutation: ContractInventoryMutation = ContractInventoryMutation.Confirmed,
) : ContractInventoryGateway {
    var removeCalls = 0

    override suspend fun prepare(playerId: String, itemKey: String, quantity: Int): PreparedContractInventory? {
        if (itemKey != this.itemKey || quantity != 1) return null
        return object : PreparedContractInventory {
            override val payloads = listOf(EscrowedItemPayload.capture(itemKey, 1, byteArrayOf(1, 2, 3)))

            override suspend fun removeExact(canRemove: () -> Boolean): ContractInventoryMutation {
                if (!canRemove()) return ContractInventoryMutation.NotPerformed("submission_expired")
                removeCalls += 1
                return mutation
            }

            override suspend fun restoreExact(): ContractInventoryMutation = ContractInventoryMutation.NotPerformed("unsupported")
        }
    }
}

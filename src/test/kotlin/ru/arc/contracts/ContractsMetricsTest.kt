package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe

class ContractsMetricsTest : StringSpec({
    "exports bounded contract policy and progress without player labels" {
        val points =
            ContractsMetrics.points(
                enabled = true,
                available = true,
                mode = ContractsMode.OBSERVE,
                localLeader = true,
                submissionRuntimeReady = false,
                submissionsEnabled = false,
                seasonMutationRuntimeReady = false,
                serverWeeklyBudgetMinor = 25_000_000L,
                seasonCatalog = testSeasonCatalog(),
                seasonState = null,
                seasonMoneyJournal = SeasonMoneyJournalAudit.unavailable(),
                seasonTrophyJournal = SeasonTrophyJournalSummary.unavailable(),
                seasonDungeonRewardJournal = SeasonDungeonRewardJournalSummary.unavailable(),
                dungeonObservation =
                    DungeonContractObservationSnapshot(
                        catalogAvailable = true,
                        activeRunsByContract = mapOf("mines_recon" to 1),
                        statsByContract =
                            mapOf(
                                "mines_recon" to
                                    DungeonContractObservationStats(
                                        startedRuns = 2,
                                        nativeCompletedRuns = 1,
                                        completionPlayers = 2,
                                        nativeCompletionDurationSeconds = 600,
                                        playerOutcomes = mapOf(DungeonCompletionPlayerOutcome.START_TO_FINISH to 2),
                                    ),
                            ),
                    ),
                journal =
                    ContractSubmissionJournalSummary(
                        available = true,
                        totalRecords = 2,
                        stateCounts = ContractSubmissionJournalStatus.entries.associate { it.label to if (it == ContractSubmissionJournalStatus.MANUAL_REVIEW) 1 else 0 },
                        heldItemQuantity = 8L,
                        pendingPayoutMinor = 2_000L,
                        ambiguousPayoutMinor = 2_000L,
                        manualReviewCount = 1,
                        oldestAttentionAgeSeconds = 60L,
                        capacityRemaining = 4_094,
                        manualReviewSubmissionIds = listOf("submission-secret"),
                    ),
                views =
                    listOf(
                        ResourceContractView(
                            id = "road_stone",
                            displayName = "Камень для тракта",
                            itemKey = "minecraft:stone",
                            funding = "server_envelope",
                            status = "open",
                            windowStartsAt = 1_000L,
                            windowEndsAt = 2_000L,
                            payoutMinorPerUnit = 250L,
                            budgetMinor = 50_000L,
                            spentMinor = 12_500L,
                            reservedMinor = 1_250L,
                            targetQuantity = 200L,
                            acceptedQuantity = 50L,
                            reservedQuantity = 5L,
                            remainingQuantity = 145L,
                            contributors = 3,
                        ),
                    ),
            )

        points.first { it.name == "arc_contracts_submission_runtime_ready" }.value shouldBeExactly 0.0
        points.first { it.name == "arc_contracts_submissions_enabled" }.value shouldBeExactly 0.0
        points.first { it.name == "arc_season_mutation_runtime_ready" }.value shouldBeExactly 0.0
        points.first { it.name == "arc_season_catalog_available" }.value shouldBeExactly 1.0
        points.first { it.name == "arc_season_trophy_journal_available" }.value shouldBeExactly 0.0
        points.first { it.name == "arc_season_dungeon_reward_journal_available" }.value shouldBeExactly 0.0
        points.first {
            it.name == "arc_season_project_resource_quantity" &&
                it.tags["contract"] == "road_stone" && it.tags["component"] == "accepted"
        }.value shouldBeExactly 50.0
        points.first {
            it.name == "arc_season_dungeon_money_currency" &&
                it.tags["contract"] == "mines_recon" && it.tags["component"] == "entry_burn"
        }.value shouldBeExactly 1_000.0
        points.first { it.name == "arc_contracts_available" }.value shouldBeExactly 1.0
        points.first { it.name == "arc_contracts_server_weekly_budget_currency" }.value shouldBeExactly 250_000.0
        points.first { it.name == "arc_contract_journal_manual_review" }.value shouldBeExactly 1.0
        points.first { it.name == "arc_contract_journal_capacity_remaining" }.value shouldBeExactly 4_094.0
        points.first {
            it.name == "arc_contract_journal_payout_currency" && it.tags["component"] == "ambiguous"
        }.value shouldBeExactly 20.0
        points.first {
            it.name == "arc_contract_quantity" && it.tags["component"] == "remaining"
        }.value shouldBeExactly 145.0
        points.first {
            it.name == "arc_contract_quantity" && it.tags["component"] == "reserved"
        }.value shouldBeExactly 5.0
        points.first {
            it.name == "arc_dungeon_contract_active_runs" && it.tags["contract"] == "mines_recon"
        }.value shouldBeExactly 1.0
        points.first {
            it.name == "arc_dungeon_contract_completion_players_total" &&
                it.tags["outcome"] == DungeonCompletionPlayerOutcome.START_TO_FINISH.label
        }.value shouldBeExactly 2.0
        points.first { it.name == "arc_dungeon_contract_completion_duration_seconds_total" }
            .value shouldBeExactly 600.0
        points.flatMap { it.tags.keys }.none { it.contains("player", ignoreCase = true) } shouldBe true
        points.flatMap { it.tags.values }.none { it.contains("player", ignoreCase = true) } shouldBe true
        points.flatMap { it.tags.values }.none { it.contains("submission", ignoreCase = true) } shouldBe true
        points.none { it.name.contains("alert", ignoreCase = true) } shouldBe true
    }
})

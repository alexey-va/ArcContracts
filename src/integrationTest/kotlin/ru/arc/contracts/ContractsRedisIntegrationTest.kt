package ru.arc.contracts

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.arc.paper.api.ArcRedisSettings
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.redis.PaperRedisServiceProvider
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.RedisOperations
import ru.arc.repository.CachedRepository
import ru.arc.repository.redisRepo
import ru.arc.testing.containers.RedisTestService
import java.util.UUID
import kotlin.time.Duration.Companion.hours

class ContractsRedisIntegrationTest : StringSpec({
    "contract state and journal quota survive Redis restart boundaries and reconciliation" {
        RedisTestService.start().use { redisServer ->
            MockBukkitTestRuntime.open().use { paper ->
                val host = paper.createSimplePlugin("ARC")
                val consumer = paper.createSimplePlugin("ArcContracts")
                PaperRedisServiceProvider(host).use { provider ->
                    provider.configurePrimary(
                        ArcRedisSettings(redisServer.endpoint.host, redisServer.endpoint.port, null, null),
                        "survival",
                    )
                    val client = PaperRedisClient.openPrimary(consumer).also { it.init() }
                    val namespace = "arccontracts:integration:${UUID.randomUUID()}"
                    val gson = Gson()
                    val definition = ResourceContractDefinition(
                        id = "integration_stone",
                        displayName = "Integration stone",
                        itemKey = "minecraft:stone",
                        funding = ContractFunding.SERVER_ENVELOPE,
                        windowStartsAt = 1_000L,
                        windowEndsAt = 20_000L,
                        payoutMinorPerUnit = 10L,
                        budgetMinor = 100L,
                        targetQuantity = 10L,
                        perPlayerQuantityCap = 10L,
                    )
                    val scopes = mutableListOf<CoroutineScope>()
                    fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob()).also(scopes::add)
                    try {
                        val initialState = ResourceContractState.empty(definition)
                        val firstPlan = ResourceContractEngine.plan(
                            definition, initialState, "integration-seed", "player-one", 2, 1_500L,
                        ) as ContractSubmissionPlan.Accepted
                        val committed = ResourceContractEngine.commit(definition, initialState, firstPlan, 1_501L).state
                        val reservedPlan = ResourceContractEngine.plan(
                            definition, committed, "integration-reservation", "player-two", 8, 1_600L,
                        ) as ContractSubmissionPlan.Accepted
                        val prepared = ContractSubmissionJournalEngine.prepare(
                            definition,
                            reservedPlan,
                            listOf(EscrowedItemPayload.capture(definition.itemKey, 8, byteArrayOf(1, 2, 3))),
                            1_601L,
                        )
                        val manualReview = ContractSubmissionJournalEngine.recoverInterrupted(
                            ContractSubmissionJournalEngine.beginItemRemoval(prepared, 1_602L),
                            1_603L,
                        )

                        val stateRepo = stateRepository(client, gson, newScope(), namespace)
                        val journalRepo = journalRepository(client, gson, newScope(), namespace)
                        try {
                            stateRepo.save(ResourceContractRecord(
                                ResourceContractRecord.stateId(definition.id, definition.windowStartsAt),
                                committed,
                                definition,
                            )).getOrThrow()
                            journalRepo.save(manualReview).getOrThrow()
                            stateRepo.saveDirty().getOrThrow()
                            journalRepo.saveDirty().getOrThrow()
                        } finally {
                            journalRepo.shutdown()
                            stateRepo.shutdown()
                        }

                        val reloadedStateRepo = stateRepository(client, gson, newScope(), namespace)
                        val reloadedJournalRepo = journalRepository(client, gson, newScope(), namespace)
                        try {
                            val durableState = reloadedStateRepo.allNow().single().state
                            val durableReview = reloadedJournalRepo.allNow().single()
                            durableState.acceptedQuantity shouldBe 2L
                            durableReview.status shouldBe ContractSubmissionJournalStatus.MANUAL_REVIEW
                            durableReview.quotaReservation()?.quantity shouldBe 8L
                            ResourceContractEngine.plan(
                                definition,
                                durableState,
                                "integration-blocked",
                                "player-three",
                                1,
                                1_604L,
                                listOfNotNull(durableReview.quotaReservation()),
                            ) shouldBe ContractSubmissionPlan.Rejected(SubmissionRejection.QUANTITY_EXHAUSTED)

                            val request = ContractSubmissionReconciliationRequest(
                                submissionId = durableReview.submissionId,
                                expectedRevision = durableReview.revision,
                                resolution = ContractSubmissionReconciliationResolution.NO_ITEMS_REMOVED,
                                operatorId = "integration.ops",
                                operatorEvidence = "Disposable fixture confirms no inventory removal occurred.",
                                idempotencyKey = "resolve-${durableReview.submissionId}",
                            )
                            val preview = ContractSubmissionReconciliationEngine.preview(durableReview, request)
                            val resolved = ContractSubmissionReconciliationEngine.apply(
                                durableReview, request, preview.reviewDigest, 1_605L,
                            )
                            reloadedJournalRepo.save(resolved).getOrThrow()
                            reloadedJournalRepo.saveDirty().getOrThrow()
                        } finally {
                            reloadedJournalRepo.shutdown()
                            reloadedStateRepo.shutdown()
                        }

                        val finalStateRepo = stateRepository(client, gson, newScope(), namespace)
                        val finalJournalRepo = journalRepository(client, gson, newScope(), namespace)
                        try {
                            val finalState = finalStateRepo.allNow().single().state
                            val resolved = finalJournalRepo.allNow().single()
                            resolved.status shouldBe ContractSubmissionJournalStatus.CANCELLED
                            resolved.reconciliation?.resolution shouldBe ContractSubmissionReconciliationResolution.NO_ITEMS_REMOVED
                            resolved.quotaReservation() shouldBe null
                            ResourceContractEngine.plan(
                                definition, finalState, "integration-released", "player-three", 8, 1_606L,
                            ).let { plan ->
                                (plan as ContractSubmissionPlan.Accepted).acceptedQuantity shouldBe 8L
                            }
                        } finally {
                            finalJournalRepo.shutdown()
                            finalStateRepo.shutdown()
                        }
                    } finally {
                        scopes.forEach { it.cancel() }
                        client.close()
                    }
                }
            }
        }
    }
})

private fun stateRepository(
    redis: RedisOperations,
    gson: Gson,
    scope: CoroutineScope,
    namespace: String,
): CachedRepository<ResourceContractRecord> = redisRepo<ResourceContractRecord>(
    redis = redis,
    gson = gson,
    id = "$namespace:state",
    storageKey = "$namespace:states",
    updateChannel = "$namespace:states:updates",
    scope = scope,
) {
    loadAllOnStart(true)
    enableCleanup(false)
    saveInterval(1.hours)
}

private fun journalRepository(
    redis: RedisOperations,
    gson: Gson,
    scope: CoroutineScope,
    namespace: String,
): CachedRepository<ContractSubmissionJournalRecord> = redisRepo<ContractSubmissionJournalRecord>(
    redis = redis,
    gson = gson,
    id = "$namespace:journal",
    storageKey = "$namespace:journals",
    updateChannel = "$namespace:journals:updates",
    scope = scope,
) {
    loadAllOnStart(true)
    enableCleanup(false)
    saveInterval(1.hours)
}

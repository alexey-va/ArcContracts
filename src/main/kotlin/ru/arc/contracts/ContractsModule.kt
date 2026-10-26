package ru.arc.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.contracts.api.ResourceContractCommittedEvent
import ru.arc.core.Tasks
import ru.arc.metrics.core.MetricPoint
import ru.arc.repository.CachedRepository
import ru.arc.redis.RedisOperations
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

object ContractsModule {
    fun init() {
        ContractsManager.init()
    }

    fun reload() {
        ContractsManager.reload()
    }

    fun shutdown() {
        ContractsManager.shutdown()
    }
}

data class ResourceContractView(
    val id: String,
    val displayName: String,
    val itemKey: String,
    val funding: String,
    val status: String,
    val windowStartsAt: Long,
    val windowEndsAt: Long,
    val payoutMinorPerUnit: Long,
    val budgetMinor: Long,
    val spentMinor: Long,
    val reservedMinor: Long,
    val targetQuantity: Long,
    val acceptedQuantity: Long,
    val reservedQuantity: Long,
    val remainingQuantity: Long,
    val contributors: Int,
    val group: String = ResourceContractDefinition.DEFAULT_GROUP,
    val basePayoutMinorPerUnit: Long = payoutMinorPerUnit,
    val minSubmissionQuantity: Int = 1,
    val minimumSubmissionPayoutMinor: Long = payoutMinorPerUnit,
)

data class ResourceContractPlayerView(
    val contract: ResourceContractView,
    val minSubmissionQuantity: Int,
    val maxSubmissionQuantity: Int,
    val perPlayerQuantityCap: Long,
    val playerAcceptedQuantity: Long,
    val playerReservedQuantity: Long,
    val playerRemainingQuantity: Long,
    val playerPayoutMinorPerUnit: Long,
    val capBasisPoints: Int,
    val payoutBasisPoints: Int,
    val pricingDefinition: ResourceContractDefinition? = null,
    val pricingSupply: Long = 0L,
    val pricingAt: Long = 0L,
)

sealed interface SeasonDungeonLaunchPreparationOutcome {
    data class Ready(val reservation: SeasonDungeonLaunchReservation) : SeasonDungeonLaunchPreparationOutcome

    data class Rejected(val code: String) : SeasonDungeonLaunchPreparationOutcome

    data object Unavailable : SeasonDungeonLaunchPreparationOutcome
}

enum class SeasonDungeonInstanceDecision {
    NOT_PROTECTED,
    AUTHORIZED,
    DENIED,
}

/**
 * Runtime owner for the bounded contract catalog and network-persisted state.
 * Mutating submissions remain disabled unless policy mode is explicitly
 * `enforce`; each active NPC board filters this catalog by a validated group.
 */
object ContractsManager {
    // RedisEconomy 4.5.12 exposes no idempotency key. Resource submissions use
    // durable-before-side-effect journaling, exact inventory escrow, bounded
    // terminal retention and authenticated manual reconciliation. Ambiguous
    // provider or inventory outcomes halt without retrying the valuable effect.
    // Season money has a separate authenticated, replay-safe reconciliation.
    // The bound-item contribution journal, transfer guards, EliteMobs
    // pre-start admission guard and durable money-plus-bound-trophy reward
    // delivery are implemented. Season mutations remain gated until production
    // crash injection, manual reward reconciliation and control-plane smoke
    // cover the final operational boundary.
    // Resource submissions completed their durable journal, exact-inventory,
    // provider-evidence, restart-recovery and authenticated reconciliation
    // gates. Season mutations remain independently disabled below.
    private const val SUBMISSION_RUNTIME_READY = true
    private const val SEASON_MUTATION_RUNTIME_READY = false

    private val configRef = AtomicReference<ContractsConfig>()
    @Volatile private var accepting = false
    @Volatile private var readViews = emptyList<RuntimeResourceContractView>()
    @Volatile private var readSummary = emptyMap<String, Any?>()
    @Volatile private var readJournal = ContractSubmissionJournalSummary.unavailable()
    private val refreshPending = java.util.concurrent.atomic.AtomicBoolean()

    fun enabled(): Boolean = configRef.get()?.enabled == true
    fun serverWeeklyBudgetMinor(): Long = configRef.get()?.serverWeeklyBudgetMinor ?: 0L
    fun suspendAdmission() { accepting = false }

    private var repo: CachedRepository<ResourceContractRecord>? = null
    private var selectionRuntime: ContractSelectionRuntime? = null
    private var discardedCleanupFailure: Throwable? = null
    private var scope: CoroutineScope? = null
    private var journalRepo: CachedRepository<ContractSubmissionJournalRecord>? = null
    private var journalScope: CoroutineScope? = null
    private var submissionScope: CoroutineScope? = null
    private var submissionCoordinator: ContractSubmissionCoordinator? = null
    private val submissionsInFlight = ConcurrentHashMap.newKeySet<UUID>()
    private var seasonScope: CoroutineScope? = null
    private var seasonRepositoryScope: CoroutineScope? = null
    private var seasonStateRepo: CachedRepository<SeasonRuntimeState>? = null
    private var seasonMoneyJournalRepo: CachedRepository<SeasonMoneyJournalRecord>? = null
    private var seasonMoneyCoordinator: SeasonMoneyCoordinator? = null
    private var seasonTrophyJournalRepo: CachedRepository<SeasonTrophyJournalRecord>? = null
    private var seasonTrophyCoordinator: SeasonTrophyContributionCoordinator? = null
    private var seasonDungeonRewardJournalRepo: CachedRepository<SeasonDungeonRewardJournalRecord>? = null
    private var seasonDungeonRewardCoordinator: SeasonDungeonRewardCoordinator? = null
    private var submissionMutex = Mutex()
    private val dungeonObserver = DungeonContractObserver()

    @JvmStatic
    @Synchronized
    fun init() {
        if (repo != null || journalRepo != null) return
        check(discardedCleanupFailure == null) { "Previous contract startup cleanup failed; terminal shutdown must report it before retry" }
        val loaded = ContractsConfig.fromSnapshot(ContractsRuntime.legacyDataPath).validated(SEASON_MUTATION_RUNTIME_READY)
        configRef.set(loaded)
        ContractOriginGate.configure(loaded)
        dungeonObserver.configure(loaded.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY))
        if (!loaded.enabled || loaded.mode == ContractsMode.DISABLED) {
            ContractsRuntime.info("Contracts disabled by policy")
            publishMetrics()
            return
        }
        val redis = ContractsRuntime.redis
        if (redis == null) {
            ContractsRuntime.info("Contracts unavailable because Redis is disabled")
            publishMetrics()
            return
        }
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val newRepo =
            try {
                ru.arc.repository.redisRepo<ResourceContractRecord>(
                    redis = redis,
                    gson = ContractsRuntime.gson,
                    id = "contracts",
                    storageKey = "arc.contracts.v1",
                    updateChannel = "arc.contracts.v1.update",
                    scope = newScope,
                ) {
                    loadAllOnStart(true)
                    enableCleanup(false)
                    saveInterval(1.seconds)
                }
            } catch (failure: Throwable) {
                newScope.cancel()
                throw failure
            }
        val newJournalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val newJournalRepo =
            try {
                ru.arc.repository.redisRepo<ContractSubmissionJournalRecord>(
                    redis = redis,
                    gson = ContractsRuntime.gson,
                    id = "contract-submission-journal",
                    storageKey = "arc.contract-submissions.v1",
                    updateChannel = "arc.contract-submissions.v1.update",
                    scope = newJournalScope,
                ) {
                    loadAllOnStart(true)
                    enableCleanup(false)
                    saveInterval(1.seconds)
                }
            } catch (failure: Throwable) {
                discardFailedStartup(failure,
                    { runBlocking { newRepo.shutdown() } },
                    { newScope.cancel() },
                    { newJournalScope.cancel() },
                )
                throw failure
            }
        try {
            ensureCurrentStates(loaded, newRepo)
            recoverInterruptedJournals(loaded, newRepo, newJournalRepo)
            val newSubmissionScope =
                if (SUBMISSION_RUNTIME_READY) CoroutineScope(Dispatchers.IO + SupervisorJob()) else null
            val newSubmissionCoordinator =
                if (SUBMISSION_RUNTIME_READY) {
                    ContractSubmissionCoordinator(
                        RedisContractSubmissionPersistence(newRepo, newJournalRepo),
                        PaperContractInventoryGateway(),
                        RedisEconomyContractPaymentGateway(),
                    )
                } else {
                    null
                }
            val seasonRuntime = createSeasonRuntime(loaded, newRepo, redis, ContractsRuntime.gson)
            val newSelection = ContractSelectionRuntime(
                redis = redis,
                gson = ContractsRuntime.gson,
                config = { requireNotNull(configRef.get()) },
                leader = { isLeader() },
                records = {
                    val missing = newRepo.allNow().filter { it.definitionSnapshot == null }.map { it.stateId }.toSet()
                    val snapshots = newJournalRepo.allNow().filter { it.definitionSnapshot != null &&
                        ResourceContractRecord.stateId(it.contractId, it.contractWindowStartsAt) in missing }
                        .groupBy { ResourceContractRecord.stateId(it.contractId, it.contractWindowStartsAt) }
                        .mapValues { (_, journals) -> ContractDynamicPricingUpgrade.legacyJournalSnapshot(
                            journals.map { requireNotNull(it.definitionSnapshot) }) }
                    newRepo.allNow().map { record ->
                        if (record.definitionSnapshot != null) record
                        else record.copy(definitionSnapshot = snapshots[record.stateId])
                    }
                },
                reservedStateIds = {
                    newJournalRepo.allNow().filter { it.quotaReservation() != null }
                        .map { ResourceContractRecord.stateId(it.contractId, it.contractWindowStartsAt) }.toSet()
                },
                enableDynamicPricing = { plan, candidates ->
                    submissionMutex.withLock {
                        val blocked = newJournalRepo.allNow().filterNot { it.isTerminal() }
                            .map { ResourceContractRecord.stateId(it.contractId, it.contractWindowStartsAt) }.toSet()
                        val upgrade = ContractDynamicPricingUpgrade.plan(plan, candidates, newRepo.allNow(), blocked)
                        upgrade.records.forEach(newRepo::markDirty)
                        if (upgrade.records.isNotEmpty()) newRepo.saveDirty().getOrThrow()
                        upgrade.plan
                    }
                },
            )
            selectionRuntime = newSelection
            newSelection.start()
            repo = newRepo
            scope = newScope
            journalRepo = newJournalRepo
            journalScope = newJournalScope
            submissionScope = newSubmissionScope
            submissionCoordinator = newSubmissionCoordinator
            seasonScope = seasonRuntime?.scope
            seasonRepositoryScope = seasonRuntime?.repositoryScope
            seasonStateRepo = seasonRuntime?.stateRepository
            seasonMoneyJournalRepo = seasonRuntime?.journalRepository
            seasonMoneyCoordinator = seasonRuntime?.coordinator
            seasonTrophyJournalRepo = seasonRuntime?.trophyJournalRepository
            seasonTrophyCoordinator = seasonRuntime?.trophyCoordinator
            seasonDungeonRewardJournalRepo = seasonRuntime?.dungeonRewardJournalRepository
            seasonDungeonRewardCoordinator = seasonRuntime?.dungeonRewardCoordinator
        } catch (failure: Throwable) {
            val discardedSelection = selectionRuntime.also { selectionRuntime = null }
            discardFailedStartup(failure,
                { discardedSelection?.close() },
                { runBlocking { newJournalRepo.shutdown() } },
                { runBlocking { newRepo.shutdown() } },
                { newScope.cancel() },
                { newJournalScope.cancel() },
            )
            throw failure
        }
        ContractsRuntime.info(
            "Contracts initialized in {} mode on {} with {} resource order(s)",
            loaded.mode.label,
            ContractsRuntime.serverName.ifBlank { "unknown" },
            loaded.resourceOrders().size,
        )
        accepting = true
        publishMetrics()
    }

    /** Failed startup has no published repository owner; carry cleanup errors into the terminal drain. */
    private fun discardFailedStartup(startupFailure: Throwable, vararg cleanup: () -> Unit) {
        cleanup.forEach { close ->
            try { close() } catch (failure: Throwable) {
                val previous = discardedCleanupFailure
                if (previous == null) discardedCleanupFailure = failure
                else if (previous !== failure) previous.addSuppressed(failure)
                if (startupFailure !== failure) startupFailure.addSuppressed(failure)
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun reload() {
        val previousCatalogDigest =
            runCatching {
                configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)?.revisionDigest()
            }.getOrNull()
        val loaded = ContractsConfig.fromSnapshot(ContractsRuntime.legacyDataPath).validated(SEASON_MUTATION_RUNTIME_READY)
        configRef.set(loaded)
        ContractOriginGate.configure(loaded)
        dungeonObserver.configure(loaded.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY))
        val currentRepo = repo
        if (currentRepo != null && (!loaded.enabled || loaded.mode == ContractsMode.DISABLED)) {
            shutdown()
            publishMetrics()
            return
        }
        if (currentRepo == null && loaded.enabled && loaded.mode != ContractsMode.DISABLED) {
            init()
            return
        }
        val nextCatalogDigest =
            loaded.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)?.revisionDigest()
        if (SEASON_MUTATION_RUNTIME_READY && currentRepo != null && previousCatalogDigest != nextCatalogDigest) {
            shutdown()
            init()
            return
        }
        if (currentRepo != null) {
            ensureCurrentStates(loaded, currentRepo)
            journalRepo?.let { current ->
                ContractSubmissionJournalAudit.summarize(current.allNow().onEach { it.validated() }, System.currentTimeMillis())
            }
        }
        accepting = currentRepo != null
        publishMetrics()
    }

    @JvmStatic
    @Synchronized
    fun shutdown() {
        accepting = false
        var failure = discardedCleanupFailure.also { discardedCleanupFailure = null }
        fun record(error: Throwable) {
            val previous = failure
            if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
        }
        fun attempt(close: () -> Unit) {
            try { close() } catch (error: Throwable) { record(error) }
        }
        attempt { ContractOriginGate.clear() }
        val currentSelection = selectionRuntime
        selectionRuntime = null
        attempt { currentSelection?.close() }
        val currentRepo = repo
        val currentScope = scope
        val currentJournalRepo = journalRepo
        val currentJournalScope = journalScope
        val currentSubmissionScope = submissionScope
        val currentSeasonScope = seasonScope
        val currentSeasonRepositoryScope = seasonRepositoryScope
        val currentSeasonStateRepo = seasonStateRepo
        val currentSeasonJournalRepo = seasonMoneyJournalRepo
        val currentSeasonTrophyJournalRepo = seasonTrophyJournalRepo
        val currentSeasonDungeonRewardJournalRepo = seasonDungeonRewardJournalRepo
        repo = null
        scope = null
        journalRepo = null
        journalScope = null
        submissionScope = null
        submissionCoordinator = null
        submissionsInFlight.clear()
        seasonScope = null
        seasonRepositoryScope = null
        seasonStateRepo = null
        seasonMoneyJournalRepo = null
        seasonMoneyCoordinator = null
        seasonTrophyJournalRepo = null
        seasonTrophyCoordinator = null
        seasonDungeonRewardJournalRepo = null
        seasonDungeonRewardCoordinator = null
        submissionMutex = Mutex()
        attempt { dungeonObserver.configure(null) }
        attempt { currentSubmissionScope?.cancel() }
        attempt { currentSeasonScope?.cancel() }
        attempt {
            runBlocking {
                for (repository in listOf(currentSeasonDungeonRewardJournalRepo, currentSeasonTrophyJournalRepo,
                    currentSeasonJournalRepo, currentSeasonStateRepo, currentJournalRepo, currentRepo)) {
                    try { repository?.shutdown() } catch (error: Throwable) { record(error) }
                }
            }
        }
        attempt { currentScope?.cancel() }
        attempt { currentJournalScope?.cancel() }
        attempt { currentSeasonRepositoryScope?.cancel() }
        failure?.let { throw it }
    }

    @JvmStatic
    fun isAvailable(): Boolean = ContractsRuntime.isAdmissionReady() && accepting && repo != null && journalRepo != null

    @JvmStatic
    fun mode(): ContractsMode = configRef.get()?.mode ?: ContractsMode.DISABLED

    @JvmStatic
    fun isLeader(): Boolean =
        configRef.get()?.leaderServer?.equals(ContractsRuntime.serverName, ignoreCase = true) == true

    @JvmStatic
    fun submissionsEnabled(): Boolean =
        SUBMISSION_RUNTIME_READY && isAvailable() && isLeader() && mode() == ContractsMode.ENFORCE

    @JvmStatic
    fun seasonMoneyEnabled(): Boolean =
        SEASON_MUTATION_RUNTIME_READY && seasonStateRepo != null && seasonMoneyJournalRepo != null &&
            seasonMoneyCoordinator != null && isLeader() && mode() == ContractsMode.ENFORCE

    @JvmStatic
    fun seasonTrophyEnabled(): Boolean =
        seasonMoneyEnabled() && seasonTrophyJournalRepo != null && seasonTrophyCoordinator != null

    @JvmStatic
    fun seasonDungeonRewardsEnabled(): Boolean =
        seasonMoneyEnabled() && seasonDungeonRewardJournalRepo != null && seasonDungeonRewardCoordinator != null

    @JvmStatic
    fun seasonDungeonProtectionEnabled(): Boolean =
        SEASON_MUTATION_RUNTIME_READY && seasonStateRepo != null && seasonMoneyCoordinator != null &&
            mode() == ContractsMode.ENFORCE

    @JvmStatic
    fun observeDungeonStarted(
        runId: String,
        world: String,
        participantIds: Set<String>,
        instanceWorld: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val recorded = dungeonObserver.started(runId, world, participantIds, now)
        if (recorded) {
            participantIds.forEach { rawId ->
                runCatching { java.util.UUID.fromString(rawId) }.getOrNull()?.let { id ->
                    ContractsRuntime.recordDungeonStarted(id, world, runId, participantIds.size, instanceWorld)
                }
            }
            instanceWorld?.let { consumeSeasonDungeonAdmissionsAtStart(it, now) }
            publishMetrics()
        }
        return recorded
    }

    @JvmStatic
    fun observeDungeonCompleted(
        runId: String,
        world: String,
        participantIds: Set<String>,
        instanceWorld: String? = null,
        now: Long = System.currentTimeMillis(),
    ): DungeonContractCompletionObservation? {
        val observation = dungeonObserver.completed(runId, world, participantIds, now)
        if (observation != null) {
            observation.playerOutcomes.forEach { (rawId, outcome) ->
                runCatching { java.util.UUID.fromString(rawId) }.getOrNull()?.let { id ->
                    ContractsRuntime.recordDungeonFinished(id, world, runId, outcome.name, instanceWorld)
                }
            }
            val completedPlayers =
                observation.playerOutcomes.filterValues { it == DungeonCompletionPlayerOutcome.START_TO_FINISH }.keys
            completedPlayers.forEach { playerId ->
                runCatching { UUID.fromString(playerId) }.getOrNull()?.let { playerId ->
                    ContractsRuntime.recordDungeonCompleted(playerId, runId)
                }
            }
            if (instanceWorld != null) {
                deliverSeasonDungeonRewards(observation, completedPlayers, instanceWorld, now)
            }
            publishMetrics()
        }
        return observation
    }

    private fun consumeSeasonDungeonAdmissionsAtStart(instanceWorld: String, now: Long) {
        if (!seasonDungeonRewardsEnabled() || !isLeader()) return
        val catalog = configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY) ?: return
        val coordinator = seasonMoneyCoordinator ?: return
        try {
            runBlocking {
                withTimeout(DUNGEON_EVENT_PERSIST_TIMEOUT_MILLIS) {
                    submissionMutex.withLock {
                        coordinator.consumeAuthorizedDungeonAdmissions(catalog, instanceWorld, now)
                    }
                }
            }
        } catch (failure: Throwable) {
            ContractsRuntime.logError("Season dungeon admission consumption failed at native start for {}", instanceWorld, failure)
        }
    }

    private fun deliverSeasonDungeonRewards(
        observation: DungeonContractCompletionObservation,
        playerIds: Set<String>,
        instanceWorld: String,
        now: Long,
    ) {
        if (!seasonDungeonRewardsEnabled() || !isLeader()) return
        val currentScope = seasonScope ?: return
        val rewardCoordinator = seasonDungeonRewardCoordinator ?: return
        val moneyCoordinator = seasonMoneyCoordinator ?: return
        currentScope.launch {
            try {
                submissionMutex.withLock {
                    if (!seasonDungeonRewardsEnabled()) return@withLock
                    val catalog = requireNotNull(configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY))
                    moneyCoordinator.consumeAuthorizedDungeonAdmissions(catalog, instanceWorld, now)
                    val authorization =
                        requireNotNull(coordinatorState(catalog).authorizedDungeonRuns[instanceWorld.trim().lowercase()]) {
                            "Season dungeon completion has no durable authorization"
                        }.validated()
                    require(authorization.dungeonContractId == observation.contractId) {
                        "Season dungeon completion does not match its authorization"
                    }
                    playerIds.sorted().forEach { playerId ->
                        when (val outcome = rewardCoordinator.deliver(catalog, authorization, playerId, 1.0, now)) {
                            is SeasonDungeonRewardOutcome.ManualReview ->
                                ContractsRuntime.logError("Season dungeon reward {} requires manual review", outcome.rewardId)
                            is SeasonDungeonRewardOutcome.Unavailable ->
                                throw IllegalStateException("Season dungeon reward ${outcome.rewardId} is unavailable")
                            else -> Unit
                        }
                    }
                    moneyCoordinator.finishAuthorizedDungeonRun(catalog, instanceWorld)
                }
            } catch (failure: Throwable) {
                ContractsRuntime.logError("Season dungeon reward delivery failed for {}", instanceWorld, failure)
            } finally {
                publishMetrics()
            }
        }
    }

    @JvmStatic
    fun resumeSeasonDungeonRewards(playerId: UUID) {
        if (!seasonDungeonRewardsEnabled() || !isLeader()) return
        val currentScope = seasonScope ?: return
        val coordinator = seasonDungeonRewardCoordinator ?: return
        currentScope.launch {
            try {
                submissionMutex.withLock {
                    if (!seasonDungeonRewardsEnabled()) return@withLock
                    val catalog = requireNotNull(configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY))
                    coordinator.resumePlayer(catalog, playerId.toString())
                }
            } catch (failure: Throwable) {
                ContractsRuntime.logError("Season dungeon reward resume failed for {}", playerId, failure)
            } finally {
                publishMetrics()
            }
        }
    }

    @JvmStatic
    fun dungeonObservationSnapshot(now: Long = System.currentTimeMillis()): DungeonContractObservationSnapshot =
        dungeonObserver.snapshot(now)

    @JvmStatic
    fun quote(player: Player, contractId: String, requestedQuantity: Int): ContractSubmissionQuote? {
        check(Bukkit.isPrimaryThread()) { "Contract quotes must be created on the main thread" }
        if (!submissionsEnabled()) return null
        val now = System.currentTimeMillis()
        val runtime = currentRuntimeViews(now).firstOrNull { it.definition.id == contractId } ?: return null
        val definition = runtime.definition
        if (!ContractOriginGate.canSubmit(player, definition.group)) return null
        if (!seasonResourceStageOpen(definition)) return null
        // The ready read model was fully validated on the worker. Submission later rechecks
        // current state, all reservations and the cross-contract budget under submissionMutex.
        val state = runtime.state
        val reservations = runtime.reservations
        val policy = ContractRankPolicyResolver.resolve(player)
        var plan = ResourceContractEngine.plan(
            definition, state.validatedAgainst(definition), "quote-${UUID.randomUUID()}", player.uniqueId.toString(),
            requestedQuantity, now, reservations, policy,
        ) as? ContractSubmissionPlan.Accepted ?: return null
        val networkBudget = (readSummary["remainingWeeklyBudgetMinor"] as? Number)?.toLong() ?: 0L
        if (plan.payoutMinor > networkBudget) {
            val quantity = ContractMarketPricing.affordableQuantity(definition,
                state.acceptedQuantity + reservations.sumOf { it.quantity }, plan.acceptedQuantity, networkBudget, now, policy)
            if (quantity < definition.minSubmissionQuantity) return null
            plan = ResourceContractEngine.plan(definition, state.validatedAgainst(definition),
                "quote-${UUID.randomUUID()}", player.uniqueId.toString(), quantity.toInt(), now, reservations, policy)
                as? ContractSubmissionPlan.Accepted ?: return null
        }
        return ContractSubmissionQuote(
            definition.id, definition.windowStartsAt, plan.playerId, plan.acceptedQuantity.toInt(),
            plan.payoutMinor, plan.expectedRevision, now,
        )
    }

    private fun definitionAt(contractId: String, now: Long): ResourceContractDefinition? =
        configRef.get()?.let { currentDefinitions(it, now) }?.firstOrNull { it.id == contractId }?.let(::frozenDefinition)

    private fun currentDefinitions(config: ContractsConfig, now: Long): List<ResourceContractDefinition> {
        if (config.selectionPolicy.applies(now)) return selectionRuntime?.current(now)?.orders.orEmpty()
        if (isLeader()) return config.resourceOrdersAt(now)
        // Observe replicas browse the leader's immutable definitions; they never create orders.
        return ContractBookCatalog.fromSnapshots(repo?.allNow().orEmpty().mapNotNull { it.definitionSnapshot }, now)
    }

    private fun frozenDefinition(definition: ResourceContractDefinition): ResourceContractDefinition =
        repo?.getNow(ResourceContractRecord.stateId(definition.id, definition.windowStartsAt))?.definitionSnapshot
            ?: definition

    /** Counts overlapping windows including retired catalog entries, never only visible orders. */
    private fun remainingWeeklyBudget(now: Long): Long = ContractRotation.remainingBudget(
        configRef.get()?.serverWeeklyBudgetMinor ?: 0L, repo?.allNow().orEmpty(), journalRepo?.allNow().orEmpty(), now,
    )

    @JvmStatic
    fun submit(
        player: Player,
        quote: ContractSubmissionQuote,
        inventorySlots: Set<Int>? = null,
        offeredInventory: PreparedContractInventory? = null,
    ): CompletableFuture<ContractSubmissionOutcome> {
        check(Bukkit.isPrimaryThread()) { "Contract submissions must be started on the main thread" }
        val playerId = player.uniqueId
        if (quote.playerId != playerId.toString()) {
            return CompletableFuture.completedFuture(ContractSubmissionOutcome.Rejected(SubmissionRejection.INVALID_REQUEST))
        }
        val policy = ContractRankPolicyResolver.resolve(player)
        val submissionId = "arc-${UUID.randomUUID()}"
        val result = CompletableFuture<ContractSubmissionOutcome>()
        val currentScope = submissionScope
        val coordinator = submissionCoordinator
        val currentMutex = submissionMutex
        val currentJournal = journalRepo
        if (!submissionsEnabled() || currentScope == null || coordinator == null) {
            return result.apply { complete(ContractSubmissionOutcome.Unavailable(submissionId)) }
        }
        if (!submissionsInFlight.add(playerId)) {
            return result.apply {
                complete(ContractSubmissionOutcome.Rejected(SubmissionRejection.SUBMISSION_IN_PROGRESS))
            }
        }
        val definition = definitionAt(quote.contractId, System.currentTimeMillis())
            ?: return result.apply {
                submissionsInFlight.remove(playerId)
                complete(ContractSubmissionOutcome.Rejected(SubmissionRejection.INVALID_REQUEST))
            }
        if (!ContractOriginGate.canSubmit(player, definition.group)) {
            submissionsInFlight.remove(playerId)
            return result.apply { complete(ContractSubmissionOutcome.Rejected(SubmissionRejection.INVALID_REQUEST)) }
        }
        currentScope.launch {
            try {
                val outcome =
                    currentMutex.withLock {
                        val submitted =
                            if (!submissionsEnabled()) {
                                ContractSubmissionOutcome.Unavailable(submissionId)
                            } else if (!seasonResourceStageOpen(definition)) {
                                ContractSubmissionOutcome.Rejected(SubmissionRejection.PROJECT_STAGE_LOCKED)
                            } else {
                                val currentRepo = requireNotNull(repo)
                                val stateId = ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)
                                if (currentRepo.getNow(stateId) == null) {
                                    currentRepo.markDirty(ResourceContractRecord.empty(definition))
                                    currentRepo.saveDirty().getOrThrow()
                                }
                                coordinator.submit(
                                    definition,
                                    submissionId,
                                    playerId.toString(),
                                    quote.quantity,
                                    policy,
                                    quote = quote,
                                    availableNetworkBudgetMinor = remainingWeeklyBudget(System.currentTimeMillis()),
                                    inventorySlots = inventorySlots,
                                    offeredInventory = offeredInventory,
                                )
                            }
                        publishResourceContractCommitted(quote.contractId, submitted)
                        val projected =
                            if (submitted is ContractSubmissionOutcome.Committed ||
                                submitted is ContractSubmissionOutcome.Duplicate
                            ) {
                                runCatching { reconcileSeasonResources() }.isSuccess
                            } else {
                                true
                            }
                        if (currentJournal != null) {
                            try {
                                pruneTerminalJournals(currentJournal, System.currentTimeMillis())
                            } catch (failure: Throwable) {
                                ContractsRuntime.logError("Contract journal retention failed after submission", failure)
                            }
                        }
                        if (projected) submitted else ContractSubmissionOutcome.ManualReview(submissionId)
                    }
                result.complete(outcome)
            } catch (failure: Throwable) {
                ContractsRuntime.logError("Contract submission {} stopped unexpectedly", submissionId, failure)
                result.complete(ContractSubmissionOutcome.ManualReview(submissionId))
            } finally {
                submissionsInFlight.remove(playerId)
                publishMetrics()
            }
        }
        return result
    }

    private fun publishResourceContractCommitted(contractId: String, outcome: ContractSubmissionOutcome) {
        val receipt = when (outcome) {
            is ContractSubmissionOutcome.Committed -> outcome.receipt
            is ContractSubmissionOutcome.Duplicate -> outcome.receipt
            else -> return
        }
        val event = ResourceContractCommittedEvent(
            receipt.submissionId,
            runCatching { UUID.fromString(receipt.playerId) }.getOrNull() ?: return,
            contractId,
            receipt.quantity,
        )
        val publish = Runnable { Bukkit.getPluginManager().callEvent(event) }
        if (Bukkit.isPrimaryThread()) publish.run() else Tasks.scheduler.runSync(publish)
    }

    @JvmStatic
    fun submitSeasonMoney(
        playerId: UUID,
        request: SeasonMoneyActionRequest,
    ): CompletableFuture<SeasonMoneyActionOutcome> {
        val actionId = "arc-${UUID.randomUUID()}"
        val result = CompletableFuture<SeasonMoneyActionOutcome>()
        val currentScope = seasonScope
        val coordinator = seasonMoneyCoordinator
        if (!seasonMoneyEnabled() || currentScope == null || coordinator == null) {
            return result.apply { complete(SeasonMoneyActionOutcome.Unavailable(actionId)) }
        }
        currentScope.launch {
            try {
                val outcome =
                    submissionMutex.withLock {
                        if (!seasonMoneyEnabled()) {
                            SeasonMoneyActionOutcome.Unavailable(actionId)
                        } else {
                            reconcileSeasonResources()
                            val catalog = requireNotNull(configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY))
                            coordinator.submit(catalog, actionId, playerId.toString(), request)
                        }
                    }
                result.complete(outcome)
            } catch (failure: Throwable) {
                ContractsRuntime.logError("Season money action {} stopped unexpectedly", actionId, failure)
                result.complete(SeasonMoneyActionOutcome.ManualReview(actionId))
            } finally {
                publishMetrics()
            }
        }
        return result
    }

    @JvmStatic
    fun submitSeasonTrophy(
        playerId: UUID,
        stageId: String,
        itemKey: String,
        requestedQuantity: Int,
    ): CompletableFuture<SeasonTrophyContributionOutcome> {
        val contributionId = "arc-${UUID.randomUUID()}"
        val result = CompletableFuture<SeasonTrophyContributionOutcome>()
        val currentScope = seasonScope
        val coordinator = seasonTrophyCoordinator
        if (!seasonTrophyEnabled() || currentScope == null || coordinator == null) {
            return result.apply { complete(SeasonTrophyContributionOutcome.Unavailable(contributionId)) }
        }
        currentScope.launch {
            try {
                val outcome =
                    submissionMutex.withLock {
                        if (!seasonTrophyEnabled()) {
                            SeasonTrophyContributionOutcome.Unavailable(contributionId)
                        } else {
                            reconcileSeasonResources()
                            val catalog = requireNotNull(configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY))
                            coordinator.submit(
                                catalog,
                                contributionId,
                                stageId,
                                itemKey,
                                playerId.toString(),
                                requestedQuantity,
                            )
                        }
                    }
                result.complete(outcome)
            } catch (failure: Throwable) {
                ContractsRuntime.logError("Season trophy contribution {} stopped unexpectedly", contributionId, failure)
                result.complete(SeasonTrophyContributionOutcome.ManualReview(contributionId))
            } finally {
                publishMetrics()
            }
        }
        return result
    }

    @JvmStatic
    fun prepareSeasonDungeonLaunch(
        dungeonContractId: String,
        participantIds: Set<UUID>,
    ): CompletableFuture<SeasonDungeonLaunchPreparationOutcome> {
        val result = CompletableFuture<SeasonDungeonLaunchPreparationOutcome>()
        val currentScope = seasonScope
        val coordinator = seasonMoneyCoordinator
        if (!seasonMoneyEnabled() || currentScope == null || coordinator == null) {
            return result.apply { complete(SeasonDungeonLaunchPreparationOutcome.Unavailable) }
        }
        currentScope.launch {
            val outcome =
                try {
                    submissionMutex.withLock {
                        if (!seasonMoneyEnabled()) {
                            SeasonDungeonLaunchPreparationOutcome.Unavailable
                        } else {
                            reconcileSeasonResources()
                            val catalog = requireNotNull(configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY))
                            SeasonDungeonLaunchPreparationOutcome.Ready(
                                coordinator.reserveDungeonLaunch(
                                    catalog,
                                    dungeonContractId,
                                    participantIds.mapTo(linkedSetOf()) { it.toString() },
                                    System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                } catch (failure: IllegalArgumentException) {
                    SeasonDungeonLaunchPreparationOutcome.Rejected(
                        failure.message?.lowercase()?.replace(Regex("[^a-z0-9]+"), "_")?.trim('_')?.take(80)
                            ?.ifBlank { "invalid_launch" } ?: "invalid_launch",
                    )
                } catch (failure: Throwable) {
                    ContractsRuntime.logError("Season dungeon launch preparation failed for {}", dungeonContractId, failure)
                    SeasonDungeonLaunchPreparationOutcome.Unavailable
                }
            result.complete(outcome)
            publishMetrics()
        }
        return result
    }

    @JvmStatic
    fun cancelSeasonDungeonLaunch(tokenId: String): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        val currentScope = seasonScope
        val coordinator = seasonMoneyCoordinator
        if (!seasonMoneyEnabled() || currentScope == null || coordinator == null) {
            return result.apply { complete(false) }
        }
        currentScope.launch {
            val changed =
                try {
                    submissionMutex.withLock {
                        if (!seasonMoneyEnabled()) return@withLock false
                        val catalog = requireNotNull(configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY))
                        val before = coordinatorState(catalog)
                        coordinator.cancelDungeonLaunch(catalog, tokenId, System.currentTimeMillis()) != before
                    }
                } catch (failure: Throwable) {
                    ContractsRuntime.logError("Season dungeon launch cancellation failed for {}", tokenId, failure)
                    false
                }
            result.complete(changed)
            publishMetrics()
        }
        return result
    }

    @JvmStatic
    fun seasonDungeonBlueprintWorld(dungeonContractId: String): String? =
        configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)
            ?.dungeonContracts?.get(dungeonContractId.trim().lowercase())?.world

    @JvmStatic
    fun seasonDungeonContractIds(): List<String> =
        configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)
            ?.dungeonContracts?.keys?.sorted().orEmpty()

    @JvmStatic
    fun seasonProjectStageIds(): List<String> =
        configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)
            ?.projectStages?.keys?.sorted().orEmpty()

    @JvmStatic
    fun seasonCompletionStageId(): String? =
        configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)?.completionStage

    /** Called synchronously by EliteMobs before its world clone side effect. */
    @JvmStatic
    fun authorizeSeasonDungeonInstance(
        blueprintWorld: String,
        instanceWorld: String,
        now: Long = System.currentTimeMillis(),
    ): SeasonDungeonInstanceDecision {
        if (!seasonDungeonProtectionEnabled()) return SeasonDungeonInstanceDecision.NOT_PROTECTED
        val catalog = configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)
            ?: return SeasonDungeonInstanceDecision.DENIED
        if (catalog.dungeonContracts.values.none { it.world == blueprintWorld.trim().lowercase() }) {
            return SeasonDungeonInstanceDecision.NOT_PROTECTED
        }
        if (!isLeader()) return SeasonDungeonInstanceDecision.DENIED
        val coordinator = seasonMoneyCoordinator ?: return SeasonDungeonInstanceDecision.DENIED
        return try {
            val authorization =
                runBlocking {
                    withTimeout(DUNGEON_EVENT_PERSIST_TIMEOUT_MILLIS) {
                        submissionMutex.withLock {
                            coordinator.authorizeDungeonInstance(catalog, blueprintWorld, instanceWorld, now)
                        }
                    }
                }
            if (authorization == null) SeasonDungeonInstanceDecision.DENIED else SeasonDungeonInstanceDecision.AUTHORIZED
        } catch (failure: Throwable) {
            ContractsRuntime.logError("Season dungeon instance authorization failed for {}", instanceWorld, failure)
            SeasonDungeonInstanceDecision.DENIED
        }.also { publishMetrics() }
    }

    /** Called at MONITOR only if another listener cancelled the native clone. */
    @JvmStatic
    fun cancelAuthorizedSeasonDungeonInstance(instanceWorld: String): Boolean {
        if (!seasonDungeonProtectionEnabled() || !isLeader()) return false
        val catalog = configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY) ?: return false
        val coordinator = seasonMoneyCoordinator ?: return false
        return try {
            runBlocking {
                withTimeout(DUNGEON_EVENT_PERSIST_TIMEOUT_MILLIS) {
                    submissionMutex.withLock {
                        val before = coordinatorState(catalog)
                        val after = coordinator.cancelAuthorizedDungeonInstance(catalog, instanceWorld)
                        after != before
                    }
                }
            }
        } catch (failure: Throwable) {
            ContractsRuntime.logError("Season dungeon instance cancellation recovery failed for {}", instanceWorld, failure)
            false
        }.also { publishMetrics() }
    }

    @JvmStatic
    fun seasonDungeonRunAuthorization(instanceWorld: String): SeasonDungeonRunAuthorization? =
        seasonState()?.authorizedDungeonRuns?.get(instanceWorld.trim().lowercase())?.validated()

    /** null = ordinary world, true/false = protected instance admission decision. */
    @JvmStatic
    fun seasonDungeonPlayerAuthorized(instanceWorld: String, playerId: UUID): Boolean? {
        if (!seasonDungeonProtectionEnabled()) return null
        val authorization = seasonDungeonRunAuthorization(instanceWorld) ?: return null
        if (!isLeader()) return false
        return playerId.toString() in authorization.participantIds
    }

    @JvmStatic
    fun currentViews(now: Long = System.currentTimeMillis()): List<ResourceContractView> {
        return currentRuntimeViews(now).map { it.view }
    }

    @JvmStatic
    fun currentPlayerViews(
        playerId: UUID,
        group: String? = null,
        now: Long = System.currentTimeMillis(),
        policy: ContractRankPolicy = ContractRankPolicy.IDENTITY,
    ): List<ResourceContractPlayerView> =
        currentRuntimeViews(now)
            .asSequence()
            .filter { group == null || it.definition.group == group }
            .map { runtime ->
                val playerKey = playerId.toString()
                val accepted = runtime.state.perPlayerQuantity[playerKey] ?: 0L
                val reserved =
                    runtime.reservations.asSequence()
                        .filter { it.playerId == playerKey }
                        .fold(0L) { total, reservation -> Math.addExact(total, reservation.quantity) }
                val effectiveCap = policy.playerCap(runtime.definition.perPlayerQuantityCap)
                ResourceContractPlayerView(
                    contract = runtime.view,
                    minSubmissionQuantity = runtime.definition.minSubmissionQuantity,
                    maxSubmissionQuantity = runtime.definition.maxSubmissionQuantity,
                    perPlayerQuantityCap = effectiveCap,
                    playerAcceptedQuantity = accepted,
                    playerReservedQuantity = reserved,
                    playerRemainingQuantity =
                        (effectiveCap - accepted - reserved).coerceAtLeast(0L),
                    playerPayoutMinorPerUnit = ContractMarketPricing.unitPayoutMinor(
                        runtime.definition, runtime.view.acceptedQuantity + runtime.view.reservedQuantity, now, policy,
                    ),
                    capBasisPoints = policy.playerCapBasisPoints,
                    payoutBasisPoints = policy.payoutBasisPoints,
                    pricingDefinition = runtime.definition,
                    pricingSupply = runtime.view.acceptedQuantity + runtime.view.reservedQuantity,
                    pricingAt = now,
                )
            }.toList()

    private data class RuntimeResourceContractView(
        val definition: ResourceContractDefinition,
        val state: ResourceContractState,
        val reservations: List<ContractQuotaReservation>,
        val view: ResourceContractView,
    )

    private fun currentRuntimeViews(now: Long): List<RuntimeResourceContractView> = readViews.map { runtime ->
        if (now < runtime.definition.windowEndsAt || runtime.view.status == ContractStatus.EXPIRED.label) runtime
        else runtime.copy(view = runtime.view.copy(status = ContractStatus.EXPIRED.label))
    }

    private fun calculateRuntimeViews(now: Long): List<RuntimeResourceContractView> {
        val config = configRef.get() ?: return emptyList()
        val currentRepo = repo
        return currentDefinitions(config, now).map(::frozenDefinition).map { definition ->
            val record =
                currentRepo?.getNow(ResourceContractRecord.stateId(definition.id, definition.windowStartsAt))
                    ?: ResourceContractRecord.empty(definition)
            val state = record.validatedAgainst(definition).state
            val reservations = activeReservations(definition, state)
            val reservedQuantity = reservations.fold(0L) { total, reservation -> Math.addExact(total, reservation.quantity) }
            val reservedMinor = reservations.fold(0L) { total, reservation -> Math.addExact(total, reservation.payoutMinor) }
            val effectiveStatus =
                when {
                    now >= definition.windowEndsAt -> ContractStatus.EXPIRED
                    !definition.isOpenAt(now) && state.status == ContractStatus.OPEN -> ContractStatus.PAUSED
                    else -> state.status
                }
            val view =
                ResourceContractView(
                    id = definition.id,
                    displayName = definition.displayName,
                    itemKey = definition.itemKey,
                    funding = definition.funding.label,
                    status = effectiveStatus.label,
                    windowStartsAt = definition.windowStartsAt,
                    windowEndsAt = definition.windowEndsAt,
                    payoutMinorPerUnit = ContractMarketPricing.unitPayoutMinor(
                        definition, state.acceptedQuantity + reservedQuantity, now, ContractRankPolicy.IDENTITY,
                    ),
                    budgetMinor = definition.budgetMinor,
                    spentMinor = state.spentMinor,
                    reservedMinor = reservedMinor,
                    targetQuantity = definition.targetQuantity,
                    acceptedQuantity = state.acceptedQuantity,
                    reservedQuantity = reservedQuantity,
                    remainingQuantity =
                        (definition.targetQuantity - state.acceptedQuantity - reservedQuantity).coerceAtLeast(0L),
                    contributors = (state.perPlayerQuantity.keys + reservations.map { it.playerId }).size,
                    group = definition.group,
                    basePayoutMinorPerUnit = definition.payoutMinorPerUnit,
                    minSubmissionQuantity = definition.minSubmissionQuantity,
                    minimumSubmissionPayoutMinor = ContractMarketPricing.payoutMinor(
                        definition, state.acceptedQuantity + reservedQuantity,
                        definition.minSubmissionQuantity.toLong(), now,
                    ),
                )
            RuntimeResourceContractView(definition, state, reservations, view)
        }
    }

    @JvmStatic
    fun summary(): Map<String, Any?> = readSummary

    private fun calculateSummary(): Map<String, Any?> {
        val config = configRef.get()
        val views = currentViews()
        val currentSeasonState = seasonState()
        return linkedMapOf(
            "enabled" to (config?.enabled ?: false),
            "mode" to (config?.mode?.label ?: ContractsMode.DISABLED.label),
            "leaderServer" to (config?.leaderServer ?: "spawn"),
            "localServer" to ContractsRuntime.serverName.ifBlank { "unknown" },
            "localLeader" to isLeader(),
            "submissionRuntimeReady" to SUBMISSION_RUNTIME_READY,
            "submissionsEnabled" to submissionsEnabled(),
            "seasonMutationRuntimeReady" to SEASON_MUTATION_RUNTIME_READY,
            "seasonMoneyEnabled" to seasonMoneyEnabled(),
            "seasonTrophyEnabled" to seasonTrophyEnabled(),
            "seasonDungeonRewardsEnabled" to seasonDungeonRewardsEnabled(),
            "serverWeeklyBudgetMinor" to (config?.serverWeeklyBudgetMinor ?: 0L),
            "remainingWeeklyBudgetMinor" to remainingWeeklyBudget(System.currentTimeMillis()),
            "selection" to selectionRuntime?.summary(System.currentTimeMillis()),
            "salesWorld" to "rc_origin_spawn",
            "salesEntry" to "gui",
            "seasonCatalog" to config?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)?.summary(),
            "seasonState" to
                currentSeasonState?.let { state ->
                    linkedMapOf(
                        "seasonId" to state.seasonId,
                        "project" to state.project,
                        "contributors" to state.projectContributors.size,
                        "admissionPasses" to
                            DungeonAdmissionPassStatus.entries.associate { status ->
                                status.label to state.admissionPasses.values.count { it.status == status }
                            },
                        "pendingDungeonLaunches" to state.dungeonLaunchTokens.size,
                        "authorizedDungeonRuns" to state.authorizedDungeonRuns.size,
                        "dungeonRewardReceipts" to state.recentDungeonRewardReceipts.size,
                        "revision" to state.revision,
                    )
                },
            "seasonMoneyJournal" to seasonMoneyJournalSummary(),
            "seasonTrophyJournal" to seasonTrophyJournalSummary(),
            "seasonDungeonRewardJournal" to seasonDungeonRewardJournalSummary(),
            "dungeonObservation" to dungeonObservationSnapshot(),
            "submissionJournal" to journalSummary(),
            "orders" to views,
        )
    }

    @JvmStatic
    fun journalSummary(now: Long = System.currentTimeMillis()): ContractSubmissionJournalSummary {
        if (journalRepo == null) return ContractSubmissionJournalSummary.unavailable()
        if (now < 0L) throw IllegalArgumentException("Journal audit timestamp must be non-negative")
        return readJournal
    }

    @JvmStatic
    fun seasonState(): SeasonRuntimeState? {
        val catalog = configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY) ?: return null
        return seasonStateRepo?.getNow(SeasonRuntimeState.stateId(catalog))?.validatedAgainst(catalog)
    }

    private fun coordinatorState(catalog: ObserveSeasonCatalog): SeasonRuntimeState =
        requireNotNull(seasonStateRepo?.getNow(SeasonRuntimeState.stateId(catalog))) {
            "Season runtime state is unavailable"
        }.validatedAgainst(catalog)

    @JvmStatic
    fun seasonMoneyJournalSummary(): SeasonMoneyJournalSummary {
        val current = seasonMoneyJournalRepo ?: return SeasonMoneyJournalAudit.unavailable()
        return SeasonMoneyJournalAudit.summarize(current.allNow())
    }

    @JvmStatic
    fun seasonTrophyJournalSummary(): SeasonTrophyJournalSummary {
        val current = seasonTrophyJournalRepo ?: return SeasonTrophyJournalSummary.unavailable()
        return SeasonTrophyJournalAudit.summarize(current.allNow())
    }

    @JvmStatic
    fun seasonDungeonRewardJournalSummary(): SeasonDungeonRewardJournalSummary {
        val current = seasonDungeonRewardJournalRepo ?: return SeasonDungeonRewardJournalSummary.unavailable()
        return SeasonDungeonRewardJournalAudit.summarize(current.allNow())
    }

    @JvmStatic
    fun seasonMoneyReconciliationRecords(limit: Int = 20): List<SeasonMoneyJournalRecord> {
        require(limit in 1..100) { "Season money reconciliation limit must be 1..100" }
        val current = seasonMoneyJournalRepo ?: throw IllegalStateException("Season money journal is unavailable")
        return current.allNow().asSequence()
            .map { it.validated() }
            .filter { it.status == SeasonMoneyJournalStatus.MANUAL_REVIEW || it.reconciliation != null }
            .sortedWith(compareByDescending<SeasonMoneyJournalRecord> { it.updatedAt }.thenBy { it.actionId })
            .take(limit)
            .toList()
    }

    @JvmStatic
    fun seasonMoneyReconciliationRecord(actionId: String): SeasonMoneyJournalRecord? {
        val current = seasonMoneyJournalRepo ?: throw IllegalStateException("Season money journal is unavailable")
        return current.getNow(actionId)?.validated()
    }

    @JvmStatic
    @Synchronized
    fun previewSeasonMoneyReconciliation(
        request: SeasonMoneyReconciliationRequest,
    ): SeasonMoneyReconciliationPreview {
        require(isLeader()) { "Season money reconciliation is available only on the configured leader" }
        val currentJournal = seasonMoneyJournalRepo ?: throw IllegalStateException("Season money journal is unavailable")
        requireUniqueSeasonMoneyReconciliationKey(currentJournal, request)
        val record = requireNotNull(currentJournal.getNow(request.actionId)) { "Season money action not found" }.validated()
        val preview = SeasonMoneyReconciliationEngine.preview(record, request)
        verifyReconciledSeasonCommit(record, preview)
        return preview
    }

    @JvmStatic
    @Synchronized
    fun applySeasonMoneyReconciliation(
        request: SeasonMoneyReconciliationRequest,
        reviewDigest: String,
        now: Long = System.currentTimeMillis(),
    ): SeasonMoneyReconciliationApplyResult {
        require(isLeader()) { "Season money reconciliation is available only on the configured leader" }
        val currentJournal = seasonMoneyJournalRepo ?: throw IllegalStateException("Season money journal is unavailable")
        val coordinator = seasonMoneyCoordinator ?: throw IllegalStateException("Season money coordinator is unavailable")
        val catalog = requireNotNull(configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)) {
            "Season catalog is unavailable"
        }
        return runBlocking {
            submissionMutex.withLock {
                val before = requireNotNull(currentJournal.getNow(request.actionId)) {
                    "Season money action not found"
                }.validated()
                requireUniqueSeasonMoneyReconciliationKey(currentJournal, request)
                val preview = SeasonMoneyReconciliationEngine.preview(before, request)
                verifyReconciledSeasonCommit(before, preview)
                var resolved = SeasonMoneyReconciliationEngine.apply(before, request, reviewDigest, now)
                val replayed = before.reconciliation != null
                if (resolved != before) {
                    currentJournal.markDirty(resolved)
                    currentJournal.saveDirty().getOrThrow()
                }

                var receipt: SeasonMoneyActionReceipt? = null
                if (resolved.status == SeasonMoneyJournalStatus.FUNDS_WITHDRAWN) {
                    when (val outcome = coordinator.commitReconciled(catalog, resolved)) {
                        is SeasonMoneyActionOutcome.Committed -> receipt = outcome.receipt
                        is SeasonMoneyActionOutcome.Duplicate -> receipt = outcome.receipt
                        else -> throw IllegalStateException("Reconciled season money state commit remains incomplete")
                    }
                    resolved = requireNotNull(currentJournal.getNow(request.actionId)) {
                        "Committed season money journal disappeared"
                    }.validated()
                } else if (resolved.status == SeasonMoneyJournalStatus.STATE_COMMITTED) {
                    receipt = seasonState()?.recentReceipts?.get(resolved.actionId)
                }

                if (!replayed) {
                    ContractsRuntime.info(
                        "Season money action {} reconciled as {} by authenticated operator {}",
                        resolved.actionId,
                        requireNotNull(resolved.reconciliation).resolution.label,
                        resolved.reconciliation.operatorId,
                    )
                }
                publishMetrics()
                SeasonMoneyReconciliationApplyResult(preview, resolved, receipt, replayed)
            }
        }
    }

    @JvmStatic
    fun reconciliationRecords(limit: Int = 20): List<ContractSubmissionJournalRecord> {
        require(limit in 1..100) { "Reconciliation limit must be 1..100" }
        val current = journalRepo ?: throw IllegalStateException("Contract submission journal is unavailable")
        return current.allNow().asSequence()
            .map { it.validated() }
            .filter { it.status == ContractSubmissionJournalStatus.MANUAL_REVIEW || it.reconciliation != null }
            .sortedWith(compareByDescending<ContractSubmissionJournalRecord> { it.updatedAt }.thenBy { it.submissionId })
            .take(limit)
            .toList()
    }

    @JvmStatic
    fun reconciliationRecord(submissionId: String): ContractSubmissionJournalRecord? {
        val current = journalRepo ?: throw IllegalStateException("Contract submission journal is unavailable")
        return current.getNow(submissionId)?.validated()
    }

    @JvmStatic
    @Synchronized
    fun previewReconciliation(
        request: ContractSubmissionReconciliationRequest,
    ): ContractSubmissionReconciliationPreview {
        require(isLeader()) { "Contract reconciliation is available only on the configured leader" }
        val currentJournal = journalRepo ?: throw IllegalStateException("Contract submission journal is unavailable")
        requireUniqueReconciliationKey(currentJournal, request)
        val record = requireNotNull(currentJournal.getNow(request.submissionId)) { "Contract submission not found" }.validated()
        val preview = ContractSubmissionReconciliationEngine.preview(record, request)
        verifyReconciledPaymentCommit(record, preview)
        return preview
    }

    @JvmStatic
    @Synchronized
    fun applyReconciliation(
        request: ContractSubmissionReconciliationRequest,
        reviewDigest: String,
        now: Long = System.currentTimeMillis(),
    ): ContractSubmissionReconciliationApplyResult {
        require(isLeader()) { "Contract reconciliation is available only on the configured leader" }
        val currentContract = repo ?: throw IllegalStateException("Contract state repository is unavailable")
        val currentJournal = journalRepo ?: throw IllegalStateException("Contract submission journal is unavailable")
        return runBlocking {
            submissionMutex.withLock {
                val before = requireNotNull(currentJournal.getNow(request.submissionId)) {
                    "Contract submission not found"
                }.validated()
                requireUniqueReconciliationKey(currentJournal, request)
                val preview = ContractSubmissionReconciliationEngine.preview(before, request)
                verifyReconciledPaymentCommit(before, preview)
                var resolved = ContractSubmissionReconciliationEngine.apply(before, request, reviewDigest, now)
                val replayed = before.reconciliation != null
                if (resolved != before) {
                    currentJournal.markDirty(resolved)
                    currentJournal.saveDirty().getOrThrow()
                }

                var receipt: ContractSubmissionReceipt? = null
                if (resolved.status == ContractSubmissionJournalStatus.PAID) {
                    val definition = reconciliationDefinition(resolved)
                    val stateId = ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)
                    val state = requireNotNull(currentContract.getNow(stateId)) {
                        "Missing state for reconciled paid contract ${definition.id}"
                    }.validatedAgainst(definition).state
                    val recovery = ContractSubmissionRecoveryEngine.recoverPaid(definition, state, resolved, now)
                    if (recovery.commit.changed) {
                        currentContract.markDirty(ResourceContractRecord(stateId, recovery.commit.state, definitionSnapshot = definition))
                        currentContract.saveDirty().getOrThrow()
                    }
                    resolved = recovery.journal
                    currentJournal.markDirty(resolved)
                    currentJournal.saveDirty().getOrThrow()
                    receipt = recovery.commit.receipt
                    reconcileSeasonResources()
                }

                try {
                    pruneTerminalJournals(currentJournal, now)
                } catch (failure: Throwable) {
                    ContractsRuntime.logError("Contract journal retention failed after reconciliation", failure)
                }
                if (!replayed) {
                    ContractsRuntime.info(
                        "Contract submission {} reconciled as {} by authenticated operator {}",
                        resolved.submissionId,
                        requireNotNull(resolved.reconciliation).resolution.label,
                        resolved.reconciliation.operatorId,
                    )
                }
                publishMetrics()
                ContractSubmissionReconciliationApplyResult(preview, resolved, receipt, replayed)
            }
        }
    }

    @JvmStatic
    fun publishMetrics() {
        if (!refreshPending.compareAndSet(false, true)) return
        ContractsRuntime.background {
            try {
                refreshReadModels()
            } finally { refreshPending.set(false) }
        }.whenComplete { _, failure ->
            if (failure != null) { refreshPending.set(false); ContractsRuntime.logError("Contract read model refresh failed", failure) }
        }
    }

    /** Worker-only; ready getters never validate or decode persisted item payloads. */
    internal fun refreshReadModels() {
        readViews = calculateRuntimeViews(System.currentTimeMillis())
        readJournal = journalRepo?.let { ContractSubmissionJournalAudit.summarize(it.allNow(), System.currentTimeMillis()) }
            ?: ContractSubmissionJournalSummary.unavailable()
        readSummary = calculateSummary()
        publishPreparedMetrics()
    }

    private fun publishPreparedMetrics() {
        ContractsRuntime.publishMetrics {
            val config = configRef.get()
            ContractsMetrics.points(
                enabled = config?.enabled == true,
                available = isAvailable(),
                mode = config?.mode ?: ContractsMode.DISABLED,
                localLeader = isLeader(),
                submissionRuntimeReady = SUBMISSION_RUNTIME_READY,
                submissionsEnabled = submissionsEnabled(),
                seasonMutationRuntimeReady = SEASON_MUTATION_RUNTIME_READY,
                serverWeeklyBudgetMinor = config?.serverWeeklyBudgetMinor ?: 0L,
                views = currentViews(),
                journal = journalSummary(),
                seasonCatalog = config?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY),
                seasonState = seasonState(),
                seasonMoneyJournal = seasonMoneyJournalSummary(),
                seasonTrophyJournal = seasonTrophyJournalSummary(),
                seasonDungeonRewardJournal = seasonDungeonRewardJournalSummary(),
                dungeonObservation = dungeonObservationSnapshot(),
            )
        }
    }

    private data class SeasonRuntimeRepositories(
        val scope: CoroutineScope,
        val repositoryScope: CoroutineScope,
        val stateRepository: CachedRepository<SeasonRuntimeState>,
        val journalRepository: CachedRepository<SeasonMoneyJournalRecord>,
        val coordinator: SeasonMoneyCoordinator,
        val trophyJournalRepository: CachedRepository<SeasonTrophyJournalRecord>,
        val trophyCoordinator: SeasonTrophyContributionCoordinator,
        val dungeonRewardJournalRepository: CachedRepository<SeasonDungeonRewardJournalRecord>,
        val dungeonRewardCoordinator: SeasonDungeonRewardCoordinator,
    )

    private const val DUNGEON_EVENT_PERSIST_TIMEOUT_MILLIS = 3_000L

    private fun createSeasonRuntime(
        config: ContractsConfig,
        contractRepository: CachedRepository<ResourceContractRecord>,
        redis: RedisOperations,
        gson: com.google.gson.Gson,
    ): SeasonRuntimeRepositories? {
        if (!SEASON_MUTATION_RUNTIME_READY) return null
        val catalog = requireNotNull(config.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)) {
            "Season mutation runtime requires an exact season catalog"
        }
        require(catalog.dungeonContracts.values.all { PaperSeasonTrophyItems.supports(it.plannedBoundReward) }) {
            "Season mutation runtime contains an unsupported dungeon trophy design"
        }
        val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val stateRepository =
            try {
                ru.arc.repository.redisRepo<SeasonRuntimeState>(
                    redis = redis,
                    gson = gson,
                    id = "season-runtime",
                    storageKey = "arc.season-runtime.v1",
                    updateChannel = "arc.season-runtime.v1.update",
                    scope = repositoryScope,
                ) {
                    loadAllOnStart(true)
                    enableCleanup(false)
                    saveInterval(1.seconds)
                }
            } catch (failure: Throwable) {
                repositoryScope.cancel()
                throw failure
            }
        val journalRepository =
            try {
                ru.arc.repository.redisRepo<SeasonMoneyJournalRecord>(
                    redis = redis,
                    gson = gson,
                    id = "season-money-journal",
                    storageKey = "arc.season-money-journal.v1",
                    updateChannel = "arc.season-money-journal.v1.update",
                    scope = repositoryScope,
                ) {
                    loadAllOnStart(true)
                    enableCleanup(false)
                    saveInterval(1.seconds)
                }
            } catch (failure: Throwable) {
                runBlocking { stateRepository.shutdown() }
                repositoryScope.cancel()
                throw failure
            }
        val trophyJournalRepository =
            try {
                ru.arc.repository.redisRepo<SeasonTrophyJournalRecord>(
                    redis = redis,
                    gson = gson,
                    id = "season-trophy-journal",
                    storageKey = "arc.season-trophy-journal.v1",
                    updateChannel = "arc.season-trophy-journal.v1.update",
                    scope = repositoryScope,
                ) {
                    loadAllOnStart(true)
                    enableCleanup(false)
                    saveInterval(1.seconds)
                }
            } catch (failure: Throwable) {
                runBlocking {
                    try {
                        journalRepository.shutdown()
                    } finally {
                        stateRepository.shutdown()
                    }
                }
                repositoryScope.cancel()
                throw failure
            }
        val dungeonRewardJournalRepository =
            try {
                ru.arc.repository.redisRepo<SeasonDungeonRewardJournalRecord>(
                    redis = redis,
                    gson = gson,
                    id = "season-dungeon-reward-journal",
                    storageKey = "arc.season-dungeon-reward-journal.v1",
                    updateChannel = "arc.season-dungeon-reward-journal.v1.update",
                    scope = repositoryScope,
                ) {
                    loadAllOnStart(true)
                    enableCleanup(false)
                    saveInterval(1.seconds)
                }
            } catch (failure: Throwable) {
                runBlocking {
                    try {
                        trophyJournalRepository.shutdown()
                    } finally {
                        try {
                            journalRepository.shutdown()
                        } finally {
                            stateRepository.shutdown()
                        }
                    }
                }
                repositoryScope.cancel()
                throw failure
            }
        return try {
            val persistence = RedisSeasonMoneyPersistence(stateRepository, journalRepository)
            val trophyPersistence = RedisSeasonTrophyPersistence(stateRepository, trophyJournalRepository)
            val dungeonRewardPersistence =
                RedisSeasonDungeonRewardPersistence(stateRepository, dungeonRewardJournalRepository)
            ensureSeasonState(catalog, stateRepository)
            SeasonMoneyJournalAudit.summarize(journalRepository.allNow())
            SeasonTrophyJournalAudit.summarize(trophyJournalRepository.allNow())
            SeasonDungeonRewardJournalAudit.summarize(dungeonRewardJournalRepository.allNow())
            val coordinator = SeasonMoneyCoordinator(persistence, RedisEconomySeasonMoneyGateway())
            val trophyCoordinator =
                SeasonTrophyContributionCoordinator(trophyPersistence, PaperSeasonTrophyInventoryGateway())
            val dungeonRewardCoordinator =
                SeasonDungeonRewardCoordinator(
                    dungeonRewardPersistence,
                    RedisEconomyContractPaymentGateway(),
                    PaperSeasonDungeonTrophyDeliveryGateway(),
                )
            if (config.leaderServer.equals(ContractsRuntime.serverName, ignoreCase = true)) {
                runBlocking { coordinator.recover(catalog) }
                runBlocking { dungeonRewardCoordinator.recover(catalog) }
                runBlocking {
                    coordinator.recoverDungeonLaunches(
                        catalog,
                        Bukkit.getWorlds().mapTo(linkedSetOf()) { it.name.lowercase() },
                        System.currentTimeMillis(),
                    )
                }
                runBlocking { trophyCoordinator.recover(catalog) }
                runBlocking {
                    projectSeasonResources(catalog, config, contractRepository, stateRepository)
                }
            }
            SeasonRuntimeRepositories(
                CoroutineScope(Dispatchers.IO + SupervisorJob()),
                repositoryScope,
                stateRepository,
                journalRepository,
                coordinator,
                trophyJournalRepository,
                trophyCoordinator,
                dungeonRewardJournalRepository,
                dungeonRewardCoordinator,
            )
        } catch (failure: Throwable) {
            runBlocking {
                try {
                    try {
                        try {
                            dungeonRewardJournalRepository.shutdown()
                        } finally {
                            trophyJournalRepository.shutdown()
                        }
                    } finally {
                        journalRepository.shutdown()
                    }
                } finally {
                    stateRepository.shutdown()
                }
            }
            repositoryScope.cancel()
            throw failure
        }
    }

    private fun ensureSeasonState(
        catalog: ObserveSeasonCatalog,
        repository: CachedRepository<SeasonRuntimeState>,
    ) {
        val stateId = SeasonRuntimeState.stateId(catalog)
        val existing = repository.getNow(stateId)
        if (existing != null) {
            existing.validatedAgainst(catalog)
        } else {
            repository.markDirty(SeasonRuntimeState.empty(catalog))
            runBlocking { repository.saveDirty().getOrThrow() }
        }
    }

    private fun seasonResourceStageOpen(definition: ResourceContractDefinition): Boolean {
        if (!SEASON_MUTATION_RUNTIME_READY) return true
        val catalog = configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY) ?: return false
        val state = seasonStateRepo?.getNow(SeasonRuntimeState.stateId(catalog))?.validatedAgainst(catalog) ?: return false
        val stage = catalog.projectStages.values.singleOrNull { definition.id in it.requiredResources } ?: return false
        return SeasonProjectEngine.status(catalog, state.project, stage.id) == SeasonProjectStageStatus.OPEN
    }

    private suspend fun reconcileSeasonResources() {
        if (!SEASON_MUTATION_RUNTIME_READY) return
        val config = requireNotNull(configRef.get()) { "Contracts config is unavailable" }
        val catalog = requireNotNull(config.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)) {
            "Season catalog is unavailable"
        }
        val contractRepository = requireNotNull(repo) { "Contract repository is unavailable" }
        val stateRepository = requireNotNull(seasonStateRepo) { "Season state repository is unavailable" }
        projectSeasonResources(catalog, config, contractRepository, stateRepository)
    }

    private suspend fun projectSeasonResources(
        catalog: ObserveSeasonCatalog,
        config: ContractsConfig,
        contractRepository: CachedRepository<ResourceContractRecord>,
        stateRepository: CachedRepository<SeasonRuntimeState>,
    ) {
        val definitions = config.resourceOrders()
        val states =
            definitions.map { definition ->
                requireNotNull(
                    contractRepository.getNow(ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)),
                ) { "Missing state for season resource contract ${definition.id}" }
                    .validatedAgainst(definition)
                    .state
            }
        val current = requireNotNull(stateRepository.getNow(SeasonRuntimeState.stateId(catalog))) {
            "Season runtime state is unavailable"
        }.validatedAgainst(catalog)
        val projection = SeasonResourceProjectionEngine.project(catalog, current, definitions, states)
        if (projection.changed) {
            stateRepository.markDirty(projection.state)
            stateRepository.saveDirty().getOrThrow()
        }
    }

    private fun ensureCurrentStates(
        config: ContractsConfig,
        repository: CachedRepository<ResourceContractRecord>,
    ) {
        val definitions = currentDefinitions(config, System.currentTimeMillis())
        definitions.forEach { definition ->
            val stateId = ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)
            val existing = repository.getNow(stateId)
            existing?.validatedAgainst(existing.definitionSnapshot ?: definition)
        }
        definitions.forEach { definition ->
            val stateId = ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)
            if (repository.getNow(stateId) == null) repository.markDirty(ResourceContractRecord.empty(definition))
        }
    }

    private fun recoverInterruptedJournals(
        config: ContractsConfig,
        contractRepository: CachedRepository<ResourceContractRecord>,
        journalRepository: CachedRepository<ContractSubmissionJournalRecord>,
        now: Long = System.currentTimeMillis(),
    ) {
        val records = journalRepository.allNow().onEach { it.validated() }
        ContractSubmissionJournalAudit.summarize(records, now)
        if (!config.leaderServer.equals(ContractsRuntime.serverName, ignoreCase = true)) return
        var changed = 0
        records.forEach { record ->
            val recovered =
                if (record.status == ContractSubmissionJournalStatus.PREPARED) {
                    ContractSubmissionJournalEngine.cancelPrepared(record, "restart_before_item_removal", now)
                } else {
                    ContractSubmissionJournalEngine.recoverInterrupted(record, now)
                }
            if (recovered != record) {
                journalRepository.markDirty(recovered)
                changed += 1
            }
        }
        if (changed > 0) {
            runBlocking { journalRepository.saveDirty().getOrThrow() }
            ContractsRuntime.info("Contracts safely recovered {} pre-mutation or interrupted submission(s)", changed)
        }

        records.filter { it.status == ContractSubmissionJournalStatus.PAID }.forEach { paid ->
            val stateId = ResourceContractRecord.stateId(paid.contractId, paid.contractWindowStartsAt)
            val current = requireNotNull(contractRepository.getNow(stateId)) { "Missing state for paid contract ${paid.contractId}" }
            val definition = paid.definitionSnapshot ?: current.definitionSnapshot ?:
                config.resourceOrdersAt(paid.contractWindowStartsAt).firstOrNull {
                    it.id == paid.contractId && it.windowStartsAt == paid.contractWindowStartsAt
                } ?: return@forEach
            val recovery =
                ContractSubmissionRecoveryEngine.recoverPaid(
                    definition,
                    current.validatedAgainst(definition).state,
                    paid,
                    now,
                )
            if (recovery.commit.changed) {
                contractRepository.markDirty(ResourceContractRecord(stateId, recovery.commit.state, definitionSnapshot = definition))
                runBlocking { contractRepository.saveDirty().getOrThrow() }
            }
            journalRepository.markDirty(recovery.journal)
            runBlocking { journalRepository.saveDirty().getOrThrow() }
        }
        runBlocking { pruneTerminalJournals(journalRepository, now) }
    }

    private fun verifyReconciledPaymentCommit(
        record: ContractSubmissionJournalRecord,
        preview: ContractSubmissionReconciliationPreview,
    ) {
        if (!preview.commitsContractState || record.status == ContractSubmissionJournalStatus.CONTRACT_COMMITTED) return
        val definition = reconciliationDefinition(record)
        val currentContract = repo ?: throw IllegalStateException("Contract state repository is unavailable")
        val stateId = ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)
        val state = requireNotNull(currentContract.getNow(stateId)) {
            "Missing state for reconciled paid contract ${definition.id}"
        }.validatedAgainst(definition).state
        ResourceContractEngine.commitReserved(
            definition,
            state,
            requireNotNull(record.quotaReservation()),
            System.currentTimeMillis(),
        )
    }

    private fun reconciliationDefinition(record: ContractSubmissionJournalRecord): ResourceContractDefinition =
        requireNotNull(
            record.definitionSnapshot ?: configRef.get()?.resourceOrdersAt(record.contractWindowStartsAt)?.firstOrNull {
                it.id == record.contractId && it.windowStartsAt == record.contractWindowStartsAt
            },
        ) { "Configured contract policy for submission ${record.submissionId} is unavailable" }

    private fun verifyReconciledSeasonCommit(
        record: SeasonMoneyJournalRecord,
        preview: SeasonMoneyReconciliationPreview,
    ) {
        if (!preview.commitsSeasonState || record.status == SeasonMoneyJournalStatus.STATE_COMMITTED) return
        val catalog = requireNotNull(configRef.get()?.observeSeasonCatalog(SEASON_MUTATION_RUNTIME_READY)) {
            "Season catalog is unavailable"
        }
        val state = seasonState() ?: throw IllegalStateException("Season runtime state is unavailable")
        SeasonMoneyActionEngine.commit(
            catalog,
            state,
            SeasonMoneyActionPlan.Accepted(
                actionId = record.actionId,
                kind = record.kind,
                targetId = record.targetId,
                playerId = record.playerId,
                amountMinor = record.amountMinor,
                expectedStateRevision = state.revision,
                catalogDigest = record.catalogDigest,
                plannedAt = record.createdAt,
            ),
            System.currentTimeMillis(),
        )
    }

    private fun requireUniqueSeasonMoneyReconciliationKey(
        journalRepository: CachedRepository<SeasonMoneyJournalRecord>,
        request: SeasonMoneyReconciliationRequest,
    ) {
        val reused =
            journalRepository.allNow().asSequence()
                .map { it.validated() }
                .firstOrNull {
                    it.actionId != request.actionId &&
                        it.reconciliation?.idempotencyKey == request.idempotencyKey
                }
        require(reused == null) { "Season money reconciliation idempotency key is already bound to another action" }
    }

    private fun requireUniqueReconciliationKey(
        journalRepository: CachedRepository<ContractSubmissionJournalRecord>,
        request: ContractSubmissionReconciliationRequest,
    ) {
        val reused =
            journalRepository.allNow().asSequence()
                .map { it.validated() }
                .firstOrNull {
                    it.submissionId != request.submissionId &&
                        it.reconciliation?.idempotencyKey == request.idempotencyKey
                }
        require(reused == null) { "Reconciliation idempotency key is already bound to another submission" }
    }

    private suspend fun pruneTerminalJournals(
        journalRepository: CachedRepository<ContractSubmissionJournalRecord>,
        now: Long,
    ): ContractSubmissionRetentionPlan {
        val plan = ContractSubmissionRetentionPolicy.plan(journalRepository.allNow(), now)
        plan.deleteSubmissionIds.forEach { submissionId ->
            journalRepository.deleteDurably(submissionId).getOrThrow()
        }
        if (plan.deleteSubmissionIds.isNotEmpty()) {
            ContractsRuntime.info(
                "Contracts retained {} journal record(s) after deleting {} terminal record(s)",
                plan.totalAfter,
                plan.deleteSubmissionIds.size,
            )
        }
        return plan
    }

    private fun activeReservations(
        definition: ResourceContractDefinition,
        state: ResourceContractState,
    ): List<ContractQuotaReservation> =
        journalRepo?.allNow().orEmpty().asSequence()
            .filter { it.contractId == definition.id && it.contractWindowStartsAt == definition.windowStartsAt }
            .map { it.validated() }
            .mapNotNull { it.quotaReservation() }
            .onEach { reservation ->
                state.recentReceipts[reservation.submissionId]?.let { receipt ->
                    require(
                        receipt.playerId == reservation.playerId &&
                            receipt.quantity == reservation.quantity &&
                            receipt.payoutMinor == reservation.payoutMinor,
                    ) { "Contract reservation disagrees with its committed receipt" }
                }
            }
            .filterNot { it.submissionId in state.recentReceipts }
            .toList()

    private class RedisSeasonMoneyPersistence(
        private val stateRepository: CachedRepository<SeasonRuntimeState>,
        private val journalRepository: CachedRepository<SeasonMoneyJournalRecord>,
    ) : SeasonMoneyPersistence {
        override fun state(catalog: ObserveSeasonCatalog): SeasonRuntimeState =
            requireNotNull(stateRepository.getNow(SeasonRuntimeState.stateId(catalog))) {
                "Season runtime state is not loaded"
            }.validatedAgainst(catalog)

        override fun journalRecords(): List<SeasonMoneyJournalRecord> = journalRepository.allNow()

        override suspend fun persistState(state: SeasonRuntimeState) {
            stateRepository.markDirty(state)
            stateRepository.saveDirty().getOrThrow()
        }

        override suspend fun persistJournal(record: SeasonMoneyJournalRecord) {
            journalRepository.markDirty(record.validated())
            journalRepository.saveDirty().getOrThrow()
        }

        override suspend fun deleteJournal(actionId: String) {
            journalRepository.deleteDurably(actionId).getOrThrow()
        }
    }

    private class RedisSeasonTrophyPersistence(
        private val stateRepository: CachedRepository<SeasonRuntimeState>,
        private val journalRepository: CachedRepository<SeasonTrophyJournalRecord>,
    ) : SeasonTrophyPersistence {
        override fun state(catalog: ObserveSeasonCatalog): SeasonRuntimeState =
            requireNotNull(stateRepository.getNow(SeasonRuntimeState.stateId(catalog))) {
                "Season runtime state is not loaded"
            }.validatedAgainst(catalog)

        override fun journalRecords(): List<SeasonTrophyJournalRecord> = journalRepository.allNow()

        override suspend fun persistState(state: SeasonRuntimeState) {
            stateRepository.markDirty(state)
            stateRepository.saveDirty().getOrThrow()
        }

        override suspend fun persistJournal(record: SeasonTrophyJournalRecord) {
            journalRepository.markDirty(record.validated())
            journalRepository.saveDirty().getOrThrow()
        }
    }

    private class RedisSeasonDungeonRewardPersistence(
        private val stateRepository: CachedRepository<SeasonRuntimeState>,
        private val journalRepository: CachedRepository<SeasonDungeonRewardJournalRecord>,
    ) : SeasonDungeonRewardPersistence {
        override fun state(catalog: ObserveSeasonCatalog): SeasonRuntimeState =
            requireNotNull(stateRepository.getNow(SeasonRuntimeState.stateId(catalog))) {
                "Season runtime state is not loaded"
            }.validatedAgainst(catalog)

        override fun journalRecords(): List<SeasonDungeonRewardJournalRecord> = journalRepository.allNow()

        override suspend fun persistState(state: SeasonRuntimeState) {
            stateRepository.markDirty(state)
            stateRepository.saveDirty().getOrThrow()
        }

        override suspend fun persistJournal(record: SeasonDungeonRewardJournalRecord) {
            journalRepository.markDirty(record.validated())
            journalRepository.saveDirty().getOrThrow()
        }
    }

    private class RedisContractSubmissionPersistence(
        private val contractRepository: CachedRepository<ResourceContractRecord>,
        private val journalRepository: CachedRepository<ContractSubmissionJournalRecord>,
    ) : ContractSubmissionPersistence {
        override fun contractState(definition: ResourceContractDefinition): ResourceContractState =
            requireNotNull(
                contractRepository.getNow(ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)),
            ) { "Contract state is not loaded" }
                .validatedAgainst(definition)
                .state

        override fun journalRecords(): List<ContractSubmissionJournalRecord> = journalRepository.allNow()

        override suspend fun persistJournal(record: ContractSubmissionJournalRecord) {
            journalRepository.markDirty(record.validated())
            journalRepository.saveDirty().getOrThrow()
        }

        override suspend fun persistContract(
            definition: ResourceContractDefinition,
            state: ResourceContractState,
        ) {
            state.validatedAgainst(definition)
            contractRepository.markDirty(
                ResourceContractRecord(
                    ResourceContractRecord.stateId(definition.id, definition.windowStartsAt),
                    state,
                    definitionSnapshot = definition,
                ),
            )
            contractRepository.saveDirty().getOrThrow()
        }
    }
}

object ContractsMetrics {
    fun points(
        enabled: Boolean,
        available: Boolean,
        mode: ContractsMode,
        localLeader: Boolean,
        submissionRuntimeReady: Boolean,
        submissionsEnabled: Boolean,
        seasonMutationRuntimeReady: Boolean,
        serverWeeklyBudgetMinor: Long,
        views: List<ResourceContractView>,
        journal: ContractSubmissionJournalSummary,
        seasonCatalog: ObserveSeasonCatalog?,
        seasonState: SeasonRuntimeState?,
        seasonMoneyJournal: SeasonMoneyJournalSummary,
        seasonTrophyJournal: SeasonTrophyJournalSummary,
        seasonDungeonRewardJournal: SeasonDungeonRewardJournalSummary,
        dungeonObservation: DungeonContractObservationSnapshot,
    ): List<MetricPoint> =
        buildList {
            add(point("arc_contracts_enabled", "Whether the Economy V2 contracts policy is enabled", enabled))
            add(point("arc_contracts_available", "Whether the contracts state repository is available", available))
            add(point("arc_contracts_local_leader", "Whether this Paper node is the configured contracts mutation leader", localLeader))
            add(point("arc_contracts_submission_runtime_ready", "Whether the atomic inventory and payout runtime is implemented", submissionRuntimeReady))
            add(point("arc_contracts_submissions_enabled", "Whether contract submissions may mutate inventory and money", submissionsEnabled))
            add(
                point(
                    "arc_season_mutation_runtime_ready",
                    "Whether season project and dungeon money or item mutations are enabled",
                    seasonMutationRuntimeReady,
                ),
            )
            add(
                MetricPoint(
                    "arc_contracts_mode",
                    "Current bounded Economy V2 contracts mode",
                    1.0,
                    mapOf("mode" to mode.label),
                ),
            )
            add(point("arc_season_catalog_available", "Whether the exact Economy V2 season catalog is loaded", seasonCatalog != null))
            add(point("arc_season_state_available", "Whether durable season project and admission state is loaded", seasonState != null))
            add(
                MetricPoint(
                    "arc_season_dungeon_launches",
                    "Durable season dungeon launches grouped by bounded state",
                    (seasonState?.dungeonLaunchTokens?.size ?: 0).toDouble(),
                    mapOf("state" to "pending"),
                ),
            )
            add(
                MetricPoint(
                    "arc_season_dungeon_launches",
                    "Durable season dungeon launches grouped by bounded state",
                    (seasonState?.authorizedDungeonRuns?.size ?: 0).toDouble(),
                    mapOf("state" to "authorized"),
                ),
            )
            add(point("arc_season_money_journal_available", "Whether the durable season money journal is loaded", seasonMoneyJournal.available))
            add(
                point(
                    "arc_season_trophy_journal_available",
                    "Whether the durable bound trophy contribution journal is loaded",
                    seasonTrophyJournal.available,
                ),
            )
            add(
                point(
                    "arc_season_dungeon_reward_journal_available",
                    "Whether the durable dungeon money and trophy reward journal is loaded",
                    seasonDungeonRewardJournal.available,
                ),
            )
            add(
                MetricPoint(
                    "arc_season_dungeon_reward_journal_records",
                    "Durable dungeon rewards grouped by bounded state",
                    seasonDungeonRewardJournal.records.toDouble(),
                    mapOf("state" to "all"),
                ),
            )
            seasonDungeonRewardJournal.statusCounts.forEach { (state, count) ->
                add(
                    MetricPoint(
                        "arc_season_dungeon_reward_journal_records",
                        "Durable dungeon rewards grouped by bounded state",
                        count.toDouble(),
                        mapOf("state" to state),
                    ),
                )
            }
            add(
                MetricPoint(
                    "arc_season_dungeon_reward_journal_payout_currency",
                    "Dungeon payout with trophy or state delivery still pending",
                    seasonDungeonRewardJournal.pendingPayoutMinor / 100.0,
                    mapOf("state" to "pending"),
                ),
            )
            add(
                MetricPoint(
                    "arc_season_dungeon_reward_journal_payout_currency",
                    "Dungeon payout requiring manual review",
                    seasonDungeonRewardJournal.manualReviewPayoutMinor / 100.0,
                    mapOf("state" to "manual_review"),
                ),
            )
            add(
                MetricPoint(
                    "arc_season_trophy_journal_records",
                    "Durable bound trophy contributions grouped by bounded state",
                    seasonTrophyJournal.records.toDouble(),
                    mapOf("state" to "all"),
                ),
            )
            seasonTrophyJournal.statusCounts.forEach { (state, count) ->
                add(
                    MetricPoint(
                        "arc_season_trophy_journal_records",
                        "Durable bound trophy contributions grouped by bounded state",
                        count.toDouble(),
                        mapOf("state" to state),
                    ),
                )
            }
            add(
                MetricPoint(
                    "arc_season_trophy_journal_quantity",
                    "Bound trophy quantity awaiting state commit or manual review",
                    seasonTrophyJournal.removedPendingCommitQuantity.toDouble(),
                    mapOf("state" to "removed_pending_commit"),
                ),
            )
            add(
                MetricPoint(
                    "arc_season_trophy_journal_quantity",
                    "Bound trophy quantity awaiting state commit or manual review",
                    seasonTrophyJournal.manualReviewQuantity.toDouble(),
                    mapOf("state" to "manual_review"),
                ),
            )
            add(
                MetricPoint(
                    "arc_season_money_journal_records",
                    "Durable season money actions grouped by bounded state",
                    seasonMoneyJournal.records.toDouble(),
                    mapOf("state" to "all"),
                ),
            )
            seasonMoneyJournal.statusCounts.forEach { (state, count) ->
                add(
                    MetricPoint(
                        "arc_season_money_journal_records",
                        "Durable season money actions grouped by bounded state",
                        count.toDouble(),
                        mapOf("state" to state),
                    ),
                )
            }
            add(
                MetricPoint(
                    "arc_season_money_journal_burn_currency",
                    "Season burn amount with a pending or ambiguous provider outcome",
                    seasonMoneyJournal.pendingBurnMinor / 100.0,
                    mapOf("component" to "pending"),
                ),
            )
            add(
                MetricPoint(
                    "arc_season_money_journal_burn_currency",
                    "Season burn amount with a pending or ambiguous provider outcome",
                    seasonMoneyJournal.ambiguousBurnMinor / 100.0,
                    mapOf("component" to "ambiguous"),
                ),
            )
            seasonCatalog?.let { catalog ->
                add(
                    MetricPoint(
                        "arc_season_window_timestamp_seconds",
                        "Exact season window boundary as a Unix timestamp",
                        catalog.startsAt / 1_000.0,
                        mapOf("boundary" to "start", "season" to catalog.id),
                    ),
                )
                add(
                    MetricPoint(
                        "arc_season_window_timestamp_seconds",
                        "Exact season window boundary as a Unix timestamp",
                        catalog.endsAt / 1_000.0,
                        mapOf("boundary" to "end", "season" to catalog.id),
                    ),
                )
                val viewByContract = views.associateBy { it.id }
                catalog.projectStages.values.forEach { stage ->
                    val progress = seasonState?.project?.stages?.get(stage.id) ?: SeasonProjectStageProgress()
                    add(
                        MetricPoint(
                            "arc_season_project_cash_currency",
                            "Public project cash requirement and committed burn by stage",
                            stage.cashContributionMinor / 100.0,
                            mapOf("season" to catalog.id, "stage" to stage.id, "component" to "required"),
                        ),
                    )
                    add(
                        MetricPoint(
                            "arc_season_project_cash_currency",
                            "Public project cash requirement and committed burn by stage",
                            progress.cashMinor / 100.0,
                            mapOf("season" to catalog.id, "stage" to stage.id, "component" to "committed"),
                        ),
                    )
                    stage.requiredResources.forEach { (contractId, required) ->
                        add(
                            MetricPoint(
                                "arc_season_project_resource_quantity",
                                "Public project resource requirement and paid contract progress",
                                required.toDouble(),
                                mapOf(
                                    "season" to catalog.id,
                                    "stage" to stage.id,
                                    "contract" to contractId,
                                    "component" to "required",
                                ),
                            ),
                        )
                        add(
                            MetricPoint(
                                "arc_season_project_resource_quantity",
                                "Public project resource requirement and paid contract progress",
                                (viewByContract[contractId]?.acceptedQuantity ?: 0L).toDouble(),
                                mapOf(
                                    "season" to catalog.id,
                                    "stage" to stage.id,
                                    "contract" to contractId,
                                    "component" to "accepted",
                                ),
                            ),
                        )
                    }
                    stage.requiredBoundRewards.forEach { (item, required) ->
                        add(
                            MetricPoint(
                                "arc_season_project_bound_reward_quantity",
                                "Public project bound dungeon reward requirement and committed progress",
                                required.toDouble(),
                                mapOf("season" to catalog.id, "stage" to stage.id, "item" to item, "component" to "required"),
                            ),
                        )
                        add(
                            MetricPoint(
                                "arc_season_project_bound_reward_quantity",
                                "Public project bound dungeon reward requirement and committed progress",
                                (progress.boundRewards[item] ?: 0L).toDouble(),
                                mapOf("season" to catalog.id, "stage" to stage.id, "item" to item, "component" to "committed"),
                            ),
                        )
                    }
                    if (seasonState != null) {
                        val status = SeasonProjectEngine.status(catalog, seasonState.project, stage.id)
                        add(
                            MetricPoint(
                                "arc_season_project_stage_status",
                                "Current public project stage status",
                                1.0,
                                mapOf("season" to catalog.id, "stage" to stage.id, "status" to status.label),
                            ),
                        )
                    }
                }
                catalog.dungeonContracts.values.forEach { dungeon ->
                    add(
                        MetricPoint(
                            "arc_season_dungeon_money_currency",
                            "Configured dungeon entry burn and qualifying payout",
                            dungeon.entryBurnMinorPerPlayer / 100.0,
                            mapOf("season" to catalog.id, "contract" to dungeon.id, "component" to "entry_burn"),
                        ),
                    )
                    add(
                        MetricPoint(
                            "arc_season_dungeon_money_currency",
                            "Configured dungeon entry burn and qualifying payout",
                            dungeon.payoutMinorPerPlayer / 100.0,
                            mapOf("season" to catalog.id, "contract" to dungeon.id, "component" to "qualifying_payout"),
                        ),
                    )
                }
            }
            add(
                MetricPoint(
                    "arc_contracts_server_weekly_budget_currency",
                    "Configured weekly server-funded contracts envelope",
                    serverWeeklyBudgetMinor / 100.0,
                    emptyMap(),
                ),
            )
            add(point("arc_contract_journal_available", "Whether the durable contract submission journal is available", journal.available))
            add(
                MetricPoint(
                    "arc_contract_journal_records",
                    "Durable contract submission journal records by bounded state",
                    journal.totalRecords.toDouble(),
                    mapOf("state" to "all"),
                ),
            )
            add(
                MetricPoint(
                    "arc_contract_journal_capacity_remaining",
                    "Remaining bounded network record capacity before contract submissions must fail closed",
                    journal.capacityRemaining.toDouble(),
                    emptyMap(),
                ),
            )
            journal.stateCounts.forEach { (state, count) ->
                add(
                    MetricPoint(
                        "arc_contract_journal_records",
                        "Durable contract submission journal records by bounded state",
                        count.toDouble(),
                        mapOf("state" to state),
                    ),
                )
            }
            add(
                MetricPoint(
                    "arc_contract_journal_held_item_quantity",
                    "Item quantity confirmed removed and not yet committed or refunded",
                    journal.heldItemQuantity.toDouble(),
                    emptyMap(),
                ),
            )
            add(
                MetricPoint(
                    "arc_contract_journal_payout_currency",
                    "Contract payout exposure split into pending and ambiguous provider outcomes",
                    journal.pendingPayoutMinor / 100.0,
                    mapOf("component" to "pending"),
                ),
            )
            add(
                MetricPoint(
                    "arc_contract_journal_payout_currency",
                    "Contract payout exposure split into pending and ambiguous provider outcomes",
                    journal.ambiguousPayoutMinor / 100.0,
                    mapOf("component" to "ambiguous"),
                ),
            )
            add(
                MetricPoint(
                    "arc_contract_journal_manual_review",
                    "Contract submissions halted for manual reconciliation",
                    journal.manualReviewCount.toDouble(),
                    emptyMap(),
                ),
            )
            add(
                MetricPoint(
                    "arc_contract_journal_oldest_attention_age_seconds",
                    "Age of the oldest non-terminal contract submission journal record",
                    journal.oldestAttentionAgeSeconds.toDouble(),
                    emptyMap(),
                ),
            )
            add(
                point(
                    "arc_dungeon_contract_catalog_available",
                    "Whether a validated observe-only dungeon contract catalog is loaded",
                    dungeonObservation.catalogAvailable,
                ),
            )
            dungeonObservation.statsByContract.forEach { (contractId, observation) ->
                add(
                    MetricPoint(
                        "arc_dungeon_contract_active_runs",
                        "Native EliteMobs dungeon runs currently observed by configured contract",
                        (dungeonObservation.activeRunsByContract[contractId] ?: 0).toDouble(),
                        mapOf("contract" to contractId),
                    ),
                )
                add(
                    MetricPoint(
                        "arc_dungeon_contract_runs_total",
                        "Native EliteMobs dungeon run events observed by configured contract",
                        observation.startedRuns.toDouble(),
                        mapOf("contract" to contractId, "event" to "started"),
                    ),
                )
                add(
                    MetricPoint(
                        "arc_dungeon_contract_runs_total",
                        "Native EliteMobs dungeon run events observed by configured contract",
                        observation.nativeCompletedRuns.toDouble(),
                        mapOf("contract" to contractId, "event" to "native_completed"),
                    ),
                )
                add(
                    MetricPoint(
                        "arc_dungeon_contract_completion_duration_seconds_total",
                        "Total duration of native completions whose configured dungeon start was observed",
                        observation.nativeCompletionDurationSeconds.toDouble(),
                        mapOf("contract" to contractId),
                    ),
                )
                DungeonCompletionPlayerOutcome.entries.forEach { outcome ->
                    add(
                        MetricPoint(
                            "arc_dungeon_contract_completion_players_total",
                            "Players associated with native dungeon runs grouped by bounded completion outcome",
                            (observation.playerOutcomes[outcome] ?: 0L).toDouble(),
                            mapOf("contract" to contractId, "outcome" to outcome.label),
                        ),
                    )
                }
            }
            views.forEach { view ->
                add(quantity(view, "target", view.targetQuantity))
                add(quantity(view, "accepted", view.acceptedQuantity))
                add(quantity(view, "reserved", view.reservedQuantity))
                add(quantity(view, "remaining", view.remainingQuantity))
                add(money(view, "budget", view.budgetMinor))
                add(money(view, "spent", view.spentMinor))
                add(money(view, "reserved", view.reservedMinor))
                add(money(view, "remaining", (view.budgetMinor - view.spentMinor - view.reservedMinor).coerceAtLeast(0L)))
                add(
                    MetricPoint(
                        "arc_contract_contributors",
                        "Distinct contributors recorded inside a configured contract window",
                        view.contributors.toDouble(),
                        mapOf("contract" to view.id),
                    ),
                )
                add(
                    MetricPoint(
                        "arc_contract_status",
                        "Current bounded status of a configured contract window",
                        1.0,
                        mapOf("contract" to view.id, "status" to view.status),
                    ),
                )
                add(window(view, "start", view.windowStartsAt))
                add(window(view, "end", view.windowEndsAt))
            }
        }

    private fun point(name: String, description: String, value: Boolean): MetricPoint =
        MetricPoint(name, description, if (value) 1.0 else 0.0, emptyMap())

    private fun quantity(view: ResourceContractView, component: String, value: Long): MetricPoint =
        MetricPoint(
            "arc_contract_quantity",
            "Configured, accepted and remaining item quantity by bounded contract",
            value.toDouble(),
            mapOf("contract" to view.id, "component" to component),
        )

    private fun money(view: ResourceContractView, component: String, valueMinor: Long): MetricPoint =
        MetricPoint(
            "arc_contract_money_currency",
            "Configured, spent and remaining contract money by bounded contract",
            valueMinor / 100.0,
            mapOf("contract" to view.id, "component" to component),
        )

    private fun window(view: ResourceContractView, boundary: String, value: Long): MetricPoint =
        MetricPoint(
            "arc_contract_window_timestamp_seconds",
            "Contract window boundary as a Unix timestamp",
            value / 1_000.0,
            mapOf("contract" to view.id, "boundary" to boundary),
        )
}

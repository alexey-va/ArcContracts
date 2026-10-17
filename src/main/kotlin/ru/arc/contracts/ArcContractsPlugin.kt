package ru.arc.contracts

import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Bukkit
import ru.arc.config.ConfigManager
import ru.arc.contracts.api.ArcContractsHostConfiguration
import ru.arc.contracts.api.ArcContractsService
import ru.arc.core.PaperArcRuntime
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.whenCompleteSync
import ru.arc.logging.LoggingModuleConfig
import ru.arc.paper.api.ArcRedisService
import ru.arc.config.Config
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.logging.ArcLogging
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.metrics.PaperArcMetrics
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.runtime.PaperPluginRuntime

open class ArcContractsPlugin : JavaPlugin() {
    private var lifecycle: PaperPluginRuntime? = null
    private class Generation(val worker: BoundedAsyncExecutor) {
        @Volatile var redis: PaperRedisClient? = null
    }
    private var generation: Generation? = null
    private var predecessor = java.util.concurrent.CompletableFuture.completedFuture(Unit)
    @Volatile private var stopped = true
    private var preparing = false
    private val redisClient get() = generation?.redis
    internal fun drained(): java.util.concurrent.CompletableFuture<Unit> = predecessor.copy()
    private var trophyProtection: Listener? = null
    private var npcAccess: Listener? = null
    private var serviceProvider: ArcContractsService? = null
    private var serviceRegistered = false

    override fun onEnable() {
        PaperArcRuntime.installScheduling(this)
        stopped = false
        preparing = true
        predecessor = ContractsRuntime.predecessorDrain()
        val runtime = PaperPluginRuntime(this, "arc-contracts").also {
            lifecycle = it
            it.start("version" to pluginMeta.version)
        }
        runtime.registerContractsHealth(CONTRACTS_HEALTH_REPORT_PERIOD_TICKS) {
            val available = ContractsManager.isAvailable()
            RuntimeHealthContribution(
                state = if (preparing) RuntimeHealthState.STARTING else if (!ContractsManager.enabled() || available)
                    RuntimeHealthState.UP else RuntimeHealthState.DEGRADED,
                schemas = mapOf("season_catalog" to ContractsConfig.SEASON_CATALOG_SCHEMA_VERSION),
                dependencies = mapOf("shared_redis" to (redisClient != null), "contracts_state" to available),
            )
        }
        runtime.tasks.runTimer(20L, 20L) { if (!preparing) ContractsManager.publishMetrics() }
        // Previous terminal IO may still own global manager/repository bindings. A new generation
        // does not install or initialize anything until that exact drain finishes.
        predecessor.whenCompleteSync(runtime.tasks) { _, failure ->
            if (failure != null) { failPreparation(failure); return@whenCompleteSync }
            val owner = Generation(BoundedAsyncExecutor("ArcContracts-storage", 1, 256))
            generation = owner
            ContractsRuntime.installWorker(owner.worker)
            prepareGeneration(false)
        }
    }

    override fun onDisable() { closeRuntime() }

    internal fun reloadContracts() {
        check(Bukkit.isPrimaryThread()) { "ArcContracts reload must run on the primary server thread" }
        if (!stopped && !preparing) prepareGeneration(true)
    }

    /** IO and recovery finish before native listeners/menus and admission are published. */
    private fun prepareGeneration(reload: Boolean) {
        val runtime = requireNotNull(lifecycle)
        val owner = requireNotNull(generation)
        val worker = owner.worker
        preparing = true
        ContractsRuntime.suspendAdmission()
        ContractsManager.suspendAdmission()
        NpcContractsGui.shutdown()
        val token = runtime.tasks.token()
        val host = try { requireHostConfiguration() } catch (failure: Throwable) { failPreparation(failure); return }
        val readiness = try { host.ready() } catch (failure: Throwable) { failPreparation(failure); return }
        readiness.whenCompleteSync(runtime.tasks, token) { _, readinessFailure ->
            if (readinessFailure != null) { failPreparation(readinessFailure); return@whenCompleteSync }
            val captured = runCatching {
                HostSnapshot(host.legacyDataPath(), host.sharedRedisAvailable(), host.sharedRedisGeneration(),
                    server.servicesManager.load(ArcRedisService::class.java), metricsService())
            }.getOrElse { failPreparation(it); return@whenCompleteSync }
            val root = captured.root
            val redisAvailable = captured.redisAvailable
            val redisService = captured.redisService
            val metrics = captured.metrics
            worker.submit {
                if (reload) ConfigManager.reloadAll()
                mergeBundledDefaults(root)
                val logging = ConfigManager.ofModule(root, LoggingModuleConfig.RESOURCE)
                val menus = ContractsMenuSchema.load(root)
                val gui = ConfigManager.of(root, "guis/contracts.yml")
                ContractsModule.shutdown()
                owner.redis?.close()
                val redis = if (!redisAvailable) null else runCatching {
                    openContractsRedis(requireNotNull(redisService) { "ARC Redis service is unavailable" })
                }.onFailure { ArcLogging.warn("Shared Redis lease acquisition failed", it) }.getOrNull()
                owner.redis = redis
                PreparedGeneration(logging, menus, gui, redis)
            }.whenCompleteSync(runtime.tasks, token) { prepared, failure ->
                if (failure != null) { failPreparation(failure); return@whenCompleteSync }
                val candidate = requireNotNull(prepared)
                try {
                    ContractsRuntime.install(this, host, candidate.redis, metrics, candidate.logging)
                    if (reload) ContractsMenus.reload(candidate.menus) else ContractsMenus.initialize(this, root, candidate.menus)
                } catch (attachFailure: Throwable) { failPreparation(attachFailure); return@whenCompleteSync }
                worker.submit { ContractsModule.init(); ContractsManager.refreshReadModels() }.whenCompleteSync(runtime.tasks, token) { _, initFailure ->
                    if (initFailure != null) { failPreparation(initFailure); return@whenCompleteSync }
                    try {
                        if (!serviceRegistered) {
                            registerListeners()
                            val provider = ArcContractsServiceProvider(this)
                            serviceProvider = provider
                            server.servicesManager.register(ArcContractsService::class.java, provider, this, ServicePriority.Normal)
                            serviceRegistered = true
                        }
                        NpcContractsGui.start(candidate.gui)
                        ContractsRuntime.readyAdmission()
                        ContractsManager.publishMetrics()
                        preparing = false
                        runtime.ready("redis" to (redisClient != null), "contracts_available" to ContractsManager.isAvailable())
                    } catch (publishFailure: Throwable) { failPreparation(publishFailure) }
                }
            }
        }
    }

    private data class HostSnapshot(val root: java.nio.file.Path, val redisAvailable: Boolean, val generation: Long,
        val redisService: ArcRedisService?, val metrics: ArcMetricsService?)

    private data class PreparedGeneration(val logging: Config, val menus: PaperMenuConfiguration, val gui: Config, val redis: PaperRedisClient?)

    private fun failPreparation(failure: Throwable) {
        preparing = false
        ContractsRuntime.suspendAdmission()
        ContractsManager.suspendAdmission()
        lifecycle?.health?.markDown()
        lifecycle?.emitHealth()
        logger.log(java.util.logging.Level.SEVERE, "ArcContracts preparation failed closed", failure)
    }

    private fun requireHostConfiguration(): ArcContractsHostConfiguration =
        checkNotNull(server.servicesManager.load(ArcContractsHostConfiguration::class.java)) {
            "ARC host configuration service is not registered"
        }

    private fun metricsService(): ArcMetricsService? = PaperArcMetrics.find(this)

    private fun mergeBundledDefaults(root: java.nio.file.Path) {
        ConfigManager.of(root, "modules/contracts.yml").mergeMissingFromBundled("modules/contracts.yml")
        ConfigManager.of(root, "guis/contracts.yml").mergeMissingFromBundled("guis/contracts.yml")
        ConfigManager.of(root, "guis/menus.yml").mergeMissingFromBundled("guis/menus.yml")
    }

    private fun registerListeners() {
        val protection = SeasonTrophyProtectionListener()
        server.pluginManager.registerEvents(protection, this)
        trophyProtection = protection
        if (server.pluginManager.isPluginEnabled("Citizens")) {
            val access = ContractNpcAccessListener()
            server.pluginManager.registerEvents(access, this)
            npcAccess = access
        } else {
            ArcLogging.info("Citizens is unavailable; NPC desk access is disabled")
        }
    }

    private fun closeRuntime() {
        if (stopped && lifecycle == null && generation == null) return
        val owner = generation
        generation = null
        if (serviceRegistered) {
            serviceProvider?.let { server.servicesManager.unregister(ArcContractsService::class.java, it) }
            serviceRegistered = false
        }
        serviceProvider = null
        stopped = true
        ContractsRuntime.suspendAdmission()
        ContractsManager.suspendAdmission()
        NpcContractsGui.shutdown()
        listOfNotNull(npcAccess, trophyProtection).forEach { listener ->
            runCatching { HandlerList.unregisterAll(listener) }
                .onFailure { ArcLogging.warn("Contract listener cleanup failed", it) }
        }
        npcAccess = null
        trophyProtection = null
        runCatching { ContractsMenus.close() }
            .onFailure { ArcLogging.warn("Contract menu runtime cleanup failed", it) }
        runCatching { ContractsRuntime.close() }
            .onFailure { ArcLogging.warn("Contract runtime cleanup failed", it) }
        if (owner != null) {
            val actualDrain = owner.worker.closeAsync {
                try { ContractsModule.shutdown() }
                finally {
                    val client = owner.redis
                    owner.redis = null
                    client?.close()
                }
            }
            predecessor = actualDrain
            ContractsRuntime.recordDrain(actualDrain)
            actualDrain.whenComplete { _, failure ->
                if (failure != null) logger.log(java.util.logging.Level.SEVERE, "Contract terminal flush failed", failure)
            }
        }
        lifecycle?.close()
        lifecycle = null
    }
}

private const val CONTRACTS_HEALTH_REPORT_PERIOD_TICKS = 1_200L

/** Worker-only: retain before initialization/repository use; the owner closes after its actual terminal flush. */
internal fun openContractsRedis(service: ArcRedisService): PaperRedisClient {
    val client = PaperRedisClient.openPrimary(service, "ArcContracts")
    try {
        client.retainDuringShutdown()
        client.init()
        return client
    } catch (failure: Throwable) {
        runCatching { client.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

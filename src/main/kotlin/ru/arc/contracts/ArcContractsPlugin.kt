package ru.arc.contracts

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
import ru.arc.logging.ArcLogging
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.metrics.PaperArcMetrics
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.runtime.PaperPluginHotload
import java.util.concurrent.CompletableFuture

open class ArcContractsPlugin : JavaPlugin() {
    private var lifecycle: PaperPluginRuntime? = null
    private var worker: BoundedAsyncExecutor? = null
    private var closeCompletion = java.util.concurrent.CompletableFuture.completedFuture(Unit)
    @Volatile private var stopped = true
    private var preparing = false
    @Volatile private var redisClient: PaperRedisClient? = null
    internal fun drained(): java.util.concurrent.CompletableFuture<Unit> = closeCompletion.copy()
    private var trophyProtection: Listener? = null
    private var npcAccess: Listener? = null
    private var serviceProvider: ArcContractsService? = null
    private var serviceRegistered = false
    private val hotload = PaperPluginHotload(this, ::stopRuntime)

    override fun onEnable() {
        hotload.beginGeneration()
        hotload.register()
        PaperArcRuntime.installScheduling(this)
        stopped = false
        preparing = true
        check(closeCompletion.isDone && !closeCompletion.isCompletedExceptionally) {
            "Previous Contracts storage cleanup did not finish successfully"
        }
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
        val executor = BoundedAsyncExecutor("ArcContracts-storage", 1, 256)
        worker = executor
        ContractsRuntime.installWorker(executor)
        prepareRuntime(false)
    }

    override fun onDisable() {
        hotload.prepare()
        hotload.close()
    }

    internal fun reloadContracts() {
        check(Bukkit.isPrimaryThread()) { "ArcContracts reload must run on the primary server thread" }
        if (!stopped && !preparing) prepareRuntime(true)
    }

    /** Maintenance prepares configuration sequentially; recovery still owns its gameplay continuation. */
    private fun prepareRuntime(reload: Boolean) {
        val runtime = requireNotNull(lifecycle)
        val executor = requireNotNull(worker)
        preparing = true
        ContractsRuntime.suspendAdmission()
        ContractsManager.suspendAdmission()
        NpcContractsGui.shutdown()
        val token = runtime.tasks.token()
        val host = try { requireHostConfiguration() } catch (failure: Throwable) { failPreparation(failure); return }
        val readiness = try { host.ready() } catch (failure: Throwable) { failPreparation(failure); return }
        fun prepareReadyHost() {
            if (stopped || lifecycle !== runtime) return
            try {
                readiness.join() // Already complete; never wait for a pending host Main callback.
                val root = host.legacyDataPath()
                val redisAvailable = host.sharedRedisAvailable()
                val redisService = server.servicesManager.load(ArcRedisService::class.java)
                if (reload) ConfigManager.reloadAll()
                mergeBundledDefaults(root)
                val logging = ConfigManager.ofModule(root, LoggingModuleConfig.RESOURCE)
                val menus = ContractsMenuSchema.load(root)
                val gui = ConfigManager.of(root, "guis/contracts.yml")
                val redis = executor.submit {
                    ContractsModule.shutdown()
                    redisClient?.close()
                    redisClient = if (!redisAvailable) null else runCatching {
                        openContractsRedis(requireNotNull(redisService) { "ARC Redis service is unavailable" })
                    }.onFailure { ArcLogging.warn("Shared Redis lease acquisition failed", it) }.getOrNull()
                    redisClient
                }.get() // IO-only FIFO; maintenance may wait after admission is suspended.
                ContractsRuntime.install(this, host, redis, metricsService(), logging)
                if (reload) ContractsMenus.reload(menus) else ContractsMenus.initialize(this, root, menus)
                if (!serviceRegistered) {
                    registerListeners()
                    val provider = ArcContractsServiceProvider(this)
                    serviceProvider = provider
                    server.servicesManager.register(ArcContractsService::class.java, provider, this, ServicePriority.Normal)
                    serviceRegistered = true
                }
                // Recovery may use Main-thread gateways; waiting for this on Main would deadlock.
                executor.submit { ContractsModule.init(); ContractsManager.refreshReadModels() }.whenCompleteSync(runtime.tasks, token) { _, initFailure ->
                    if (initFailure != null) { failPreparation(initFailure); return@whenCompleteSync }
                    try {
                        NpcContractsGui.start(gui)
                        ContractsRuntime.readyAdmission()
                        ContractsManager.publishMetrics()
                        preparing = false
                        runtime.ready("redis" to (redisClient != null), "contracts_available" to ContractsManager.isAvailable())
                    } catch (publishFailure: Throwable) { failPreparation(publishFailure) }
                }
            } catch (failure: Throwable) { failPreparation(failure) }
        }
        if (readiness.isDone) prepareReadyHost()
        else readiness.whenCompleteSync(runtime.tasks, token) { _, _ -> prepareReadyHost() }
    }

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

    private fun stopRuntime(): Map<String, CompletableFuture<*>> {
        var failure: Throwable? = null
        fun attempt(close: () -> Unit) {
            try { close() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
                logger.log(java.util.logging.Level.SEVERE, "Contract runtime cleanup failed", problem)
            }
        }
        val executor = worker.also { worker = null }
        val client = redisClient.also { redisClient = null }
        stopped = true
        preparing = false
        attempt { ContractsRuntime.suspendAdmission() }
        attempt { ContractsManager.suspendAdmission() }
        attempt { server.servicesManager.unregisterAll(this) }
        serviceRegistered = false
        serviceProvider = null
        attempt { NpcContractsGui.shutdown() }
        npcAccess = null
        trophyProtection = null
        attempt { ContractsMenus.close() }
        attempt { lifecycle?.close() }
        lifecycle = null
        val storage = try {
            executor?.closeAsync {
                try { ContractsModule.shutdown() }
                finally { client?.close() }
            } ?: CompletableFuture.completedFuture(Unit)
        } catch (problem: Throwable) { CompletableFuture.failedFuture(problem) }
        // The existing maintenance drain contains detached storage work only.
        attempt { storage.join() }
        attempt { ContractsRuntime.close() }
        attempt { hotload.retireGameplayRegistrations() }
        attempt { ru.arc.logging.ArcLogging.close() }
        val native = failure?.let { CompletableFuture.failedFuture<Unit>(it) }
            ?: CompletableFuture.completedFuture(Unit)
        closeCompletion = CompletableFuture.allOf(storage, native).thenApply { Unit }
        return linkedMapOf("storage" to storage, "runtime" to native)
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

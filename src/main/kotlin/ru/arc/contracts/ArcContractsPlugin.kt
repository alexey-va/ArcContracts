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
import ru.arc.logging.ArcLogging
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.metrics.PaperArcMetrics
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.runtime.PaperPluginRuntime

class ArcContractsPlugin : JavaPlugin() {
    private var lifecycle: PaperPluginRuntime? = null
    private var redisClient: PaperRedisClient? = null
    private var hostConfiguration: ArcContractsHostConfiguration? = null
    private var redisGeneration = Long.MIN_VALUE
    private var trophyProtection: Listener? = null
    private var npcAccess: Listener? = null
    private var serviceProvider: ArcContractsService? = null
    private var serviceRegistered = false

    override fun onEnable() {
        PaperArcRuntime.installScheduling(this)
        try {
            val host = requireHostConfiguration()
            mergeBundledDefaults(host)
            val runtime = PaperPluginRuntime(this, "arc-contracts").also {
                lifecycle = it
                it.start("version" to pluginMeta.version, "server" to host.serverName())
            }
            val redis = openRedis(host)
            redisClient = redis
            redisGeneration = host.sharedRedisGeneration()
            hostConfiguration = host
            ContractsRuntime.install(this, host, redis, metricsService())
            ContractsMenus.initialize(this, host.legacyDataPath())
            registerListeners()

            ContractsModule.init()
            val provider = ArcContractsServiceProvider(this)
            serviceProvider = provider
            server.servicesManager.register(ArcContractsService::class.java, provider, this, ServicePriority.Normal)
            serviceRegistered = true

            runtime.registerContractsHealth(CONTRACTS_HEALTH_REPORT_PERIOD_TICKS) {
                val redisAvailable = hostConfiguration?.sharedRedisAvailable() == true && redisClient != null
                val contractsAvailable = ContractsManager.isAvailable()
                RuntimeHealthContribution(
                    state = when {
                        !ContractsConfig.load().enabled || ContractsManager.mode() == ContractsMode.DISABLED -> RuntimeHealthState.UP
                        redisAvailable && contractsAvailable -> RuntimeHealthState.UP
                        else -> RuntimeHealthState.DEGRADED
                    },
                    schemas = mapOf("season_catalog" to ContractsConfig.SEASON_CATALOG_SCHEMA_VERSION),
                    dependencies = mapOf("shared_redis" to redisAvailable, "contracts_state" to contractsAvailable),
                )
            }
            runtime.ready("redis" to (redis != null), "contracts_available" to ContractsManager.isAvailable())
        } catch (failure: Throwable) {
            ArcLogging.error("ArcContracts initialization failed", failure)
            closeRuntime()
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        closeRuntime()
    }

    internal fun reloadContracts() {
        check(Bukkit.isPrimaryThread()) { "ArcContracts reload must run on the primary server thread" }
        val host = requireHostConfiguration()
        ConfigManager.reloadAll()
        mergeBundledDefaults(host)
        ContractsRuntime.replaceHost(host)
        ContractsMenus.reload()
        ContractsRuntime.refreshMetrics(metricsService())

        val nextGeneration = host.sharedRedisGeneration()
        val redisBindingChanged =
            nextGeneration != redisGeneration || host.sharedRedisAvailable() != (redisClient != null)
        if (!redisBindingChanged) {
            ContractsModule.reload()
            hostConfiguration = host
            lifecycle?.reloadContractsLifecycle(CONTRACTS_HEALTH_REPORT_PERIOD_TICKS)
            return
        }

        ContractsModule.shutdown()
        val previousRedis = redisClient
        redisClient = null
        ContractsRuntime.replaceRedis(null)
        runCatching { previousRedis?.close() }.onFailure {
            ContractsRuntime.logWarning("Previous shared Redis lease could not be closed", it)
        }
        val replacement = openRedis(host)
        redisClient = replacement
        ContractsRuntime.replaceRedis(replacement)
        redisGeneration = nextGeneration
        hostConfiguration = host
        ContractsModule.init()
        lifecycle?.reloadContractsLifecycle(CONTRACTS_HEALTH_REPORT_PERIOD_TICKS)
    }

    private fun requireHostConfiguration(): ArcContractsHostConfiguration =
        checkNotNull(server.servicesManager.load(ArcContractsHostConfiguration::class.java)) {
            "ARC host configuration service is not registered"
        }

    private fun metricsService(): ArcMetricsService? = PaperArcMetrics.find(this)

    private fun openRedis(host: ArcContractsHostConfiguration): PaperRedisClient? {
        if (!host.sharedRedisAvailable()) return null
        return runCatching { PaperRedisClient.openPrimary(this).also { it.init() } }
            .onFailure { ArcLogging.warn("Shared Redis lease acquisition failed", it) }
            .getOrNull()
    }

    private fun mergeBundledDefaults(host: ArcContractsHostConfiguration) {
        val root = host.legacyDataPath()
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
        if (serviceRegistered) {
            server.servicesManager.unregister(ArcContractsService::class.java, serviceProvider)
            serviceRegistered = false
        }
        serviceProvider = null
        runCatching { ContractsModule.shutdown() }
            .onFailure { ArcLogging.warn("Contract module shutdown failed", it) }
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
        runCatching { redisClient?.close() }
            .onFailure { ArcLogging.warn("Shared Redis lease cleanup failed", it) }
        redisClient = null
        hostConfiguration = null
        lifecycle?.close()
        lifecycle = null
    }
}

private const val CONTRACTS_HEALTH_REPORT_PERIOD_TICKS = 1_200L

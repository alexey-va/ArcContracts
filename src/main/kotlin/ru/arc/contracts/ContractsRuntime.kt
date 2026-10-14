package ru.arc.contracts

import com.google.gson.Gson
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.contracts.api.ArcContractsHostConfiguration
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.LoggingModuleConfig
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.config.ConfigManager
import ru.arc.paper.api.ArcMetricsScope
import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.api.ArcTelemetryProvider
import ru.arc.metrics.core.MetricPoint
import ru.arc.paper.metrics.PaperArcMetrics
import ru.arc.paper.redis.PaperRedisClient
import java.util.UUID

/** Plugin-local bindings supplied by the owning Paper runtime. */
internal object ContractsRuntime {
    lateinit var plugin: JavaPlugin
        private set
    lateinit var host: ArcContractsHostConfiguration
        private set
    val gson: Gson = Gson()
    var redis: PaperRedisClient? = null
        private set
    var metrics: ArcMetricsScope? = null
        private set
    private var metricsService: ArcMetricsService? = null

    val legacyDataPath get() = host.legacyDataPath()
    val serverName get() = host.serverName()

    fun install(
        plugin: JavaPlugin,
        host: ArcContractsHostConfiguration,
        redis: PaperRedisClient?,
        metricsService: ArcMetricsService?,
    ) {
        this.plugin = plugin
        this.host = host
        this.redis = redis
        this.metricsService = metricsService
        metrics = metricsService?.open("arc-contracts")
        val logging = ConfigManager.ofModule(host.legacyDataPath(), LoggingModuleConfig.RESOURCE)
        val loggingConfig = LoggingModuleConfig(logging)
        ArcLogging.install(
            platform = PaperLoggingPlatform("ArcContracts", "ArcContracts"),
            configSource = object : LoggingConfigSource {
                override fun config() = logging

                override fun configVersion(): Int = ConfigManager.getVersion()
            },
        )
        ArcLogging.setLogLevel(loggingConfig.level)
    }

    fun replaceRedis(redis: PaperRedisClient?) {
        this.redis = redis
    }

    fun replaceHost(host: ArcContractsHostConfiguration) {
        this.host = host
    }

    /** Clears bounded owner meters so removed contract-ID series do not survive a config reload. */
    fun refreshMetrics(nextService: ArcMetricsService?) {
        metrics?.close()
        metricsService = nextService
        metrics = metricsService?.open("arc-contracts")
    }

    fun telemetry(): ArcTelemetryProvider? =
        plugin.server.servicesManager.load(ArcTelemetryProvider::class.java)

    fun publishMetrics(points: Collection<MetricPoint>) {
        val scope = metrics ?: return
        points.forEach { point ->
            scope.gauge(point.name, point.description, point.tags).set(point.value)
        }
    }

    fun recordDungeonStarted(playerId: UUID, world: String, runId: String, participants: Int, instanceWorld: String?) {
        telemetry()?.recordActivity(
            playerId,
            "dungeons",
            "dungeon.started",
            world,
            runId,
            mapOf("participants" to participants.toString()) +
                (instanceWorld?.let { mapOf("instanceWorld" to it) } ?: emptyMap()),
        )
    }

    fun recordDungeonFinished(playerId: UUID, world: String, runId: String, outcome: String, instanceWorld: String?) {
        telemetry()?.recordActivity(
            playerId,
            "dungeons",
            "dungeon.finished",
            world,
            runId,
            mapOf("outcome" to outcome) +
                (instanceWorld?.let { mapOf("instanceWorld" to it) } ?: emptyMap()),
        )
    }

    fun recordDungeonCompleted(playerId: UUID, runId: String) {
        val provider = telemetry() ?: return
        provider.recordActivity(
            playerId,
            "arc",
            "mechanic.outcome",
            "dungeon_complete",
            null,
            mapOf("entry" to "gameplay", "feature" to "dungeons"),
        )
        provider.record(
            playerId = playerId,
            source = "arccontracts",
            feature = "dungeons",
            outcome = "dungeon_complete",
            action = null,
            operationId = runId,
        )
    }

    fun logInfo(message: String) {
        ArcLogging.info(message)
    }

    fun info(pattern: String, vararg arguments: Any?) {
        ArcLogging.info(pattern, *arguments)
    }

    fun logWarning(message: String, failure: Throwable? = null) {
        if (failure == null) ArcLogging.warn(message) else ArcLogging.warn(message, failure)
    }

    fun logSevere(message: String, failure: Throwable? = null) {
        if (failure == null) ArcLogging.error(message) else ArcLogging.error(message, failure)
    }

    fun logError(pattern: String, vararg arguments: Any?) {
        ArcLogging.error(pattern, *arguments)
    }

    fun close() {
        metrics?.close()
        metrics = null
        metricsService = null
        redis = null
    }
}

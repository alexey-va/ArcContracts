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
    private val metricSnapshotPublisher = ContractMetricSnapshotPublisher()

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
        metricSnapshotPublisher.attach(metricsService)
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
        metricSnapshotPublisher.attach(nextService)
    }

    fun telemetry(): ArcTelemetryProvider? =
        plugin.server.servicesManager.load(ArcTelemetryProvider::class.java)

    fun publishMetrics(snapshot: () -> Collection<MetricPoint>) = metricSnapshotPublisher.publish(snapshot)

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
        metricSnapshotPublisher.close()
        redis = null
    }
}

/**
 * Serializes snapshot calculation, scope replacement, reload and close. The
 * maximum configured snapshot is bounded at 1202; the 2048 budget and
 * its arithmetic are documented in README. Changed key sets close the
 * previous scope, removing stale status and deleted-contract series before
 * publishing the new snapshot.
 */
internal class ContractMetricSnapshotPublisher {
    private val lock = Any()
    private var service: ArcMetricsService? = null
    private var scope: ArcMetricsScope? = null
    private var lastKeys: Set<MetricKey>? = null

    fun attach(nextService: ArcMetricsService?) = synchronized(lock) {
        scope?.close()
        service = nextService
        scope = nextService?.open(OWNER_ID, MAX_METRICS)
        lastKeys = null
    }

    fun publish(snapshot: () -> Collection<MetricPoint>) = synchronized(lock) {
        val points = snapshot()
        val keys = points.mapTo(mutableSetOf()) { point ->
            MetricKey(point.name, point.tags.toSortedMap().toList())
        }
        if (lastKeys != null && lastKeys != keys) {
            scope?.close()
            scope = service?.open(OWNER_ID, MAX_METRICS)
        }
        lastKeys = keys
        val currentScope = scope ?: return@synchronized
        points.forEach { point ->
            currentScope.gauge(point.name, point.description, point.tags).set(point.value)
        }
    }

    fun close() = synchronized(lock) {
        scope?.close()
        scope = null
        service = null
        lastKeys = null
    }

    private data class MetricKey(val name: String, val tags: List<Pair<String, String>>)

    private companion object {
        const val OWNER_ID = "arc-contracts"
        const val MAX_METRICS = 2048
    }
}

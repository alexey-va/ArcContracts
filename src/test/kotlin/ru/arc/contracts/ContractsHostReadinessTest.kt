package ru.arc.contracts

import net.kyori.adventure.text.Component
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.arc.contracts.api.ArcContractsHostConfiguration
import ru.arc.contracts.api.ArcContractsService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class ContractsHostReadinessTest {
    @Test
    fun `pending host never reads cold configuration and completion after disable cannot attach`() {
        MockBukkitTestRuntime.open().use { paper ->
            val providerPlugin = paper.createSimplePlugin("ARC")
            val ready = CompletableFuture<Void>()
            val reads = AtomicInteger()
            val host = object : ArcContractsHostConfiguration {
                override fun ready(): CompletableFuture<Void> = ready
                override fun legacyDataPath(): Path { reads.incrementAndGet(); error("Host configuration is not ready") }
                override fun serverName(): String = error("Host configuration is not ready")
                override fun sharedRedisAvailable(): Boolean = error("Host configuration is not ready")
                override fun sharedRedisGeneration(): Long = error("Host configuration is not ready")
                override fun commandText(key: String, fallback: String): Component = Component.text(fallback)
            }
            paper.server.servicesManager.register(ArcContractsHostConfiguration::class.java, host, providerPlugin, ServicePriority.Normal)
            val plugin = paper.loadPlugin<ArcContractsPlugin>()
            assertFalse(ContractsManager.isAvailable())
            assertEquals(0, reads.get())
            assertNull(paper.server.servicesManager.load(ArcContractsService::class.java))
            paper.server.pluginManager.disablePlugin(plugin)
            plugin.drained().get(5, java.util.concurrent.TimeUnit.SECONDS)
            ready.complete(null)
            paper.performTicks(2)
            assertEquals(0, reads.get())
            assertFalse(ContractsManager.isAvailable())
            assertNull(paper.server.servicesManager.load(ArcContractsService::class.java))
        }
    }
}

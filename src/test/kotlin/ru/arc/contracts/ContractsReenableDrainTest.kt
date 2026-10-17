package ru.arc.contracts

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import net.kyori.adventure.text.Component
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.contracts.api.ArcContractsHostConfiguration
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ContractsReenableDrainTest {
    @Test
    fun `reenable waits actual old drain and repeated disable preserves that predecessor`() {
        MockBukkitTestRuntime.open().use { paper ->
            val providerPlugin = paper.createSimplePlugin("ARC")
            val calls = AtomicInteger()
            val hostReady = CompletableFuture<Void>()
            val host = object : ArcContractsHostConfiguration {
                override fun ready(): CompletableFuture<Void> { calls.incrementAndGet(); return hostReady }
                override fun legacyDataPath(): Path = error("No configuration before host readiness")
                override fun serverName(): String = error("No configuration before host readiness")
                override fun sharedRedisAvailable(): Boolean = error("No configuration before host readiness")
                override fun sharedRedisGeneration(): Long = error("No configuration before host readiness")
                override fun commandText(key: String, fallback: String): Component = Component.text(fallback)
            }
            paper.server.servicesManager.register(ArcContractsHostConfiguration::class.java, host, providerPlugin, ServicePriority.Normal)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            mockkObject(ContractsModule)
            every { ContractsModule.shutdown() } answers { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            var plugin: ArcContractsPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcContractsPlugin>()
                awaitMain(paper) { calls.get() == 1 }
                paper.server.pluginManager.disablePlugin(plugin)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(plugin.drained().isDone)
                plugin.onDisable() // Idempotent: must not replace the real predecessor with a completed future.
                assertFalse(plugin.drained().isDone)
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(2)
                assertEquals(1, calls.get())
                assertFalse(ContractsManager.isAvailable())
                release.countDown()
                awaitMain(paper) { calls.get() == 2 }
                assertTrue(plugin.isEnabled)
                hostReady.completeExceptionally(IllegalStateException("Host failed closed"))
                paper.performTicks(2)
                plugin.reloadContracts() // A fresh enabled generation must have stopped=false.
                awaitMain(paper) { calls.get() == 3 }
            } finally {
                release.countDown()
                plugin?.let { active ->
                    paper.server.pluginManager.disablePlugin(active)
                    active.drained().get(5, TimeUnit.SECONDS)
                }
                unmockkObject(ContractsModule)
            }
        }
    }
}

private fun awaitMain(paper: MockBukkitTestRuntime, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition() && System.nanoTime() < deadline) { paper.performTicks(1); Thread.sleep(1) }
    assertTrue(condition())
}

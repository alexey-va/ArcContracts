package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.Runs
import io.mockk.unmockkConstructor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import ru.arc.contracts.api.ArcContractsHostConfiguration
import ru.arc.logging.ArcLogging
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.repository.CachedRepository
import ru.arc.repository.Entity
import ru.arc.repository.RepoConfig
import ru.arc.repository.RepoResult
import ru.arc.repository.Storage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class ContractsShutdownFailureTest : FreeSpec({
    "selection terminal save failure is propagated after the remaining repositories release their listeners" {
        withContractsManager { channels, redis ->
            val terminal = IllegalStateException("selection terminal save failed")
            val journal = IllegalStateException("journal close failed")
            val dirty = failedTerminalRepository(terminal)
            every { anyConstructed<ContractSelectionRuntime>().start() } just Runs
            every { anyConstructed<ContractSelectionRuntime>().close() } answers {
                callOriginal()
                runBlocking { dirty.shutdown() }
            }
            every { redis.unregisterChannel("arc.contract-submissions.v1.update", any()) } answers {
                channels.remove(firstArg<String>())
                throw journal
            }
            ContractsManager.init()

            val failure = shouldThrow<IllegalStateException> { ContractsManager.shutdown() }
            failure shouldBe terminal
            failure.suppressed.toList() shouldBe listOf(journal)
            channels.isEmpty() shouldBe true
            CachedRepository.allStats().none { it.repoId in setOf("contracts", "contract-submission-journal", "contract-selection") } shouldBe true
        }
    }

    "failed startup selection cleanup remains observable in terminal shutdown" {
        withContractsManager { channels, _ ->
            val startup = IllegalStateException("selection start failed")
            val terminal = IllegalStateException("discarded selection terminal save failed")
            val dirty = failedTerminalRepository(terminal)
            every { anyConstructed<ContractSelectionRuntime>().start() } throws startup
            every { anyConstructed<ContractSelectionRuntime>().close() } answers {
                callOriginal()
                runBlocking { dirty.shutdown() }
            }

            shouldThrow<IllegalStateException> { ContractsManager.init() } shouldBe startup
            channels.isEmpty() shouldBe true
            shouldThrow<IllegalStateException> { ContractsManager.shutdown() } shouldBe terminal
            channels.isEmpty() shouldBe true
        }
    }
})

private data class CleanupRecord(val key: String) : Entity {
    override fun id(): String = key
}

private fun failedTerminalRepository(failure: Throwable): CachedRepository<CleanupRecord> {
    val storage = mockk<Storage<CleanupRecord>>()
    coEvery { storage.saveMany(any()) } returns RepoResult.error("terminal write failed", failure)
    return CachedRepository(
        config = RepoConfig.builder<CleanupRecord>("contract-selection-terminal-test").maxRetries(1).build(),
        storage = storage,
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ).apply { markDirty(CleanupRecord("pending")) }
}

private fun withContractsManager(block: (MutableSet<String>, PaperRedisClient) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val root = Files.createTempDirectory("contracts-shutdown-failure")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(root.resolve("modules/contracts.yml"), "enabled: true\nmode: observe\nleader-server: test-server\n")
        val channels = linkedSetOf<String>()
        val redis = mockk<PaperRedisClient>(relaxed = true)
        every { redis.loadMap(any()) } returns CompletableFuture.completedFuture(emptyMap())
        every { redis.registerChannelUnique(any(), any()) } answers { channels += firstArg<String>(); Unit }
        every { redis.unregisterChannel(any(), any()) } answers { channels -= firstArg<String>(); Unit }
        val host = object : ArcContractsHostConfiguration {
            override fun ready(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
            override fun legacyDataPath(): Path = root
            override fun serverName(): String = "test-server"
            override fun sharedRedisAvailable(): Boolean = true
            override fun sharedRedisGeneration(): Long = 1L
            override fun commandText(key: String, fallback: String): Component = Component.text(fallback)
        }
        ContractsRuntime.install(paper.createSimplePlugin("ContractsShutdownTest"), host, redis, null, ru.arc.config.ConfigManager.empty())
        mockkConstructor(ContractSelectionRuntime::class)
        try {
            block(channels, redis)
        } finally {
            runCatching { ContractsManager.shutdown() }
            ContractsRuntime.close()
            ArcLogging.close()
            unmockkConstructor(ContractSelectionRuntime::class)
            root.toFile().deleteRecursively()
        }
    }
}

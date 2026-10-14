package ru.arc.contracts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.arc.core.TestTaskScheduler
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

class ContractsHealthLifecycleTest {
    @Test
    fun `registered probe is owned once and periodic reports resume after reload`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ContractsHealthLifecycleTest")
            val timerExecutions = AtomicInteger()
            val scheduler = TestTaskScheduler(Executor { task ->
                timerExecutions.incrementAndGet()
                task.run()
            })
            val runtime = PaperPluginRuntime(
                plugin = plugin,
                component = "contracts",
                scheduler = scheduler,
                eventSink = {},
            )

            try {
                runtime.start()
                runtime.registerContractsHealth(reportPeriodTicks = 2) {
                    RuntimeHealthContribution(schemas = mapOf("season_catalog" to 1))
                }
                assertEquals(1, runtime.ownedResourceCount())
                runtime.ready()
                assertEquals(RuntimeHealthState.UP, runtime.snapshot().state)
                assertEquals(listOf("contracts"), runtime.snapshot().probes.map { it.id })
                assertEquals(mapOf("contracts.season_catalog" to 1), runtime.snapshot().schemas)

                scheduler.tick(2)
                assertEquals(1, timerExecutions.get())

                runtime.reloadContractsLifecycle(reportPeriodTicks = 2)
                assertEquals(1, scheduler.timerCount())

                scheduler.tick(1)
                assertEquals(1, timerExecutions.get())
                scheduler.tick(1)
                assertEquals(2, timerExecutions.get())
            } finally {
                runtime.close()
            }
            assertEquals(0, scheduler.timerCount())
        }
    }
}

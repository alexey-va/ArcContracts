package ru.arc.contracts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.arc.core.TestTaskScheduler
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.testing.MockBukkitTestRuntime

class ContractsHealthLifecycleTest {
    @Test
    fun `registered probe is owned once and periodic reports resume after reload`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ContractsHealthLifecycleTest")
            val scheduler = TestTaskScheduler()
            val reports = mutableListOf<ru.arc.observability.RuntimeHealthSnapshot>()
            val runtime = PaperPluginRuntime(
                plugin = plugin,
                component = "contracts",
                scheduler = scheduler,
                eventSink = {},
                healthSink = reports::add,
            )

            try {
                runtime.start()
                runtime.registerContractsHealth(reportPeriodTicks = 2) {
                    RuntimeHealthContribution(schemas = mapOf("season_catalog" to 1))
                }
                assertEquals(1, runtime.ownedResourceCount())
                runtime.ready()
                reports.clear()

                scheduler.tick(2)
                assertEquals(1, reports.size)

                runtime.reloadContractsLifecycle(reportPeriodTicks = 2)
                assertEquals(2, reports.size)
                assertEquals(1, scheduler.timerCount())

                scheduler.tick(1)
                assertEquals(2, reports.size)
                scheduler.tick(1)
                assertEquals(3, reports.size)
            } finally {
                runtime.close()
            }
        }
    }
}

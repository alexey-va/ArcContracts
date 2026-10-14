package ru.arc.contracts

import ru.arc.observability.RuntimeHealthContribution
import ru.arc.paper.runtime.PaperPluginRuntime

/** Register the runtime-owned health probe and start periodic reporting. */
internal fun PaperPluginRuntime.registerContractsHealth(
    reportPeriodTicks: Long,
    probe: () -> RuntimeHealthContribution,
) {
    this.registerHealth("contracts", probe)
    this.reportHealthEvery(reportPeriodTicks)
}

/** A runtime reload cancels periodic tasks, so resume health reports afterward. */
internal fun PaperPluginRuntime.reloadContractsLifecycle(reportPeriodTicks: Long) {
    this.reload()
    this.reportHealthEvery(reportPeriodTicks)
    this.emitHealth()
}

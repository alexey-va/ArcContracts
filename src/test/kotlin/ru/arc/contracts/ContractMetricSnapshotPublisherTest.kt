package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import ru.arc.metrics.core.MetricPoint
import ru.arc.paper.api.ArcMetricCounter
import ru.arc.paper.api.ArcMetricGauge
import ru.arc.paper.api.ArcMetricTimer
import ru.arc.paper.api.ArcMetricsScope
import ru.arc.paper.api.ArcMetricsService
import java.util.concurrent.TimeUnit

class ContractMetricSnapshotPublisherTest : StringSpec({
    "publishes more than the default budget and replaces stale snapshot series" {
        val publisher = ContractMetricSnapshotPublisher()
        val service = RecordingMetricsService()
        val views = (0 until 30).map { metricView("metric_contract_$it") }
        val points = metricPoints(views)
        val viewPoints = points.filter { it.tags["contract"] in views.map(ResourceContractView::id) }
        viewPoints.size shouldBe 360

        publisher.attach(service)
        publisher.publish { points }
        service.requestedBudgets shouldBe listOf("arc-contracts" to 2048)
        service.activeContractPoints(views) shouldBe viewPoints.size

        val initialScope = service.scopes.single()
        val contributors = service.activeGauge("arc_contract_contributors", mapOf("contract" to views.first().id))
        publisher.publish { metricPoints(views.mapIndexed { index, view -> if (index == 0) view.copy(contributors = 4) else view }) }
        service.scopes.single() shouldBe initialScope
        service.activeGauge("arc_contract_contributors", mapOf("contract" to views.first().id)) shouldBe contributors
        contributors.value() shouldBeExactly 4.0

        val statusChanged = views.mapIndexed { index, view -> if (index == 0) view.copy(status = "paused") else view }
        publisher.publish { metricPoints(statusChanged) }
        initialScope.closed shouldBe true
        service.activeContractPoints(statusChanged) shouldBe 360
        service.hasActiveGauge("arc_contract_status", mapOf("contract" to views.first().id, "status" to "open")) shouldBe false
        service.hasActiveGauge("arc_contract_status", mapOf("contract" to views.first().id, "status" to "paused")) shouldBe true

        val removedContract = statusChanged.last().id
        val remaining = statusChanged.dropLast(1)
        publisher.publish { metricPoints(remaining) }
        service.activeContractPoints(remaining) shouldBe 348
        service.hasAnyActiveContractGauge(removedContract) shouldBe false

        val reload = RecordingMetricsService()
        publisher.attach(reload)
        publisher.publish { metricPoints(remaining) }
        reload.requestedBudgets shouldBe listOf("arc-contracts" to 2048)
        reload.activeContractPoints(remaining) shouldBe 348
        publisher.close()
        reload.activeGaugeCount() shouldBe 0
    }
})

private fun metricPoints(views: List<ResourceContractView>): List<MetricPoint> =
    ContractsMetrics.points(
        enabled = true,
        available = true,
        mode = ContractsMode.OBSERVE,
        localLeader = true,
        submissionRuntimeReady = false,
        submissionsEnabled = false,
        seasonMutationRuntimeReady = false,
        serverWeeklyBudgetMinor = 0L,
        views = views,
        journal = ContractSubmissionJournalSummary.unavailable(),
        seasonCatalog = null,
        seasonState = null,
        seasonMoneyJournal = SeasonMoneyJournalAudit.unavailable(),
        seasonTrophyJournal = SeasonTrophyJournalSummary.unavailable(),
        seasonDungeonRewardJournal = SeasonDungeonRewardJournalSummary.unavailable(),
        dungeonObservation = DungeonContractObservationSnapshot(false, emptyMap(), emptyMap()),
    )

private fun metricView(id: String): ResourceContractView =
    ResourceContractView(
        id = id,
        displayName = id,
        itemKey = "minecraft:stone",
        funding = "server_envelope",
        status = "open",
        windowStartsAt = 1_000L,
        windowEndsAt = 2_000L,
        payoutMinorPerUnit = 100L,
        budgetMinor = 10_000L,
        spentMinor = 0L,
        reservedMinor = 0L,
        targetQuantity = 100L,
        acceptedQuantity = 0L,
        reservedQuantity = 0L,
        remainingQuantity = 100L,
        contributors = 1,
    )

private class RecordingMetricsService : ArcMetricsService {
    val requestedBudgets = mutableListOf<Pair<String, Int>>()
    val scopes = mutableListOf<RecordingMetricsScope>()

    override fun open(ownerId: String): ArcMetricsScope = open(ownerId, 128)

    override fun open(ownerId: String, maxMetrics: Int): ArcMetricsScope {
        requestedBudgets += ownerId to maxMetrics
        return RecordingMetricsScope().also(scopes::add)
    }

    fun activeGaugeCount(): Int = scopes.filterNot { it.closed }.sumOf { it.gauges.size }

    fun activeContractPoints(views: List<ResourceContractView>): Int {
        val ids = views.mapTo(mutableSetOf(), ResourceContractView::id)
        return scopes.filterNot { it.closed }.sumOf { scope ->
            scope.gauges.keys.count { it.tags["contract"] in ids }
        }
    }

    fun activeGauge(name: String, tags: Map<String, String>): RecordingGauge =
        scopes.filterNot { it.closed }.flatMap { it.gauges.entries }
            .single { it.key.name == name && it.key.tags == tags.toSortedMap() }.value

    fun hasActiveGauge(name: String, tags: Map<String, String>): Boolean =
        scopes.filterNot { it.closed }.any { scope ->
            scope.gauges.keys.any { it.name == name && it.tags == tags.toSortedMap() }
        }

    fun hasAnyActiveContractGauge(contractId: String): Boolean =
        scopes.filterNot { it.closed }.any { scope ->
            scope.gauges.keys.any { it.tags["contract"] == contractId }
        }
}

private class RecordingMetricsScope : ArcMetricsScope {
    val gauges = linkedMapOf<RecordingMetricKey, RecordingGauge>()
    var closed = false
        private set

    override fun counter(name: String, description: String, tags: Map<String, String>): ArcMetricCounter =
        error("Contract metric snapshots publish gauges only")

    override fun gauge(name: String, description: String, tags: Map<String, String>): ArcMetricGauge =
        gauges.getOrPut(RecordingMetricKey(name, tags.toSortedMap())) { RecordingGauge() }

    override fun timer(name: String, description: String, tags: Map<String, String>): ArcMetricTimer =
        error("Contract metric snapshots publish gauges only")

    override fun close() {
        closed = true
        gauges.clear()
    }
}

private data class RecordingMetricKey(val name: String, val tags: Map<String, String>)

private class RecordingGauge : ArcMetricGauge {
    private var currentValue = 0.0

    override fun set(value: Double) {
        currentValue = value
    }

    override fun value(): Double = currentValue
}

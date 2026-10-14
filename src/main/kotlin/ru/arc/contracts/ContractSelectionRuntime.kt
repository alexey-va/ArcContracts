package ru.arc.contracts

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ru.arc.core.ScheduledTask
import ru.arc.core.repeatingAsync
import ru.arc.redis.RedisOperations
import ru.arc.repository.CachedRepository
import kotlin.time.Duration.Companion.seconds

/** One configured leader publishes durable weekly plans; followers only consume them.
 * No plan for this week means no selected orders, never a fallback to the whole pool.
 */
class ContractSelectionRuntime(
    redis: RedisOperations,
    gson: Gson,
    private val config: () -> ContractsConfig,
    private val leader: () -> Boolean,
    private val records: () -> List<ResourceContractRecord>,
    private val reservedStateIds: () -> Set<String>,
    private val enableDynamicPricing: suspend (ContractSelectionPlan, List<ResourceContractDefinition>) -> ContractSelectionPlan,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repository: CachedRepository<ContractSelectionPlan> = try { ru.arc.repository.redisRepo(
        redis = redis,
        gson = gson,
        id = "contract-selection", storageKey = "arc.contract-selection.v1",
        updateChannel = "arc.contract-selection.v1.update", scope = scope,
    ) { loadAllOnStart(true); enableCleanup(false); saveInterval(1.seconds) }
    } catch (failure: Throwable) { scope.cancel(); throw failure }
    private val publisher = ContractSelectionPublisher(object : ContractSelectionPersistence {
        override fun find(week: Long) = repository.getNow(week.toString())
        override suspend fun commit(plan: ContractSelectionPlan) {
            repository.markDirty(plan)
            repository.saveDirty().getOrThrow()
        }
    }, enableDynamicPricing)
    private var task: ScheduledTask? = null
    @Volatile private var unavailable = false

    fun start() {
        refresh()
        task = repeatingAsync(30.seconds, delay = 30.seconds) { refresh() }
    }

    @Synchronized
    private fun refresh() {
        try {
            val cfg = config()
            val now = System.currentTimeMillis()
            if (!cfg.selectionPolicy.applies(now)) return
            val week = ContractRotation.weekStart(now)
            runBlocking {
                if (!leader()) repository.loadAll().getOrThrow()
                publisher.refresh(week, leader(), cfg.resourceOrders(), cfg.selectionPolicy,
                    cfg.serverWeeklyBudgetMinor, repository.allNow(), records(), reservedStateIds())
                unavailable = false
                if (leader()) repository.allNow().filter { it.weekStartsAt < week - 8 * ContractRotation.WEEK_MILLIS }
                    .forEach { repository.deleteDurably(it.id()).getOrThrow() }
            }
        } catch (failure: Throwable) {
            unavailable = true
            ContractsRuntime.logError("Contract selection unavailable; the saved plan will be retried", failure)
        }
    }

    private fun createPlan(week: Long, cfg: ContractsConfig) = ContractSelectionPlanner.plan(
        week, cfg.resourceOrders(), cfg.selectionPolicy, cfg.serverWeeklyBudgetMinor,
        repository.allNow(), records(), reservedStateIds(),
    )

    fun current(now: Long): ContractSelectionPlan? = publisher.current(now)?.takeIf {
        config().selectionPolicy.applies(now) && it.budgetMinor <= config().serverWeeklyBudgetMinor
    }

    fun summary(now: Long): Map<String, Any?> {
        val cfg = config()
        val current = current(now)
        val previewWeek = maxOf(cfg.selectionPolicy.startsAt, ContractRotation.weekStart(now) + ContractRotation.WEEK_MILLIS)
        val preview = if (cfg.selectionPolicy.enabled) runCatching { createPlan(previewWeek, cfg) }.getOrNull() else null
        fun describe(plan: ContractSelectionPlan?) = plan?.let {
            mapOf("weekStartsAt" to it.weekStartsAt, "budgetMinor" to it.budgetMinor,
                "allocatedMinor" to it.orders.sumOf { order -> order.budgetMinor },
                "orders" to it.orders.map { order -> mapOf("id" to order.id, "group" to order.group, "reason" to it.reasons[order.id]) })
        }
        return mapOf("unavailable" to unavailable, "enabled" to cfg.selectionPolicy.enabled, "startsAt" to cfg.selectionPolicy.startsAt,
            "current" to describe(current), "previewOnly" to true, "nextPreview" to describe(preview))
    }

    @Synchronized
    fun close() {
        task?.cancel()
        task = null
        try { runBlocking { repository.shutdown() } } finally { scope.cancel() }
    }
}

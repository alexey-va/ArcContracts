package ru.arc.contracts

import ru.arc.repository.Entity

/** Selection spends existing envelopes; it never derives prices from player activity. */
data class ContractSelectionPolicy(
    val enabled: Boolean = false, val startsAt: Long = 0, val perGroup: Int = 3,
    val perGroupOverrides: Map<String, Int> = emptyMap(),
) {
    init {
        require(perGroup in 1..9)
        require(startsAt >= 0 && (!enabled || startsAt == ContractRotation.weekStart(startsAt)))
        require(perGroupOverrides.all { (group, count) ->
            Regex("[a-z0-9][a-z0-9_-]{2,47}").matches(group) && count in 1..64
        })
    }
    fun applies(now: Long) = enabled && now >= startsAt
    fun count(group: String) = perGroupOverrides[group] ?: perGroup
}

data class ContractSelectionPlan(
    val weekStartsAt: Long,
    val budgetMinor: Long,
    val orders: List<ResourceContractDefinition>,
    val reasons: Map<String, String>,
    val schemaVersion: Int = 1,
) : Entity {
    override fun id() = weekStartsAt.toString()
    fun validated(): ContractSelectionPlan = apply {
        require(schemaVersion == 1 && weekStartsAt == ContractRotation.weekStart(weekStartsAt))
        require(budgetMinor >= 0 && orders.size <= 128 && orders.map { it.id }.distinct().size == orders.size)
        require(orders.all { it.windowStartsAt == weekStartsAt && it.windowEndsAt == weekStartsAt + ContractRotation.WEEK_MILLIS })
        require(orders.fold(0L) { total, order -> Math.addExact(total, order.budgetMinor) } <= budgetMinor)
        require(reasons.keys == orders.map { it.id }.toSet())
        require(reasons.values.all { it in setOf("existing-obligation", "rotation", "proven-demand", "catalog-rotation") })
    }
}

object ContractSelectionPlanner {
    fun plan(
        week: Long,
        candidates: List<ResourceContractDefinition>,
        policy: ContractSelectionPolicy,
        budgetMinor: Long,
        history: List<ContractSelectionPlan>,
        records: Collection<ResourceContractRecord>,
        reservedStateIds: Set<String> = emptySet(),
    ): ContractSelectionPlan {
        require(policy.applies(week))
        val snapshots = records.associateBy { it.stateId }
        val eligible = candidates.filter { it.weeklyRecurring && it.windowStartsAt <= week }
            .map { ContractRotation.at(it, week) }
            .map { candidate ->
                snapshots[ResourceContractRecord.stateId(candidate.id, week)]?.definitionSnapshot ?: candidate
            }
        val prior = history.filter { it.weekStartsAt < week }.onEach { it.validated() }
        val lastChosen = prior.flatMap { plan -> plan.orders.map { it.id to plan.weekStartsAt } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.max() }
        val demand = records.filter { it.state.windowEndsAt <= week && it.state.windowStartsAt >= week - 8 * ContractRotation.WEEK_MILLIS }
            .groupBy { it.state.contractId }.mapValues { (_, states) ->
                states.flatMap { it.state.perPlayerQuantity.filterValues { amount -> amount > 0 }.keys }.distinct().size >= 3
            }
        // Existing paid/held obligations survive catalog edits and retries.
        val pinned = records.filter { it.state.windowStartsAt == week &&
            (it.state.acceptedQuantity > 0 || it.stateId in reservedStateIds) }
            .map { record ->
                record.definitionSnapshot ?: requireNotNull(eligible.firstOrNull { it.id == record.state.contractId }) {
                    "Cannot resolve legacy contract definition ${record.stateId}; restore its catalog or journal snapshot"
                }.also { record.validatedAgainst(it) }
            }.distinctBy { it.id }.sortedBy { it.id }
        val selected = pinned.toMutableList()
        val reasons = pinned.associate { it.id to "existing-obligation" }.toMutableMap()
        var remaining = budgetMinor - pinned.fold(0L) { total, order -> Math.addExact(total, order.budgetMinor) }
        require(remaining >= 0) { "Existing contract envelopes exceed selection budget" }
        val ranked = eligible.filter { candidate -> pinned.none { it.id == candidate.id } }
            .sortedWith(compareBy<ResourceContractDefinition> { lastChosen[it.id] ?: Long.MIN_VALUE }
                .thenByDescending { demand[it.id] == true }
                .thenBy { stableRotation(it.id, week) }.thenBy { it.id })
            .groupBy { it.group }.toSortedMap()
        repeat(maxOf(policy.perGroup, policy.perGroupOverrides.values.maxOrNull() ?: 0)) {
            ranked.forEach { (group, options) ->
                if (selected.count { it.group == group } < policy.count(group)) {
                    val next = options.firstOrNull { option -> option.budgetMinor <= remaining && selected.none { it.id == option.id } }
                    if (next != null) {
                        selected += next
                        remaining -= next.budgetMinor
                        reasons[next.id] = when {
                            lastChosen[next.id] != null -> "rotation"
                            demand[next.id] == true -> "proven-demand"
                            else -> "catalog-rotation"
                        }
                    }
                }
            }
        }
        return ContractSelectionPlan(week, budgetMinor, selected, reasons).validated()
    }

    private fun stableRotation(id: String, week: Long): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$week:$id".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

interface ContractSelectionPersistence {
    fun find(week: Long): ContractSelectionPlan?
    suspend fun commit(plan: ContractSelectionPlan)
}

/** Publication is downstream of a confirmed durable commit, including retries of cached writes. */
class ContractSelectionPublisher(
    private val persistence: ContractSelectionPersistence,
    private val enableDynamicPricing: suspend (ContractSelectionPlan, List<ResourceContractDefinition>) -> ContractSelectionPlan = { plan, _ -> plan },
) {
    private val published = java.util.concurrent.atomic.AtomicReference<ContractSelectionPlan?>()

    suspend fun refresh(
        week: Long,
        leader: Boolean,
        candidates: List<ResourceContractDefinition>,
        policy: ContractSelectionPolicy,
        budgetMinor: Long,
        history: List<ContractSelectionPlan>,
        records: List<ResourceContractRecord>,
        held: Set<String>,
    ) {
        var plan = persistence.find(week) ?: if (leader) {
            ContractSelectionPlanner.plan(week, candidates, policy, budgetMinor, history, records, held)
        } else return
        // Newly configured desks can open during a week, without reshuffling
        // the saved orders or changing their prices, windows and obligations.
        if (leader) {
            val newGroups = candidates.map { it.group }.toSet() - plan.orders.map { it.group }.toSet()
            if (newGroups.isNotEmpty()) {
                val retained = plan.orders.map(ResourceContractRecord::empty) + records
                val additions = ContractSelectionPlanner.plan(
                    week, plan.orders + candidates.filter { it.group in newGroups }, policy,
                    budgetMinor, history, retained, held + retained.map { it.stateId },
                )
                plan = additions
            }
            plan = enableDynamicPricing(plan, candidates)
        }
        try {
            plan.validated()
            require(plan.weekStartsAt == week && plan.budgetMinor <= budgetMinor)
        } catch (failure: Throwable) {
            published.set(null)
            throw failure
        }
        if (published.get() == plan) return
        if (leader) persistence.commit(plan)
        published.set(plan)
    }

    fun current(now: Long): ContractSelectionPlan? = published.get()?.takeIf { it.weekStartsAt == ContractRotation.weekStart(now) }
}

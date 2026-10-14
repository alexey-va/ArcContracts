package ru.arc.contracts

/** A one-way, flag-only policy upgrade after every old transaction is terminal. */
internal object ContractDynamicPricingUpgrade {
    data class Upgrade(val plan: ContractSelectionPlan, val records: List<ResourceContractRecord>)

    fun legacyJournalSnapshot(snapshots: List<ResourceContractDefinition>): ResourceContractDefinition {
        val policies = snapshots.distinct()
        if (policies.size == 1) return policies.single()
        val enabled = policies.first().copy(dynamicPricing = true)
        require(policies.all { it.copy(dynamicPricing = true) == enabled }) {
            "Conflicting legacy contract journal policies"
        }
        return enabled
    }

    fun plan(saved: ContractSelectionPlan, candidates: List<ResourceContractDefinition>,
        records: List<ResourceContractRecord>, blocked: Set<String>): Upgrade {
        saved.validated()
        val configured = candidates.filter { it.weeklyRecurring && it.windowStartsAt <= saved.weekStartsAt }
            .associate { it.id to ContractRotation.at(it, saved.weekStartsAt) }
        val existing = records.associateBy { it.stateId }
        val writes = mutableListOf<ResourceContractRecord>()
        val orders = saved.orders.map { frozen ->
            val enabled = frozen.copy(dynamicPricing = true)
            val id = ResourceContractRecord.stateId(frozen.id, frozen.windowStartsAt)
            if (frozen.dynamicPricing || configured[frozen.id] != enabled || id in blocked) return@map frozen
            val current = existing[id]
            if (current?.definitionSnapshot == enabled) {
                current.validatedAgainst(enabled) // Retry after record commit / before plan commit.
            } else {
                current?.validatedAgainst(frozen)
                val state = (current?.state ?: ResourceContractState.empty(frozen))
                    .let { it.copy(revision = Math.addExact(it.revision, 1L)) }
                writes += ResourceContractRecord(id, state, enabled).validatedAgainst(enabled)
            }
            enabled
        }
        return Upgrade(saved.copy(orders = orders).validated(), writes)
    }
}

package ru.arc.contracts

/** Read-only replica catalog before the first autonomous selection period. */
internal object ContractBookCatalog {
    fun fromSnapshots(definitions: List<ResourceContractDefinition>, now: Long): List<ResourceContractDefinition> =
        definitions.filter { it.windowEndsAt > now }
            .groupBy { it.id }
            .values.map { versions -> versions.minBy { it.windowStartsAt } }
            .sortedBy { it.id }
}

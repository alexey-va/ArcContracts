package ru.arc.contracts

/** A read-only ranking of committed item quantities in the current contract week. */
internal data class ContractWeeklyLeaderboard(
    val weekStartsAt: Long,
    val entries: List<Entry>,
    val byContract: Map<String, List<Entry>>,
    val incompleteContracts: Set<String> = emptySet(),
) {
    data class Entry(val playerId: String, val quantity: Long)

    companion object {
        fun calculate(
            records: Collection<ResourceContractRecord>,
            journals: Collection<ContractSubmissionJournalRecord>,
            now: Long,
        ): ContractWeeklyLeaderboard {
            val start = ContractRotation.weekStart(now)
            val end = start + ContractRotation.WEEK_MILLIS
            val unresolved = journals.filter { it.status != ContractSubmissionJournalStatus.CONTRACT_COMMITTED }
                .groupBy { ResourceContractRecord.stateId(it.contractId, it.contractWindowStartsAt) }
            val totals = mutableMapOf<String, Long>()
            val byContract = mutableMapOf<String, List<Entry>>()
            val incomplete = mutableSetOf<String>()
            records.filter { it.state.windowStartsAt == start && it.state.windowEndsAt == end }.forEach { record ->
                val quantities = record.state.perPlayerQuantity.toMutableMap()
                val pending = unresolved[record.stateId].orEmpty()
                // Once an unresolved paid receipt leaves the bounded receipt
                // history, its share cannot be proved from cumulative totals.
                // Withhold that player's order total until reconciliation.
                pending.filter { it.status == ContractSubmissionJournalStatus.PAID &&
                    it.submissionId !in record.state.recentReceipts && record.state.revision > it.expectedContractRevision }
                    .forEach {
                        if ((quantities.remove(it.playerId) ?: 0L) > 0L) incomplete += record.state.contractId
                    }
                // A failed state save can remain in the repository cache. Do not
                // rank its receipt until the durable submission has completed.
                pending.forEach { journal ->
                    record.state.recentReceipts[journal.submissionId]?.let { receipt ->
                        quantities[receipt.playerId]?.let { quantity ->
                            quantities[receipt.playerId] = Math.subtractExact(quantity, receipt.quantity)
                            incomplete += record.state.contractId
                        }
                    }
                }
                quantities.forEach { (playerId, quantity) ->
                    if (quantity > 0L) totals[playerId] = Math.addExact(totals[playerId] ?: 0L, quantity)
                }
                byContract[record.state.contractId] = ranked(quantities)
            }
            return ContractWeeklyLeaderboard(start, ranked(totals), byContract, incomplete)
        }

        private fun ranked(quantities: Map<String, Long>) = quantities.filterValues { it > 0L }
            .map { (playerId, quantity) -> Entry(playerId, quantity) }
            .sortedWith(compareByDescending<Entry> { it.quantity }.thenBy { it.playerId })
    }
}

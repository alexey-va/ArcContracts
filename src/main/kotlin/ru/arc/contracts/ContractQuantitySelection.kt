package ru.arc.contracts

data class ContractQuantitySelection(
    val minimum: Int,
    val maximum: Int,
    val selected: Int,
    val payoutMinor: Long,
) {
    val canSubmit: Boolean get() = selected >= minimum && maximum >= minimum
}

/** Pure quantity model shared by the menu and its regression tests. */
object ContractQuantitySelector {
    fun select(
        view: ResourceContractPlayerView,
        availableItems: Int,
        requested: Int? = null,
        marketQuote: ContractMarketQuoteData? = null,
    ): ContractQuantitySelection {
        val contract = view.contract
        val effectiveQuote = marketQuote ?: view.pricingDefinition?.let {
            ContractMarketQuoteData(it, view.pricingSupply, view.pricingAt)
        }
        val budgetRemaining = (contract.budgetMinor - contract.spentMinor - contract.reservedMinor).coerceAtLeast(0L)
        val maximum =
            minOf(
                availableItems.coerceAtLeast(0).toLong(),
                view.maxSubmissionQuantity.toLong(),
                view.playerRemainingQuantity,
                contract.remainingQuantity,
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val affordableMaximum = effectiveQuote?.let { quote ->
            ContractMarketPricing.affordableQuantity(
                quote.definition, quote.supplyBefore, maximum.toLong(), budgetRemaining, quote.now,
                ContractRankPolicy(view.capBasisPoints, view.payoutBasisPoints),
            ).toInt()
        } ?: (budgetRemaining / view.playerPayoutMinorPerUnit).coerceAtMost(maximum.toLong()).toInt()
        val effectiveMaximum = if (maximum < view.minSubmissionQuantity) maximum else affordableMaximum
        val selected =
            if (effectiveMaximum < view.minSubmissionQuantity) {
                0
            } else {
                (requested ?: effectiveMaximum).coerceIn(view.minSubmissionQuantity, effectiveMaximum)
            }
        return ContractQuantitySelection(
            minimum = view.minSubmissionQuantity,
            maximum = effectiveMaximum,
            selected = selected,
            payoutMinor = effectiveQuote?.let { quote ->
                ContractMarketPricing.payoutMinor(quote.definition, quote.supplyBefore, selected.toLong(), quote.now,
                    ContractRankPolicy(view.capBasisPoints, view.payoutBasisPoints))
            } ?: Math.multiplyExact(selected.toLong(), view.playerPayoutMinorPerUnit),
        )
    }

    fun decrease(selection: ContractQuantitySelection, jumpToMinimum: Boolean): Int =
        if (jumpToMinimum) {
            selection.minimum
        } else {
            (selection.selected - selection.minimum).coerceAtLeast(selection.minimum)
        }

    fun increase(selection: ContractQuantitySelection, jumpToMaximum: Boolean): Int =
        if (jumpToMaximum) {
            selection.maximum
        } else {
            (selection.selected + selection.minimum).coerceAtMost(selection.maximum)
        }

    fun adjust(
        selection: ContractQuantitySelection,
        decrease: Boolean,
        jumpToBoundary: Boolean,
    ): Int =
        if (decrease) {
            decrease(selection, jumpToBoundary)
        } else {
            increase(selection, jumpToBoundary)
        }
}

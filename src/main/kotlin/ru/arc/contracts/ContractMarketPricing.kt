package ru.arc.contracts

import java.math.BigInteger

data class ContractMarketQuoteData(
    val definition: ResourceContractDefinition,
    val supplyBefore: Long,
    val now: Long,
)

/** Pure bounded demand/supply quote math. The caller persists the returned amount. */
object ContractMarketPricing {
    const val WEEK_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    private val FOUR = BigInteger.valueOf(4L)

    fun unitPayoutMinor(
        definition: ResourceContractDefinition,
        supplyBefore: Long,
        now: Long,
        policy: ContractRankPolicy = ContractRankPolicy.IDENTITY,
    ): Long {
        require(supplyBefore >= 0L) { "Contract supply must be non-negative" }
        if (!definition.dynamicPricing) return policy.payoutMinorPerUnit(definition.payoutMinorPerUnit)
        val base = BigInteger.valueOf(definition.payoutMinorPerUnit)
        val target = BigInteger.valueOf(definition.targetQuantity)
        val week = BigInteger.valueOf(WEEK_MILLIS)
        val elapsed = BigInteger.valueOf((now - definition.windowStartsAt).coerceAtLeast(0L).coerceAtMost(WEEK_MILLIS))
        val denominator = FOUR * week * target
        val numerator = denominator + elapsed * target - BigInteger.valueOf(3L) * BigInteger.valueOf(supplyBefore) * week
        val bounded = numerator.coerceIn(denominator / BigInteger.valueOf(2L), denominator * BigInteger.valueOf(5L) / FOUR)
        val marketMinor = (base * bounded / denominator).max(BigInteger.ONE)
        return (marketMinor * BigInteger.valueOf(policy.payoutBasisPoints.toLong()) /
            BigInteger.valueOf(ContractRankPolicy.BASE_BASIS_POINTS.toLong())).longValueExact()
    }

    fun payoutMinor(
        definition: ResourceContractDefinition,
        supplyBefore: Long,
        quantity: Long,
        now: Long,
        policy: ContractRankPolicy = ContractRankPolicy.IDENTITY,
    ): Long {
        require(quantity >= 0L) { "Contract quantity must be non-negative" }
        require(quantity <= 2_304L) { "Contract quantity exceeds bounded batch" }
        var total = BigInteger.ZERO
        repeat(quantity.toInt()) { offset ->
            total += BigInteger.valueOf(unitPayoutMinor(definition, Math.addExact(supplyBefore, offset.toLong()), now, policy))
        }
        return total.longValueExact()
    }

    /** Returns the largest affordable prefix without re-pricing every prefix. */
    fun affordableQuantity(
        definition: ResourceContractDefinition,
        supplyBefore: Long,
        upperQuantity: Long,
        budgetMinor: Long,
        now: Long,
        policy: ContractRankPolicy = ContractRankPolicy.IDENTITY,
    ): Long {
        require(upperQuantity in 0L..2_304L) { "Contract quantity exceeds bounded batch" }
        if (upperQuantity == 0L || budgetMinor <= 0L) return 0L
        val unit = unitPayoutMinor(definition, supplyBefore, now, policy)
        if (!definition.dynamicPricing) return minOf(upperQuantity, budgetMinor / unit)
        var total = BigInteger.ZERO
        var affordable = 0L
        repeat(upperQuantity.toInt()) { offset ->
            total += BigInteger.valueOf(unitPayoutMinor(definition, Math.addExact(supplyBefore, offset.toLong()), now, policy))
            if (total <= BigInteger.valueOf(budgetMinor)) affordable++
        }
        return affordable
    }

    fun minimumSubmissionPayout(definition: ResourceContractDefinition): Long {
        val perUnit = if (definition.dynamicPricing) {
            (definition.payoutMinorPerUnit / 2L).coerceAtLeast(1L)
        } else {
            definition.payoutMinorPerUnit
        }
        return Math.multiplyExact(perUnit, definition.minSubmissionQuantity.toLong())
    }

    fun payoutAllowed(
        definition: ResourceContractDefinition,
        quantity: Long,
        payoutMinor: Long,
        policy: ContractRankPolicy = ContractRankPolicy.MAXIMUM,
    ): Boolean {
        if (quantity <= 0L || payoutMinor <= 0L) return false
        if (!definition.dynamicPricing) return ContractRankPolicy.payoutAllowed(definition.payoutMinorPerUnit, quantity, payoutMinor)
        val minimumPerUnit = (BigInteger.valueOf(definition.payoutMinorPerUnit) / BigInteger.valueOf(2L)).max(BigInteger.ONE)
        val maximumPerUnit = BigInteger.valueOf(definition.payoutMinorPerUnit) * BigInteger.valueOf(5L) / FOUR
        val rankMaximumPerUnit = maximumPerUnit * BigInteger.valueOf(policy.payoutBasisPoints.toLong()) /
            BigInteger.valueOf(ContractRankPolicy.BASE_BASIS_POINTS.toLong())
        val minimum = minimumPerUnit * BigInteger.valueOf(quantity)
        val rankMaximum = rankMaximumPerUnit * BigInteger.valueOf(quantity)
        return BigInteger.valueOf(payoutMinor) in minimum..rankMaximum
    }

    private fun BigInteger.coerceIn(minimum: BigInteger, maximum: BigInteger): BigInteger =
        when {
            this < minimum -> minimum
            this > maximum -> maximum
            else -> this
        }
}

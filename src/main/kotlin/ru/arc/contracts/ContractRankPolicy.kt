package ru.arc.contracts

import org.bukkit.entity.Player
import java.math.BigInteger

data class ContractRankPolicy(
    val playerCapBasisPoints: Int = BASE_BASIS_POINTS,
    val payoutBasisPoints: Int = BASE_BASIS_POINTS,
) {
    init {
        require(playerCapBasisPoints in BASE_BASIS_POINTS..MAX_PLAYER_CAP_BASIS_POINTS) {
            "Contract player-cap boost is outside the released range"
        }
        require(payoutBasisPoints in BASE_BASIS_POINTS..MAX_PAYOUT_BASIS_POINTS) {
            "Contract payout boost is outside the released range"
        }
    }

    fun playerCap(base: Long): Long = scaledFloor(base, playerCapBasisPoints)

    fun payoutMinorPerUnit(base: Long): Long = scaledFloor(base, payoutBasisPoints)

    companion object {
        const val BASE_BASIS_POINTS = 10_000
        const val MAX_PLAYER_CAP_BASIS_POINTS = 20_000
        const val MAX_PAYOUT_BASIS_POINTS = 12_500
        val IDENTITY = ContractRankPolicy()
        val MAXIMUM = ContractRankPolicy(MAX_PLAYER_CAP_BASIS_POINTS, MAX_PAYOUT_BASIS_POINTS)

        fun payoutAllowed(basePerUnit: Long, quantity: Long, payoutMinor: Long): Boolean {
            if (basePerUnit <= 0L || quantity <= 0L || payoutMinor <= 0L) return false
            val minimum = runCatching { Math.multiplyExact(basePerUnit, quantity) }.getOrNull() ?: return false
            val maximumRate = runCatching { MAXIMUM.payoutMinorPerUnit(basePerUnit) }.getOrNull() ?: return false
            val maximum = runCatching { Math.multiplyExact(maximumRate, quantity) }.getOrNull() ?: return false
            return payoutMinor in minimum..maximum
        }

        private fun scaledFloor(value: Long, basisPoints: Int): Long {
            require(value > 0L) { "Contract policy base must be positive" }
            return BigInteger.valueOf(value)
                .multiply(BigInteger.valueOf(basisPoints.toLong()))
                .divide(BigInteger.valueOf(BASE_BASIS_POINTS.toLong()))
                .longValueExact()
        }
    }
}

object ContractRankPolicyResolver {
    const val PLAYER_CAP_PERMISSION_PREFIX = "arc.contracts.player.cap.percent."
    const val PAYOUT_PERMISSION_PREFIX = "arc.contracts.payout.percent."

    fun resolve(hasPermission: (String) -> Boolean): ContractRankPolicy = ContractRankPolicy(
        playerCapBasisPoints = highestPercent(
            ContractRankPolicy.MAX_PLAYER_CAP_BASIS_POINTS / 100,
            PLAYER_CAP_PERMISSION_PREFIX,
            hasPermission,
        ) * 100,
        payoutBasisPoints = highestPercent(
            ContractRankPolicy.MAX_PAYOUT_BASIS_POINTS / 100,
            PAYOUT_PERMISSION_PREFIX,
            hasPermission,
        ) * 100,
    )

    fun resolve(player: Player): ContractRankPolicy = resolve(player::hasPermission)

    private fun highestPercent(maximum: Int, prefix: String, hasPermission: (String) -> Boolean): Int =
        (maximum downTo 100).firstOrNull { hasPermission("$prefix$it") } ?: 100
}

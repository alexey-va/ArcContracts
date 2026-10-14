package ru.arc.contracts

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import net.milkbowl.vault.economy.EconomyResponse
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Exact RedisEconomy 4.5.12 sink adapter; the provider call is never retried. */
class RedisEconomySeasonMoneyGateway(
    private val apiProvider: () -> RedisEconomyAPI? = RedisEconomyAPI::getAPI,
) : SeasonMoneyGateway {
    override suspend fun balanceMinor(playerId: String): Long? {
        val uuid = parsePlayer(playerId) ?: return null
        val currency = apiProvider()?.defaultCurrency ?: return null
        return runCatching { currency.getBalance(uuid).toExactMinor() }.getOrNull()
    }

    override suspend fun withdraw(
        playerId: String,
        amountMinor: Long,
        reason: String,
        expectedBalanceBeforeMinor: Long,
    ): SeasonMoneyEvidence {
        require(amountMinor > 0L) { "Season withdrawal must be positive" }
        val parts = reason.split(':', limit = 3)
        val kind = SeasonMoneyActionKind.entries.firstOrNull { parts.getOrNull(1) == it.ledgerSource }
        val actionId = parts.getOrNull(2).orEmpty()
        require(
            parts.getOrNull(0) == "arc-season" && kind != null && SeasonRuntimeState.validActionId(actionId) &&
                reason == SeasonMoneyJournalRecord.withdrawalReason(kind, actionId),
        ) {
            "Invalid season withdrawal reason"
        }
        val uuid = parsePlayer(playerId)
            ?: return SeasonMoneyEvidence(false, false, null, "invalid_player_id")
        val currency = apiProvider()?.defaultCurrency
            ?: return SeasonMoneyEvidence(false, false, null, "provider_unavailable")
        val before = runCatching { currency.getBalance(uuid).toExactMinor() }.getOrNull()
            ?: return SeasonMoneyEvidence(false, false, null, "provider_balance_unavailable")
        if (before != expectedBalanceBeforeMinor) {
            return SeasonMoneyEvidence(false, false, before, "provider_balance_changed_before_call")
        }
        if (currency.transactionTax != 0.0) {
            return SeasonMoneyEvidence(false, false, before, "provider_transaction_tax_nonzero")
        }
        val amount = BigDecimal.valueOf(amountMinor, 2).toDouble()
        val response: EconomyResponse =
            try {
                currency.withdrawPlayer(uuid, currency.currencyName, amount, reason)
            } catch (_: Throwable) {
                return SeasonMoneyEvidence(
                    providerAccepted = null,
                    providerCallAttempted = true,
                    balanceAfterMinor = runCatching { currency.getBalance(uuid).toExactMinor() }.getOrNull(),
                )
            }
        val after = runCatching { currency.getBalance(uuid).toExactMinor() }.getOrNull()
        return SeasonMoneyEvidence(
            providerAccepted = response.transactionSuccess(),
            providerCallAttempted = true,
            balanceAfterMinor = after,
            failureCode = if (response.transactionSuccess()) null else "provider_rejected",
        )
    }

    private fun parsePlayer(playerId: String): UUID? =
        runCatching { UUID.fromString(playerId) }.getOrNull()?.takeIf { it.toString() == playerId }

    private fun Double.toExactMinor(): Long? {
        if (!isFinite()) return null
        return runCatching {
            BigDecimal.valueOf(this)
                .movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
        }.getOrNull()
    }
}

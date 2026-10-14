package ru.arc.contracts

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import net.milkbowl.vault.economy.EconomyResponse
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull

/** RedisEconomy adapter; a provider mutation is attempted at most once. */
class RedisEconomyContractPaymentGateway(
    private val apiProvider: () -> RedisEconomyAPI? = RedisEconomyAPI::getAPI,
) : ContractPaymentGateway {
    override suspend fun balanceMinor(playerId: String): Long? {
        val uuid = parsePlayer(playerId) ?: return null
        val currency = apiProvider()?.defaultCurrency ?: return null
        return remoteBalanceMinor(currency, uuid)
    }

    override suspend fun deposit(
        playerId: String,
        amountMinor: Long,
        reason: String,
    ): ContractPaymentEvidence {
        require(amountMinor > 0L) { "Contract payout must be positive" }
        val uuid = parsePlayer(playerId)
            ?: return ContractPaymentEvidence(false, null, "invalid_player_id")
        val currency = apiProvider()?.defaultCurrency
            ?: return ContractPaymentEvidence(false, null, "provider_unavailable")

        val remoteBefore = remoteBalanceMinor(currency, uuid)
            ?: return ContractPaymentEvidence(null, null, "provider_remote_balance_unavailable")
        val cachedBefore = runCatching { currency.getBalance(uuid).toContractEvidenceMinor() }.getOrNull()
            ?: return ContractPaymentEvidence(null, null, "provider_balance_unavailable")
        if (cachedBefore != remoteBefore) {
            return ContractPaymentEvidence(false, remoteBefore, "provider_balance_changed_before_call")
        }
        val expectedAfter = runCatching { Math.addExact(remoteBefore, amountMinor) }.getOrNull()
            ?: return ContractPaymentEvidence(false, remoteBefore, "provider_balance_overflow")

        val amount = BigDecimal.valueOf(amountMinor, 2).toDouble()
        val response: EconomyResponse =
            try {
                currency.depositPlayer(uuid, currency.currencyName, amount, reason)
            } catch (_: Throwable) {
                return ContractPaymentEvidence(
                    providerAccepted = null,
                    balanceAfterMinor = remoteBalanceMinor(currency, uuid),
                    failureCode = "provider_threw",
                )
            }

        val remoteAfter = if (response.transactionSuccess()) {
            confirmRemoteBalance(currency, uuid, expectedAfter)
        } else {
            remoteBalanceMinor(currency, uuid)
        }
        if (response.transactionSuccess() && remoteAfter == expectedAfter) {
            return ContractPaymentEvidence(true, remoteAfter)
        }
        if (!response.transactionSuccess() && remoteAfter == remoteBefore) {
            return ContractPaymentEvidence(false, remoteAfter, "provider_rejected")
        }
        return ContractPaymentEvidence(
            providerAccepted = null,
            balanceAfterMinor = remoteAfter,
            failureCode = if (response.transactionSuccess()) {
                "provider_remote_confirmation_timeout"
            } else {
                "provider_rejection_balance_unconfirmed"
            },
        )
    }

    private suspend fun confirmRemoteBalance(
        currency: Currency,
        playerId: UUID,
        expected: Long,
    ): Long? {
        var observed: Long? = null
        val confirmed = withTimeoutOrNull(REMOTE_CONFIRMATION_TIMEOUT_MILLIS) {
            while (observed != expected) {
                observed = remoteBalanceMinor(currency, playerId) ?: return@withTimeoutOrNull null
                if (observed != expected) delay(REMOTE_CONFIRMATION_DELAY_MILLIS)
            }
            observed
        }
        return confirmed ?: observed
    }

    private suspend fun remoteBalanceMinor(currency: Currency, playerId: UUID): Long? {
        val remote =
            withTimeoutOrNull(REMOTE_CONFIRMATION_TIMEOUT_MILLIS) {
                awaitRemoteBalance(currency, playerId)
            } ?: return null
        return remote.toContractEvidenceMinor()
    }

    private suspend fun awaitRemoteBalance(currency: Currency, playerId: UUID): Double? =
        runCatching { currency.getAccountRedis(playerId).toCompletableFuture().await() }.getOrNull()

    private fun parsePlayer(playerId: String): UUID? =
        runCatching { UUID.fromString(playerId) }.getOrNull()

    companion object {
        private const val REMOTE_CONFIRMATION_TIMEOUT_MILLIS = 5_000L
        private const val REMOTE_CONFIRMATION_DELAY_MILLIS = 50L
    }
}

/** RedisEconomy stores balances as doubles; contract evidence is integer cents. */
private fun Double.toContractEvidenceMinor(): Long? {
    if (!isFinite()) return null
    return runCatching {
        BigDecimal.valueOf(this)
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()
}

package ru.arc.contracts

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.test.runTest
import net.milkbowl.vault.economy.EconomyResponse

class RedisEconomyContractPaymentGatewayTest : StringSpec({
    fun remote(value: Double): CompletableFuture<Double> = CompletableFuture.completedFuture(value)

    fun api(currency: Currency): RedisEconomyAPI = mockk<RedisEconomyAPI>().also {
        every { it.defaultCurrency } returns currency
        every { currency.currencyName } returns "vault"
    }

    "reads balance from remote Redis instead of the local cache" {
        runTest {
            val playerId = UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = api(currency)
            every { currency.getBalance(playerId) } returns 99.0
            every { currency.getAccountRedis(playerId) } returns remote(12.5)

            RedisEconomyContractPaymentGateway { api }.balanceMinor(playerId.toString()) shouldBe 1_250L
            verify(exactly = 1) { currency.getAccountRedis(playerId) }
        }
    }

    "rejects non-finite remote balances" {
        runTest {
            val playerId = UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = api(currency)
            every { currency.getAccountRedis(playerId) } returns remote(Double.NaN)

            RedisEconomyContractPaymentGateway { api }.balanceMinor(playerId.toString()) shouldBe null
        }
    }

    "confirms success only after remote balance catches up" {
        runTest {
            val playerId = UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = api(currency)
            every { currency.getBalance(playerId) } returns 100.0
            every { currency.getAccountRedis(playerId) } returnsMany listOf(remote(100.0), remote(100.0), remote(120.0))
            every { currency.depositPlayer(playerId, "vault", 20.0, "arc-contract:remote-success") } returns
                EconomyResponse(20.0, 120.0, EconomyResponse.ResponseType.SUCCESS, null)

            RedisEconomyContractPaymentGateway { api }.deposit(
                playerId.toString(),
                2_000L,
                "arc-contract:remote-success",
            ) shouldBe
                ContractPaymentEvidence(true, 12_000L)

            verify(exactly = 1) { currency.depositPlayer(playerId, "vault", 20.0, "arc-contract:remote-success") }
        }
    }

    "returns ambiguous after a successful call whose remote balance never confirms" {
        runTest {
            val playerId = UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = api(currency)
            every { currency.getBalance(playerId) } returns 100.0
            every { currency.getAccountRedis(playerId) } returns remote(100.0)
            every { currency.depositPlayer(playerId, "vault", 20.0, "arc-contract:remote-timeout") } returns
                EconomyResponse(20.0, 120.0, EconomyResponse.ResponseType.SUCCESS, null)

            val evidence = RedisEconomyContractPaymentGateway { api }.deposit(
                playerId.toString(),
                2_000L,
                "arc-contract:remote-timeout",
            )

            evidence.providerAccepted shouldBe null
            evidence.balanceAfterMinor shouldBe 10_000L
            verify(exactly = 1) { currency.depositPlayer(playerId, "vault", 20.0, "arc-contract:remote-timeout") }
        }
    }

    "does not deposit when remote before disagrees with the local cache" {
        runTest {
            val playerId = UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = api(currency)
            every { currency.getBalance(playerId) } returns 100.0
            every { currency.getAccountRedis(playerId) } returns remote(99.99)

            RedisEconomyContractPaymentGateway { api }.deposit(
                playerId.toString(),
                2_000L,
                "arc-contract:drift",
            ) shouldBe ContractPaymentEvidence(false, 9_999L, "provider_balance_changed_before_call")

            verify(exactly = 0) { currency.depositPlayer(any<UUID>(), any(), any(), any()) }
        }
    }

    "preserves fractional balances while confirming an exact cent delta" {
        runTest {
            val playerId = UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = api(currency)
            every { currency.getBalance(playerId) } returns 100_002_403.42407733
            every { currency.getAccountRedis(playerId) } returnsMany listOf(
                remote(100_002_403.42407733),
                remote(100_002_418.42407733),
            )
            every { currency.depositPlayer(playerId, "vault", 15.0, "arc-contract:fractional") } returns
                EconomyResponse(15.0, 100_002_418.42407733, EconomyResponse.ResponseType.SUCCESS, null)

            RedisEconomyContractPaymentGateway { api }.deposit(
                playerId.toString(),
                1_500L,
                "arc-contract:fractional",
            ) shouldBe
                ContractPaymentEvidence(true, 10_000_241_842L)
        }
    }

    "returns ambiguous and never retries when remote before fails" {
        runTest {
            val playerId = UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = api(currency)
            every { currency.getBalance(playerId) } returns 100.0
            every { currency.getAccountRedis(playerId) } returns CompletableFuture<Double>().also {
                it.completeExceptionally(IllegalStateException("remote unavailable"))
            }

            RedisEconomyContractPaymentGateway { api }.deposit(
                playerId.toString(),
                2_000L,
                "arc-contract:remote-error",
            ) shouldBe ContractPaymentEvidence(null, null, "provider_remote_balance_unavailable")

            verify(exactly = 0) { currency.depositPlayer(any<UUID>(), any(), any(), any()) }
        }
    }

    "returns ambiguous after deposit throws and makes no retry" {
        runTest {
            val playerId = UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = api(currency)
            every { currency.getBalance(playerId) } returns 100.0
            every { currency.getAccountRedis(playerId) } returnsMany listOf(remote(100.0), remote(100.0))
            every { currency.depositPlayer(playerId, "vault", 20.0, "arc-contract:provider-throw") } throws
                IllegalStateException("provider call failed")

            val evidence =
                RedisEconomyContractPaymentGateway { api }.deposit(
                    playerId.toString(),
                    2_000L,
                    "arc-contract:provider-throw",
                )
            evidence.providerAccepted shouldBe null
            evidence.balanceAfterMinor shouldBe 10_000L
            evidence.failureCode shouldBe "provider_threw"
            verify(exactly = 1) { currency.depositPlayer(playerId, "vault", 20.0, "arc-contract:provider-throw") }
        }
    }

    "bounds an unfinished remote read and does not pay from cache" {
        runTest {
            val playerId = UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = api(currency)
            val pending = CompletableFuture<Double>()
            every { currency.getAccountRedis(playerId) } returns pending

            RedisEconomyContractPaymentGateway { api }.balanceMinor(playerId.toString()) shouldBe null
            pending.isCancelled shouldBe true
            verify(exactly = 0) { currency.depositPlayer(any<UUID>(), any(), any(), any()) }
        }
    }

    "an error after provider success remains ambiguous without another deposit" {
        runTest {
            val playerId = UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = api(currency)
            val failed = CompletableFuture<Double>().also {
                it.completeExceptionally(IllegalStateException("confirmation unavailable"))
            }
            every { currency.getBalance(playerId) } returns 100.0
            every { currency.getAccountRedis(playerId) } returnsMany listOf(remote(100.0), failed)
            every { currency.depositPlayer(playerId, "vault", 20.0, "arc-contract:confirmation-error") } returns
                EconomyResponse(20.0, 120.0, EconomyResponse.ResponseType.SUCCESS, null)

            RedisEconomyContractPaymentGateway { api }.deposit(
                playerId.toString(), 2_000L, "arc-contract:confirmation-error",
            ).providerAccepted shouldBe null
            verify(exactly = 1) { currency.depositPlayer(playerId, "vault", 20.0, "arc-contract:confirmation-error") }
        }
    }

    "confirms a definite rejection only with unchanged remote balance" {
        runTest {
            val playerId = UUID.randomUUID()
            val currency = mockk<Currency>()
            val api = api(currency)
            every { currency.getBalance(playerId) } returns 100.0
            every { currency.getAccountRedis(playerId) } returnsMany listOf(remote(100.0), remote(100.0))
            every { currency.depositPlayer(playerId, "vault", 20.0, "arc-contract:rejected") } returns
                EconomyResponse(0.0, 100.0, EconomyResponse.ResponseType.FAILURE, "rejected")

            RedisEconomyContractPaymentGateway { api }.deposit(
                playerId.toString(),
                2_000L,
                "arc-contract:rejected",
            ) shouldBe ContractPaymentEvidence(false, 10_000L, "provider_rejected")
        }
    }
})

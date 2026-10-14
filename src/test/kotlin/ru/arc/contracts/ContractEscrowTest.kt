package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractEscrowTest : StringSpec({
    fun empty() = EscrowAccount(accountId = "order-1", ownerId = "customer-1")

    "funds reserves settles and refunds without creating money" {
        val funded = ContractEscrowEngine.fund(empty(), 10_000L)
        val reserved = ContractEscrowEngine.reserve(funded, "submission-1", 6_000L, 100L)
        reserved.availableMinor shouldBe 4_000L
        reserved.reservedMinor shouldBe 6_000L

        val settled = ContractEscrowEngine.settle(reserved, "submission-1")
        settled.paidMinor shouldBe 6_000L
        settled.availableMinor shouldBe 4_000L
        val refunded = ContractEscrowEngine.refund(settled, 4_000L)
        refunded.refundedMinor shouldBe 4_000L
        refunded.availableMinor shouldBe 0L
        ContractEscrowEngine.close(refunded).status shouldBe EscrowStatus.CLOSED
    }

    "reservation and settlement are idempotent" {
        val funded = ContractEscrowEngine.fund(empty(), 10_000L)
        val reserved = ContractEscrowEngine.reserve(funded, "stable", 2_500L, 100L)
        ContractEscrowEngine.reserve(reserved, "stable", 2_500L, 101L) shouldBe reserved
        val settled = ContractEscrowEngine.settle(reserved, "stable")
        ContractEscrowEngine.settle(settled, "stable") shouldBe settled
    }

    "release returns a reservation to available escrow" {
        val funded = ContractEscrowEngine.fund(empty(), 10_000L)
        val reserved = ContractEscrowEngine.reserve(funded, "release", 3_000L, 100L)
        val released = ContractEscrowEngine.release(reserved, "release")
        released.reservedMinor shouldBe 0L
        released.availableMinor shouldBe 10_000L
    }

    "cannot overdraw escrow or close with active value" {
        val funded = ContractEscrowEngine.fund(empty(), 1_000L)
        shouldThrow<IllegalArgumentException> {
            ContractEscrowEngine.reserve(funded, "too-much", 1_001L, 100L)
        }
        shouldThrow<IllegalArgumentException> {
            ContractEscrowEngine.close(funded)
        }
    }

    "rejects overflowing accounting and unbounded idempotency history" {
        shouldThrow<ArithmeticException> {
            empty().copy(fundedMinor = Long.MAX_VALUE, paidMinor = Long.MAX_VALUE, refundedMinor = 1L)
        }
        shouldThrow<IllegalArgumentException> {
            empty().copy(
                settledReservationIds =
                    (1..EscrowAccount.MAX_SETTLED_RESERVATIONS + 1)
                        .mapTo(linkedSetOf()) { "settled-$it" },
            )
        }
    }
})

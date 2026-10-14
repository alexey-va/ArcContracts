package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractRankPolicyTest : StringSpec({
    "highest bounded numerical permissions become the player contract policy" {
        val granted = setOf(
            "arc.contracts.player.cap.percent.110",
            "arc.contracts.player.cap.percent.150",
            "arc.contracts.payout.percent.104",
            "arc.contracts.payout.percent.112",
        )

        ContractRankPolicyResolver.resolve(granted::contains) shouldBe ContractRankPolicy(15_000, 11_200)
    }

    "missing permissions preserve the base contract" {
        val policy = ContractRankPolicyResolver.resolve { false }

        policy shouldBe ContractRankPolicy.IDENTITY
        policy.playerCap(40) shouldBe 40L
        policy.payoutMinorPerUnit(250) shouldBe 250L
    }

    "policy bounds reject values outside the released privilege envelope" {
        shouldThrow<IllegalArgumentException> { ContractRankPolicy(9_999, 10_000) }
        shouldThrow<IllegalArgumentException> { ContractRankPolicy(20_001, 10_000) }
        shouldThrow<IllegalArgumentException> { ContractRankPolicy(10_000, 9_999) }
        shouldThrow<IllegalArgumentException> { ContractRankPolicy(10_000, 12_501) }
    }
})

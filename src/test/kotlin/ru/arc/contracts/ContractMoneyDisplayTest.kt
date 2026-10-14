package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractMoneyDisplayTest :
    StringSpec({
        "whole contract amounts do not show redundant decimal zeroes" {
            formatContractMoney(100L) shouldBe "1"
            formatContractMoney(1_200L) shouldBe "12"
        }

        "contract amounts preserve meaningful cents and strip only trailing zeroes" {
            formatContractMoney(750L) shouldBe "7.5"
            formatContractMoney(1_125L) shouldBe "11.25"
        }
    })

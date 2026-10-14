package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ContractDeadlineFormatTest : StringSpec({
    "end-exclusive Moscow midnight is shown as the final Sunday minute" {
        formatContractDeadline(Instant.parse("2026-09-13T21:00:00Z").toEpochMilli()) shouldBe
            "воскресенье, 23:59"
    }
})

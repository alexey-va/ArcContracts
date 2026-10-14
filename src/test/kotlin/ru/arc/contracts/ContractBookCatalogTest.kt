package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractBookCatalogTest : StringSpec({
    fun order(id: String, start: Long, end: Long) = ResourceContractDefinition(
        id, id, "minecraft:stone", ContractFunding.SERVER_ENVELOPE, start, end,
        10, 1000, 100, 50, group = "forge_orders",
    )

    "replica browsing excludes expired snapshots and keeps current ahead of future" {
        val expired = order("stone", 0, 100)
        val current = order("stone", 100, 200)
        val future = order("stone", 200, 300)
        val upcoming = order("coal", 200, 300)
        ContractBookCatalog.fromSnapshots(listOf(future, expired, upcoming, current), 100) shouldBe listOf(upcoming, current)
        ContractBookCatalog.fromSnapshots(listOf(current, future), 200) shouldBe listOf(future)
    }

    "an unavailable replica catalog does not invent an order" {
        ContractBookCatalog.fromSnapshots(emptyList(), 100) shouldBe emptyList()
    }
})

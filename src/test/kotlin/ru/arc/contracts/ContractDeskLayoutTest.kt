package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractDeskLayoutTest : StringSpec({
    "all desks fill orders from the top left including the cells between page buttons" {
        for (count in 0..80) ContractDeskLayout.calculate(count).rows shouldBe 6
        for (count in 0..20) ContractDeskLayout.calculate(count).orderSlots shouldBe
            (0 until count).map { it / 4 * 9 + it % 4 }
        ContractDeskLayout.calculate(21).orderSlots.last() shouldBe 46
        ContractDeskLayout.calculate(21).pageCount shouldBe 1
        ContractDeskLayout.calculate(22).orderSlots.takeLast(2) shouldBe listOf(46, 47)
    }
    "input fills the right four columns and sale is at the top of the central column" {
        for (count in 0..80) {
            val layout = ContractDeskLayout.calculate(count)
            layout.depositSlots shouldBe (0 until 6).flatMap { row -> (5..8).map { row * 9 + it } }
            layout.saleSlot shouldBe 4
            val all = layout.orderSlots + layout.depositSlots + listOfNotNull(layout.saleSlot, layout.previousPage, layout.nextPage)
            all.distinct().size shouldBe all.size
            all.all { it in 0 until 54 } shouldBe true
            layout.orderSlots.all { it % 9 < 4 && it !in listOf(45, 48) } shouldBe true
        }
    }
    "pagination reserves the left bottom corners and keeps deposit slots stable" {
        val single = ContractDeskLayout.calculate(22)
        single.pageCount shouldBe 1
        single.previousPage shouldBe 45
        single.nextPage shouldBe 48
        val first = ContractDeskLayout.calculate(23, -10)
        val last = ContractDeskLayout.calculate(23, 40)
        first.visibleOrderRange shouldBe (0..21)
        last.visibleOrderRange shouldBe (22..22)
        last.orderSlots shouldBe listOf(0)
        first.previousPage shouldBe 45
        first.nextPage shouldBe 48
        last.depositSlots shouldBe first.depositSlots
        last.saleSlot shouldBe first.saleSlot
        for (count in 23..27) ContractDeskLayout.calculate(count, 1).orderSlots shouldBe
            listOf(0, 1, 2, 3, 9).take(count - 22)
        ContractDeskLayout.calculate(0).visibleOrderRange.isEmpty() shouldBe true
    }
})

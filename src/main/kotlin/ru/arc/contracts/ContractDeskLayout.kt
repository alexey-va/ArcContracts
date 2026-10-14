package ru.arc.contracts

/** Six rows: orders left, controls in the central column, native offered items right. */
internal object ContractDeskLayout {
    const val ROWS = 6
    const val ORDER_COLUMNS = 4
    const val ORDERS_PER_PAGE = ORDER_COLUMNS * ROWS - 2
    const val DEPOSIT_CAPACITY = 4 * ROWS

    data class Geometry(
        val rows: Int,
        val page: Int,
        val pageCount: Int,
        val visibleOrderRange: IntRange,
        val orderSlots: List<Int>,
        val depositSlots: List<Int>,
        val saleSlot: Int,
        val previousPage: Int,
        val nextPage: Int,
    )

    fun calculate(totalOrders: Int, requestedPage: Int = 0): Geometry {
        require(totalOrders >= 0) { "totalOrders must be non-negative" }
        val pageCount = if (totalOrders == 0) 1 else (totalOrders - 1) / ORDERS_PER_PAGE + 1
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val first = page * ORDERS_PER_PAGE
        val count = minOf(totalOrders - first, ORDERS_PER_PAGE)
        val slots = ((0 until ROWS - 1).flatMap { row ->
            (0 until ORDER_COLUMNS).map { row * 9 + it }
        } + listOf(46, 47)).take(count)
        return Geometry(
            ROWS, page, pageCount, first until first + count, slots,
            (0 until ROWS).flatMap { row -> (5..8).map { row * 9 + it } },
            saleSlot = 4,
            previousPage = 45,
            nextPage = 48,
        )
    }
}

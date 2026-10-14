package ru.arc.contracts

/** Presentation only: quotes and submission validation remain authoritative. */
enum class ContractBookAvailability(val messageKey: String, val fallback: String) {
    READY("ready", "<green>Можно сдать сейчас"),
    NOT_STARTED("not-started", "<yellow>Приём ещё не начался"),
    CLOSED("closed", "<yellow>Приём сейчас закрыт"),
    COMPLETED("completed", "<green>Нужный объём уже собран"),
    PLAYER_CAP("player-cap", "<yellow>Ваш лимит по заказу исчерпан"),
    ITEMS_MISSING("not-enough-items", "<#ff6b61>Не хватает предметов для партии"),
    BUDGET_EXHAUSTED("budget-exhausted", "<yellow>Бюджета не хватает на минимальную партию"),
    ORIGIN_REQUIRED("origin-required", "<yellow>Сдача у конторщика на спавне"),
    UNAVAILABLE("unavailable", "<yellow>Сдача сейчас недоступна. Обновите книгу заказов"),
    ;

    fun isCatalogVisible(): Boolean = this == READY || this == ITEMS_MISSING || this == ORIGIN_REQUIRED

    companion object {
        fun resolve(
            view: ResourceContractPlayerView,
            available: Int,
            originAllowed: Boolean,
            quoteAvailable: Boolean = true,
            now: Long = System.currentTimeMillis(),
        ): ContractBookAvailability = when {
            now < view.contract.windowStartsAt -> NOT_STARTED
            now >= view.contract.windowEndsAt -> CLOSED
            view.contract.status == ContractStatus.COMPLETED.label ->
                if (view.contract.remainingQuantity < view.minSubmissionQuantity) COMPLETED else BUDGET_EXHAUSTED
            view.contract.status != ContractStatus.OPEN.label -> CLOSED
            view.contract.remainingQuantity < view.minSubmissionQuantity -> COMPLETED
            view.playerRemainingQuantity < view.minSubmissionQuantity -> PLAYER_CAP
            available < view.minSubmissionQuantity -> ITEMS_MISSING
            !ContractQuantitySelector.select(view, available).canSubmit -> BUDGET_EXHAUSTED
            !originAllowed -> ORIGIN_REQUIRED
            !quoteAvailable -> UNAVAILABLE
            else -> READY
        }

        /** A known opening/reset only; a one-off deadline does not promise new orders. */
        fun nextOpeningAt(view: ResourceContractPlayerView, now: Long): Long? = when {
            now < view.contract.windowStartsAt -> view.contract.windowStartsAt
            view.pricingDefinition?.weeklyRecurring == true && now < view.contract.windowEndsAt -> view.contract.windowEndsAt
            else -> null
        }
    }
}

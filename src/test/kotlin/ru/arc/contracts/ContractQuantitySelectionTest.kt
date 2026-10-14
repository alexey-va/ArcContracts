package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractQuantitySelectionTest : StringSpec({
    "book distinguishes item shortage budget shortage and authoritative refusal" {
        ContractBookAvailability.resolve(view(), 31, true) shouldBe ContractBookAvailability.ITEMS_MISSING
        ContractBookAvailability.resolve(view(unspentBudgetMinor = 100), 64, true) shouldBe ContractBookAvailability.BUDGET_EXHAUSTED
        ContractBookAvailability.resolve(view(), 64, true, quoteAvailable = false) shouldBe ContractBookAvailability.UNAVAILABLE
        ContractBookAvailability.resolve(view(), 64, false) shouldBe ContractBookAvailability.ORIGIN_REQUIRED
        ContractBookAvailability.resolve(view(), 64, true) shouldBe ContractBookAvailability.READY
    }

    "book preserves completed state and does not invent one-off renewals" {
        val exhausted = view(unspentBudgetMinor = 0)
        ContractBookAvailability.resolve(
            exhausted.copy(contract = exhausted.contract.copy(status = ContractStatus.COMPLETED.label)),
            0, false,
        ) shouldBe ContractBookAvailability.BUDGET_EXHAUSTED
        val base = view()
        val future = base.copy(contract = base.contract.copy(windowStartsAt = 100, windowEndsAt = 200))
        ContractBookAvailability.resolve(future, 64, true, now = 99) shouldBe ContractBookAvailability.NOT_STARTED
        ContractBookAvailability.nextOpeningAt(future, 99) shouldBe 100L
        ContractBookAvailability.nextOpeningAt(future, 100) shouldBe null
        ContractBookAvailability.resolve(future, 64, true, now = 200) shouldBe ContractBookAvailability.CLOSED
        ContractBookAvailability.resolve(view(remaining = 0), 0, false) shouldBe ContractBookAvailability.COMPLETED
        ContractBookAvailability.resolve(view(playerRemaining = 0), 64, true) shouldBe ContractBookAvailability.PLAYER_CAP
    }

    "catalog keeps only orders the player can act on" {
        ContractBookAvailability.entries.filter { it.isCatalogVisible() }.toSet() shouldBe setOf(
            ContractBookAvailability.READY,
            ContractBookAvailability.ITEMS_MISSING,
            ContractBookAvailability.ORIGIN_REQUIRED,
        )
    }

    "bounds the initial selection by inventory quota target and budget" {
        val selection =
            ContractQuantitySelector.select(
                view(
                    remaining = 900,
                    playerRemaining = 700,
                    maxSubmission = 512,
                    unspentBudgetMinor = 30_000,
                ),
                availableItems = 800,
            )

        selection.minimum shouldBe 32
        selection.maximum shouldBe 300
        selection.selected shouldBe 300
        selection.payoutMinor shouldBe 30_000L
        selection.canSubmit shouldBe true
    }

    "disables confirmation below the minimum plain-item batch" {
        val selection = ContractQuantitySelector.select(view(), availableItems = 31)

        selection.maximum shouldBe 31
        selection.selected shouldBe 0
        selection.payoutMinor shouldBe 0L
        selection.canSubmit shouldBe false
    }

    "quantity controls step by the minimum and clamp to both ends" {
        val selection = ContractQuantitySelector.select(view(maxSubmission = 100), availableItems = 100, requested = 64)

        ContractQuantitySelector.decrease(selection, jumpToMinimum = false) shouldBe 32
        ContractQuantitySelector.decrease(selection, jumpToMinimum = true) shouldBe 32
        ContractQuantitySelector.increase(selection, jumpToMaximum = false) shouldBe 96
        ContractQuantitySelector.increase(selection, jumpToMaximum = true) shouldBe 100
    }

    "single quantity item maps normal and shift clicks to both boundaries" {
        val selection = ContractQuantitySelector.select(view(maxSubmission = 100), availableItems = 100, requested = 64)

        ContractQuantitySelector.adjust(selection, decrease = false, jumpToBoundary = false) shouldBe 96
        ContractQuantitySelector.adjust(selection, decrease = false, jumpToBoundary = true) shouldBe 100
        ContractQuantitySelector.adjust(selection, decrease = true, jumpToBoundary = false) shouldBe 32
        ContractQuantitySelector.adjust(selection, decrease = true, jumpToBoundary = true) shouldBe 32
    }

    "rank payout rate controls both budget capacity and displayed payout" {
        val selection = ContractQuantitySelector.select(
            view(unspentBudgetMinor = 1_000).copy(playerPayoutMinorPerUnit = 112L),
            availableItems = 100,
        )

        selection.maximum shouldBe 8
        selection.payoutMinor shouldBe 0L
        selection.canSubmit shouldBe false
    }

    "dialog accepts only an exact integer inside the current slider range" {
        val selection = ContractQuantitySelector.select(view(maxSubmission = 100), availableItems = 100, requested = 64)

        ContractDialogRules.quantity(32f, selection) shouldBe 32
        ContractDialogRules.quantity(100f, selection) shouldBe 100
        ContractDialogRules.quantity(31f, selection) shouldBe null
        ContractDialogRules.quantity(101f, selection) shouldBe null
        ContractDialogRules.quantity(64.5f, selection) shouldBe null
        ContractDialogRules.quantity(Float.NaN, selection) shouldBe null
        ContractDialogRules.quantity(null, selection) shouldBe null
    }

    "dialog confirmation rejects any changed quote term but ignores quote age" {
        val original = quote()

        ContractDialogRules.sameQuote(original, original.copy(quotedAt = original.quotedAt + 1_000)) shouldBe true
        ContractDialogRules.sameQuote(original, original.copy(quantity = original.quantity + 1)) shouldBe false
        ContractDialogRules.sameQuote(original, original.copy(payoutMinor = original.payoutMinor + 1)) shouldBe false
        ContractDialogRules.sameQuote(original, original.copy(expectedRevision = original.expectedRevision + 1)) shouldBe false
        ContractDialogRules.sameQuote(original, original.copy(windowStartsAt = original.windowStartsAt + 1)) shouldBe false
        ContractDialogRules.sameQuote(original, original.copy(contractId = "bank_test")) shouldBe false
        ContractDialogRules.sameQuote(original, original.copy(playerId = "00000000-0000-0000-0000-000000000002")) shouldBe false
    }
})

private fun quote() = ContractSubmissionQuote(
    contractId = "forge_test",
    windowStartsAt = 1L,
    playerId = "00000000-0000-0000-0000-000000000001",
    quantity = 64,
    payoutMinor = 6_400L,
    expectedRevision = 7L,
    quotedAt = 10L,
)

private fun view(
    remaining: Long = 1_000,
    playerRemaining: Long = 1_000,
    maxSubmission: Int = 512,
    unspentBudgetMinor: Long = 100_000,
): ResourceContractPlayerView =
    ResourceContractPlayerView(
        contract =
            ResourceContractView(
                id = "forge_test",
                displayName = "Тестовый заказ",
                itemKey = "minecraft:raw_iron",
                funding = "server_envelope",
                status = "open",
                windowStartsAt = 1L,
                windowEndsAt = Long.MAX_VALUE,
                payoutMinorPerUnit = 100L,
                budgetMinor = unspentBudgetMinor,
                spentMinor = 0L,
                reservedMinor = 0L,
                targetQuantity = 1_000L,
                acceptedQuantity = 1_000L - remaining,
                reservedQuantity = 0L,
                remainingQuantity = remaining,
                contributors = 0,
                group = "forge_orders",
            ),
        minSubmissionQuantity = 32,
        maxSubmissionQuantity = maxSubmission,
        perPlayerQuantityCap = 1_000L,
        playerAcceptedQuantity = 1_000L - playerRemaining,
        playerReservedQuantity = 0L,
        playerRemainingQuantity = playerRemaining,
        playerPayoutMinorPerUnit = 100L,
        capBasisPoints = 10_000,
        payoutBasisPoints = 10_000,
    )

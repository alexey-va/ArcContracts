package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractTrackingTest : StringSpec({
    val week = ContractRotation.weekStart(1_789_344_000_000)
    val view = ResourceContractPlayerView(
        contract = ResourceContractView(
            id = "weekly_coal",
            displayName = "Уголь",
            itemKey = "minecraft:coal",
            funding = "server_envelope",
            status = "open",
            windowStartsAt = week,
            windowEndsAt = week + ContractRotation.WEEK_MILLIS,
            payoutMinorPerUnit = 125,
            budgetMinor = 100_000,
            spentMinor = 0,
            reservedMinor = 0,
            targetQuantity = 1_000,
            acceptedQuantity = 0,
            reservedQuantity = 0,
            remainingQuantity = 1_000,
            contributors = 0,
        ),
        minSubmissionQuantity = 8,
        maxSubmissionQuantity = 2_304,
        perPlayerQuantityCap = 500,
        playerAcceptedQuantity = 0,
        playerReservedQuantity = 0,
        playerRemainingQuantity = 500,
        playerPayoutMinorPerUnit = 125,
        capBasisPoints = 10_000,
        payoutBasisPoints = 10_000,
    )

    "zero target selects the minimum valid batch within every cap" {
        ContractTrackingLogic.targetQuantity(view, 0) shouldBe 8L
        ContractTrackingLogic.targetQuantity(view.copy(playerRemainingQuantity = 12), 0) shouldBe 8L
        ContractTrackingLogic.targetQuantity(view.copy(minSubmissionQuantity = 80), 0) shouldBe 80L
    }

    "requested target is clamped to the player's and contract's remaining caps" {
        ContractTrackingLogic.targetQuantity(view, 900) shouldBe 500L
        ContractTrackingLogic.targetQuantity(view.copy(contract = view.contract.copy(remainingQuantity = 20)), 900) shouldBe 20L
        shouldThrow<IllegalArgumentException> {
            ContractTrackingLogic.targetQuantity(view.copy(playerRemainingQuantity = 4), 0)
        }
    }

    "status is bound to the exact window and never leaks into the next rotation" {
        val state = ContractTrackingLogic.stateFor(view, 64)
        ContractTrackingLogic.status(state, view, 12, week + 1_000)?.remainingQuantity shouldBe 52L
        ContractTrackingLogic.status(state, view, 64, week + 1_000)?.complete shouldBe true
        ContractTrackingLogic.status(state, view, 64, view.contract.windowEndsAt) shouldBe null
        val nextView = view.copy(
            contract = view.contract.copy(
                windowStartsAt = week + ContractRotation.WEEK_MILLIS,
                windowEndsAt = week + 2 * ContractRotation.WEEK_MILLIS,
            ),
        )
        ContractTrackingLogic.status(state, nextView, 64, nextView.contract.windowStartsAt + 1) shouldBe null
    }
})

package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SeasonDungeonLaunchGateTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val now = catalog.startsAt + 10_000L

    fun purchasedState(): SeasonRuntimeState {
        val unlocked = completedRoadFoundation(catalog, playerId)
        val plan =
            SeasonMoneyActionEngine.plan(
                catalog,
                unlocked,
                "action-launch-pass",
                playerId,
                SeasonMoneyActionRequest.DungeonAdmission("mines_recon"),
                now,
            ) as SeasonMoneyActionPlan.Accepted
        return SeasonMoneyActionEngine.commit(catalog, unlocked, plan, now + 1).state
    }

    "reservation durably binds passes before one exact native instance launch" {
        val gate =
            SeasonDungeonLaunchGate(
                tokenTtlMillis = 1_000L,
                tokenIdFactory = { "launch-token-1" },
                runIdFactory = { "season-run-1" },
            )
        val purchased = purchasedState()
        gate.authorizeInstance(catalog, purchased, "em_id_the_mines", "em_id_the_mines_1", now) shouldBe null

        val reservation = gate.reserve(catalog, purchased, "mines_recon", setOf(playerId), now)
        val boundPass = reservation.state.admissionPasses.values.single()
        boundPass.status shouldBe DungeonAdmissionPassStatus.BOUND_TO_RUN
        boundPass.boundRunId shouldBe reservation.token.runId
        reservation.state.dungeonLaunchTokens.getValue(reservation.token.tokenId) shouldBe reservation.token

        gate.authorizeInstance(
            catalog,
            reservation.state,
            "em_id_the_bridge",
            "em_id_the_bridge_1",
            now + 1,
        ) shouldBe null
        val result =
            requireNotNull(
                gate.authorizeInstance(
                    catalog,
                    reservation.state,
                    "em_id_the_mines",
                    "em_id_the_mines_1",
                    now + 1,
                ),
            )
        result.authorization.runId shouldBe reservation.token.runId
        result.authorization.participantIds shouldBe setOf(playerId)
        result.state.dungeonLaunchTokens shouldBe emptyMap()
        result.state.authorizedDungeonRuns.getValue("em_id_the_mines_1") shouldBe result.authorization
        gate.authorizeInstance(
            catalog,
            result.state,
            "em_id_the_mines",
            "em_id_the_mines_1",
            now + 2,
        ) shouldBe null

        val started =
            gate.consumeAuthorizedRunAdmissions(catalog, result.state, "em_id_the_mines_1", now + 3)
        started.admissionPasses.values.single().status shouldBe DungeonAdmissionPassStatus.CONSUMED
        gate.consumeAuthorizedRunAdmissions(catalog, started, "em_id_the_mines_1", now + 4) shouldBe started

        val finished = gate.finishAuthorizedRun(catalog, started, "EM_ID_THE_MINES_1")
        finished.authorizedDungeonRuns shouldBe emptyMap()
        finished.admissionPasses.values.single().status shouldBe DungeonAdmissionPassStatus.CONSUMED
    }

    "native cancellation before clone releases authorization and passes" {
        val gate =
            SeasonDungeonLaunchGate(
                tokenIdFactory = { "launch-token-cancel" },
                runIdFactory = { "season-run-cancel" },
            )
        val reserved = gate.reserve(catalog, purchasedState(), "mines_recon", setOf(playerId), now)
        val authorized =
            requireNotNull(
                gate.authorizeInstance(
                    catalog,
                    reserved.state,
                    "em_id_the_mines",
                    "em_id_the_mines_cancelled",
                    now + 1,
                ),
            )
        val cancelled = gate.cancelAuthorizedRun(catalog, authorized.state, "em_id_the_mines_cancelled")
        cancelled.authorizedDungeonRuns shouldBe emptyMap()
        cancelled.admissionPasses.values.single().status shouldBe DungeonAdmissionPassStatus.AVAILABLE
    }

    "expired unconsumed launch returns exactly its bound pass to available" {
        val gate =
            SeasonDungeonLaunchGate(
                tokenTtlMillis = 1_000L,
                tokenIdFactory = { "launch-token-2" },
                runIdFactory = { "season-run-2" },
            )
        val reservation = gate.reserve(catalog, purchasedState(), "mines_recon", setOf(playerId), now)
        val released = gate.releaseExpired(catalog, reservation.state, now + 1_000L)
        val pass = released.admissionPasses.values.single()
        pass.status shouldBe DungeonAdmissionPassStatus.AVAILABLE
        pass.boundRunId shouldBe null
        pass.boundAt shouldBe null
        released.dungeonLaunchTokens shouldBe emptyMap()
        gate.authorizeInstance(catalog, released, "em_id_the_mines", "em_id_the_mines_1", now + 1_001L) shouldBe null
    }

    "restart releases authorization only when cloned instance world is missing" {
        val gate =
            SeasonDungeonLaunchGate(
                tokenIdFactory = { "launch-token-restart" },
                runIdFactory = { "season-run-restart" },
            )
        val reservation = gate.reserve(catalog, purchasedState(), "mines_recon", setOf(playerId), now)
        val authorized =
            requireNotNull(
                gate.authorizeInstance(
                    catalog,
                    reservation.state,
                    "em_id_the_mines",
                    "em_id_the_mines_7",
                    now + 1,
                ),
            ).state

        gate.releaseMissingAuthorizedRuns(catalog, authorized, setOf("em_id_the_mines_7")) shouldBe authorized
        val released = gate.releaseMissingAuthorizedRuns(catalog, authorized, emptySet())
        released.authorizedDungeonRuns shouldBe emptyMap()
        released.admissionPasses.values.single().status shouldBe DungeonAdmissionPassStatus.AVAILABLE
    }

    "reservation requires an open unlocked season and exact fresh passes" {
        val gate =
            SeasonDungeonLaunchGate(
                tokenIdFactory = { "launch-token-3" },
                runIdFactory = { "season-run-3" },
            )
        shouldThrow<IllegalArgumentException> {
            gate.reserve(
                catalog,
                SeasonRuntimeState.empty(catalog),
                "mines_recon",
                setOf(playerId),
                now,
            )
        }.message shouldBe "Season dungeon is still locked by project progress"

        val purchased = purchasedState()
        shouldThrow<IllegalArgumentException> {
            gate.reserve(catalog, purchased, "mines_recon", setOf(playerId), catalog.endsAt)
        }.message shouldBe "Cannot reserve a season dungeon launch outside the season window"

        val reserved = gate.reserve(catalog, purchased, "mines_recon", setOf(playerId), now)
        shouldThrow<IllegalArgumentException> {
            gate.reserve(catalog, reserved.state, "mines_recon", setOf(playerId), now + 1)
        }.message shouldBe "A season dungeon launch participant already has a pending token"
    }
})

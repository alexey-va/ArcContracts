package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID

class ContractOriginGateTest : StringSpec({
    val player = mockk<Player>()
    val origin = mockk<World>()
    val survival = mockk<World>()

    every { player.isOnline } returns true
    every { player.world } returns origin
    every { origin.name } returns "rc_origin_spawn"
    every { survival.name } returns "classic_survival"

    "accepts an unexpired click grant for its player and group" {
        val playerId = UUID.randomUUID()
        ContractOriginGate.isGrantUsable(
            ContractOriginGate.Grant(playerId, 390, "guild_orders", 2_000L),
            playerId,
            "guild_orders",
            1_999L,
        ) shouldBe true
    }

    "accepts grants for each canonical public desk group" {
        val playerId = UUID.randomUUID()
        listOf(
            ContractOriginGate.Grant(playerId, 350, "forge_orders", 2_000L),
            ContractOriginGate.Grant(playerId, 367, "bank_orders", 2_000L),
            ContractOriginGate.Grant(playerId, 390, "guild_orders", 2_000L),
        ).forEach { grant ->
            ContractOriginGate.isGrantUsable(grant, playerId, grant.group, 1_999L) shouldBe true
        }
    }

    "rejects a grant for another group or player" {
        val playerId = UUID.randomUUID()
        val grant = ContractOriginGate.Grant(playerId, 390, "guild_orders", 2_000L)
        ContractOriginGate.isGrantUsable(grant, playerId, "forge_orders", 1_000L) shouldBe false
        ContractOriginGate.isGrantUsable(grant, UUID.randomUUID(), "guild_orders", 1_000L) shouldBe false
    }

    "rejects an expired grant at its deadline" {
        val playerId = UUID.randomUUID()
        ContractOriginGate.isGrantUsable(
            ContractOriginGate.Grant(playerId, 390, "guild_orders", 2_000L),
            playerId,
            "guild_orders",
            2_000L,
        ) shouldBe false
    }

    "keeps the legacy location predicate limited to online Origin players" {
        ContractOriginGate.isInOrigin(player) shouldBe true
        every { player.world } returns survival
        ContractOriginGate.isInOrigin(player) shouldBe false
        every { player.world } returns origin
        every { player.isOnline } returns false
        ContractOriginGate.isInOrigin(player) shouldBe false
    }
})

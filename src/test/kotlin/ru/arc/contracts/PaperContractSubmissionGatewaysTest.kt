package ru.arc.contracts

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import net.kyori.adventure.text.Component
import net.milkbowl.vault.economy.EconomyResponse
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.testing.MockBukkitTestRuntime
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class PaperContractSubmissionGatewaysTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        paper.addSimpleWorld("rc_origin_spawn")
    }

    afterEach { paper.close() }

    "deposit transactions consume only the offered slots and recheck the NPC grant" {
        runTest {
            val player = paper.addPlayer("OfferedFish")
            player.teleport(paper.server.getWorld("rc_origin_spawn")!!.spawnLocation)
            player.inventory.setItem(0, ItemStack(Material.PUFFERFISH, 64))
            player.inventory.setItem(7, ItemStack(Material.COD, 8))
            io.mockk.mockkObject(ContractOriginGate)
            try {
                every { ContractOriginGate.canSubmit(player, "food_orders") } returns true
                val gateway = PaperContractInventoryGateway(PaperPlayerDataPersistence {})
                val prepared = gateway.prepareFromSlots(player.uniqueId.toString(), PaperContractItems.ANY_RAW_FISH,
                    8, "food_orders", setOf(7))!!
                gateway.prepareFromSlots(player.uniqueId.toString(), PaperContractItems.ANY_RAW_FISH,
                    9, "food_orders", setOf(7)) shouldBe null
                prepared.removeExact() shouldBe ContractInventoryMutation.Confirmed
                player.inventory.getItem(0)?.amount shouldBe 64
                player.inventory.getItem(7) shouldBe null
                prepared.restoreExact() shouldBe ContractInventoryMutation.Confirmed
                val next = gateway.prepareFromSlots(player.uniqueId.toString(), PaperContractItems.ANY_RAW_FISH,
                    8, "food_orders", setOf(7))!!
                every { ContractOriginGate.canSubmit(player, "food_orders") } returns false
                next.removeExact() shouldBe ContractInventoryMutation.NotPerformed("outside_origin")
                player.inventory.getItem(7)?.amount shouldBe 8
            } finally { io.mockk.unmockkObject(ContractOriginGate) }
        }
    }

    "any raw fish counts mixed species and restores their exact identity" {
        runTest {
            val player = paper.addPlayer("MixedFish")
            player.teleport(paper.server.getWorld("rc_origin_spawn")!!.spawnLocation)
            player.inventory.setItem(0, ItemStack(Material.COD, 3))
            player.inventory.setItem(1, ItemStack(Material.SALMON, 4))
            player.inventory.setItem(2, ItemStack(Material.TROPICAL_FISH, 2))
            player.inventory.setItem(3, ItemStack(Material.PUFFERFISH, 1))
            player.inventory.setItem(4, ItemStack(Material.COOKED_COD, 64))
            val named = ItemStack(Material.COD, 64)
            named.editMeta { it.displayName(Component.text("Special fish")) }
            player.inventory.setItem(5, named)
            PaperContractItems.countPlain(player, PaperContractItems.ANY_RAW_FISH) shouldBe 10
            val prepared = PaperContractInventoryGateway(PaperPlayerDataPersistence {})
                .prepare(player.uniqueId.toString(), PaperContractItems.ANY_RAW_FISH, 8)!!
            prepared.payloads.map { it.quantity } shouldContainExactly listOf(3, 4, 1)
            prepared.removeExact() shouldBe ContractInventoryMutation.Confirmed
            player.inventory.getItem(2)?.amount shouldBe 1
            player.inventory.getItem(4)?.amount shouldBe 64
            prepared.restoreExact() shouldBe ContractInventoryMutation.Confirmed
            player.inventory.getItem(0)?.type shouldBe Material.COD
            player.inventory.getItem(1)?.type shouldBe Material.SALMON
            player.inventory.getItem(2)?.amount shouldBe 2
        }
    }

    "removes and restores the exact prevalidated vanilla slots" {
        runTest {
            val player = paper.addPlayer("ContractMiner")
            player.teleport(paper.server.getWorld("rc_origin_spawn")!!.spawnLocation)
            player.inventory.setItem(0, ItemStack(Material.STONE, 5))
            player.inventory.setItem(1, ItemStack(Material.STONE, 10))
            // MockBukkit 4.116.3 saveData throws UnimplementedOperationException.
            // Record native inventory snapshots through the existing shared platform port.
            val savedAmounts = mutableListOf<Int>()
            val gateway = PaperContractInventoryGateway(PaperPlayerDataPersistence {
                savedAmounts += it.inventory.storageContents.filterNotNull().sumOf(ItemStack::getAmount)
            })

            player.isOnline shouldBe true
            player.inventory.getItem(0)!!.type.key.toString() shouldBe "minecraft:stone"

            val prepared = gateway.prepare(player.uniqueId.toString(), "minecraft:stone", 8)!!
            prepared.payloads.map { it.quantity }.shouldContainExactly(5, 3)

            prepared.removeExact() shouldBe ContractInventoryMutation.Confirmed
            player.inventory.getItem(0) shouldBe null
            player.inventory.getItem(1)?.amount shouldBe 7

            prepared.restoreExact() shouldBe ContractInventoryMutation.Confirmed
            player.inventory.getItem(0)?.amount shouldBe 5
            player.inventory.getItem(1)?.amount shouldBe 10
            savedAmounts shouldContainExactly listOf(7, 15)
        }
    }

    "persists removed and refunded inventory before acknowledging either mutation" {
        runTest {
            val nativePlayer = paper.addPlayer("DurableMiner")
            nativePlayer.inventory.setItem(0, ItemStack(Material.STONE, 4))
            val player = mockk<Player>()
            every { player.uniqueId } returns nativePlayer.uniqueId
            every { player.isOnline } returns true
            every { player.world } returns paper.server.getWorld("rc_origin_spawn")!!
            every { player.inventory } returns nativePlayer.inventory
            val savedAmounts = mutableListOf<Int>()
            every { player.saveData() } answers {
                savedAmounts += player.inventory.getItem(0)?.amount ?: 0
            }
            val prepared = PaperContractInventoryGateway { player }
                .prepare(player.uniqueId.toString(), "minecraft:stone", 2)!!

            prepared.removeExact() shouldBe ContractInventoryMutation.Confirmed
            savedAmounts shouldContainExactly listOf(2)
            prepared.restoreExact() shouldBe ContractInventoryMutation.Confirmed
            savedAmounts shouldContainExactly listOf(2, 4)
        }
    }

    "a native save failure never acknowledges removed or refunded items" {
        runTest {
            for (failedSave in 1..2) {
                val player = paper.addPlayer("SaveFailure$failedSave")
                player.teleport(paper.server.getWorld("rc_origin_spawn")!!.spawnLocation)
                player.inventory.setItem(0, ItemStack(Material.STONE, 4))
                var saves = 0
                val persistedAmounts = mutableListOf<Int>()
                val gateway = PaperContractInventoryGateway(PaperPlayerDataPersistence {
                    saves++
                    if (saves == failedSave) error("synthetic native save failure")
                    persistedAmounts += it.inventory.getItem(0)?.amount ?: 0
                })
                val prepared = gateway.prepare(player.uniqueId.toString(), "minecraft:stone", 2)!!
                if (failedSave == 1) {
                    prepared.removeExact() shouldBe ContractInventoryMutation.Ambiguous
                    persistedAmounts shouldBe emptyList()
                    player.inventory.getItem(0)?.amount shouldBe 2
                } else {
                    prepared.removeExact() shouldBe ContractInventoryMutation.Confirmed
                    prepared.restoreExact() shouldBe ContractInventoryMutation.Ambiguous
                    persistedAmounts shouldContainExactly listOf(2)
                    player.inventory.getItem(0)?.amount shouldBe 4
                    // RAM already contains the refund: do not blindly restore it again.
                    prepared.restoreExact() shouldBe ContractInventoryMutation.NotPerformed("refund_slot_changed")
                }
                saves shouldBe failedSave
            }
        }
    }

    "proves no mutation when a slot changes before removal" {
        runTest {
            val player = paper.addPlayer("ChangingMiner")
            player.teleport(paper.server.getWorld("rc_origin_spawn")!!.spawnLocation)
            player.inventory.setItem(0, ItemStack(Material.STONE, 8))
            player.isOnline shouldBe true
            player.inventory.getItem(0)!!.type.key.toString() shouldBe "minecraft:stone"
            val prepared =
                PaperContractInventoryGateway()
                    .prepare(player.uniqueId.toString(), "minecraft:stone", 8)!!
            player.inventory.setItem(0, ItemStack(Material.DIRT, 8))

            prepared.removeExact() shouldBe ContractInventoryMutation.NotPerformed("slot_changed")
            player.inventory.getItem(0)?.type shouldBe Material.DIRT
            player.inventory.getItem(0)?.amount shouldBe 8
        }
    }

    "a deadline that expires after preparation is checked on the inventory thread before removal" {
        runTest {
            val player = paper.addPlayer("LateMiner")
            player.teleport(paper.server.getWorld("rc_origin_spawn")!!.spawnLocation)
            player.inventory.setItem(0, ItemStack(Material.STONE, 8))
            var saves = 0
            val prepared = PaperContractInventoryGateway(PaperPlayerDataPersistence { saves++ })
                .prepare(player.uniqueId.toString(), "minecraft:stone", 8)!!
            var now = 1_500L
            val canRemove = {
                org.bukkit.Bukkit.isPrimaryThread() shouldBe true
                now <= 31_500L
            }
            now = 31_501L

            prepared.removeExact(canRemove) shouldBe ContractInventoryMutation.NotPerformed("submission_expired")
            player.inventory.getItem(0)?.amount shouldBe 8
            saves shouldBe 0
        }
    }

    "leaving Origin after preparation cannot remove resources" {
        runTest {
            val player = paper.addPlayer("TravellingMiner")
            player.teleport(paper.server.getWorld("rc_origin_spawn")!!.spawnLocation)
            player.inventory.setItem(0, ItemStack(Material.STONE, 8))
            val prepared = PaperContractInventoryGateway().prepare(player.uniqueId.toString(), "minecraft:stone", 8)!!
            player.teleport(paper.addSimpleWorld("survival").spawnLocation)
            prepared.removeExact() shouldBe ContractInventoryMutation.NotPerformed("outside_origin")
            player.inventory.getItem(0)?.amount shouldBe 8
            PaperContractInventoryGateway().prepare(player.uniqueId.toString(), "minecraft:stone", 8) shouldBe null
        }
    }

    "rejects custom namespaces and metadata-bearing vanilla variants" {
        runTest {
            val player = paper.addPlayer("NamedMiner")
            player.teleport(paper.server.getWorld("rc_origin_spawn")!!.spawnLocation)
            val namedStone = ItemStack(Material.STONE, 8)
            namedStone.itemMeta = namedStone.itemMeta.also { it.displayName(Component.text("Особый камень")) }
            player.inventory.setItem(0, namedStone)
            val gateway = PaperContractInventoryGateway()

            gateway.prepare(player.uniqueId.toString(), "minecraft:stone", 8) shouldBe null
            gateway.prepare(player.uniqueId.toString(), "slimefun:basic_machine", 1) shouldBe null
        }
    }

    "counts only plain matching stacks for a contract menu" {
        val player = paper.addPlayer("CountingMiner")
            player.teleport(paper.server.getWorld("rc_origin_spawn")!!.spawnLocation)
        player.inventory.setItem(0, ItemStack(Material.RAW_IRON, 32))
        player.inventory.setItem(1, ItemStack(Material.RAW_IRON, 7))
        val named = ItemStack(Material.RAW_IRON, 64)
        named.itemMeta = named.itemMeta.also { it.displayName(Component.text("Отмеченная руда")) }
        player.inventory.setItem(2, named)
        player.inventory.setItem(3, ItemStack(Material.COAL, 64))

        PaperContractItems.countPlain(player, "minecraft:raw_iron") shouldBe 39
        PaperContractItems.countPlain(player, "slimefun:raw_iron") shouldBe 0
    }

    "withdraws an exact season burn with the RedisEconomy reason API" {
        runTest {
            val playerId = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
            val currency = mockk<Currency>()
            val api = mockk<RedisEconomyAPI>()
            every { api.defaultCurrency } returns currency
            every { currency.currencyName } returns "vault"
            every { currency.transactionTax } returns 0.0
            every { currency.getBalance(playerId) } returnsMany listOf(100.0, 90.0)
            every { currency.withdrawPlayer(playerId, "vault", 10.0, "arc-season:dungeon_entry:action-pass-1") } returns
                EconomyResponse(10.0, 90.0, EconomyResponse.ResponseType.SUCCESS, null)

            RedisEconomySeasonMoneyGateway { api }.withdraw(
                playerId.toString(),
                1_000L,
                "arc-season:dungeon_entry:action-pass-1",
                10_000L,
            ) shouldBe SeasonMoneyEvidence(true, true, 9_000L)
            verify(exactly = 1) {
                currency.withdrawPlayer(playerId, "vault", 10.0, "arc-season:dungeon_entry:action-pass-1")
            }
        }
    }

    "skips the season provider call when tax or balance changed" {
        runTest {
            val playerId = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
            val currency = mockk<Currency>()
            val api = mockk<RedisEconomyAPI>()
            every { api.defaultCurrency } returns currency
            every { currency.getBalance(playerId) } returns 99.0
            every { currency.transactionTax } returns 0.0

            RedisEconomySeasonMoneyGateway { api }.withdraw(
                playerId.toString(),
                1_000L,
                "arc-season:dungeon_entry:action-pass-1",
                10_000L,
            ) shouldBe SeasonMoneyEvidence(false, false, 9_900L, "provider_balance_changed_before_call")
            verify(exactly = 0) { currency.withdrawPlayer(any<java.util.UUID>(), any(), any(), any()) }

            every { currency.getBalance(playerId) } returns 100.0
            every { currency.transactionTax } returns 0.01
            RedisEconomySeasonMoneyGateway { api }.withdraw(
                playerId.toString(),
                1_000L,
                "arc-season:dungeon_entry:action-pass-2",
                10_000L,
            ) shouldBe SeasonMoneyEvidence(false, false, 10_000L, "provider_transaction_tax_nonzero")
            verify(exactly = 0) { currency.withdrawPlayer(any<java.util.UUID>(), any(), any(), any()) }
        }
    }

    "proves a skipped season provider call when the API disappears" {
        runTest {
            val gateway = RedisEconomySeasonMoneyGateway { null }

            gateway.withdraw(
                "11111111-1111-1111-1111-111111111111",
                1_000L,
                "arc-season:dungeon_entry:action-pass-3",
                10_000L,
            ) shouldBe SeasonMoneyEvidence(false, false, null, "provider_unavailable")
        }
    }
})

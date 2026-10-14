package ru.arc.contracts

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class PaperSeasonTrophyItemsTest {
    @Test
    fun `created trophy has exact identity and owner evidence`() = MockBukkitTestRuntime.open().use {
        val owner = UUID.fromString("11111111-1111-1111-1111-111111111111")

        val trophy = PaperSeasonTrophyItems.create("arc:road_revival/mines_core", owner)

        trophy.type shouldBe Material.ECHO_SHARD
        trophy.amount shouldBe 1
        PaperSeasonTrophyItems.identity(trophy) shouldBe "arc:road_revival/mines_core"
        PaperSeasonTrophyItems.owner(trophy) shouldBe owner
        PaperSeasonTrophyItems.isBoundTrophy(trophy) shouldBe true
    }

    @Test
    fun `bound trophy cannot enter a container or a sell command`() = MockBukkitTestRuntime.open().use { paper ->
        val player = paper.addPlayer("TrophyOwner")
        val trophy = PaperSeasonTrophyItems.create("arc:road_revival/mines_core", player.uniqueId)
        val listener = SeasonTrophyProtectionListener()
        val view = requireNotNull(player.openInventory(Bukkit.createInventory(null, 9)))
        view.setCursor(trophy)
        val containerClick = InventoryClickEvent(
            view,
            InventoryType.SlotType.CONTAINER,
            0,
            ClickType.LEFT,
            InventoryAction.PLACE_ALL,
        )

        listener.onInventoryClick(containerClick)

        containerClick.isCancelled shouldBe true
        player.inventory.setItemInMainHand(trophy)
        val sell = PlayerCommandPreprocessEvent(player, "/sell hand")
        listener.onSellCommand(sell)
        sell.isCancelled shouldBe true
    }

    @Test
    fun `dungeon reward delivery writes and verifies one exact owner bound trophy`() =
        MockBukkitTestRuntime.open().use { paper -> runBlocking {
            val player = paper.addPlayer("DungeonWinner")
            val gateway = PaperSeasonDungeonTrophyDeliveryGateway { uuid -> player.takeIf { it.uniqueId == uuid } }
            val payload = gateway.createPayload(player.uniqueId.toString(), "arc:road_revival/mines_core")
            val delivery = requireNotNull(gateway.prepareDelivery(player.uniqueId.toString(), payload))

            delivery.deliverExact() shouldBe ContractInventoryMutation.Confirmed

            val trophy = player.inventory.storageContents.filterNotNull().single()
            PaperSeasonTrophyItems.identity(trophy) shouldBe "arc:road_revival/mines_core"
            PaperSeasonTrophyItems.owner(trophy) shouldBe player.uniqueId
        } }

    @Test
    fun `dungeon reward delivery proves no mutation when its prepared slot changed`() =
        MockBukkitTestRuntime.open().use { paper -> runBlocking {
            val player = paper.addPlayer("BusyDungeonWinner")
            val gateway = PaperSeasonDungeonTrophyDeliveryGateway { uuid -> player.takeIf { it.uniqueId == uuid } }
            val payload = gateway.createPayload(player.uniqueId.toString(), "arc:road_revival/mines_core")
            val delivery = requireNotNull(gateway.prepareDelivery(player.uniqueId.toString(), payload))
            player.inventory.setItem(0, ItemStack(Material.STONE))

            delivery.deliverExact() shouldBe ContractInventoryMutation.NotPerformed("slot_changed")
            player.inventory.storageContents.any { PaperSeasonTrophyItems.isBoundTrophy(it) } shouldBe false
        } }
}

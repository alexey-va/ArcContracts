package ru.arc.contracts

import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack

/** Keeps season trophies in the exact owner's player inventory until project consumption. */
class SeasonTrophyProtectionListener : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val hotbar =
            event.hotbarButton.takeIf { it >= 0 }
                ?.let(player.inventory::getItem)
        val involved = listOf(event.currentItem, event.cursor, hotbar).filter(PaperSeasonTrophyItems::isBoundTrophy)
        if (involved.any { PaperSeasonTrophyItems.owner(it) != player.uniqueId }) {
            event.isCancelled = true
            return
        }
        if (involved.isEmpty()) return

        val boundCursor = PaperSeasonTrophyItems.isBoundTrophy(event.cursor)
        val occupiedNonTrophyTarget =
            event.currentItem?.takeUnless { it.type.isAir }?.let { !PaperSeasonTrophyItems.isBoundTrophy(it) } == true
        val clickedTop = event.rawSlot in 0 until event.view.topInventory.size
        val shiftToOtherInventory = event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY
        val collectAcrossInventory = event.action == InventoryAction.COLLECT_TO_CURSOR
        val dropsOrClones =
            event.action in
                setOf(
                    InventoryAction.DROP_ALL_CURSOR,
                    InventoryAction.DROP_ONE_CURSOR,
                    InventoryAction.DROP_ALL_SLOT,
                    InventoryAction.DROP_ONE_SLOT,
                    InventoryAction.CLONE_STACK,
                )
        if (clickedTop || shiftToOtherInventory || collectAcrossInventory || dropsOrClones ||
            (boundCursor && occupiedNonTrophyTarget)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val trophy = event.oldCursor.takeIf(PaperSeasonTrophyItems::isBoundTrophy) ?: return
        if (PaperSeasonTrophyItems.owner(trophy) != player.uniqueId ||
            event.rawSlots.any { it in 0 until event.view.topInventory.size }
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryMove(event: InventoryMoveItemEvent) {
        if (PaperSeasonTrophyItems.isBoundTrophy(event.item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (PaperSeasonTrophyItems.isBoundTrophy(event.itemDrop.itemStack)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSellCommand(event: PlayerCommandPreprocessEvent) {
        val arguments = event.message.removePrefix("/").trim().lowercase().split(Regex("\\s+"))
        val root = arguments.firstOrNull()?.substringAfter(':') ?: return
        if (root !in SELL_COMMANDS) return
        val sellsAll = root == "sellall" || arguments.getOrNull(1) in setOf("all", "inventory")
        val candidates =
            if (sellsAll) event.player.inventory.storageContents.asSequence()
            else sequenceOf(event.player.inventory.itemInMainHand)
        if (candidates.any(PaperSeasonTrophyItems::isBoundTrophy)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val trophy = event.item.itemStack.takeIf(PaperSeasonTrophyItems::isBoundTrophy) ?: return
        val player = event.entity as? Player
        if (player == null || PaperSeasonTrophyItems.owner(trophy) != player.uniqueId) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMerge(event: ItemMergeEvent) {
        if (PaperSeasonTrophyItems.isBoundTrophy(event.entity.itemStack) ||
            PaperSeasonTrophyItems.isBoundTrophy(event.target.itemStack)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityDisplay(event: PlayerInteractEntityEvent) {
        if (event.rightClicked !is ItemFrame) return
        if (PaperSeasonTrophyItems.isBoundTrophy(event.player.inventory.getItem(event.hand))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onArmorStand(event: PlayerArmorStandManipulateEvent) {
        if (PaperSeasonTrophyItems.isBoundTrophy(event.playerItem) ||
            PaperSeasonTrophyItems.isBoundTrophy(event.armorStandItem)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        val trophies = event.drops.filter(PaperSeasonTrophyItems::isBoundTrophy)
        if (trophies.isEmpty()) return
        event.drops.removeAll(trophies.toSet())
        trophies.map(ItemStack::clone).forEach(event.itemsToKeep::add)
    }

    private companion object {
        val SELL_COMMANDS = setOf("sell", "sellall")
    }
}

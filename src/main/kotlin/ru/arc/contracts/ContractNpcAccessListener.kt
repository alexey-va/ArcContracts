package ru.arc.contracts

import net.citizensnpcs.api.event.NPCRightClickEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/** Bridges the verified Citizens click into the transient contract desk grant. */
class ContractNpcAccessListener : Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onNpcRightClick(event: NPCRightClickEvent) {
        val player = event.clicker
        // The existing Citizens CommandTrait opens the GUI after this event.
        // Grant first so that command remains a harmless follow-up bridge.
        ContractOriginGate.grantFromNpcClick(player, event.npc)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun revokeCancelledNpcClick(event: NPCRightClickEvent) {
        if (event.isCancelled) ContractOriginGate.revoke(event.clicker.uniqueId)
        else ContractOriginGate.groupForNpc(event.npc.id)?.let { group ->
            if (ContractOriginGate.canSubmit(event.clicker, group)) NpcContractDepositGui.open(event.clicker, group)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        ContractOriginGate.revoke(event.player.uniqueId)
    }
}

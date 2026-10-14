package ru.arc.contracts

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.core.Tasks
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Paper inventory adapter for plain vanilla resource orders. Custom namespaces
 * and any ItemMeta/NBT-bearing variants fail closed until an exact resolver is
 * implemented for that item family.
 */
class PaperContractInventoryGateway(
    private val persistence: PaperPlayerDataPersistence = NativePaperPlayerDataPersistence,
    private val playerLookup: (UUID) -> Player? = Bukkit::getPlayer,
) : ContractInventoryGateway {
    override suspend fun prepare(
        playerId: String,
        itemKey: String,
        quantity: Int,
    ): PreparedContractInventory? = prepareInternal(playerId, itemKey, quantity, contractGroup = null)

    override suspend fun prepare(
        playerId: String,
        itemKey: String,
        quantity: Int,
        contractGroup: String,
    ): PreparedContractInventory? = prepareInternal(playerId, itemKey, quantity, contractGroup)

    override suspend fun prepareFromSlots(
        playerId: String, itemKey: String, quantity: Int, contractGroup: String, slots: Set<Int>,
    ): PreparedContractInventory? = prepareInternal(playerId, itemKey, quantity, contractGroup, slots)

    private suspend fun prepareInternal(
        playerId: String,
        itemKey: String,
        quantity: Int,
        contractGroup: String?,
        sourceSlots: Set<Int>? = null,
    ): PreparedContractInventory? =
        onBukkitMain {
            val uuid = runCatching { UUID.fromString(playerId) }.getOrNull() ?: return@onBukkitMain null
            val player = playerLookup(uuid)?.takeIf { it.isOnline } ?: return@onBukkitMain null
            if (contractGroup != null) {
                if (!ContractOriginGate.canSubmit(player, contractGroup)) return@onBukkitMain null
            } else if (!ContractOriginGate.isInOrigin(player)) {
                return@onBukkitMain null
            }
            val material = PaperContractItems.material(itemKey) ?: return@onBukkitMain null
            require(quantity in 1..EscrowedItemPayload.MAX_ITEM_QUANTITY) { "Invalid contract inventory quantity" }
            require(sourceSlots == null || sourceSlots.isNotEmpty() && sourceSlots.all { it in 0..35 })

            var remaining = quantity
            val slots = mutableListOf<PaperContractSlotPlan>()
            player.inventory.storageContents.forEachIndexed { slot, stack ->
                if (remaining == 0 || sourceSlots != null && slot !in sourceSlots ||
                    stack == null || !PaperContractItems.isPlainExact(stack, material, itemKey)) {
                    return@forEachIndexed
                }
                val take = minOf(stack.amount, remaining)
                val removed = stack.clone().also { it.amount = take }
                slots +=
                    PaperContractSlotPlan(
                        slot = slot,
                        removeQuantity = take,
                        beforeBytes = stack.serializeAsBytes(),
                        payload = PaperContractItemPayloadCodec.captureVerified(itemKey, removed),
                    )
                remaining -= take
            }
            if (remaining != 0 || slots.isEmpty()) return@onBukkitMain null
            PaperPreparedContractInventory(uuid, playerLookup, slots, persistence, contractGroup)
        }

}

internal object PaperContractItems {
    const val ANY_RAW_FISH = "arc:any_raw_fish"
    val rawFishKeys = setOf("minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish", "minecraft:pufferfish")

    fun material(itemKey: String): Material? {
        if (itemKey == ANY_RAW_FISH) return Material.COD
        if (!itemKey.startsWith("minecraft:")) return null
        val material = Material.matchMaterial(itemKey.substringAfter(':')) ?: return null
        return material.takeIf { it.isItem && !it.isAir }
    }

    fun isPlainExact(stack: ItemStack, material: Material, itemKey: String): Boolean =
        matchesKey(itemKey, stack.type.key.toString()) &&
            (itemKey == ANY_RAW_FISH || stack.type == material) &&
            stack.isSimilar(ItemStack(stack.type, stack.amount))

    fun matchesKey(orderKey: String, stackKey: String): Boolean =
        if (orderKey == ANY_RAW_FISH) stackKey in rawFishKeys else orderKey == stackKey

    fun countPlain(player: Player, itemKey: String): Int {
        check(Bukkit.isPrimaryThread()) { "Contract inventory count must run on the Bukkit main thread" }
        val material = material(itemKey) ?: return 0
        return player.inventory.storageContents
            .asSequence()
            .filterNotNull()
            .filter { isPlainExact(it, material, itemKey) }
            .sumOf(ItemStack::getAmount)
    }
}

private data class PaperContractSlotPlan(
    val slot: Int,
    val removeQuantity: Int,
    val beforeBytes: ByteArray,
    val payload: EscrowedItemPayload,
)

private class PaperPreparedContractInventory(
    private val playerId: UUID,
    private val playerLookup: (UUID) -> Player?,
    private val slots: List<PaperContractSlotPlan>,
    private val persistence: PaperPlayerDataPersistence,
    private val contractGroup: String?,
) : PreparedContractInventory {
    override val payloads: List<EscrowedItemPayload> = slots.map { it.payload }
    private var removed = false

    override suspend fun removeExact(canRemove: () -> Boolean): ContractInventoryMutation =
        onBukkitMain {
            if (removed) return@onBukkitMain ContractInventoryMutation.Ambiguous
            val player = playerLookup(playerId)?.takeIf { it.isOnline }
                ?: return@onBukkitMain ContractInventoryMutation.NotPerformed("player_offline")
            val gateOpen = if (contractGroup != null) {
                ContractOriginGate.canSubmit(player, contractGroup)
            } else {
                ContractOriginGate.isInOrigin(player)
            }
            if (!gateOpen) {
                return@onBukkitMain ContractInventoryMutation.NotPerformed("outside_origin")
            }
            val inventory = player.inventory
            if (slots.any { plan -> !inventory.getItem(plan.slot).sameBytes(plan.beforeBytes) }) {
                return@onBukkitMain ContractInventoryMutation.NotPerformed("slot_changed")
            }

            if (!canRemove()) return@onBukkitMain ContractInventoryMutation.NotPerformed("submission_expired")

            try {
                slots.forEach { plan ->
                    val current = requireNotNull(inventory.getItem(plan.slot))
                    val remaining = current.amount - plan.removeQuantity
                    require(remaining >= 0) { "Contract slot underflow" }
                    if (remaining == 0) {
                        inventory.setItem(plan.slot, null)
                    } else {
                        inventory.setItem(plan.slot, current.clone().also { it.amount = remaining })
                    }
                }
                if (slots.any { plan -> !inventory.getItem(plan.slot).isExpectedAfterRemoval(plan) }) {
                    return@onBukkitMain ContractInventoryMutation.Ambiguous
                }
                // Persist native inventory before the durable journal can advance to payment.
                // A failed save has an unknown outcome and must not trigger a blind refund.
                persistence.persist(player)
                removed = true
                ContractInventoryMutation.Confirmed
            } catch (_: Throwable) {
                ContractInventoryMutation.Ambiguous
            }
        }

    override suspend fun restoreExact(): ContractInventoryMutation =
        onBukkitMain {
            val player = playerLookup(playerId)?.takeIf { it.isOnline }
                ?: return@onBukkitMain ContractInventoryMutation.NotPerformed("player_offline")
            val inventory = player.inventory
            if (!removed) {
                return@onBukkitMain if (slots.all { inventory.getItem(it.slot).sameBytes(it.beforeBytes) }) {
                    ContractInventoryMutation.Confirmed
                } else {
                    ContractInventoryMutation.NotPerformed("escrow_not_removed")
                }
            }
            if (slots.any { plan -> !inventory.getItem(plan.slot).isExpectedAfterRemoval(plan) }) {
                return@onBukkitMain ContractInventoryMutation.NotPerformed("refund_slot_changed")
            }

            try {
                slots.forEach { plan -> inventory.setItem(plan.slot, ItemStack.deserializeBytes(plan.beforeBytes)) }
                if (slots.any { plan -> !inventory.getItem(plan.slot).sameBytes(plan.beforeBytes) }) {
                    return@onBukkitMain ContractInventoryMutation.Ambiguous
                }
                // REFUNDED must not outlive the restored inventory after a process crash.
                persistence.persist(player)
                removed = false
                ContractInventoryMutation.Confirmed
            } catch (_: Throwable) {
                ContractInventoryMutation.Ambiguous
            }
        }

    private fun ItemStack?.isExpectedAfterRemoval(plan: PaperContractSlotPlan): Boolean {
        val before = runCatching { ItemStack.deserializeBytes(plan.beforeBytes) }.getOrNull() ?: return false
        val expectedAmount = before.amount - plan.removeQuantity
        return if (expectedAmount == 0) {
            this == null || type.isAir
        } else {
            sameBytes(before.clone().also { it.amount = expectedAmount }.serializeAsBytes())
        }
    }
}

private fun ItemStack?.sameBytes(expected: ByteArray): Boolean =
    this != null && runCatching { serializeAsBytes().contentEquals(expected) }.getOrDefault(false)

internal suspend fun <T> onBukkitMain(block: () -> T): T {
    if (Bukkit.isPrimaryThread()) return block()
    return suspendCancellableCoroutine { continuation ->
        try {
            Tasks.scheduler.runSync(
                Runnable {
                    if (!continuation.isActive) return@Runnable
                    try {
                        continuation.resume(block())
                    } catch (failure: Throwable) {
                        continuation.resumeWithException(failure)
                    }
                },
            )
        } catch (failure: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
}

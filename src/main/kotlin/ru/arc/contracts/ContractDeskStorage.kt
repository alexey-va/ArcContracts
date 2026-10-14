package ru.arc.contracts

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.menu.PaperCloudStorage
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Offered items and native inventory live in the same player-data save. */
internal class ContractDeskStorage(
    private val player: Player,
    private val group: String,
    private val accepts: (ItemStack) -> Boolean,
    private val persistence: PaperPlayerDataPersistence = NativePaperPlayerDataPersistence,
) : PaperCloudStorage {
    private val key = NamespacedKey("arc", "contract_desk_$group")
    var pending = false
    private var dirty = false

    private fun hasNonEmptyCursor(): Boolean {
        val cursor = player.openInventory.cursor
        return cursor != null && !cursor.type.isAir && cursor.amount > 0
    }

    override fun snapshot(): List<ItemStack?> = player.persistentDataContainer.get(key, PersistentDataType.BYTE_ARRAY)
        ?.let(::decodeContractDeskItems) ?: List(ContractDeskLayout.DEPOSIT_CAPACITY) { null }

    override fun compareAndSet(expected: List<ItemStack?>, replacement: List<ItemStack?>): Boolean {
        if (pending || !player.isOnline || replacement.any { item -> item != null &&
                expected.none { it?.isSimilar(item) == true } && !accepts(item) }) return false
        return replace(expected, replacement)
    }

    private fun replace(expected: List<ItemStack?>, replacement: List<ItemStack?>): Boolean {
        if (snapshot() != expected) return false
        require(replacement.size == ContractDeskLayout.DEPOSIT_CAPACITY)
        if (replacement.all { it == null }) player.persistentDataContainer.remove(key)
        else player.persistentDataContainer.set(key, PersistentDataType.BYTE_ARRAY, encodeContractDeskItems(replacement))
        dirty = true
        return true
    }

    /** Called after the cloud chest has synchronously updated native slots/cursor. */
    fun persistTransfer() {
        if (!dirty) return
        persistence.persist(player)
        dirty = false
    }

    fun returnItems() {
        if (pending) return
        val before = snapshot()
        if (before.all { it == null }) return
        val remaining = before.map { item -> item?.let { player.inventory.addItem(it.clone()).values.firstOrNull() } }
        check(replace(before, remaining))
        persistTransfer()
    }

    fun prepare(itemKey: String, quantity: Int): PreparedContractInventory? {
        if (!pending || !ContractOriginGate.canSubmit(player, group)) return null
        val material = PaperContractItems.material(itemKey) ?: return null
        val before = snapshot()
        val after = before.map { it?.clone() }.toMutableList()
        val payloads = mutableListOf<EscrowedItemPayload>()
        var left = quantity
        before.forEachIndexed { slot, item ->
            if (left == 0 || item == null || !PaperContractItems.isPlainExact(item, material, itemKey)) return@forEachIndexed
            val take = minOf(left, item.amount)
            payloads += PaperContractItemPayloadCodec.captureVerified(itemKey, item.clone().also { it.amount = take })
            after[slot] = (item.amount - take).takeIf { it > 0 }?.let { amount -> item.clone().also { it.amount = amount } }
            left -= take
        }
        if (quantity <= 0 || left != 0) return null
        if (hasNonEmptyCursor()) return null
        // Native bottom-slot moves do not dirty the desk. Persist their settled
        // before-image before the coordinator records ITEM_REMOVAL_STARTED.
        persistence.persist(player)
        dirty = false
        return object : PreparedContractInventory {
            override val payloads = payloads.toList()
            private var removed = false
            override suspend fun removeExact(canRemove: () -> Boolean): ContractInventoryMutation = onBukkitMain {
                when {
                    removed -> ContractInventoryMutation.Ambiguous
                    !player.isOnline || !pending || !ContractOriginGate.canSubmit(player, group) ->
                        ContractInventoryMutation.NotPerformed("desk_unavailable")
                    !canRemove() -> ContractInventoryMutation.NotPerformed("submission_expired")
                    hasNonEmptyCursor() -> ContractInventoryMutation.NotPerformed("cursor_not_empty")
                    !replace(before, after) -> ContractInventoryMutation.NotPerformed("slot_changed")
                    else -> try {
                        persistTransfer()
                        removed = true
                        ContractInventoryMutation.Confirmed
                    } catch (_: Throwable) { ContractInventoryMutation.Ambiguous }
                }
            }
            override suspend fun restoreExact(): ContractInventoryMutation = onBukkitMain {
                if (!player.isOnline) return@onBukkitMain ContractInventoryMutation.NotPerformed("player_offline")
                if (!removed) return@onBukkitMain if (snapshot() == before) ContractInventoryMutation.Confirmed
                    else ContractInventoryMutation.NotPerformed("escrow_not_removed")
                if (hasNonEmptyCursor()) return@onBukkitMain ContractInventoryMutation.NotPerformed("cursor_not_empty")
                if (!replace(after, before)) return@onBukkitMain ContractInventoryMutation.NotPerformed("refund_slot_changed")
                try {
                    persistTransfer()
                    removed = false
                    ContractInventoryMutation.Confirmed
                } catch (_: Throwable) { ContractInventoryMutation.Ambiguous }
            }
        }
    }
}

internal fun encodeContractDeskItems(items: List<ItemStack?>): ByteArray {
    require(items.size == 9 || items.size == ContractDeskLayout.DEPOSIT_CAPACITY)
    return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        if (items.size == ContractDeskLayout.DEPOSIT_CAPACITY) out.writeInt(-24)
        items.forEach { item ->
            val payload = item?.serializeAsBytes()
            out.writeInt(payload?.size ?: -1)
            if (payload != null) out.write(payload)
        }
    } }.toByteArray()
}

internal fun decodeContractDeskItems(bytes: ByteArray): List<ItemStack?> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
    require(bytes.size <= 256 * 1024)
    val first = input.readInt()
    val expanded = first == -24
    val items = List(if (expanded) ContractDeskLayout.DEPOSIT_CAPACITY else 9) { slot ->
        val payloadSize = if (!expanded && slot == 0) first else input.readInt()
        if (payloadSize == -1) null else {
            require(payloadSize in 1..65536 && payloadSize <= input.available())
            ItemStack.deserializeBytes(input.readNBytes(payloadSize))
        }
    }
    require(input.available() == 0)
    // Old desks have nine unversioned records. The new negative marker keeps truncation fail-closed.
    items + List(ContractDeskLayout.DEPOSIT_CAPACITY - items.size) { null }
}

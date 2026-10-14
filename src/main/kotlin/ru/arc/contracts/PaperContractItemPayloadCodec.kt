package ru.arc.contracts

import org.bukkit.inventory.ItemStack

/** Paper adapter for exact NBT item snapshots stored by the write-ahead journal. */
object PaperContractItemPayloadCodec {
    /** The caller must verify the canonical vanilla/custom item key before capture. */
    fun captureVerified(itemKey: String, stack: ItemStack): EscrowedItemPayload {
        require(!stack.type.isAir && stack.amount > 0) { "Cannot escrow an empty item stack" }
        val snapshot = stack.clone()
        return EscrowedItemPayload.capture(itemKey, snapshot.amount, snapshot.serializeAsBytes())
    }

    fun restore(payload: EscrowedItemPayload): ItemStack {
        val restored = ItemStack.deserializeBytes(payload.decodedBytes())
        require(!restored.type.isAir && restored.amount == payload.quantity) {
            "Restored item stack does not match journal quantity"
        }
        return restored
    }
}

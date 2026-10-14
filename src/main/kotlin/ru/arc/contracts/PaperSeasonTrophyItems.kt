package ru.arc.contracts

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import ru.arc.contracts.api.ArcContractTrophyMarkers
import java.util.UUID

object PaperSeasonTrophyItems {
    private val itemKey: NamespacedKey by lazy {
        requireNotNull(NamespacedKey.fromString(ArcContractTrophyMarkers.IDENTITY_NAMESPACED_KEY))
    }
    private val ownerKey: NamespacedKey by lazy {
        requireNotNull(NamespacedKey.fromString(ArcContractTrophyMarkers.OWNER_NAMESPACED_KEY))
    }

    fun identity(stack: ItemStack?): String? = ArcContractTrophyMarkers.identity(stack)

    fun owner(stack: ItemStack?): UUID? = ArcContractTrophyMarkers.owner(stack)

    fun isBoundTrophy(stack: ItemStack?): Boolean = identity(stack) != null && owner(stack) != null

    fun supports(exactItemKey: String): Boolean = exactItemKey in DESIGNS

    fun create(exactItemKey: String, owner: UUID, amount: Int = 1): ItemStack {
        val design = requireNotNull(DESIGNS[exactItemKey]) { "Unknown season trophy item design" }
        require(amount in 1..design.material.maxStackSize) { "Invalid season trophy stack amount" }
        val stack = ItemStack(design.material, amount)
        stack.editMeta { meta ->
            meta.displayName(Component.text(design.displayName, NamedTextColor.GOLD))
            meta.lore(
                listOf(
                    Component.text("Связанный трофей сезона", NamedTextColor.YELLOW),
                    Component.text("Можно только передать в экспедиционный музей", NamedTextColor.GRAY),
                ),
            )
        }
        return bind(stack, exactItemKey, owner)
    }

    fun bind(stack: ItemStack, exactItemKey: String, owner: UUID): ItemStack {
        require(ResourceContractDefinition.normalizeItemKey(exactItemKey) == exactItemKey) {
            "Season trophy item key must be normalized"
        }
        val bound = stack.clone()
        require(!bound.type.isAir && bound.amount > 0) { "Cannot bind an empty season trophy" }
        bound.editMeta { meta ->
            meta.persistentDataContainer.set(itemKey, org.bukkit.persistence.PersistentDataType.STRING, exactItemKey)
            meta.persistentDataContainer.set(ownerKey, org.bukkit.persistence.PersistentDataType.STRING, owner.toString())
        }
        return bound
    }

    private data class TrophyDesign(val material: Material, val displayName: String)

    private val DESIGNS =
        mapOf(
            "arc:road_revival/mines_core" to TrophyDesign(Material.ECHO_SHARD, "Сердце старой шахты"),
            "arc:road_revival/bridge_relic" to TrophyDesign(Material.HEART_OF_THE_SEA, "Реликвия древнего моста"),
            "arc:road_revival/quarry_permit" to TrophyDesign(Material.PAPER, "Печать каменоломни"),
            "arc:road_revival/deep_mines_gear" to TrophyDesign(Material.NETHERITE_SCRAP, "Шестерня глубоких шахт"),
        )
}

class PaperSeasonTrophyInventoryGateway(
    private val playerLookup: (UUID) -> Player? = Bukkit::getPlayer,
) : ContractInventoryGateway {
    override suspend fun prepare(playerId: String, itemKey: String, quantity: Int): PreparedContractInventory? =
        onBukkitMain {
            val uuid = runCatching { UUID.fromString(playerId) }.getOrNull() ?: return@onBukkitMain null
            val player = playerLookup(uuid)?.takeIf { it.isOnline } ?: return@onBukkitMain null
            require(quantity in 1..EscrowedItemPayload.MAX_ITEM_QUANTITY) { "Invalid season trophy quantity" }
            var remaining = quantity
            val slots = mutableListOf<PaperSeasonTrophySlotPlan>()
            player.inventory.storageContents.forEachIndexed { slot, stack ->
                if (remaining == 0 || stack == null ||
                    PaperSeasonTrophyItems.identity(stack) != itemKey || PaperSeasonTrophyItems.owner(stack) != uuid
                ) return@forEachIndexed
                val take = minOf(stack.amount, remaining)
                val removed = stack.clone().also { it.amount = take }
                slots +=
                    PaperSeasonTrophySlotPlan(
                        slot = slot,
                        removeQuantity = take,
                        beforeBytes = stack.serializeAsBytes(),
                        payload = PaperContractItemPayloadCodec.captureVerified(itemKey, removed),
                    )
                remaining -= take
            }
            if (remaining != 0 || slots.isEmpty()) return@onBukkitMain null
            PaperPreparedSeasonTrophyInventory(uuid, playerLookup, slots)
        }
}

class PaperSeasonDungeonTrophyDeliveryGateway(
    private val playerLookup: (UUID) -> Player? = Bukkit::getPlayer,
) : SeasonDungeonTrophyDeliveryGateway {
    override suspend fun createPayload(playerId: String, itemKey: String): EscrowedItemPayload =
        onBukkitMain {
            val owner = UUID.fromString(playerId)
            val trophy = PaperSeasonTrophyItems.create(itemKey, owner)
            PaperContractItemPayloadCodec.captureVerified(itemKey, trophy)
        }

    override suspend fun prepareDelivery(
        playerId: String,
        payload: EscrowedItemPayload,
    ): PreparedSeasonDungeonTrophyDelivery? =
        onBukkitMain {
            val owner = runCatching { UUID.fromString(playerId) }.getOrNull() ?: return@onBukkitMain null
            val player = playerLookup(owner)?.takeIf { it.isOnline } ?: return@onBukkitMain null
            val trophy = runCatching { PaperContractItemPayloadCodec.restore(payload.validated()) }.getOrNull()
                ?: return@onBukkitMain null
            if (PaperSeasonTrophyItems.identity(trophy) != payload.itemKey || PaperSeasonTrophyItems.owner(trophy) != owner) {
                return@onBukkitMain null
            }
            val contents = player.inventory.storageContents
            val slot =
                contents.indices.firstOrNull { index ->
                    val current = contents[index]
                    current != null && current.isSimilar(trophy) && current.amount < current.maxStackSize
                } ?: contents.indices.firstOrNull { index -> contents[index] == null || contents[index]?.type?.isAir == true }
                ?: return@onBukkitMain null
            val before = contents[slot]?.takeUnless { it.type.isAir }?.serializeAsBytes()
            val after =
                if (before == null) {
                    trophy.clone()
                } else {
                    ItemStack.deserializeBytes(before).also { it.amount = Math.addExact(it.amount, trophy.amount) }
                }
            if (after.amount > after.maxStackSize) return@onBukkitMain null
            PaperPreparedSeasonDungeonTrophyDelivery(
                playerId = owner,
                playerLookup = playerLookup,
                slot = slot,
                beforeBytes = before,
                afterBytes = after.serializeAsBytes(),
            )
        }
}

private class PaperPreparedSeasonDungeonTrophyDelivery(
    private val playerId: UUID,
    private val playerLookup: (UUID) -> Player?,
    private val slot: Int,
    private val beforeBytes: ByteArray?,
    private val afterBytes: ByteArray,
) : PreparedSeasonDungeonTrophyDelivery {
    private var attempted = false

    override suspend fun deliverExact(): ContractInventoryMutation =
        onBukkitMain {
            if (attempted) return@onBukkitMain ContractInventoryMutation.Ambiguous
            val player = playerLookup(playerId)?.takeIf { it.isOnline }
                ?: return@onBukkitMain ContractInventoryMutation.NotPerformed("player_offline")
            val inventory = player.inventory
            if (!sameNullableBytes(inventory.getItem(slot), beforeBytes)) {
                return@onBukkitMain ContractInventoryMutation.NotPerformed("slot_changed")
            }
            attempted = true
            try {
                inventory.setItem(slot, ItemStack.deserializeBytes(afterBytes))
                if (!sameNullableBytes(inventory.getItem(slot), afterBytes)) {
                    ContractInventoryMutation.Ambiguous
                } else {
                    ContractInventoryMutation.Confirmed
                }
            } catch (_: Throwable) {
                ContractInventoryMutation.Ambiguous
            }
        }

    private fun sameNullableBytes(stack: ItemStack?, expected: ByteArray?): Boolean =
        if (expected == null) {
            stack == null || stack.type.isAir
        } else {
            stack != null && runCatching { stack.serializeAsBytes().contentEquals(expected) }.getOrDefault(false)
        }
}

private data class PaperSeasonTrophySlotPlan(
    val slot: Int,
    val removeQuantity: Int,
    val beforeBytes: ByteArray,
    val payload: EscrowedItemPayload,
)

private class PaperPreparedSeasonTrophyInventory(
    private val playerId: UUID,
    private val playerLookup: (UUID) -> Player?,
    private val slots: List<PaperSeasonTrophySlotPlan>,
) : PreparedContractInventory {
    override val payloads: List<EscrowedItemPayload> = slots.map { it.payload }
    private var removed = false

    override suspend fun removeExact(canRemove: () -> Boolean): ContractInventoryMutation =
        onBukkitMain {
            if (removed) return@onBukkitMain ContractInventoryMutation.Ambiguous
            val player = playerLookup(playerId)?.takeIf { it.isOnline }
                ?: return@onBukkitMain ContractInventoryMutation.NotPerformed("player_offline")
            val inventory = player.inventory
            if (slots.any { plan -> !sameBytes(inventory.getItem(plan.slot), plan.beforeBytes) }) {
                return@onBukkitMain ContractInventoryMutation.NotPerformed("slot_changed")
            }
            if (!canRemove()) return@onBukkitMain ContractInventoryMutation.NotPerformed("submission_expired")

            try {
                slots.forEach { plan ->
                    val current = requireNotNull(inventory.getItem(plan.slot))
                    val remainder = current.amount - plan.removeQuantity
                    require(remainder >= 0) { "Season trophy inventory underflow" }
                    inventory.setItem(
                        plan.slot,
                        if (remainder == 0) null else current.clone().also { it.amount = remainder },
                    )
                }
                if (slots.any { plan -> !expectedAfterRemoval(inventory.getItem(plan.slot), plan) }) {
                    return@onBukkitMain ContractInventoryMutation.Ambiguous
                }
                removed = true
                ContractInventoryMutation.Confirmed
            } catch (_: Throwable) {
                ContractInventoryMutation.Ambiguous
            }
        }

    /** Automatic trophy refunds are deliberately unsupported after process loss. */
    override suspend fun restoreExact(): ContractInventoryMutation = ContractInventoryMutation.NotPerformed("unsupported")

    private fun expectedAfterRemoval(stack: ItemStack?, plan: PaperSeasonTrophySlotPlan): Boolean {
        val before = runCatching { ItemStack.deserializeBytes(plan.beforeBytes) }.getOrNull() ?: return false
        val remainder = before.amount - plan.removeQuantity
        return if (remainder == 0) {
            stack == null || stack.type.isAir
        } else {
            sameBytes(stack, before.clone().also { it.amount = remainder }.serializeAsBytes())
        }
    }

    private fun sameBytes(stack: ItemStack?, expected: ByteArray): Boolean =
        stack != null && runCatching { stack.serializeAsBytes().contentEquals(expected) }.getOrDefault(false)
}

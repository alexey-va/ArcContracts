package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.testing.MockBukkitTestRuntime

class ContractDeskStorageTest : StringSpec({
    "staging persists with native slots, survives reopening and returns exact species on close" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.server.addPlayer()
            val saved = mutableListOf<Pair<Int, Int>>()
            lateinit var storage: ContractDeskStorage
            storage = ContractDeskStorage(player, "food_orders", { it.type == Material.COD || it.type == Material.SALMON },
                PaperPlayerDataPersistence { saved += player.inventory.storageContents.filterNotNull().sumOf { it.amount } to
                    storage.snapshot().filterNotNull().sumOf { it.amount } })
            player.inventory.setItem(0, ItemStack(Material.COD, 8))
            val before = storage.snapshot()
            val offered = before.toMutableList().also { it[2] = player.inventory.getItem(0)?.clone() }
            storage.compareAndSet(before, offered) shouldBe true
            saved.size shouldBe 0 // The chest updates native inventory before the shared save.
            player.inventory.setItem(0, null)
            storage.persistTransfer()
            saved shouldBe listOf(0 to 8)
            storage.compareAndSet(before, offered) shouldBe false
            storage.compareAndSet(offered, offered.toMutableList().also { it[3] = ItemStack(Material.DIAMOND) }) shouldBe false
            val reopened = ContractDeskStorage(player, "food_orders", { true }, PaperPlayerDataPersistence {})
            reopened.snapshot() shouldBe offered
            reopened.returnItems()
            reopened.snapshot() shouldBe List(24) { null }
            player.inventory.storageContents.filterNotNull() shouldBe listOf(ItemStack(Material.COD, 8))
        }
    }
    "confirmed sale removes only staged items and refund is exact durable CAS with the final NPC gate" {
        MockBukkitTestRuntime.open().use { runtime -> runTest {
            val player = runtime.server.addPlayer()
            player.inventory.setItem(0, ItemStack(Material.PUFFERFISH, 64))
            val storage = ContractDeskStorage(player, "food_orders", { true }, PaperPlayerDataPersistence {})
            val offered = storage.snapshot().toMutableList().also {
                it[0] = ItemStack(Material.COD, 3); it[1] = ItemStack(Material.SALMON, 4)
            }
            storage.compareAndSet(storage.snapshot(), offered) shouldBe true
            mockkObject(ContractOriginGate)
            try {
                every { ContractOriginGate.canSubmit(player, "food_orders") } returns true
                storage.prepare(PaperContractItems.ANY_RAW_FISH, 5) shouldBe null
                storage.pending = true
                storage.compareAndSet(offered, List(24) { null }) shouldBe false
                val prepared = storage.prepare(PaperContractItems.ANY_RAW_FISH, 5)!!
                prepared.payloads.map { it.quantity } shouldBe listOf(3, 2)
                prepared.removeExact() shouldBe ContractInventoryMutation.Confirmed
                storage.snapshot()[0] shouldBe null
                storage.snapshot()[1] shouldBe ItemStack(Material.SALMON, 2)
                player.inventory.getItem(0) shouldBe ItemStack(Material.PUFFERFISH, 64)
                prepared.restoreExact() shouldBe ContractInventoryMutation.Confirmed
                storage.snapshot() shouldBe offered
                val next = storage.prepare(PaperContractItems.ANY_RAW_FISH, 5)!!
                every { ContractOriginGate.canSubmit(player, "food_orders") } returns false
                next.removeExact() shouldBe ContractInventoryMutation.NotPerformed("desk_unavailable")
                storage.snapshot() shouldBe offered
            } finally { unmockkObject(ContractOriginGate) }
        } }
    }
    "prepare checkpoints native inventory even when desk storage is already clean" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.server.addPlayer()
            val saved = mutableListOf<Pair<Int, Int>>()
            lateinit var storage: ContractDeskStorage
            storage = ContractDeskStorage(player, "forge_orders", { true }, PaperPlayerDataPersistence {
                saved += player.inventory.storageContents.filterNotNull().sumOf { it.amount } to
                    storage.snapshot().filterNotNull().sumOf { it.amount }
            })
            player.inventory.setItem(0, ItemStack(Material.STONE, 9))
            val offered = storage.snapshot().toMutableList().also { it[0] = ItemStack(Material.RAW_IRON, 5) }
            storage.compareAndSet(storage.snapshot(), offered) shouldBe true
            storage.persistTransfer()
            saved.clear()
            player.inventory.setItem(1, ItemStack(Material.EMERALD, 4))
            storage.pending = true

            mockkObject(ContractOriginGate)
            try {
                every { ContractOriginGate.canSubmit(player, "forge_orders") } returns true
                val prepared = storage.prepare("minecraft:raw_iron", 5)!!
                prepared.payloads.sumOf { it.quantity } shouldBe 5
                saved shouldBe listOf(13 to 5)
                player.inventory.storageContents.filterNotNull().sumOf { it.amount } shouldBe 13
                storage.snapshot() shouldBe offered
            } finally { unmockkObject(ContractOriginGate) }
        }
    }
    "prepare propagates checkpoint failure before producing a removable escrow" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.server.addPlayer()
            player.inventory.setItem(0, ItemStack(Material.EMERALD, 4))
            val storage = ContractDeskStorage(player, "forge_orders", { true }, PaperPlayerDataPersistence {
                error("checkpoint failed")
            })
            val offered = storage.snapshot().toMutableList().also { it[0] = ItemStack(Material.RAW_IRON, 5) }
            storage.compareAndSet(storage.snapshot(), offered) shouldBe true
            storage.pending = true

            mockkObject(ContractOriginGate)
            try {
                every { ContractOriginGate.canSubmit(player, "forge_orders") } returns true
                shouldThrowAny { storage.prepare("minecraft:raw_iron", 5) }
                storage.snapshot() shouldBe offered
                player.inventory.getItem(0) shouldBe ItemStack(Material.EMERALD, 4)
            } finally { unmockkObject(ContractOriginGate) }
        }
    }
    "prepare refuses a nonempty cursor before checkpointing" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.server.addPlayer()
            var saves = 0
            val storage = ContractDeskStorage(player, "forge_orders", { true }, PaperPlayerDataPersistence { saves++ })
            val offered = storage.snapshot().toMutableList().also { it[0] = ItemStack(Material.RAW_IRON, 5) }
            storage.compareAndSet(storage.snapshot(), offered) shouldBe true
            storage.pending = true
            val cursor = ItemStack(Material.STONE, 1)
            player.openInventory.setCursor(cursor)

            mockkObject(ContractOriginGate)
            try {
                every { ContractOriginGate.canSubmit(player, "forge_orders") } returns true
                storage.prepare("minecraft:raw_iron", 5) shouldBe null
                saves shouldBe 0
                player.openInventory.cursor shouldBe cursor
                storage.snapshot() shouldBe offered
            } finally { unmockkObject(ContractOriginGate) }
        }
    }
    "cursor changes after prepare stop removal and refund without another save" {
        MockBukkitTestRuntime.open().use { runtime -> runTest {
            val player = runtime.server.addPlayer()
            var saves = 0
            val storage = ContractDeskStorage(player, "forge_orders", { true }, PaperPlayerDataPersistence { saves++ })
            val offered = storage.snapshot().toMutableList().also { it[0] = ItemStack(Material.RAW_IRON, 5) }
            storage.compareAndSet(storage.snapshot(), offered) shouldBe true
            storage.pending = true

            mockkObject(ContractOriginGate)
            try {
                every { ContractOriginGate.canSubmit(player, "forge_orders") } returns true
                val prepared = storage.prepare("minecraft:raw_iron", 5)!!
                saves shouldBe 1

                val cursor = ItemStack(Material.STONE, 1)
                player.openInventory.setCursor(cursor)
                prepared.removeExact() shouldBe ContractInventoryMutation.NotPerformed("cursor_not_empty")
                saves shouldBe 1
                storage.snapshot() shouldBe offered

                player.openInventory.setCursor(null)
                prepared.removeExact() shouldBe ContractInventoryMutation.Confirmed
                saves shouldBe 2
                val afterRemoval = storage.snapshot()
                afterRemoval shouldBe List(24) { null }

                player.openInventory.setCursor(cursor)
                prepared.restoreExact() shouldBe ContractInventoryMutation.NotPerformed("cursor_not_empty")
                saves shouldBe 2
                storage.snapshot() shouldBe afterRemoval
            } finally { unmockkObject(ContractOriginGate) }
        } }
    }
    "full native inventory keeps unsold input safely in the desk and a failed save cannot acknowledge escrow" {
        MockBukkitTestRuntime.open().use { runtime -> runTest {
            val player = runtime.server.addPlayer()
            // MockBukkit's addItem also scans equipment cells; saturate its
            // complete inventory to model Paper's full storageContents.
            for (slot in 0 until player.inventory.size) player.inventory.setItem(slot, ItemStack(Material.STONE, 64))
            var failSave = false
            val storage = ContractDeskStorage(player, "forge_orders", { true }, PaperPlayerDataPersistence { if (failSave) error("save failed") })
            val offered = storage.snapshot().toMutableList().also { it[0] = ItemStack(Material.RAW_IRON, 5) }
            storage.compareAndSet(storage.snapshot(), offered) shouldBe true
            storage.returnItems()
            storage.snapshot() shouldBe offered
            mockkObject(ContractOriginGate)
            try {
                every { ContractOriginGate.canSubmit(player, "forge_orders") } returns true
                storage.pending = true
                val prepared = storage.prepare("minecraft:raw_iron", 5)!!
                failSave = true
                prepared.removeExact() shouldBe ContractInventoryMutation.Ambiguous
                storage.snapshot().all { it == null } shouldBe true
                prepared.removeExact() shouldBe ContractInventoryMutation.NotPerformed("slot_changed")
            } finally { unmockkObject(ContractOriginGate) }
        } }
    }
    "legacy nine-cell PDC expands without moving items and all twenty-four cells round-trip" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.server.addPlayer()
            val legacy = List<ItemStack?>(9) { slot -> if (slot == 8) ItemStack(Material.SALMON, 7) else null }
            val occupiedFirst = legacy.toMutableList().also { it[0] = ItemStack(Material.COD, 1) }
            decodeContractDeskItems(encodeContractDeskItems(occupiedFirst)) shouldBe occupiedFirst + List(15) { null }
            val key = org.bukkit.NamespacedKey("arc", "contract_desk_food_orders")
            player.persistentDataContainer.set(key, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY,
                encodeContractDeskItems(legacy))
            val storage = ContractDeskStorage(player, "food_orders", { true }, PaperPlayerDataPersistence {})
            val expanded = storage.snapshot()
            expanded shouldBe legacy + List(15) { null }
            val offered = expanded.toMutableList().also { it[23] = ItemStack(Material.COD, 13) }
            storage.compareAndSet(expanded, offered) shouldBe true
            decodeContractDeskItems(encodeContractDeskItems(offered)) shouldBe offered
            ContractDeskStorage(player, "food_orders", { true }, PaperPlayerDataPersistence {}).snapshot() shouldBe offered
            storage.returnItems()
            storage.snapshot() shouldBe List(24) { null }
            player.inventory.storageContents.filterNotNull().sumOf { it.amount } shouldBe 20
        }
    }
    "expanded codec rejects truncation at the old record count and trailing bytes" {
        val bytes = encodeContractDeskItems(List(24) { null })
        decodeContractDeskItems(bytes) shouldBe List(24) { null }
        shouldThrowAny { decodeContractDeskItems(bytes.copyOf(4 + 9 * 4)) }
        shouldThrowAny { decodeContractDeskItems(bytes + byteArrayOf(0)) }
    }
    "market growth is shown separately from rank and rounds consistently for cheap items" {
        contractPriceGrowth(120, 150) shouldBe "+25%"
        contractPriceGrowth(120, 126) shouldBe "+5%"
        contractPriceGrowth(120, 60) shouldBe "-50%"
        contractPriceGrowth(10_000, 12_500) shouldBe "+25%"
        contractPriceGrowth(20, 20) shouldBe "+0%"
    }
})

package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.contracts.api.ArcContractTrophyMarkers
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class ArcContractTrophyMarkersTest : StringSpec({
    "reads legacy arc namespace markers and rejects non-normalized identities" {
        MockBukkitTestRuntime.open().use {
            val owner = UUID.fromString("11111111-1111-1111-1111-111111111111")
            val stack = ItemStack(Material.PAPER)
            stack.editMeta { meta ->
                val pdc = meta.persistentDataContainer
                pdc.set(
                    requireNotNull(NamespacedKey.fromString(ArcContractTrophyMarkers.IDENTITY_NAMESPACED_KEY)),
                    PersistentDataType.STRING,
                    "arc:road_revival/mines_core",
                )
                pdc.set(
                    requireNotNull(NamespacedKey.fromString(ArcContractTrophyMarkers.OWNER_NAMESPACED_KEY)),
                    PersistentDataType.STRING,
                    owner.toString(),
                )
            }
            ArcContractTrophyMarkers.identity(stack) shouldBe "arc:road_revival/mines_core"
            ArcContractTrophyMarkers.owner(stack) shouldBe owner
            ArcContractTrophyMarkers.isBound(stack) shouldBe true

            stack.editMeta { meta ->
                meta.persistentDataContainer.set(
                    requireNotNull(NamespacedKey.fromString(ArcContractTrophyMarkers.IDENTITY_NAMESPACED_KEY)),
                    PersistentDataType.STRING,
                    " ARC:ROAD_REVIVAL/MINES_CORE ",
                )
            }
            ArcContractTrophyMarkers.identity(stack) shouldBe null
            ArcContractTrophyMarkers.isBound(stack) shouldBe false
        }
    }
})

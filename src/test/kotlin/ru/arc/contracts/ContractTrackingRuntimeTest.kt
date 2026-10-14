package ru.arc.contracts

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.Tasks
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ContractTrackingRuntimeTest {
    private val now = 1_789_344_001_000L
    private val view = ResourceContractPlayerView(
        ResourceContractView("weekly_coal", "Уголь", "minecraft:coal", "server_envelope", "open",
            1_789_344_000_000L, 1_789_948_800_000L, 100, 100000, 0, 0, 1000, 0, 0, 1000, 0),
        8, 2304, 500, 0, 0, 500, 100, 10000, 10000,
    )

    @Test
    fun `completion has one inflight write and one notification`() = fixture { paper, player, store, runtime, notices ->
        store.state = ContractTrackingLogic.stateFor(view, 64)
        player.inventory.setItem(0, ItemStack(Material.COAL, 64))
        runtime.start()
        paper.performTicks(65)
        assertEquals(1, store.markCalls)
        store.completion.complete(true)
        paper.performTicks(65)
        assertEquals(1, notices.size)
        assertEquals(1, store.markCalls)
        assertTrue(runtime.status(player, view)!!.state.completionNotified)
    }

    @Test
    fun `overlapping preference clicks are rejected and acknowledgement follows cache update`() = fixture { paper, player, store, runtime, _ ->
        runtime.start()
        paper.performTicks(2)
        store.toggleResult = CompletableFuture()
        val first = runtime.toggle(player, view, 64)
        assertTrue(runtime.toggle(player, view, 64).isCompletedExceptionally)
        store.toggleResult!!.complete(ContractTrackingLogic.stateFor(view, 64))
        paper.performTicks(2)
        assertTrue(first.isDone)
        assertTrue(runtime.isTracked(player))
        assertEquals(1, store.toggleCalls)
    }

    @Test
    fun `rejected clear refreshes a deleted preference before acknowledging`() = fixture { paper, player, store, runtime, _ ->
        store.state = ContractTrackingLogic.stateFor(view, 64)
        runtime.start()
        paper.performTicks(2)
        store.rejectClear = true
        store.state = null
        val result = runtime.clear(player)
        paper.performTicks(4)
        assertEquals(false, result.join())
        assertFalse(runtime.isTracked(player))
    }

    @Test
    fun `late completion cannot announce a removed target or a stopped runtime`() = fixture { paper, player, store, runtime, notices ->
        store.state = ContractTrackingLogic.stateFor(view, 64)
        player.inventory.setItem(0, ItemStack(Material.COAL, 64))
        runtime.start()
        paper.performTicks(3)
        assertEquals(1, store.markCalls)
        runtime.clear(player)
        paper.performTicks(2)
        store.completion.complete(true)
        paper.performTicks(25)
        assertTrue(notices.isEmpty())
        assertFalse(runtime.isTracked(player))
        store.toggleResult = CompletableFuture()
        val pending = runtime.toggle(player, view, 64)
        runtime.stop()
        assertTrue(pending.isCancelled)
        store.toggleResult!!.complete(ContractTrackingLogic.stateFor(view, 64))
        paper.performTicks(2)
        assertFalse(runtime.isTracked(player))
    }

    @Test
    fun `closed orders and exhausted quotas do not announce collection completion`() {
        val unavailable = listOf(
            view.copy(contract = view.contract.copy(status = "paused")),
            view.copy(playerRemainingQuantity = 0),
            view.copy(contract = view.contract.copy(remainingQuantity = 0)),
        )
        unavailable.forEach { current ->
            fixture(current) { paper, player, store, runtime, notices ->
                store.state = ContractTrackingLogic.stateFor(view, 64)
                player.inventory.setItem(0, ItemStack(Material.COAL, 64))
                runtime.start()
                paper.performTicks(65)
                assertEquals(0, store.markCalls)
                assertTrue(notices.isEmpty())
                assertTrue(runtime.isTracked(player))
            }
        }
    }

    private fun fixture(currentView: ResourceContractPlayerView = view, block: (MockBukkitTestRuntime, org.bukkit.entity.Player, TrackingStore, ContractTrackingRuntime, MutableList<ContractTrackingStatus>) -> Unit) {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ContractTrackingTest")
            Tasks.install(BukkitTaskScheduler(plugin))
            val player = paper.addPlayer("ContractViewer")
            player.openInventory(paper.server.createInventory(null, 9))
            val store = TrackingStore()
            val notices = mutableListOf<ContractTrackingStatus>()
            ContractTrackingRuntime(plugin, store, { _, _ -> listOf(currentView) },
                ContractTrackingPresentation(notifyCompleted = { _, status -> notices += status }), { now }).use { runtime ->
                try { block(paper, player, store, runtime, notices) } finally { runtime.stop(); Tasks.reset() }
            }
        }
    }

    private class TrackingStore : ContractTrackingStore {
        var state: ContractTrackingState? = null
        var toggleResult: CompletableFuture<ContractTrackingState?>? = null
        var toggleCalls = 0
        var markCalls = 0
        var rejectClear = false
        val completion = CompletableFuture<Boolean>()
        override fun load(playerId: UUID) = CompletableFuture.completedFuture(state)
        override fun toggle(playerId: UUID, replacement: ContractTrackingState, disableIf: (ContractTrackingState) -> Boolean): CompletableFuture<ContractTrackingState?> {
            toggleCalls++
            return toggleResult ?: CompletableFuture.completedFuture(if (state?.let(disableIf) == true) null else replacement)
        }
        override fun markCompleted(playerId: UUID, expected: ContractTrackingState): CompletableFuture<Boolean> {
            markCalls++
            return completion
        }
        override fun clear(playerId: UUID, expected: ContractTrackingState): CompletableFuture<Boolean> {
            if (rejectClear) return CompletableFuture.completedFuture(false)
            state = null
            return CompletableFuture.completedFuture(true)
        }
    }
}

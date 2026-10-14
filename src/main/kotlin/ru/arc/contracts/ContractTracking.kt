package ru.arc.contracts

import com.google.gson.Gson
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.whenCompleteSync
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdateResult
import ru.arc.redis.safety.RedisHashUpdater
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** The one player-owned order target. It is bound to one exact contract window. */
data class ContractTrackingState(
    val contractId: String,
    val windowStartsAt: Long,
    val windowEndsAt: Long,
    val itemKey: String,
    val targetQuantity: Long,
    val completionNotified: Boolean = false,
) {
    init {
        validate()
    }

    fun validated(): ContractTrackingState = apply {
        validate()
    }

    private fun validate() {
        require(ID_PATTERN.matches(contractId)) { "Invalid tracked contract id" }
        require(windowStartsAt >= 0L && windowEndsAt > windowStartsAt) { "Invalid tracked contract window" }
        require(ResourceContractDefinition.normalizeItemKey(itemKey) == itemKey) { "Invalid tracked item key" }
        require(targetQuantity in 1..MAX_TARGET_QUANTITY) { "Invalid tracked target quantity" }
    }

    fun matches(view: ResourceContractPlayerView): Boolean =
        contractId == view.contract.id &&
            windowStartsAt == view.contract.windowStartsAt &&
            windowEndsAt == view.contract.windowEndsAt &&
            itemKey == view.contract.itemKey

    companion object {
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{2,47}")
        const val MAX_TARGET_QUANTITY = 2_304L
    }
}

data class ContractTrackingStatus(
    val state: ContractTrackingState,
    val currentQuantity: Long,
) {
    val targetQuantity: Long get() = state.targetQuantity
    val remainingQuantity: Long get() = (targetQuantity - currentQuantity).coerceAtLeast(0L)
    val complete: Boolean get() = currentQuantity >= targetQuantity
}

sealed interface ContractTrackingToggleResult {
    data class Enabled(val state: ContractTrackingState) : ContractTrackingToggleResult
    data object Disabled : ContractTrackingToggleResult
}

/** Pure rules shared by the GUI adapter, runtime and tests. */
object ContractTrackingLogic {
    fun targetQuantity(view: ResourceContractPlayerView, requestedTarget: Long): Long {
        val minimum = maxOf(1L, view.minSubmissionQuantity.toLong())
        val upperBound = minOf(
            view.maxSubmissionQuantity.toLong(),
            view.playerRemainingQuantity,
            view.contract.remainingQuantity,
            ContractTrackingState.MAX_TARGET_QUANTITY,
        )
        require(upperBound >= minimum) { "Contract has no available tracking target" }
        return if (requestedTarget <= 0L) minimum else requestedTarget.coerceIn(minimum, upperBound)
    }

    fun stateFor(view: ResourceContractPlayerView, requestedTarget: Long): ContractTrackingState =
        ContractTrackingState(
            contractId = view.contract.id,
            windowStartsAt = view.contract.windowStartsAt,
            windowEndsAt = view.contract.windowEndsAt,
            itemKey = view.contract.itemKey,
            targetQuantity = targetQuantity(view, requestedTarget),
        )

    fun status(state: ContractTrackingState, view: ResourceContractPlayerView, currentQuantity: Long, now: Long): ContractTrackingStatus? {
        if (!state.matches(view) || now !in state.windowStartsAt until state.windowEndsAt) return null
        return ContractTrackingStatus(state, currentQuantity.coerceAtLeast(0L))
    }
}

/** Minimal persistence boundary. Implementations must update one player atomically. */
interface ContractTrackingStore : AutoCloseable {
    fun load(playerId: UUID): CompletableFuture<ContractTrackingState?>

    fun toggle(
        playerId: UUID,
        replacement: ContractTrackingState,
        disableIf: (ContractTrackingState) -> Boolean,
    ): CompletableFuture<ContractTrackingState?>

    fun markCompleted(playerId: UUID, expected: ContractTrackingState): CompletableFuture<Boolean>

    fun clear(playerId: UUID, expected: ContractTrackingState): CompletableFuture<Boolean>

    override fun close() = Unit
}

/** Redis-backed player preference storage using the shared bounded CAS primitive. */
class RedisContractTrackingStore(
    private val redis: RedisOperations,
    gson: Gson,
) : ContractTrackingStore {
    private val codec = codec(gson)
    private val updater = RedisHashUpdater(redis, STORAGE_KEY, codec)

    override fun load(playerId: UUID): CompletableFuture<ContractTrackingState?> =
        redis.loadMapEntries(STORAGE_KEY, playerId.toString()).thenApply { values ->
            require(values.size == 1) { "Unexpected contract tracking lookup result" }
            values.single()?.let { codec.decode(it) }
        }

    override fun toggle(
        playerId: UUID,
        replacement: ContractTrackingState,
        disableIf: (ContractTrackingState) -> Boolean,
    ): CompletableFuture<ContractTrackingState?> =
        updater.update(playerId.toString()) { current ->
            if (current != null && disableIf(current)) RedisHashDecision.Delete
            else RedisHashDecision.Write(replacement)
        }.thenApply { result ->
            when (result) {
                is RedisHashUpdateResult.Changed -> result.after
                is RedisHashUpdateResult.Unchanged -> result.current
                is RedisHashUpdateResult.Rejected -> error("Contract tracking update was rejected")
                is RedisHashUpdateResult.Contended -> error("Contract tracking update was contended")
            }
        }

    override fun markCompleted(playerId: UUID, expected: ContractTrackingState): CompletableFuture<Boolean> =
        updater.update(playerId.toString()) { current ->
            if (current == expected && !current.completionNotified) {
                RedisHashDecision.Write(current.copy(completionNotified = true))
            } else {
                RedisHashDecision.Reject
            }
        }.thenApply { result -> result is RedisHashUpdateResult.Changed }

    override fun clear(playerId: UUID, expected: ContractTrackingState): CompletableFuture<Boolean> =
        updater.update(playerId.toString()) { current ->
            if (current == expected) RedisHashDecision.Delete else RedisHashDecision.Reject
        }.thenApply { result -> result is RedisHashUpdateResult.Changed }

    private companion object {
        const val STORAGE_KEY = "arc:contract-tracking:v1"

        fun codec(gson: Gson): BoundedJsonCodec<ContractTrackingState> = BoundedJsonCodec(
            gson,
            ContractTrackingState::class.java,
            JsonObjectContract(
                allowedFields = setOf(
                    "contractId", "windowStartsAt", "windowEndsAt", "itemKey", "targetQuantity", "completionNotified",
                ),
            ),
            JsonResourceBounds(512, maxDepth = 2, maxContainerEntries = 8, maxTotalNodes = 16, maxStringCharacters = 128),
            { it.validated(); Unit },
        )
    }
}

data class ContractTrackingPresentation(
    val showProgress: (Player, ContractTrackingStatus) -> Unit = { _, _ -> },
    val notifyCompleted: (Player, ContractTrackingStatus) -> Unit = { _, _ -> },
)

/**
 * Paper lifecycle adapter. The owner supplies the current player view and all visible text,
 * so this helper never changes contract quantities or invents a second GUI/config surface.
 */
class ContractTrackingRuntime(
    private val plugin: Plugin,
    private val store: ContractTrackingStore,
    private val currentViews: (UUID, Long) -> List<ResourceContractPlayerView>,
    private val presentation: ContractTrackingPresentation = ContractTrackingPresentation(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private data class HudEmission(val quantity: Long, val emittedAt: Long)
    private class PlayerSession : AutoCloseable {
        val tasks = LifecycleTaskScope(Tasks.scheduler)

        var pending: CompletableFuture<*>? = null

        override fun close() {
            tasks.close()
            pending?.cancel(false)
            pending = null
        }
    }

    private val tracked = java.util.concurrent.ConcurrentHashMap<UUID, ContractTrackingState>()
    private val sessions = java.util.concurrent.ConcurrentHashMap<UUID, PlayerSession>()
    private val completionInflight = java.util.concurrent.ConcurrentHashMap<UUID, ContractTrackingState>()
    private val lastHud = java.util.concurrent.ConcurrentHashMap<UUID, HudEmission>()
    private val tasks = LifecycleTaskScope(Tasks.scheduler)
    private val listener = object : Listener {
        @EventHandler
        fun onJoin(event: PlayerJoinEvent) {
            if (started) load(event.player)
        }

        @EventHandler
        fun onQuit(event: PlayerQuitEvent) {
            sessions.remove(event.player.uniqueId)?.close()
            tracked.remove(event.player.uniqueId)
            completionInflight.remove(event.player.uniqueId)
            lastHud.remove(event.player.uniqueId)
        }
    }
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val token = tasks.restart()
        plugin.server.pluginManager.registerEvents(listener, plugin)
        Bukkit.getOnlinePlayers().forEach(::load)
        tasks.runTimer(token, 1L, 20L, ::tick)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        tasks.cancelAll()
        HandlerList.unregisterAll(listener)
        sessions.values.forEach(PlayerSession::close)
        sessions.clear()
        completionInflight.clear()
        tracked.clear()
        lastHud.clear()
    }

    override fun close() {
        stop()
        tasks.close()
    }

    fun toggle(
        player: Player,
        view: ResourceContractPlayerView,
        requestedTarget: Long,
        now: Long = clock(),
    ): CompletableFuture<ContractTrackingToggleResult> {
        val replacement = ContractTrackingLogic.stateFor(view, requestedTarget)
        require(now in replacement.windowStartsAt until replacement.windowEndsAt) {
            "Cannot track a contract outside its active window"
        }
        check(started) { "Contract tracking runtime is not started" }
        val session = session(player.uniqueId)
        if (session.pending != null) return CompletableFuture.failedFuture(IllegalStateException("Tracking update in progress"))
        val result = CompletableFuture<ContractTrackingToggleResult>()
        session.pending = result
        completionInflight.remove(player.uniqueId)
        val token = session.tasks.restart()
        store.toggle(player.uniqueId, replacement) { current ->
            current.matches(view) && now in current.windowStartsAt until current.windowEndsAt
        }.whenCompleteSync(session.tasks, token) { state, failure ->
            session.pending = null
            if (failure != null) result.completeExceptionally(failure)
            else {
                applyLoaded(player, session, state)
                result.complete(if (state == null) ContractTrackingToggleResult.Disabled else ContractTrackingToggleResult.Enabled(state))
            }
        }
        return result
    }

    fun clear(player: Player, now: Long = clock()): CompletableFuture<Boolean> {
        val state = current(player.uniqueId, now) ?: return CompletableFuture.completedFuture(false)
        check(started) { "Contract tracking runtime is not started" }
        val session = session(player.uniqueId)
        if (session.pending != null) return CompletableFuture.failedFuture(IllegalStateException("Tracking update in progress"))
        val result = CompletableFuture<Boolean>()
        session.pending = result
        completionInflight.remove(player.uniqueId)
        val token = session.tasks.restart()
        store.clear(player.uniqueId, state).whenCompleteSync(session.tasks, token) { cleared, failure ->
            if (failure != null) {
                session.pending = null
                result.completeExceptionally(failure)
            } else if (cleared == true) {
                applyLoaded(player, session, null)
                session.pending = null
                result.complete(true)
            } else {
                // A concurrent completion or another server changed the preference.
                // Refresh before acknowledging the click; never display a fictional removal.
                store.load(player.uniqueId).whenCompleteSync(session.tasks, token) { stored, readFailure ->
                    session.pending = null
                    if (readFailure != null) result.completeExceptionally(readFailure)
                    else { applyLoaded(player, session, stored); result.complete(false) }
                }
            }
        }
        return result
    }

    fun isTracked(player: Player, now: Long = clock()): Boolean = current(player.uniqueId, now) != null

    fun status(player: Player, view: ResourceContractPlayerView, now: Long = clock()): ContractTrackingStatus? {
        val state = current(player.uniqueId, now) ?: return null
        return ContractTrackingLogic.status(state, view, PaperContractItems.countPlain(player, state.itemKey).toLong(), now)
    }

    private fun current(playerId: UUID, now: Long): ContractTrackingState? =
        tracked[playerId]?.takeIf { now in it.windowStartsAt until it.windowEndsAt }

    private fun load(player: Player) {
        val session = session(player.uniqueId)
        val sessionToken = session.tasks.restart()
        store.load(player.uniqueId).whenCompleteSync(session.tasks, sessionToken) { state, failure ->
            if (failure != null) {
                ContractsRuntime.logWarning(
                    "Contract tracking preference load failed; retrying in 30 seconds: ${failure.javaClass.simpleName}",
                )
                if (started && player.isOnline && sessions[player.uniqueId] === session) {
                    session.tasks.runLater(sessionToken, LOAD_RETRY_TICKS) {
                        if (started && player.isOnline && sessions[player.uniqueId] === session) load(player)
                    }
                }
                return@whenCompleteSync
            }
            if (!started || !player.isOnline || sessions[player.uniqueId] !== session) return@whenCompleteSync
            applyLoaded(player, session, state)
        }
    }

    private fun tick() {
        val now = clock()
        Bukkit.getOnlinePlayers().forEach { player ->
            if (sessions[player.uniqueId]?.pending != null) return@forEach
            val state = tracked[player.uniqueId] ?: return@forEach
            if (now >= state.windowEndsAt) {
                tracked.remove(player.uniqueId, state)
                lastHud.remove(player.uniqueId)
                store.clear(player.uniqueId, state)
                return@forEach
            }
            if (now < state.windowStartsAt) return@forEach
            val views = currentViews(player.uniqueId, now)
            val view = views.firstOrNull { state.matches(it) }
            if (view == null) {
                if (views.isEmpty()) return@forEach
                tracked.remove(player.uniqueId)
                lastHud.remove(player.uniqueId)
                store.clear(player.uniqueId, state)
                return@forEach
            }
            if (view.contract.status != ContractStatus.OPEN.label ||
                view.playerRemainingQuantity < view.minSubmissionQuantity ||
                view.contract.remainingQuantity < view.minSubmissionQuantity
            ) {
                lastHud.remove(player.uniqueId)
                return@forEach
            }
            val status = ContractTrackingLogic.status(
                state,
                view,
                PaperContractItems.countPlain(player, state.itemKey).toLong(),
                now,
            ) ?: return@forEach
            if (status.complete && !state.completionNotified) {
                if (completionInflight.putIfAbsent(player.uniqueId, state) == null) {
                    val session = sessions[player.uniqueId] ?: run {
                        completionInflight.remove(player.uniqueId, state)
                        return@forEach
                    }
                    store.markCompleted(player.uniqueId, state).whenCompleteSync(session.tasks) { won, failure ->
                        completionInflight.remove(player.uniqueId, state)
                        if (failure != null || !started || !player.isOnline || sessions[player.uniqueId] !== session) return@whenCompleteSync
                        if (won == true) {
                            val stillCurrent = tracked[player.uniqueId] == state
                            if (stillCurrent) {
                                tracked[player.uniqueId] = state.copy(completionNotified = true)
                                lastHud.remove(player.uniqueId)
                                presentation.notifyCompleted(player, status)
                            }
                        } else {
                            refreshFromStore(player, session)
                        }
                    }
                }
            } else if (!status.complete && player.openInventory.topInventory.type == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                val previous = lastHud[player.uniqueId]
                if (previous == null || previous.quantity != status.currentQuantity || now - previous.emittedAt >= HUD_REFRESH_MILLIS) {
                    presentation.showProgress(player, status)
                    lastHud[player.uniqueId] = HudEmission(status.currentQuantity, now)
                }
            }
        }
    }

    private fun session(playerId: UUID): PlayerSession =
        sessions.computeIfAbsent(playerId) { PlayerSession() }

    private fun applyLoaded(player: Player, session: PlayerSession, state: ContractTrackingState?) {
        if (sessions[player.uniqueId] !== session) return
        val now = clock()
        if (state != null && now in state.windowStartsAt until state.windowEndsAt) {
            tracked[player.uniqueId] = state
        } else {
            tracked.remove(player.uniqueId)
            lastHud.remove(player.uniqueId)
            if (state != null) store.clear(player.uniqueId, state)
        }
    }

    private fun refreshFromStore(player: Player, session: PlayerSession) {
        if (!started || sessions[player.uniqueId] !== session) return
        val token = session.tasks.restart()
        store.load(player.uniqueId).whenCompleteSync(session.tasks, token) { state, failure ->
            if (failure != null || !started || !player.isOnline || sessions[player.uniqueId] !== session) return@whenCompleteSync
            applyLoaded(player, session, state)
        }
    }

    private companion object {
        const val HUD_REFRESH_MILLIS = 10_000L
        const val LOAD_RETRY_TICKS = 20L * 30L
    }
}

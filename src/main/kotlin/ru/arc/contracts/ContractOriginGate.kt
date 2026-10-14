package ru.arc.contracts

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Authorizes resource contracts from a real click on the configured public
 * Citizens NPC desk. A command or menu can use an existing grant, but cannot
 * create one. Grants are transient and are rechecked against the NPC radius
 * on every use; they also expire after the configured session TTL.
 */
object ContractOriginGate {
    const val ORIGIN_WORLD = "rc_origin_spawn"

    private data class Settings(
        val npcRoutes: Map<Int, String> = ContractsConfig.DEFAULT_SUBMISSION_NPC_ROUTES,
        val radius: Double = 4.5,
        val ttlMillis: Long = 120_000L,
    )

    internal data class Grant(
        val playerId: UUID,
        val npcId: Int,
        val group: String,
        val expiresAt: Long,
    )

    private val settings = AtomicReference(Settings())
    private val grants = ConcurrentHashMap<UUID, Grant>()

    internal fun groupForNpc(npcId: Int): String? = settings.get().npcRoutes[npcId]

    fun configure(config: ContractsConfig) {
        settings.set(
            Settings(
                npcRoutes = config.submissionNpcRoutes,
                radius = config.submissionNpcRadius,
                ttlMillis = config.submissionNpcSessionTtl.toMillis(),
            ),
        )
        grants.clear()
    }

    /** Returns true only for the group authorized by the player's NPC click. */
    fun canSubmit(player: Player, group: String): Boolean {
        if (!player.isOnline || !player.world.name.equals(ORIGIN_WORLD, ignoreCase = true)) return false
        val configured = settings.get()
        val grant = grants[player.uniqueId] ?: return false
        val now = System.currentTimeMillis()
        if (!isGrantUsable(grant, player.uniqueId, null, now)) {
            grants.remove(player.uniqueId, grant)
            return false
        }
        if (configured.npcRoutes[grant.npcId] != grant.group || !isNearConfiguredNpc(player, configured, grant.npcId)) {
            grants.remove(player.uniqueId, grant)
            return false
        }
        return grant.group == group
    }

    /** Legacy non-contract inventory paths may retain their world-only guard. */
    internal fun isInOrigin(player: Player): Boolean =
        player.isOnline && player.world.name.equals(ORIGIN_WORLD, ignoreCase = true)

    /** Mints a grant only from the authoritative Citizens right-click event. */
    internal fun grantFromNpcClick(player: Player, npc: NPC, now: Long = System.currentTimeMillis()): Boolean {
        val configured = settings.get()
        val group = configured.npcRoutes[npc.id] ?: return false
        if (!player.isOnline || !npc.isSpawned) return false
        if (!player.world.name.equals(ORIGIN_WORLD, ignoreCase = true)) return false
        if (!isNearConfiguredNpc(player, configured, npc.id)) return false
        grants[player.uniqueId] = Grant(
            playerId = player.uniqueId,
            npcId = npc.id,
            group = group,
            expiresAt = now + configured.ttlMillis,
        )
        return true
    }

    internal fun revoke(playerId: UUID) {
        grants.remove(playerId)
    }

    internal fun clear() {
        grants.clear()
    }

    internal fun isGrantUsable(grant: Grant?, playerId: UUID, group: String?, now: Long): Boolean =
        grant != null &&
            grant.playerId == playerId &&
            (group == null || grant.group == group) &&
            now < grant.expiresAt

    private fun isNearConfiguredNpc(player: Player, configured: Settings, npcId: Int): Boolean {
        if (npcId !in configured.npcRoutes || !Bukkit.getPluginManager().isPluginEnabled("Citizens")) return false
        val npc = runCatching { CitizensAPI.getNPCRegistry().getById(npcId) }.getOrNull() ?: return false
        val entity = npc.entity ?: return false
        if (!npc.isSpawned || entity.world.uid != player.world.uid) return false
        return entity.location.distanceSquared(player.location) <= configured.radius * configured.radius
    }
}

package ru.arc.contracts

import java.util.UUID

data class SeasonDungeonLaunchToken(
    val tokenId: String,
    val runId: String,
    val catalogDigest: String,
    val dungeonContractId: String,
    val blueprintWorld: String,
    val participantIds: Set<String>,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    fun validated(): SeasonDungeonLaunchToken {
        require(SeasonRuntimeState.validActionId(tokenId)) { "Invalid season dungeon launch token id" }
        require(DungeonAdmissionPass.validRunId(runId)) { "Invalid season dungeon launch run id" }
        require(SeasonRuntimeState.validDigest(catalogDigest)) { "Invalid season dungeon launch catalog digest" }
        require(SeasonRuntimeState.validTargetId(dungeonContractId)) { "Invalid season dungeon launch contract id" }
        require(validWorldName(blueprintWorld)) { "Invalid season dungeon launch blueprint world" }
        require(participantIds.isNotEmpty() && participantIds.size <= MAX_PARTICIPANTS) {
            "Invalid season dungeon launch participant count"
        }
        require(participantIds.all(SeasonRuntimeState::validPlayerId)) {
            "Invalid season dungeon launch participant"
        }
        require(issuedAt >= 0L && expiresAt > issuedAt && expiresAt - issuedAt <= MAX_TOKEN_TTL_MILLIS) {
            "Invalid season dungeon launch token lifetime"
        }
        return this
    }

    companion object {
        const val MAX_WORLD_NAME_LENGTH = 128
        const val MAX_PARTICIPANTS = 128
        const val MAX_TOKEN_TTL_MILLIS = 30_000L

        fun validWorldName(value: String): Boolean =
            value.isNotBlank() && value.length <= MAX_WORLD_NAME_LENGTH &&
                value == value.trim().lowercase() && value.none(Char::isISOControl)
    }
}

data class SeasonDungeonRunAuthorization(
    val runId: String,
    val catalogDigest: String,
    val dungeonContractId: String,
    val blueprintWorld: String,
    val instanceWorld: String,
    val participantIds: Set<String>,
    val authorizedAt: Long,
) {
    fun validated(): SeasonDungeonRunAuthorization {
        require(DungeonAdmissionPass.validRunId(runId)) { "Invalid authorized season dungeon run id" }
        require(SeasonRuntimeState.validDigest(catalogDigest)) { "Invalid authorized season dungeon catalog digest" }
        require(SeasonRuntimeState.validTargetId(dungeonContractId)) { "Invalid authorized season dungeon contract id" }
        require(SeasonDungeonLaunchToken.validWorldName(blueprintWorld)) {
            "Invalid authorized season dungeon blueprint world"
        }
        require(SeasonDungeonLaunchToken.validWorldName(instanceWorld)) {
            "Invalid authorized season dungeon instance world"
        }
        require(participantIds.isNotEmpty() && participantIds.size <= SeasonDungeonLaunchToken.MAX_PARTICIPANTS) {
            "Invalid authorized season dungeon participant count"
        }
        require(participantIds.all(SeasonRuntimeState::validPlayerId)) {
            "Invalid authorized season dungeon participant"
        }
        require(authorizedAt >= 0L) { "Invalid authorized season dungeon timestamp" }
        return this
    }
}

data class SeasonDungeonLaunchReservation(
    val state: SeasonRuntimeState,
    val token: SeasonDungeonLaunchToken,
)

data class SeasonDungeonInstanceAuthorizationResult(
    val state: SeasonRuntimeState,
    val authorization: SeasonDungeonRunAuthorization,
)

/**
 * Pure durable transitions around EliteMobs' cancellable WorldInstanceEvent.
 * The caller persists the returned SeasonRuntimeState before invoking or
 * allowing the corresponding native side effect.
 */
class SeasonDungeonLaunchGate(
    private val tokenTtlMillis: Long = 15_000L,
    private val tokenIdFactory: () -> String = { "launch-${UUID.randomUUID()}" },
    private val runIdFactory: () -> String = { "run-${UUID.randomUUID()}" },
) {
    init {
        require(tokenTtlMillis in 1..SeasonDungeonLaunchToken.MAX_TOKEN_TTL_MILLIS) {
            "Invalid season dungeon launch token TTL"
        }
    }

    fun reserve(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        dungeonContractId: String,
        participantIds: Set<String>,
        now: Long,
    ): SeasonDungeonLaunchReservation {
        val current = releaseExpired(catalog, state, now)
        require(catalog.isOpenAt(now)) { "Cannot reserve a season dungeon launch outside the season window" }
        require(current.dungeonLaunchTokens.size < SeasonRuntimeState.MAX_DUNGEON_LAUNCH_TOKENS) {
            "Season dungeon launch token capacity reached"
        }
        require(current.authorizedDungeonRuns.size < SeasonRuntimeState.MAX_AUTHORIZED_DUNGEON_RUNS) {
            "Season dungeon authorized-run capacity reached"
        }
        require(participantIds.isNotEmpty() && participantIds.size <= SeasonDungeonLaunchToken.MAX_PARTICIPANTS) {
            "Invalid season dungeon launch participant count"
        }
        require(participantIds.all(SeasonRuntimeState::validPlayerId)) {
            "Invalid season dungeon launch participant"
        }
        require(current.dungeonLaunchTokens.values.none { token -> token.participantIds.any(participantIds::contains) }) {
            "A season dungeon launch participant already has a pending token"
        }
        require(current.authorizedDungeonRuns.values.none { run -> run.participantIds.any(participantIds::contains) }) {
            "A season dungeon launch participant already has an authorized run"
        }

        val dungeon = requireNotNull(catalog.dungeonContracts[dungeonContractId]) { "Unknown season dungeon" }
        require(current.dungeonLaunchTokens.values.none { it.blueprintWorld == dungeon.world }) {
            "A season dungeon launch for this blueprint is already pending"
        }
        require(
            dungeon.requiresProjectStage in SeasonProjectEngine.completedStages(catalog, current.project),
        ) { "Season dungeon is still locked by project progress" }

        val passes = participantIds.map { playerId ->
            requireNotNull(current.admissionPasses[SeasonRuntimeState.admissionKey(playerId, dungeonContractId)]) {
                "Season dungeon launch participant has no admission pass"
            }
        }
        require(passes.all { it.status == DungeonAdmissionPassStatus.AVAILABLE }) {
            "Season dungeon launch requires fresh unbound admission passes"
        }

        val token =
            SeasonDungeonLaunchToken(
                tokenId = tokenIdFactory(),
                runId = runIdFactory(),
                catalogDigest = catalog.revisionDigest(),
                dungeonContractId = dungeon.id,
                blueprintWorld = dungeon.world,
                participantIds = participantIds.toSet(),
                issuedAt = now,
                expiresAt = Math.addExact(now, tokenTtlMillis),
            ).validated()
        require(token.tokenId !in current.dungeonLaunchTokens) { "Season dungeon launch token identity collision" }
        require(
            current.authorizedDungeonRuns.values.none { it.runId == token.runId } &&
                current.dungeonLaunchTokens.values.none { it.runId == token.runId },
        ) { "Season dungeon launch run identity collision" }

        val passesByKey = current.admissionPasses.toMutableMap()
        participantIds.forEach { playerId ->
            val key = SeasonRuntimeState.admissionKey(playerId, dungeon.id)
            val pass = requireNotNull(passesByKey[key])
            passesByKey[key] =
                pass.copy(
                    status = DungeonAdmissionPassStatus.BOUND_TO_RUN,
                    boundRunId = token.runId,
                    boundAt = now,
                )
        }
        val next =
            current.copy(
                admissionPasses = passesByKey,
                dungeonLaunchTokens = current.dungeonLaunchTokens + (token.tokenId to token),
                revision = Math.addExact(current.revision, 1L),
            ).validatedAgainst(catalog)
        return SeasonDungeonLaunchReservation(next, token)
    }

    /** Null means direct, mismatched, stale, ambiguous or replayed launch. */
    fun authorizeInstance(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        blueprintWorld: String,
        instanceWorld: String,
        now: Long,
    ): SeasonDungeonInstanceAuthorizationResult? {
        val current = releaseExpired(catalog, state, now)
        val normalizedBlueprint = blueprintWorld.trim().lowercase()
        val normalizedInstance = instanceWorld.trim().lowercase()
        if (!SeasonDungeonLaunchToken.validWorldName(normalizedBlueprint) ||
            !SeasonDungeonLaunchToken.validWorldName(normalizedInstance) ||
            normalizedInstance in current.authorizedDungeonRuns
        ) return null
        val digest = catalog.revisionDigest()
        val candidates =
            current.dungeonLaunchTokens.values.filter { token ->
                token.catalogDigest == digest && token.blueprintWorld == normalizedBlueprint &&
                    now in token.issuedAt until token.expiresAt
            }
        if (candidates.size != 1) return null
        val token = candidates.single().validated()
        token.participantIds.forEach { playerId ->
            val pass = current.admissionPasses[SeasonRuntimeState.admissionKey(playerId, token.dungeonContractId)]
                ?: return null
            if (pass.status != DungeonAdmissionPassStatus.BOUND_TO_RUN || pass.boundRunId != token.runId) return null
        }
        val authorization =
            SeasonDungeonRunAuthorization(
                runId = token.runId,
                catalogDigest = token.catalogDigest,
                dungeonContractId = token.dungeonContractId,
                blueprintWorld = token.blueprintWorld,
                instanceWorld = normalizedInstance,
                participantIds = token.participantIds,
                authorizedAt = now,
            ).validated()
        val next =
            current.copy(
                dungeonLaunchTokens = current.dungeonLaunchTokens - token.tokenId,
                authorizedDungeonRuns = current.authorizedDungeonRuns + (authorization.instanceWorld to authorization),
                revision = Math.addExact(current.revision, 1L),
            ).validatedAgainst(catalog)
        return SeasonDungeonInstanceAuthorizationResult(next, authorization)
    }

    fun cancel(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        tokenId: String,
        now: Long,
    ): SeasonRuntimeState {
        val current = releaseExpired(catalog, state, now)
        val token = current.dungeonLaunchTokens[tokenId] ?: return current
        return releaseToken(catalog, current, token)
    }

    fun releaseExpired(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        now: Long,
    ): SeasonRuntimeState {
        var current = state.validatedAgainst(catalog)
        current.dungeonLaunchTokens.values.filter { now >= it.expiresAt }.forEach { token ->
            current = releaseToken(catalog, current, token)
        }
        return current
    }

    /**
     * Releases durable authorizations whose cloned world no longer exists.
     * A loaded instance survives an ARC reload; a process restart does not.
     */
    fun releaseMissingAuthorizedRuns(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        activeInstanceWorlds: Set<String>,
    ): SeasonRuntimeState {
        val normalizedWorlds =
            activeInstanceWorlds.mapTo(linkedSetOf()) { world -> world.trim().lowercase() }
        var current = state.validatedAgainst(catalog)
        current.authorizedDungeonRuns.keys.filterNot(normalizedWorlds::contains).forEach { instanceWorld ->
            current = cancelAuthorizedRun(catalog, current, instanceWorld)
        }
        return current
    }

    /**
     * A paid pass represents one entry, not one successful completion. Consume
     * every authorized participant at the native start so leaving early cannot
     * make the same burned admission reusable.
     */
    fun consumeAuthorizedRunAdmissions(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        instanceWorld: String,
        now: Long,
    ): SeasonRuntimeState {
        val current = state.validatedAgainst(catalog)
        val normalized = instanceWorld.trim().lowercase()
        val authorization = current.authorizedDungeonRuns[normalized] ?: return current
        val passes = current.admissionPasses.toMutableMap()
        var changed = false
        authorization.participantIds.forEach { playerId ->
            val key = SeasonRuntimeState.admissionKey(playerId, authorization.dungeonContractId)
            val pass = requireNotNull(passes[key]) { "Authorized dungeon admission pass is missing" }
            when {
                pass.status == DungeonAdmissionPassStatus.CONSUMED && pass.boundRunId == authorization.runId -> Unit
                pass.status == DungeonAdmissionPassStatus.BOUND_TO_RUN && pass.boundRunId == authorization.runId -> {
                    passes[key] = pass.copy(status = DungeonAdmissionPassStatus.CONSUMED, consumedAt = now)
                    changed = true
                }
                else -> throw IllegalArgumentException("Authorized dungeon admission is not bound to this run")
            }
        }
        if (!changed) return current
        return current.copy(
            admissionPasses = passes,
            revision = Math.addExact(current.revision, 1L),
        ).validatedAgainst(catalog)
    }

    fun finishAuthorizedRun(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        instanceWorld: String,
    ): SeasonRuntimeState {
        val normalized = instanceWorld.trim().lowercase()
        val authorization = state.authorizedDungeonRuns[normalized] ?: return state.validatedAgainst(catalog)
        val passes = state.admissionPasses.toMutableMap()
        authorization.participantIds.forEach { playerId ->
            val key = SeasonRuntimeState.admissionKey(playerId, authorization.dungeonContractId)
            val pass = passes[key] ?: return@forEach
            if (pass.status == DungeonAdmissionPassStatus.BOUND_TO_RUN && pass.boundRunId == authorization.runId) {
                passes[key] =
                    pass.copy(
                        status = DungeonAdmissionPassStatus.AVAILABLE,
                        boundRunId = null,
                        boundAt = null,
                    )
            }
        }
        return state.copy(
            admissionPasses = passes,
            authorizedDungeonRuns = state.authorizedDungeonRuns - normalized,
            revision = Math.addExact(state.revision, 1L),
        ).validatedAgainst(catalog)
    }

    /** Safe only when the native WorldInstanceEvent was cancelled before clone. */
    fun cancelAuthorizedRun(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        instanceWorld: String,
    ): SeasonRuntimeState {
        val normalized = instanceWorld.trim().lowercase()
        val authorization = state.authorizedDungeonRuns[normalized] ?: return state.validatedAgainst(catalog)
        val passes = state.admissionPasses.toMutableMap()
        authorization.participantIds.forEach { playerId ->
            val key = SeasonRuntimeState.admissionKey(playerId, authorization.dungeonContractId)
            val pass = passes[key] ?: return@forEach
            if (pass.status == DungeonAdmissionPassStatus.BOUND_TO_RUN && pass.boundRunId == authorization.runId) {
                passes[key] =
                    pass.copy(
                        status = DungeonAdmissionPassStatus.AVAILABLE,
                        boundRunId = null,
                        boundAt = null,
                    )
            }
        }
        return state.copy(
            admissionPasses = passes,
            authorizedDungeonRuns = state.authorizedDungeonRuns - normalized,
            revision = Math.addExact(state.revision, 1L),
        ).validatedAgainst(catalog)
    }

    private fun releaseToken(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        token: SeasonDungeonLaunchToken,
    ): SeasonRuntimeState {
        val passes = state.admissionPasses.toMutableMap()
        token.participantIds.forEach { playerId ->
            val key = SeasonRuntimeState.admissionKey(playerId, token.dungeonContractId)
            val pass = passes[key] ?: return@forEach
            if (pass.status == DungeonAdmissionPassStatus.BOUND_TO_RUN && pass.boundRunId == token.runId) {
                passes[key] =
                    pass.copy(
                        status = DungeonAdmissionPassStatus.AVAILABLE,
                        boundRunId = null,
                        boundAt = null,
                    )
            }
        }
        return state.copy(
            admissionPasses = passes,
            dungeonLaunchTokens = state.dungeonLaunchTokens - token.tokenId,
            revision = Math.addExact(state.revision, 1L),
        ).validatedAgainst(catalog)
    }
}

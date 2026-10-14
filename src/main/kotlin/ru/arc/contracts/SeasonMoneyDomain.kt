package ru.arc.contracts

import ru.arc.repository.Entity
import java.util.UUID

enum class SeasonMoneyActionKind(val label: String, val ledgerSource: String) {
    PROJECT_CASH("project_cash", "public_projects"),
    DUNGEON_ADMISSION("dungeon_admission", "dungeon_entry"),
}

sealed interface SeasonMoneyActionRequest {
    data class ProjectCash(
        val stageId: String,
        val requestedMinor: Long,
    ) : SeasonMoneyActionRequest

    data class DungeonAdmission(
        val dungeonContractId: String,
    ) : SeasonMoneyActionRequest
}

enum class SeasonMoneyRejection(val label: String) {
    INVALID_REQUEST("invalid_request"),
    SEASON_CLOSED("season_closed"),
    STALE_STATE("stale_state"),
    PROJECT_STAGE_LOCKED("project_stage_locked"),
    PROJECT_CASH_FILLED("project_cash_filled"),
    PROJECT_CONTRIBUTOR_LIMIT_REACHED("project_contributor_limit_reached"),
    DUNGEON_LOCKED("dungeon_locked"),
    ADMISSION_ALREADY_ACTIVE("admission_already_active"),
    ADMISSION_CAPACITY_REACHED("admission_capacity_reached"),
}

enum class DungeonAdmissionPassStatus(val label: String) {
    AVAILABLE("available"),
    BOUND_TO_RUN("bound_to_run"),
    CONSUMED("consumed"),
}

data class DungeonAdmissionPass(
    val passId: String,
    val playerId: String,
    val dungeonContractId: String,
    val entryBurnMinor: Long,
    val purchasedAt: Long,
    val status: DungeonAdmissionPassStatus = DungeonAdmissionPassStatus.AVAILABLE,
    val boundRunId: String? = null,
    val boundAt: Long? = null,
    val consumedAt: Long? = null,
) {
    init {
        validated()
    }

    fun validated(): DungeonAdmissionPass {
        require(SeasonRuntimeState.validActionId(passId)) { "Invalid dungeon admission pass id" }
        require(SeasonRuntimeState.validPlayerId(playerId)) { "Invalid dungeon admission player id" }
        require(SeasonRuntimeState.validTargetId(dungeonContractId)) { "Invalid dungeon admission contract id" }
        require(entryBurnMinor > 0L && purchasedAt >= 0L) { "Invalid dungeon admission purchase" }
        when (status) {
            DungeonAdmissionPassStatus.AVAILABLE -> {
                require(boundRunId == null && boundAt == null && consumedAt == null) {
                    "Available dungeon admission must not be bound or consumed"
                }
            }
            DungeonAdmissionPassStatus.BOUND_TO_RUN -> {
                require(validRunId(boundRunId) && boundAt != null && boundAt >= purchasedAt && consumedAt == null) {
                    "Bound dungeon admission has invalid run evidence"
                }
            }
            DungeonAdmissionPassStatus.CONSUMED -> {
                require(validRunId(boundRunId) && boundAt != null && consumedAt != null && consumedAt >= boundAt) {
                    "Consumed dungeon admission has invalid run evidence"
                }
            }
        }
        return this
    }

    companion object {
        const val MAX_RUN_ID_LENGTH = 128

        fun validRunId(value: String?): Boolean =
            value != null && value.isNotBlank() && value.length <= MAX_RUN_ID_LENGTH && value.none(Char::isISOControl)
    }
}

data class SeasonContributorProgress(
    val stages: Map<String, SeasonProjectStageProgress> = emptyMap(),
)

data class SeasonMoneyActionReceipt(
    val actionId: String,
    val kind: SeasonMoneyActionKind,
    val targetId: String,
    val playerId: String,
    val amountMinor: Long,
    val committedAt: Long,
) {
    init {
        validated()
    }

    fun validated(): SeasonMoneyActionReceipt {
        require(SeasonRuntimeState.validActionId(actionId)) { "Invalid season action receipt id" }
        require(SeasonRuntimeState.validTargetId(targetId)) { "Invalid season action receipt target" }
        require(SeasonRuntimeState.validPlayerId(playerId)) { "Invalid season action receipt player" }
        require(amountMinor > 0L && committedAt >= 0L) { "Invalid season action receipt amount or timestamp" }
        return this
    }
}

data class SeasonRuntimeState(
    val stateId: String,
    val seasonId: String,
    val catalogDigest: String,
    val project: SeasonProjectState,
    val projectContributors: Map<String, SeasonContributorProgress> = emptyMap(),
    val admissionPasses: Map<String, DungeonAdmissionPass> = emptyMap(),
    val dungeonLaunchTokens: Map<String, SeasonDungeonLaunchToken> = emptyMap(),
    val authorizedDungeonRuns: Map<String, SeasonDungeonRunAuthorization> = emptyMap(),
    val recentDungeonRewardReceipts: Map<String, SeasonDungeonRewardReceipt> = emptyMap(),
    val recentTrophyReceipts: Map<String, SeasonTrophyContributionReceipt> = emptyMap(),
    val recentReceipts: Map<String, SeasonMoneyActionReceipt> = emptyMap(),
    val revision: Long = 0L,
) : Entity {
    /** Keeps newly added collections initialized when Gson reads an older Redis payload. */
    @Suppress("unused")
    internal constructor() : this(
        stateId = "uninitialized",
        seasonId = "uninitialized",
        catalogDigest = "0".repeat(64),
        project = SeasonProjectState("uninitialized"),
    )

    init {
        require(stateId.isNotBlank()) { "Season runtime state id must not be blank" }
    }

    override fun id(): String = stateId

    fun validatedAgainst(catalog: ObserveSeasonCatalog): SeasonRuntimeState {
        val expectedDigest = catalog.revisionDigest()
        require(seasonId == catalog.id && catalogDigest == expectedDigest) {
            "Season runtime state does not match the exact catalog revision"
        }
        require(stateId == stateId(catalog)) { "Season runtime state id does not match the catalog" }
        SeasonProjectEngine.validated(catalog, project)
        require(projectContributors.size <= MAX_PROJECT_CONTRIBUTORS) {
            "Season project contributor capacity exceeded"
        }
        projectContributors.forEach { (playerId, progress) ->
            require(validPlayerId(playerId)) { "Invalid season project contributor id" }
            require(progress.stages.isNotEmpty() && progress.stages.keys.all(catalog.projectStages::containsKey)) {
                "Season project contributor contains invalid stage progress"
            }
            progress.stages.forEach { (stageId, stageProgress) ->
                validateContributorStage(catalog.projectStages.getValue(stageId), stageProgress)
            }
        }
        validateContributorTotals(project, projectContributors)

        require(admissionPasses.size <= MAX_ADMISSION_PASSES) { "Season admission pass capacity exceeded" }
        admissionPasses.forEach { (key, pass) ->
            pass.validated()
            require(key == admissionKey(pass.playerId, pass.dungeonContractId)) {
                "Season admission pass key does not match its contents"
            }
            val definition = requireNotNull(catalog.dungeonContracts[pass.dungeonContractId]) {
                "Season admission pass references an unknown dungeon"
            }
            require(pass.entryBurnMinor == definition.entryBurnMinorPerPlayer) {
                "Season admission pass amount does not match the catalog"
            }
        }

        require(dungeonLaunchTokens.size <= MAX_DUNGEON_LAUNCH_TOKENS) {
            "Season dungeon launch token capacity exceeded"
        }
        val tokenParticipants = mutableSetOf<String>()
        val tokenBlueprints = mutableSetOf<String>()
        val activeRunIds = mutableSetOf<String>()
        dungeonLaunchTokens.forEach { (key, token) ->
            token.validated()
            require(key == token.tokenId) { "Season dungeon launch token key does not match its contents" }
            require(token.catalogDigest == catalog.revisionDigest()) { "Season dungeon launch token uses another catalog" }
            val dungeon = requireNotNull(catalog.dungeonContracts[token.dungeonContractId]) {
                "Season dungeon launch token references an unknown dungeon"
            }
            require(token.blueprintWorld == dungeon.world) { "Season dungeon launch token world does not match catalog" }
            require(token.participantIds.none(tokenParticipants::contains)) {
                "Season dungeon launch participant is duplicated"
            }
            tokenParticipants.addAll(token.participantIds)
            require(tokenBlueprints.add(token.blueprintWorld)) { "Season dungeon launch blueprint is duplicated" }
            require(activeRunIds.add(token.runId)) { "Season dungeon launch run id is duplicated" }
            token.participantIds.forEach { playerId ->
                val pass = requireNotNull(admissionPasses[admissionKey(playerId, token.dungeonContractId)]) {
                    "Season dungeon launch participant pass is missing"
                }
                require(pass.status == DungeonAdmissionPassStatus.BOUND_TO_RUN && pass.boundRunId == token.runId) {
                    "Season dungeon launch participant pass is not bound to its run"
                }
            }
        }

        require(authorizedDungeonRuns.size <= MAX_AUTHORIZED_DUNGEON_RUNS) {
            "Season authorized dungeon run capacity exceeded"
        }
        authorizedDungeonRuns.forEach { (key, authorization) ->
            authorization.validated()
            require(key == authorization.instanceWorld) { "Season authorized dungeon run key does not match its contents" }
            require(authorization.catalogDigest == catalog.revisionDigest()) {
                "Season authorized dungeon run uses another catalog"
            }
            val dungeon = requireNotNull(catalog.dungeonContracts[authorization.dungeonContractId]) {
                "Season authorized dungeon run references an unknown dungeon"
            }
            require(authorization.blueprintWorld == dungeon.world) {
                "Season authorized dungeon run world does not match catalog"
            }
            require(activeRunIds.add(authorization.runId)) { "Season dungeon launch run id is duplicated" }
            require(authorization.participantIds.none(tokenParticipants::contains)) {
                "Season dungeon launch participant is duplicated"
            }
            tokenParticipants.addAll(authorization.participantIds)
            authorization.participantIds.forEach { playerId ->
                val pass = requireNotNull(admissionPasses[admissionKey(playerId, authorization.dungeonContractId)]) {
                    "Season authorized dungeon participant pass is missing"
                }
                require(
                    pass.status == DungeonAdmissionPassStatus.BOUND_TO_RUN ||
                        pass.status == DungeonAdmissionPassStatus.CONSUMED,
                ) { "Season authorized dungeon participant pass has invalid status" }
                require(pass.boundRunId == authorization.runId) {
                    "Season authorized dungeon participant pass is bound to another run"
                }
            }
        }

        require(recentDungeonRewardReceipts.size <= MAX_RECENT_DUNGEON_REWARD_RECEIPTS) {
            "Season dungeon reward receipt capacity exceeded"
        }
        recentDungeonRewardReceipts.forEach { (rewardId, receipt) ->
            receipt.validated()
            require(rewardId == receipt.rewardId) { "Season dungeon reward receipt key does not match its contents" }
            require(receipt.catalogDigest == catalog.revisionDigest()) {
                "Season dungeon reward receipt uses another catalog"
            }
            val dungeon = requireNotNull(catalog.dungeonContracts[receipt.dungeonContractId]) {
                "Season dungeon reward receipt references an unknown dungeon"
            }
            require(
                receipt.payoutMinor == dungeon.payoutMinorPerPlayer &&
                    receipt.trophyItemKey == dungeon.plannedBoundReward,
            ) { "Season dungeon reward receipt does not match its catalog policy" }
        }

        require(recentTrophyReceipts.size <= MAX_RECENT_TROPHY_RECEIPTS) {
            "Season trophy receipt capacity exceeded"
        }
        recentTrophyReceipts.forEach { (contributionId, receipt) ->
            receipt.validated()
            require(contributionId == receipt.contributionId) { "Season trophy receipt key does not match its contents" }
            val stage = requireNotNull(catalog.projectStages[receipt.stageId]) {
                "Season trophy receipt references an unknown stage"
            }
            require(receipt.itemKey in stage.requiredBoundRewards) {
                "Season trophy receipt references an unrelated item"
            }
        }

        require(recentReceipts.size <= MAX_RECENT_RECEIPTS) { "Season action receipt capacity exceeded" }
        recentReceipts.forEach { (actionId, receipt) ->
            receipt.validated()
            require(actionId == receipt.actionId) { "Season action receipt key does not match its contents" }
        }
        require(revision >= 0L) { "Season runtime revision must be non-negative" }
        return this
    }

    companion object {
        const val MAX_PROJECT_CONTRIBUTORS = 4_096
        const val MAX_ADMISSION_PASSES = 16_384
        const val MAX_DUNGEON_LAUNCH_TOKENS = 64
        const val MAX_AUTHORIZED_DUNGEON_RUNS = 128
        const val MAX_RECENT_DUNGEON_REWARD_RECEIPTS = 512
        const val MAX_RECENT_TROPHY_RECEIPTS = 512
        const val MAX_RECENT_RECEIPTS = 512
        const val MAX_ACTION_ID_LENGTH = 96
        const val MAX_TARGET_ID_LENGTH = 64
        private val ACTION_ID_PATTERN = Regex("[a-z0-9][a-z0-9:_-]{2,95}")
        private val TARGET_ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{2,63}")
        private val DIGEST_PATTERN = Regex("[a-f0-9]{64}")

        fun stateId(catalog: ObserveSeasonCatalog): String = "${catalog.id}:${catalog.revisionDigest()}"

        fun empty(catalog: ObserveSeasonCatalog): SeasonRuntimeState =
            SeasonRuntimeState(
                stateId = stateId(catalog),
                seasonId = catalog.id,
                catalogDigest = catalog.revisionDigest(),
                project = SeasonProjectEngine.initial(catalog),
            ).validatedAgainst(catalog)

        fun admissionKey(playerId: String, dungeonContractId: String): String = "$playerId:$dungeonContractId"

        fun validActionId(value: String): Boolean =
            value.length <= MAX_ACTION_ID_LENGTH && ACTION_ID_PATTERN.matches(value)

        fun validTargetId(value: String): Boolean =
            value.length <= MAX_TARGET_ID_LENGTH && TARGET_ID_PATTERN.matches(value)

        fun validPlayerId(value: String): Boolean =
            value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

        fun validDigest(value: String): Boolean = DIGEST_PATTERN.matches(value)

        private fun validateContributorStage(
            definition: ObserveProjectStageDefinition,
            progress: SeasonProjectStageProgress,
        ) {
            require(progress.cashMinor in 0L..definition.cashContributionMinor) {
                "Season contributor has invalid cash progress"
            }
            require(progress.resources.keys.all(definition.requiredResources::containsKey)) {
                "Season contributor has unknown resource progress"
            }
            require(progress.boundRewards.keys.all(definition.requiredBoundRewards::containsKey)) {
                "Season contributor has unknown bound reward progress"
            }
            progress.resources.forEach { (id, quantity) ->
                require(quantity in 1L..definition.requiredResources.getValue(id)) {
                    "Season contributor has invalid resource progress"
                }
            }
            progress.boundRewards.forEach { (id, quantity) ->
                require(quantity in 1L..definition.requiredBoundRewards.getValue(id)) {
                    "Season contributor has invalid bound reward progress"
                }
            }
            require(
                progress.cashMinor > 0L || progress.resources.isNotEmpty() || progress.boundRewards.isNotEmpty(),
            ) { "Season contributor stage progress must not be empty" }
        }

        private fun validateContributorTotals(
            project: SeasonProjectState,
            contributors: Map<String, SeasonContributorProgress>,
        ) {
            val totals = linkedMapOf<String, SeasonProjectStageProgress>()
            contributors.values.forEach { contributor ->
                contributor.stages.forEach { (stageId, progress) ->
                    val current = totals[stageId] ?: SeasonProjectStageProgress()
                    totals[stageId] =
                        SeasonProjectStageProgress(
                            cashMinor = Math.addExact(current.cashMinor, progress.cashMinor),
                            resources = addQuantities(current.resources, progress.resources),
                            boundRewards = addQuantities(current.boundRewards, progress.boundRewards),
                        )
                }
            }
            require(normalizedStages(totals) == normalizedStages(project.stages)) {
                "Season project progress does not match exact contributor totals"
            }
        }

        private fun addQuantities(
            left: Map<String, Long>,
            right: Map<String, Long>,
        ): Map<String, Long> {
            val result = left.toMutableMap()
            right.forEach { (id, quantity) -> result[id] = Math.addExact(result[id] ?: 0L, quantity) }
            return result
        }

        private fun normalizedStages(stages: Map<String, SeasonProjectStageProgress>): Map<String, SeasonProjectStageProgress> =
            stages.mapValues { (_, progress) ->
                SeasonProjectStageProgress(
                    cashMinor = progress.cashMinor,
                    resources = progress.resources.filterValues { it > 0L },
                    boundRewards = progress.boundRewards.filterValues { it > 0L },
                )
            }.filterValues { progress ->
                progress.cashMinor > 0L || progress.resources.isNotEmpty() || progress.boundRewards.isNotEmpty()
            }
    }
}

sealed interface SeasonMoneyActionPlan {
    data class Accepted(
        val actionId: String,
        val kind: SeasonMoneyActionKind,
        val targetId: String,
        val playerId: String,
        val amountMinor: Long,
        val expectedStateRevision: Long,
        val catalogDigest: String,
        val plannedAt: Long,
    ) : SeasonMoneyActionPlan

    data class Duplicate(val receipt: SeasonMoneyActionReceipt) : SeasonMoneyActionPlan

    data class Rejected(val reason: SeasonMoneyRejection) : SeasonMoneyActionPlan
}

data class SeasonMoneyCommitResult(
    val state: SeasonRuntimeState,
    val receipt: SeasonMoneyActionReceipt,
    val changed: Boolean,
)

data class DungeonAdmissionBindingResult(
    val state: SeasonRuntimeState,
    val boundPlayerIds: Set<String>,
)

object SeasonMoneyActionEngine {
    fun plan(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        actionId: String,
        playerId: String,
        request: SeasonMoneyActionRequest,
        now: Long,
    ): SeasonMoneyActionPlan {
        state.validatedAgainst(catalog)
        if (!SeasonRuntimeState.validActionId(actionId) || !SeasonRuntimeState.validPlayerId(playerId) || now < 0L) {
            return SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.INVALID_REQUEST)
        }
        state.recentReceipts[actionId]?.let { return SeasonMoneyActionPlan.Duplicate(it) }
        if (!catalog.isOpenAt(now)) return SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.SEASON_CLOSED)

        return when (request) {
            is SeasonMoneyActionRequest.ProjectCash -> planProjectCash(catalog, state, actionId, playerId, request, now)
            is SeasonMoneyActionRequest.DungeonAdmission -> planAdmission(catalog, state, actionId, playerId, request, now)
        }
    }

    fun commit(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        plan: SeasonMoneyActionPlan.Accepted,
        now: Long,
    ): SeasonMoneyCommitResult {
        state.validatedAgainst(catalog)
        require(now >= plan.plannedAt) { "Season action commit precedes its plan" }
        require(plan.catalogDigest == catalog.revisionDigest()) { "Season action plan uses a different catalog revision" }
        state.recentReceipts[plan.actionId]?.let { return SeasonMoneyCommitResult(state, it, false) }
        require(state.revision == plan.expectedStateRevision) { "Season action plan uses a stale state revision" }

        val nextState =
            when (plan.kind) {
                SeasonMoneyActionKind.PROJECT_CASH -> commitProjectCash(catalog, state, plan)
                SeasonMoneyActionKind.DUNGEON_ADMISSION -> commitAdmission(catalog, state, plan)
            }
        val receipt =
            SeasonMoneyActionReceipt(
                actionId = plan.actionId,
                kind = plan.kind,
                targetId = plan.targetId,
                playerId = plan.playerId,
                amountMinor = plan.amountMinor,
                committedAt = now,
            )
        val receipts = LinkedHashMap(state.recentReceipts)
        receipts[receipt.actionId] = receipt
        while (receipts.size > SeasonRuntimeState.MAX_RECENT_RECEIPTS) receipts.remove(receipts.keys.first())
        val committed =
            nextState.copy(
                recentReceipts = receipts,
                revision = Math.addExact(state.revision, 1L),
            ).validatedAgainst(catalog)
        return SeasonMoneyCommitResult(committed, receipt, true)
    }

    fun bindAvailableAdmissions(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        dungeonContractId: String,
        runId: String,
        participantIds: Set<String>,
        now: Long,
    ): DungeonAdmissionBindingResult {
        state.validatedAgainst(catalog)
        require(catalog.isOpenAt(now)) { "Cannot bind dungeon admission outside the season window" }
        require(DungeonAdmissionPass.validRunId(runId)) { "Invalid dungeon run id" }
        require(participantIds.size <= MAX_RUN_PARTICIPANTS && participantIds.all(SeasonRuntimeState::validPlayerId)) {
            "Invalid dungeon run participants"
        }
        val dungeon = requireNotNull(catalog.dungeonContracts[dungeonContractId]) { "Unknown season dungeon" }
        require(
            dungeon.requiresProjectStage in SeasonProjectEngine.completedStages(catalog, state.project),
        ) { "Season dungeon is still locked by project progress" }

        val passes = state.admissionPasses.toMutableMap()
        val bound = linkedSetOf<String>()
        participantIds.forEach { playerId ->
            val key = SeasonRuntimeState.admissionKey(playerId, dungeonContractId)
            val pass = passes[key] ?: return@forEach
            when (pass.status) {
                DungeonAdmissionPassStatus.AVAILABLE -> {
                    passes[key] =
                        pass.copy(
                            status = DungeonAdmissionPassStatus.BOUND_TO_RUN,
                            boundRunId = runId,
                            boundAt = now,
                        )
                    bound += playerId
                }
                DungeonAdmissionPassStatus.BOUND_TO_RUN -> {
                    if (pass.boundRunId == runId) bound += playerId
                }
                DungeonAdmissionPassStatus.CONSUMED -> Unit
            }
        }
        val changed = passes != state.admissionPasses
        val next =
            if (!changed) {
                state
            } else {
                state.copy(
                    admissionPasses = passes,
                    revision = Math.addExact(state.revision, 1L),
                ).validatedAgainst(catalog)
            }
        return DungeonAdmissionBindingResult(next, bound)
    }

    fun consumeBoundAdmissions(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        dungeonContractId: String,
        runId: String,
        playerIds: Set<String>,
        now: Long,
    ): DungeonAdmissionBindingResult {
        state.validatedAgainst(catalog)
        require(DungeonAdmissionPass.validRunId(runId)) { "Invalid dungeon run id" }
        require(playerIds.size <= MAX_RUN_PARTICIPANTS && playerIds.all(SeasonRuntimeState::validPlayerId)) {
            "Invalid dungeon completion participants"
        }
        require(dungeonContractId in catalog.dungeonContracts) { "Unknown season dungeon" }
        val passes = state.admissionPasses.toMutableMap()
        val consumed = linkedSetOf<String>()
        playerIds.forEach { playerId ->
            val key = SeasonRuntimeState.admissionKey(playerId, dungeonContractId)
            val pass = passes[key] ?: return@forEach
            val consumable =
                when (pass.status) {
                    DungeonAdmissionPassStatus.AVAILABLE ->
                        pass.copy(
                            status = DungeonAdmissionPassStatus.BOUND_TO_RUN,
                            boundRunId = runId,
                            boundAt = now,
                        )
                    DungeonAdmissionPassStatus.BOUND_TO_RUN -> pass.takeIf { it.boundRunId == runId }
                    DungeonAdmissionPassStatus.CONSUMED -> null
                } ?: return@forEach
            passes[key] = consumable.copy(status = DungeonAdmissionPassStatus.CONSUMED, consumedAt = now)
            consumed += playerId
        }
        val next =
            if (consumed.isEmpty()) {
                state
            } else {
                state.copy(
                    admissionPasses = passes,
                    revision = Math.addExact(state.revision, 1L),
                ).validatedAgainst(catalog)
            }
        return DungeonAdmissionBindingResult(next, consumed)
    }

    private fun planProjectCash(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        actionId: String,
        playerId: String,
        request: SeasonMoneyActionRequest.ProjectCash,
        now: Long,
    ): SeasonMoneyActionPlan {
        if (request.requestedMinor <= 0L || !SeasonRuntimeState.validTargetId(request.stageId)) {
            return SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.INVALID_REQUEST)
        }
        val definition = catalog.projectStages[request.stageId]
            ?: return SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.INVALID_REQUEST)
        if (SeasonProjectEngine.status(catalog, state.project, request.stageId) != SeasonProjectStageStatus.OPEN) {
            return SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.PROJECT_STAGE_LOCKED)
        }
        val already = state.project.stages[request.stageId]?.cashMinor ?: 0L
        val remaining = definition.cashContributionMinor - already
        if (remaining <= 0L) return SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.PROJECT_CASH_FILLED)
        if (playerId !in state.projectContributors &&
            state.projectContributors.size >= SeasonRuntimeState.MAX_PROJECT_CONTRIBUTORS
        ) {
            return SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.PROJECT_CONTRIBUTOR_LIMIT_REACHED)
        }
        return accepted(
            catalog,
            state,
            actionId,
            SeasonMoneyActionKind.PROJECT_CASH,
            request.stageId,
            playerId,
            minOf(request.requestedMinor, remaining),
            now,
        )
    }

    private fun planAdmission(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        actionId: String,
        playerId: String,
        request: SeasonMoneyActionRequest.DungeonAdmission,
        now: Long,
    ): SeasonMoneyActionPlan {
        val dungeon = catalog.dungeonContracts[request.dungeonContractId]
            ?: return SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.INVALID_REQUEST)
        if (dungeon.requiresProjectStage !in SeasonProjectEngine.completedStages(catalog, state.project)) {
            return SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.DUNGEON_LOCKED)
        }
        val key = SeasonRuntimeState.admissionKey(playerId, dungeon.id)
        val existing = state.admissionPasses[key]
        if (existing?.status == DungeonAdmissionPassStatus.AVAILABLE ||
            existing?.status == DungeonAdmissionPassStatus.BOUND_TO_RUN
        ) {
            return SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.ADMISSION_ALREADY_ACTIVE)
        }
        if (existing == null && state.admissionPasses.size >= SeasonRuntimeState.MAX_ADMISSION_PASSES) {
            return SeasonMoneyActionPlan.Rejected(SeasonMoneyRejection.ADMISSION_CAPACITY_REACHED)
        }
        return accepted(
            catalog,
            state,
            actionId,
            SeasonMoneyActionKind.DUNGEON_ADMISSION,
            dungeon.id,
            playerId,
            dungeon.entryBurnMinorPerPlayer,
            now,
        )
    }

    private fun accepted(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        actionId: String,
        kind: SeasonMoneyActionKind,
        targetId: String,
        playerId: String,
        amountMinor: Long,
        now: Long,
    ): SeasonMoneyActionPlan.Accepted =
        SeasonMoneyActionPlan.Accepted(
            actionId = actionId,
            kind = kind,
            targetId = targetId,
            playerId = playerId,
            amountMinor = amountMinor,
            expectedStateRevision = state.revision,
            catalogDigest = catalog.revisionDigest(),
            plannedAt = now,
        )

    private fun commitProjectCash(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        plan: SeasonMoneyActionPlan.Accepted,
    ): SeasonRuntimeState {
        val contribution =
            SeasonProjectEngine.contribute(
                catalog,
                state.project,
                plan.targetId,
                SeasonProjectContribution.Cash(plan.amountMinor),
            )
        require(contribution.acceptedAmount == plan.amountMinor) { "Season project cash plan no longer fits" }
        val contributor = state.projectContributors[plan.playerId] ?: SeasonContributorProgress()
        val stage = contributor.stages[plan.targetId] ?: SeasonProjectStageProgress()
        val updatedContributor =
            contributor.copy(
                stages =
                    contributor.stages +
                        (plan.targetId to stage.copy(cashMinor = Math.addExact(stage.cashMinor, plan.amountMinor))),
            )
        return state.copy(
            project = contribution.state,
            projectContributors = state.projectContributors + (plan.playerId to updatedContributor),
        )
    }

    private fun commitAdmission(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        plan: SeasonMoneyActionPlan.Accepted,
    ): SeasonRuntimeState {
        val dungeon = catalog.dungeonContracts.getValue(plan.targetId)
        require(plan.amountMinor == dungeon.entryBurnMinorPerPlayer) { "Season admission amount changed after planning" }
        val pass =
            DungeonAdmissionPass(
                passId = plan.actionId,
                playerId = plan.playerId,
                dungeonContractId = dungeon.id,
                entryBurnMinor = plan.amountMinor,
                purchasedAt = plan.plannedAt,
            )
        return state.copy(
            admissionPasses =
                state.admissionPasses +
                    (SeasonRuntimeState.admissionKey(plan.playerId, dungeon.id) to pass),
        )
    }

    private const val MAX_RUN_PARTICIPANTS = 128
}

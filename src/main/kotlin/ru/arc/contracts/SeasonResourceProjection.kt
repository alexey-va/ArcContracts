package ru.arc.contracts

data class SeasonResourceProjectionResult(
    val state: SeasonRuntimeState,
    val changed: Boolean,
)

/**
 * Rebuilds public-project resource progress from the authoritative, paid
 * resource-contract totals. The projection is absolute and idempotent, so a
 * crash after a contract commit can be repaired without replaying inventory or
 * money side effects.
 */
object SeasonResourceProjectionEngine {
    fun project(
        catalog: ObserveSeasonCatalog,
        state: SeasonRuntimeState,
        definitions: Collection<ResourceContractDefinition>,
        contractStates: Collection<ResourceContractState>,
    ): SeasonResourceProjectionResult {
        state.validatedAgainst(catalog)
        catalog.validatedResourceLinks(definitions.toList())
        val definitionById = definitions.associateBy { it.id }
        require(definitionById.size == definitions.size) { "Duplicate season resource contract definitions" }
        val stateById = contractStates.associateBy { it.contractId }
        require(stateById.size == contractStates.size && stateById.keys == definitionById.keys) {
            "Season resource projection requires one exact state per configured order"
        }
        stateById.forEach { (orderId, contractState) ->
            contractState.validatedAgainst(definitionById.getValue(orderId))
        }

        var project = stripResources(state.project)
        val contributors = stripContributorResources(state.projectContributors).toMutableMap()
        val stageByOrder =
            catalog.projectStages.values.flatMap { stage ->
                stage.requiredResources.keys.map { orderId -> orderId to stage.id }
            }.toMap()

        topologicalStages(catalog).forEach { stageId ->
            val definition = catalog.projectStages.getValue(stageId)
            val orders = definition.requiredResources.keys.sorted()
            val hasAcceptedResources = orders.any { stateById.getValue(it).acceptedQuantity > 0L }
            if (hasAcceptedResources) {
                require(SeasonProjectEngine.status(catalog, project, stageId) == SeasonProjectStageStatus.OPEN) {
                    "Season resource contract progress exists before project stage '$stageId' is open"
                }
            }
            val resources =
                orders.mapNotNull { orderId ->
                    val quantity = stateById.getValue(orderId).acceptedQuantity
                    orderId.takeIf { quantity > 0L }?.let { it to quantity }
                }.toMap()
            if (resources.isNotEmpty()) {
                val current = project.stages[stageId] ?: SeasonProjectStageProgress()
                project = project.copy(stages = project.stages + (stageId to current.copy(resources = resources)))
            }
            orders.forEach { orderId ->
                val contract = stateById.getValue(orderId)
                contract.perPlayerQuantity.filterValues { it > 0L }.forEach { (playerId, quantity) ->
                    require(SeasonRuntimeState.validPlayerId(playerId)) {
                        "Season resource contract contains a non-UUID contributor"
                    }
                    val contributor = contributors[playerId] ?: SeasonContributorProgress()
                    val progress = contributor.stages[stageId] ?: SeasonProjectStageProgress()
                    val nextProgress = progress.copy(resources = progress.resources + (orderId to quantity))
                    contributors[playerId] = contributor.copy(stages = contributor.stages + (stageId to nextProgress))
                }
            }
        }
        require(stageByOrder.keys == definitionById.keys) {
            "Season resource projection contains an unlinked contract definition"
        }

        val projected =
            state.copy(
                project = project,
                projectContributors = contributors,
            ).let { candidate ->
                if (candidate.project == state.project && candidate.projectContributors == state.projectContributors) {
                    state
                } else {
                    candidate.copy(revision = Math.addExact(state.revision, 1L))
                }
            }.validatedAgainst(catalog)
        return SeasonResourceProjectionResult(projected, projected !== state)
    }

    private fun stripResources(project: SeasonProjectState): SeasonProjectState =
        project.copy(
            stages =
                project.stages.mapValues { (_, progress) -> progress.copy(resources = emptyMap()) }
                    .filterValues(::hasProgress),
        )

    private fun stripContributorResources(
        contributors: Map<String, SeasonContributorProgress>,
    ): Map<String, SeasonContributorProgress> =
        contributors.mapValues { (_, contributor) ->
            contributor.copy(
                stages =
                    contributor.stages.mapValues { (_, progress) -> progress.copy(resources = emptyMap()) }
                        .filterValues(::hasProgress),
            )
        }.filterValues { it.stages.isNotEmpty() }

    private fun hasProgress(progress: SeasonProjectStageProgress): Boolean =
        progress.cashMinor > 0L || progress.resources.isNotEmpty() || progress.boundRewards.isNotEmpty()

    private fun topologicalStages(catalog: ObserveSeasonCatalog): List<String> {
        val ordered = mutableListOf<String>()
        val remaining = catalog.projectStages.keys.toMutableSet()
        while (remaining.isNotEmpty()) {
            val ready =
                remaining.filter { stageId ->
                    catalog.projectStages.getValue(stageId).requiresProjectStages.all(ordered::contains)
                }.sorted()
            require(ready.isNotEmpty()) { "Season project progression is cyclic" }
            ordered += ready
            remaining -= ready.toSet()
        }
        return ordered
    }
}

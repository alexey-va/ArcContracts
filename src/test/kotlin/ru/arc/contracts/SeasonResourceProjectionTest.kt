package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SeasonResourceProjectionTest : StringSpec({
    val catalog = testSeasonCatalog()
    val playerId = "11111111-1111-1111-1111-111111111111"
    val otherPlayerId = "22222222-2222-2222-2222-222222222222"
    val definitions = resourceDefinitions(catalog)

    "projects exact paid contract totals with contributor attribution and idempotency" {
        val contracts =
            definitions.map { definition ->
                when (definition.id) {
                    "road_stone" -> contractState(definition, mapOf(playerId to 60_000L, otherPlayerId to 40_000L))
                    "station_timber" -> contractState(definition, mapOf(playerId to 20_000L))
                    else -> ResourceContractState.empty(definition)
                }
            }
        val initial = SeasonRuntimeState.empty(catalog)
        val first = SeasonResourceProjectionEngine.project(catalog, initial, definitions, contracts)

        first.changed shouldBe true
        first.state.project.stages.getValue("road_foundation").resources shouldBe
            mapOf("road_stone" to 100_000L, "station_timber" to 20_000L)
        first.state.projectContributors.getValue(playerId).stages.getValue("road_foundation").resources shouldBe
            mapOf("road_stone" to 60_000L, "station_timber" to 20_000L)
        first.state.projectContributors.getValue(otherPlayerId).stages.getValue("road_foundation").resources shouldBe
            mapOf("road_stone" to 40_000L)

        val replay = SeasonResourceProjectionEngine.project(catalog, first.state, definitions, contracts)
        replay.changed shouldBe false
        replay.state shouldBe first.state
    }

    "fails closed when a later-stage order has progress before its project stage opens" {
        val contracts =
            definitions.map { definition ->
                if (definition.id == "station_iron") {
                    contractState(definition, mapOf(playerId to 1L))
                } else {
                    ResourceContractState.empty(definition)
                }
            }

        shouldThrow<IllegalArgumentException> {
            SeasonResourceProjectionEngine.project(
                catalog,
                SeasonRuntimeState.empty(catalog),
                definitions,
                contracts,
            )
        }.message shouldBe "Season resource contract progress exists before project stage 'station_frame' is open"
    }
})

private fun resourceDefinitions(catalog: ObserveSeasonCatalog): List<ResourceContractDefinition> =
    catalog.projectStages.values.flatMap { it.requiredResources.entries }.map { (orderId, target) ->
        ResourceContractDefinition(
            id = orderId,
            displayName = orderId,
            itemKey = "minecraft:stone",
            funding = ContractFunding.SERVER_ENVELOPE,
            windowStartsAt = catalog.startsAt,
            windowEndsAt = catalog.startsAt + 7 * 86_400_000L,
            payoutMinorPerUnit = 1L,
            budgetMinor = target,
            targetQuantity = target,
            perPlayerQuantityCap = target,
            minSubmissionQuantity = 1,
            maxSubmissionQuantity = 2_304,
        )
    }

private fun contractState(
    definition: ResourceContractDefinition,
    players: Map<String, Long>,
): ResourceContractState {
    val quantity = players.values.fold(0L, Math::addExact)
    return ResourceContractState(
        contractId = definition.id,
        windowStartsAt = definition.windowStartsAt,
        windowEndsAt = definition.windowEndsAt,
        acceptedQuantity = quantity,
        spentMinor = Math.multiplyExact(quantity, definition.payoutMinorPerUnit),
        perPlayerQuantity = players,
    ).validatedAgainst(definition)
}

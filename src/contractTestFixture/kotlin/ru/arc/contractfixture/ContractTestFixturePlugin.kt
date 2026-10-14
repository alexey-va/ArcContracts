package ru.arc.contractfixture

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.entity.EntityType
import org.bukkit.GameRule
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.contracts.ContractOriginGate
import ru.arc.contracts.ContractsConfig
import ru.arc.contracts.api.ArcContractsHostConfiguration
import ru.arc.contracts.api.ArcContractsService

/** CI-only Citizens desk. The production shadow JAR never includes this source set. */
class ContractTestFixturePlugin : JavaPlugin() {
    private var desk: NPC? = null

    override fun onEnable() {
        check(server.ip == "127.0.0.1" && !server.onlineMode) {
            "Contract test fixture requires the loopback offline CI server"
        }
        val contracts = checkNotNull(server.servicesManager.load(ArcContractsService::class.java)) {
            "ArcContracts service is unavailable"
        }
        check(contracts.isAvailable() && contracts.boardSnapshot().contracts().any { it.id() == "e2e_stone" }) {
            "Synthetic contract missing"
        }
        val citizens = requireNotNull(Bukkit.getPluginManager().getPlugin("Citizens")) {
            "Contract test fixture requires Citizens"
        }
        check(citizens.isEnabled) { "Contract test fixture requires enabled Citizens" }
        val world = server.getWorld(ContractOriginGate.ORIGIN_WORLD)
            ?: error("Contract fixture world is unavailable: ${ContractOriginGate.ORIGIN_WORLD}")
        world.setGameRule(GameRule.SPAWN_RADIUS, 0)
        val location = world.spawnLocation.clone().add(2.0, 0.0, 0.0)
        val created = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, DESK_NAME)
        check(created.spawn(location)) { "Contract test desk NPC did not spawn" }
        desk = created

        val host = checkNotNull(server.servicesManager.load(ArcContractsHostConfiguration::class.java)) {
            "ARC host configuration service is unavailable"
        }
        val contractsConfig = object : ContractsConfig(
            ConfigManager.of(host.legacyDataPath(), "modules/contracts.yml"),
        ) {
            override val submissionNpcRoutes: Map<Int, String> = mapOf(created.id to "spawn")
        }
        // Test-only route replacement; authorization is still minted only by the
        // production NPCRightClickEvent listener after the client clicks this NPC.
        ContractOriginGate.configure(contractsConfig)
        logger.info(
            "CONTRACT_TEST_FIXTURE_READY:${created.id}:${world.name}:${location.x}:${location.y}:${location.z}",
        )
    }

    override fun onDisable() {
        desk?.destroy()
    }

    companion object {
        const val DESK_NAME = "E2E_Desk"
    }
}

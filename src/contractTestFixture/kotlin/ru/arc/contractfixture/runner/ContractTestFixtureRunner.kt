package ru.arc.contractfixture.runner

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.entity.EntityType
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.contracts.ContractOriginGate
import ru.arc.contracts.ContractSubmissionCoordinator
import ru.arc.contracts.ContractsConfig
import ru.arc.contracts.api.ArcContractsHostConfiguration
import ru.arc.contracts.api.ArcContractsService
import kotlin.coroutines.Continuation

/** CI-only runner loaded below the ArcContracts plugin loader; never shipped in the production JAR. */
class ContractTestFixtureRunner(private val plugin: JavaPlugin) : Runnable, AutoCloseable {
    private var desk: NPC? = null

    override fun run() {
        check(plugin.server.ip == "127.0.0.1" && !plugin.server.onlineMode) {
            "Contract test fixture requires the loopback offline CI server"
        }
        val arcContractsLoader = requireNotNull(Bukkit.getPluginManager().getPlugin("ArcContracts"))
            .javaClass.classLoader
        check(javaClass.classLoader !== arcContractsLoader) { "Fixture runner was not isolated from the ArcContracts plugin loader" }
        check(ContractSubmissionCoordinator::class.java.classLoader === arcContractsLoader) {
            "Contract implementation did not resolve from the ArcContracts plugin loader"
        }
        check(ContractsConfig::class.java.classLoader === arcContractsLoader) {
            "Contract configuration did not resolve from the ArcContracts plugin loader"
        }
        check(ConfigManager::class.java.classLoader === arcContractsLoader) {
            "Arc core implementation did not resolve from the ArcContracts plugin loader"
        }
        check(Config::class.java.classLoader === arcContractsLoader) {
            "Arc config implementation did not resolve from the ArcContracts plugin loader"
        }
        check(Continuation::class.java.classLoader === arcContractsLoader) {
            "Kotlin coroutines API did not resolve from the ArcContracts plugin loader"
        }
        check(Unit::class.java.classLoader === arcContractsLoader) {
            "Kotlin runtime did not resolve from the ArcContracts plugin loader"
        }

        val contracts = checkNotNull(plugin.server.servicesManager.load(ArcContractsService::class.java)) {
            "ArcContracts service is unavailable"
        }
        check(contracts.isAvailable() && contracts.boardSnapshot().contracts().any { it.id() == "e2e_stone" }) {
            "Synthetic contract missing"
        }
        val citizens = requireNotNull(Bukkit.getPluginManager().getPlugin("Citizens")) {
            "Contract test fixture requires Citizens"
        }
        check(citizens.isEnabled) { "Contract test fixture requires enabled Citizens" }
        val world = plugin.server.getWorld(ContractOriginGate.ORIGIN_WORLD)
            ?: error("Contract fixture world is unavailable: ${ContractOriginGate.ORIGIN_WORLD}")
        // Keep the same fixture compatible with Paper 1.21.11 and 26.1.2.
        @Suppress("DEPRECATION")
        world.setGameRule(GameRule.SPAWN_RADIUS, 0)
        val location = world.spawnLocation.clone().add(2.0, 0.0, 0.0)
        val created = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, DESK_NAME)
        check(created.spawn(location)) { "Contract test desk NPC did not spawn" }
        desk = created

        val host = checkNotNull(plugin.server.servicesManager.load(ArcContractsHostConfiguration::class.java)) {
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
        plugin.logger.info(
            "CONTRACT_TEST_FIXTURE_READY:${created.id}:${world.name}:${location.x}:${location.y}:${location.z}",
        )
    }

    override fun close() {
        desk?.destroy()
        desk = null
    }

    companion object {
        const val DESK_NAME = "E2E_Desk"
    }
}

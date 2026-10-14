package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path

class ContractsConfigTest : StringSpec({
    "accepts the generated Economy V2 observe-policy golden fixture" {
        val resource = requireNotNull(ContractsConfigTest::class.java.getResource("/contracts/modules/contracts.yml"))
        val config = ContractsConfig.fromFile(Path.of(resource.toURI()).parent.parent)

        config.validated() shouldBe config
        config.mode shouldBe ContractsMode.OBSERVE
        config.submissionNpcId shouldBe 390
        config.submissionNpcGroup shouldBe "guild_orders"
        config.submissionNpcRoutes shouldBe
            mapOf(350 to "forge_orders", 367 to "bank_orders", 390 to "guild_orders")
        config.submissionNpcRadius shouldBe 4.5
        config.submissionNpcSessionTtl shouldBe Duration.ofSeconds(120)
        config.serverWeeklyBudgetMinor shouldBe 9_500_000L
        config.resourceOrders().shouldHaveSize(5)
        val season = config.observeSeasonCatalog()
        requireNotNull(season)
        season.completionStage shouldBe "expedition_museum"
        season.startsAt shouldBe 1_787_184_764_000L
        season.endsAt shouldBe 1_789_603_964_000L
        season.dungeonContracts.size shouldBe 4
        season.projectStages.size shouldBe 4
        season.dungeonContracts.getValue("bridge_recon").requiresProjectStage shouldBe "track_and_lighting"
    }

    "loads exact decimal money and explicit resource-order windows" {
        val root = Files.createTempDirectory("arc-contract-config")
        val modules = root.resolve("modules")
        Files.createDirectories(modules)
        Files.writeString(
            modules.resolve("contracts.yml"),
            """
            enabled: true
            mode: observe
            leader-server: spawn
            server-weekly-budget: "250000.00"
            orders:
              road_stone:
                enabled: true
                group: forge_orders
                kind: resource
                display-name: "Камень для тракта"
                item: STONE
                funding: server_envelope
                window-starts-at: "2026-08-18T00:00:00Z"
                window-ends-at: "2026-08-25T00:00:00Z"
                payout-per-unit: "2.50"
                budget: "50000.00"
                target-quantity: 20000
                per-player-quantity-cap: 4000
                min-submission-quantity: 16
                max-submission-quantity: 2304
            """.trimIndent(),
        )

        val config = ContractsConfig.fromFile(root)
        config.enabled shouldBe true
        config.mode shouldBe ContractsMode.OBSERVE
        config.serverWeeklyBudgetMinor shouldBe 25_000_000L
        config.validated() shouldBe config
        val order = config.resourceOrders().shouldHaveSize(1).single()
        order.itemKey shouldBe "minecraft:stone"
        order.payoutMinorPerUnit shouldBe 250L
        order.budgetMinor shouldBe 5_000_000L
        order.targetQuantity shouldBe 20_000L
        order.group shouldBe "forge_orders"
    }

    "rejects aggregate order budgets above the network envelope" {
        val root = Files.createTempDirectory("arc-contract-config")
        val modules = root.resolve("modules")
        Files.createDirectories(modules)
        Files.writeString(
            modules.resolve("contracts.yml"),
            """
            enabled: true
            mode: observe
            server-weekly-budget: "10.00"
            orders:
              road_stone:
                enabled: true
                item: minecraft:stone
                window-starts-at: "2026-08-18T00:00:00Z"
                window-ends-at: "2026-08-25T00:00:00Z"
                payout-per-unit: "1.00"
                budget: "10.01"
                target-quantity: 100
                per-player-quantity-cap: 20
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ContractsConfig.fromFile(root).resourceOrders()
        }.message shouldBe "Configured concurrent contract budgets 1001 exceed server weekly envelope 1000 minor units"
    }

    "rejects fractional money below one minor unit" {
        shouldThrow<IllegalArgumentException> {
            ContractsConfig.moneyMinor("0.001", "payout")
        }.message shouldBe "Contract money 'payout' must have at most two decimals"
    }

    "rejects non-normalized contract ids before disabled entries can hide them" {
        val root = Files.createTempDirectory("arc-contract-config")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/contracts.yml"),
            """
            enabled: true
            mode: observe
            server-weekly-budget: "0.00"
            orders:
              Road_Stone:
                enabled: false
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ContractsConfig.fromFile(root).resourceOrders()
        }.message shouldBe "Contract id 'Road_Stone' must already be normalized lowercase ASCII"
    }

    "rejects a resource order without a concrete namespaced item" {
        val root = Files.createTempDirectory("arc-contract-config")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/contracts.yml"),
            """
            enabled: true
            mode: observe
            server-weekly-budget: "100.00"
            orders:
              road_stone:
                enabled: true
                item: ""
                window-starts-at: "2026-08-18T00:00:00Z"
                window-ends-at: "2026-08-25T00:00:00Z"
                payout-per-unit: "1.00"
                budget: "100.00"
                target-quantity: 100
                per-player-quantity-cap: 20
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ContractsConfig.fromFile(root).resourceOrders()
        }.message shouldBe "Invalid namespaced contract itemKey: "
    }

    "rejects misspelled policy modes instead of silently falling back" {
        val root = Files.createTempDirectory("arc-contract-config")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/contracts.yml"),
            """
            enabled: true
            mode: enfore
            server-weekly-budget: "0.00"
            orders: {}
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ContractsConfig.fromFile(root).validated()
        }.message shouldBe "Contract enum 'mode' must be one of disabled, observe, enforce"
    }

    "rejects an observe season catalog in enforce mode" {
        val resource = requireNotNull(ContractsConfigTest::class.java.getResource("/contracts/modules/contracts.yml"))
        val source = Path.of(resource.toURI())
        val root = Files.createTempDirectory("arc-contract-season-enforce")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/contracts.yml"),
            Files.readString(source).replace("mode: observe", "mode: enforce"),
        )

        shouldThrow<IllegalArgumentException> {
            ContractsConfig.fromFile(root).validated()
        }.message shouldBe "Season catalog is observe-only and cannot be loaded outside observe mode"

        val enabledForCompiledRuntime = ContractsConfig.fromFile(root)
        enabledForCompiledRuntime.validated(allowSeasonMutations = true) shouldBe enabledForCompiledRuntime
        enabledForCompiledRuntime.observeSeasonCatalog(allowSeasonMutations = true)?.id shouldBe "road_revival"
    }

    "rejects a season project locked behind its own dungeon reward" {
        val resource = requireNotNull(ContractsConfigTest::class.java.getResource("/contracts/modules/contracts.yml"))
        val source = Path.of(resource.toURI())
        val root = Files.createTempDirectory("arc-contract-season-cycle")
        Files.createDirectories(root.resolve("modules"))
        val broken =
            Files.readString(source)
                .replace(
                    "requires-project-stage: track_and_lighting\n      expected-active-minutes: 45",
                    "requires-project-stage: expedition_museum\n      expected-active-minutes: 45",
                ).replace(
                    "        unlocks-dungeon-contracts:\n        - bridge_recon\n        unlock: visible_rail_and_lighting_milestone",
                    "        unlocks-dungeon-contracts: []\n        unlock: visible_rail_and_lighting_milestone",
                ).replace(
                    "        unlocks-dungeon-contracts: []\n        unlock: season_museum_finale",
                    "        unlocks-dungeon-contracts:\n        - bridge_recon\n        unlock: season_museum_finale",
                )
        Files.writeString(root.resolve("modules/contracts.yml"), broken)

        shouldThrow<IllegalArgumentException> {
            ContractsConfig.fromFile(root).validated()
        }.message shouldBe "Season project progression is cyclic or reward-locked"
    }

    "rejects an old season schema and a duration mismatch" {
        val resource = requireNotNull(ContractsConfigTest::class.java.getResource("/contracts/modules/contracts.yml"))
        val source = Files.readString(Path.of(resource.toURI()))

        fun brokenConfig(name: String, content: String): ContractsConfig {
            val root = Files.createTempDirectory(name)
            Files.createDirectories(root.resolve("modules"))
            Files.writeString(root.resolve("modules/contracts.yml"), content)
            return ContractsConfig.fromFile(root)
        }

        shouldThrow<IllegalArgumentException> {
            brokenConfig(
                "arc-contract-season-schema",
                source.replace("season-catalog:\n  schema-version: 4", "season-catalog:\n  schema-version: 3"),
            ).validated()
        }.message shouldBe "Season catalog schema-version must be 4"

        shouldThrow<IllegalArgumentException> {
            brokenConfig(
                "arc-contract-season-duration",
                source.replace("ends-at: '2026-09-17T00:12:44Z'", "ends-at: '2026-09-16T00:12:44Z'"),
            ).validated()
        }.message shouldBe "Season window must exactly match duration-days"
    }

    "rejects a resource-order window outside the exact season" {
        val resource = requireNotNull(ContractsConfigTest::class.java.getResource("/contracts/modules/contracts.yml"))
        val source = Files.readString(Path.of(resource.toURI()))
        val root = Files.createTempDirectory("arc-contract-season-order-window")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/contracts.yml"),
            source.replace(
                "window-starts-at: '2026-08-20T00:12:44Z'",
                "window-starts-at: '2026-08-19T00:12:44Z'",
            ),
        )

        shouldThrow<IllegalArgumentException> {
            ContractsConfig.fromFile(root).validated()
        }.message shouldBe "Season resource-order windows must remain inside the season window"
    }
})

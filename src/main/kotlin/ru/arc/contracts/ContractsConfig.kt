package ru.arc.contracts

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

enum class ContractsMode(val label: String) {
    DISABLED("disabled"),
    OBSERVE("observe"),
    ENFORCE("enforce"),
}

data class ObserveDungeonContractDefinition(
    val id: String,
    val displayName: String,
    val world: String,
    val requiresProjectStage: String,
    val expectedActiveMinutes: Int,
    val rewardCooldownMinutes: Int,
    val payoutMinorPerPlayer: Long,
    val entryBurnMinorPerPlayer: Long,
    val minimumActiveShare: Double,
    val weeklyQualifyingPlayerCap: Int,
    val plannedBoundReward: String,
)

data class ObserveProjectStageDefinition(
    val id: String,
    val displayName: String,
    val requiresProjectStages: Set<String>,
    val cashContributionMinor: Long,
    val requiredResources: Map<String, Long>,
    val requiredBoundRewards: Map<String, Long>,
    val unlocksDungeonContracts: Set<String>,
    val unlock: String,
)

data class ObserveSeasonCatalog(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val completionStage: String,
    val durationDays: Int,
    val startsAt: Long,
    val endsAt: Long,
    val dungeonContracts: Map<String, ObserveDungeonContractDefinition>,
    val projectId: String,
    val projectTitle: String,
    val projectStages: Map<String, ObserveProjectStageDefinition>,
) {
    fun summary(): Map<String, Any?> =
        linkedMapOf(
            "observeOnly" to true,
            "schemaVersion" to schemaVersion,
            "id" to id,
            "title" to title,
            "completionStage" to completionStage,
            "durationDays" to durationDays,
            "startsAt" to startsAt,
            "endsAt" to endsAt,
            "dungeonContracts" to dungeonContracts.values,
            "publicProject" to
                linkedMapOf(
                    "id" to projectId,
                    "title" to projectTitle,
                    "stages" to projectStages.values,
                ),
        )

    fun validatedResourceLinks(resourceOrders: List<ResourceContractDefinition>): ObserveSeasonCatalog {
        val quantities = mutableMapOf<String, Long>()
        val resourceStages = mutableMapOf<String, String>()
        projectStages.values.forEach { stage ->
            stage.requiredResources.forEach { (orderId, quantity) ->
                require(resourceStages.put(orderId, stage.id) == null) {
                    "Season resource order '$orderId' must belong to exactly one project stage"
                }
                quantities[orderId] = Math.addExact(quantities[orderId] ?: 0L, quantity)
            }
        }
        val targets = resourceOrders.associate { it.id to it.targetQuantity }
        require(quantities == targets) {
            "Season project resource requirements must exactly consume configured resource-order targets"
        }
        require(
            resourceOrders.all { order -> order.windowStartsAt >= startsAt && order.windowEndsAt <= endsAt },
        ) {
            "Season resource-order windows must remain inside the season window"
        }
        return this
    }

    fun isOpenAt(now: Long): Boolean = now in startsAt until endsAt

    fun revisionDigest(): String {
        val canonical = StringBuilder()
        fun field(value: Any) {
            val text = value.toString()
            canonical.append(text.length).append(':').append(text)
        }
        field(schemaVersion)
        field(id)
        field(title)
        field(completionStage)
        field(durationDays)
        field(startsAt)
        field(endsAt)
        dungeonContracts.toSortedMap().forEach { (dungeonId, dungeon) ->
            field(dungeonId)
            field(dungeon.displayName)
            field(dungeon.world)
            field(dungeon.requiresProjectStage)
            field(dungeon.expectedActiveMinutes)
            field(dungeon.rewardCooldownMinutes)
            field(dungeon.payoutMinorPerPlayer)
            field(dungeon.entryBurnMinorPerPlayer)
            field(dungeon.minimumActiveShare)
            field(dungeon.weeklyQualifyingPlayerCap)
            field(dungeon.plannedBoundReward)
        }
        field(projectId)
        field(projectTitle)
        projectStages.toSortedMap().forEach { (stageId, stage) ->
            field(stageId)
            field(stage.displayName)
            stage.requiresProjectStages.sorted().forEach(::field)
            field(stage.cashContributionMinor)
            stage.requiredResources.toSortedMap().forEach { (key, value) ->
                field(key)
                field(value)
            }
            stage.requiredBoundRewards.toSortedMap().forEach { (key, value) ->
                field(key)
                field(value)
            }
            stage.unlocksDungeonContracts.sorted().forEach(::field)
            field(stage.unlock)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

open class ContractsConfig(
    private val config: Config,
) {
    open val enabled: Boolean get() = config.bool("enabled", false)

    open val mode: ContractsMode get() = strictEnum("mode", ContractsMode.DISABLED)

    open val leaderServer: String get() = config.string("leader-server", "spawn").trim().lowercase()

    /** The Citizens NPC that authorizes the public guild resource-contract desk. */
    open val submissionNpcId: Int get() = config.integer("submission-access.npc-id", 390)

    open val submissionNpcGroup: String
        get() = config.string("submission-access.group", "guild_orders").trim().lowercase()

    open val submissionNpcRadius: Double get() = config.double("submission-access.radius", 4.5)

    open val submissionNpcSessionTtl: Duration
        get() = config.duration("submission-access.session-ttl", Duration.ofSeconds(120))

    /**
     * Maps each public desk NPC to the only contract group its click can open.
     * The scalar fields remain readable for older deployed configs; a config
     * with neither form receives the canonical three-desk defaults.
     */
    open val submissionNpcRoutes: Map<Int, String>
        get() {
            val routeKeys = config.keys("submission-access.routes")
            if (routeKeys.isNotEmpty()) {
                return routeKeys.associate { rawNpcId ->
                    val npcId = rawNpcId.toIntOrNull()
                        ?: throw IllegalArgumentException("Contracts submission NPC id must be an integer: $rawNpcId")
                    npcId to config.string("submission-access.routes.$rawNpcId", "").trim().lowercase()
                }
            }
            if (config.exists("submission-access.npc-id") || config.exists("submission-access.group")) {
                // Keep an older scalar desk usable while adding the canonical
                // forge/bank/guild desks to an existing data directory.
                return DEFAULT_SUBMISSION_NPC_ROUTES + (submissionNpcId to submissionNpcGroup)
            }
            return DEFAULT_SUBMISSION_NPC_ROUTES
        }

    open val serverWeeklyBudgetMinor: Long
        get() = moneyMinor(config.string("server-weekly-budget", "0"), "server-weekly-budget", allowZero = true)

    open val selectionPolicy: ContractSelectionPolicy
        get() = ContractSelectionPolicy(
            enabled = config.bool("selection.enabled", false),
            startsAt = if (config.bool("selection.enabled", false))
                instant(config.string("selection.starts-at", ""), "selection.starts-at") else 0,
            perGroup = config.integer("selection.orders-per-group", 3),
            perGroupOverrides = config.keys("selection.group-order-counts").associateWith {
                config.integer("selection.group-order-counts.$it", 3)
            },
        )

    open fun validated(allowSeasonMutations: Boolean = false): ContractsConfig {
        enabled
        mode
        require(SERVER_ID_PATTERN.matches(leaderServer)) { "Invalid contracts leader-server: $leaderServer" }
        require(submissionNpcRoutes.isNotEmpty()) { "Contracts submission NPC routes must not be empty" }
        submissionNpcRoutes.forEach { (npcId, group) ->
            require(npcId > 0) { "Contracts submission NPC id must be positive" }
            require(ID_PATTERN.matches(group)) { "Contracts submission NPC group must be a normalized id" }
        }
        require(submissionNpcRoutes.values.size == submissionNpcRoutes.values.toSet().size) {
            "Contracts submission NPC routes must map each group to one NPC"
        }
        require(submissionNpcRadius.isFinite() && submissionNpcRadius in 1.0..16.0) {
            "Contracts submission NPC radius must be 1..16 blocks"
        }
        require(submissionNpcSessionTtl in Duration.ofSeconds(1)..Duration.ofMinutes(10)) {
            "Contracts submission NPC session TTL must be 1s..10m"
        }
        serverWeeklyBudgetMinor
        val orders = resourceOrders()
        if (selectionPolicy.enabled) {
            require(orders.all { it.weeklyRecurring }) { "Automatic selection requires weekly recurring candidates" }
            require(!config.exists("season-catalog")) { "Season-linked resource orders cannot use automatic selection" }
        }
        observeSeasonCatalog(allowSeasonMutations)?.validatedResourceLinks(orders)
        return this
    }

    open fun resourceOrders(): List<ResourceContractDefinition> {
        val orderIds = config.keys("orders").sorted()
        require(orderIds.size <= MAX_CONFIGURED_ORDERS) { "At most $MAX_CONFIGURED_ORDERS contracts may be configured" }
        val definitions =
            orderIds.mapNotNull { id ->
                val normalizedId = id.trim().lowercase()
                require(normalizedId == id) {
                    "Contract id '$id' must already be normalized lowercase ASCII"
                }
                val root = "orders.$id"
                if (!config.bool("$root.enabled", false)) return@mapNotNull null
                val kind = strictEnum("$root.kind", ContractKind.RESOURCE)
                require(kind == ContractKind.RESOURCE) {
                    "Contract '$id' uses unsupported runtime kind '${kind.label}'"
                }
                val startsAt = instant(config.string("$root.window-starts-at", ""), "$root.window-starts-at")
                val endsAt = instant(config.string("$root.window-ends-at", ""), "$root.window-ends-at")
                ResourceContractDefinition(
                    id = normalizedId,
                    displayName = config.string("$root.display-name", id).trim(),
                    itemKey = ResourceContractDefinition.normalizeItemKey(config.string("$root.item", "")),
                    funding = strictEnum("$root.funding", ContractFunding.SERVER_ENVELOPE),
                    windowStartsAt = startsAt,
                    windowEndsAt = endsAt,
                    payoutMinorPerUnit = moneyMinor(config.string("$root.payout-per-unit", ""), "$root.payout-per-unit"),
                    budgetMinor = moneyMinor(config.string("$root.budget", ""), "$root.budget"),
                    targetQuantity = config.long("$root.target-quantity", 0L),
                    perPlayerQuantityCap = config.long("$root.per-player-quantity-cap", 0L),
                    minSubmissionQuantity = config.integer("$root.min-submission-quantity", 1),
                    maxSubmissionQuantity = config.integer("$root.max-submission-quantity", 2_304),
                    dynamicPricing = config.bool("$root.dynamic-pricing", false),
                    weeklyRecurring = config.bool("$root.weekly-recurring", false),
                    kind = kind,
                    group =
                        normalizedId(
                            config.string("$root.group", ResourceContractDefinition.DEFAULT_GROUP),
                            "$root.group",
                        ),
                ).also { definition ->
                    if (definition.weeklyRecurring) ContractRotation.at(definition, definition.windowStartsAt)
                    require(definition.perPlayerQuantityCap <= definition.targetQuantity) {
                        "Contract '$id' per-player cap exceeds target quantity"
                    }
                    require(definition.funding == ContractFunding.SERVER_ENVELOPE) {
                        "Resource contract '$id' must use server_envelope until player escrow runtime is enabled"
                    }
                }
            }
        val concurrentBudget = maximumConcurrentBudget(definitions)
        require(concurrentBudget <= serverWeeklyBudgetMinor) {
            "Configured concurrent contract budgets $concurrentBudget exceed server weekly envelope $serverWeeklyBudgetMinor minor units"
        }
        return definitions
    }

    fun resourceOrdersAt(now: Long): List<ResourceContractDefinition> = resourceOrders().map { ContractRotation.at(it, now) }

    open fun observeSeasonCatalog(allowSeasonMutations: Boolean = false): ObserveSeasonCatalog? {
        if (!config.exists("season-catalog")) return null
        require(mode == ContractsMode.OBSERVE || (allowSeasonMutations && mode == ContractsMode.ENFORCE)) {
            "Season catalog is observe-only and cannot be loaded outside observe mode"
        }
        val root = "season-catalog"
        val schemaVersion = config.integer("$root.schema-version", 0)
        require(schemaVersion == SEASON_CATALOG_SCHEMA_VERSION) {
            "Season catalog schema-version must be $SEASON_CATALOG_SCHEMA_VERSION"
        }
        val seasonId = normalizedId(config.string("$root.id", ""), "$root.id")
        val completionStage = normalizedId(config.string("$root.completion-stage", ""), "$root.completion-stage")
        val durationDays = positiveInt("$root.duration-days")
        val startsAt = instant(config.string("$root.starts-at", ""), "$root.starts-at")
        val endsAt = instant(config.string("$root.ends-at", ""), "$root.ends-at")
        require(endsAt > startsAt) { "Season end must be after its start" }
        require(Math.subtractExact(endsAt, startsAt) == Math.multiplyExact(durationDays.toLong(), MILLIS_PER_DAY)) {
            "Season window must exactly match duration-days"
        }
        val dungeonIds = normalizedKeys("$root.dungeon-contracts", MAX_SEASON_DUNGEONS)
        val dungeons =
            dungeonIds.associateWith { id ->
                val path = "$root.dungeon-contracts.$id"
                ObserveDungeonContractDefinition(
                    id = id,
                    displayName = printable(config.string("$path.display-name", ""), "$path.display-name"),
                    world = normalizedId(config.string("$path.world", ""), "$path.world"),
                    requiresProjectStage =
                        normalizedId(config.string("$path.requires-project-stage", ""), "$path.requires-project-stage"),
                    expectedActiveMinutes = positiveInt("$path.expected-active-minutes"),
                    rewardCooldownMinutes = positiveInt("$path.reward-cooldown-minutes"),
                    payoutMinorPerPlayer = moneyMinor(config.string("$path.payout-per-qualifying-player", ""), "$path.payout-per-qualifying-player"),
                    entryBurnMinorPerPlayer = moneyMinor(config.string("$path.entry-burn-per-player", ""), "$path.entry-burn-per-player"),
                    minimumActiveShare = strictShare("$path.minimum-active-share"),
                    weeklyQualifyingPlayerCap = positiveInt("$path.weekly-qualifying-player-cap"),
                    plannedBoundReward = namespacedKey(config.string("$path.planned-bound-reward", ""), "$path.planned-bound-reward"),
                )
            }
        require(dungeons.values.map { it.world }.toSet().size == dungeons.size) {
            "Season dungeon worlds must be unique"
        }
        require(dungeons.values.map { it.plannedBoundReward }.toSet().size == dungeons.size) {
            "Season dungeon bound rewards must be unique"
        }

        val projectRoot = "$root.public-project"
        val stageIds = normalizedKeys("$projectRoot.stages", MAX_PROJECT_STAGES)
        val stages =
            stageIds.associateWith { id ->
                val path = "$projectRoot.stages.$id"
                ObserveProjectStageDefinition(
                    id = id,
                    displayName = printable(config.string("$path.display-name", ""), "$path.display-name"),
                    requiresProjectStages = normalizedList("$path.requires-project-stages"),
                    cashContributionMinor =
                        moneyMinor(config.string("$path.cash-contribution", ""), "$path.cash-contribution", allowZero = true),
                    requiredResources = positiveQuantityMap("$path.required-resources"),
                    requiredBoundRewards = positiveQuantityMap("$path.required-bound-rewards", namespacedKeys = true),
                    unlocksDungeonContracts = normalizedList("$path.unlocks-dungeon-contracts"),
                    unlock = normalizedId(config.string("$path.unlock", ""), "$path.unlock"),
                ).also { stage ->
                    require(id !in stage.requiresProjectStages) {
                        "Season project stage '$id' cannot require itself"
                    }
                    require(
                        stage.cashContributionMinor > 0L ||
                            stage.requiredResources.isNotEmpty() ||
                            stage.requiredBoundRewards.isNotEmpty(),
                    ) {
                        "Season project stage '$id' must require at least one contribution"
                    }
                }
            }
        require(completionStage in stages) { "Season completion stage '$completionStage' is missing" }
        require(stageIds.lastOrNull() == completionStage) { "Season completion stage must be declared last" }
        val dungeonUnlocks = mutableMapOf<String, String>()
        stages.values.forEach { stage ->
            require(stage.requiresProjectStages.all(stages::containsKey)) {
                "Season project stage '${stage.id}' references an unknown prerequisite"
            }
            stage.unlocksDungeonContracts.forEach { dungeonId ->
                require(dungeonId in dungeons) { "Season project stage '${stage.id}' unlocks unknown dungeon '$dungeonId'" }
                require(dungeonUnlocks.put(dungeonId, stage.id) == null) {
                    "Season dungeon '$dungeonId' is unlocked by more than one stage"
                }
            }
        }
        require(dungeonUnlocks.keys == dungeons.keys) { "Every season dungeon must have exactly one project-stage unlock" }
        dungeons.values.forEach { dungeon ->
            require(dungeonUnlocks[dungeon.id] == dungeon.requiresProjectStage) {
                "Season dungeon '${dungeon.id}' unlock does not match its required project stage"
            }
        }

        val rewardsByDungeon = dungeons.values.associate { it.plannedBoundReward to it.id }
        val reachable = mutableSetOf<String>()
        do {
            val next =
                stages.values.filter { stage ->
                    stage.id !in reachable &&
                        stage.requiresProjectStages.all(reachable::contains) &&
                        stage.requiredBoundRewards.keys.all { reward ->
                            dungeons[rewardsByDungeon[reward]]?.requiresProjectStage in reachable
                        }
                }
            reachable += next.map { it.id }
        } while (next.isNotEmpty())
        require(reachable == stages.keys) { "Season project progression is cyclic or reward-locked" }
        val completion = stages.getValue(completionStage)
        require(completion.requiredBoundRewards.keys == rewardsByDungeon.keys) {
            "Season completion stage must consume every planned dungeon reward"
        }
        val rewardConsumptionCounts =
            stages.values
                .flatMap { it.requiredBoundRewards.keys }
                .groupingBy { it }
                .eachCount()
        require(rewardConsumptionCounts == rewardsByDungeon.keys.associateWith { 1 }) {
            "Every planned dungeon reward must be consumed by exactly one project stage"
        }
        val prerequisiteClosure = mutableSetOf<String>()
        val pending = ArrayDeque<String>().apply { addAll(completion.requiresProjectStages) }
        while (pending.isNotEmpty()) {
            val stageId = pending.removeFirst()
            if (!prerequisiteClosure.add(stageId)) continue
            pending.addAll(stages.getValue(stageId).requiresProjectStages)
        }
        require(prerequisiteClosure == stages.keys - completionStage) {
            "Season completion stage must depend on every earlier project stage"
        }

        return ObserveSeasonCatalog(
            schemaVersion = schemaVersion,
            id = seasonId,
            title = printable(config.string("$root.title", ""), "$root.title"),
            completionStage = completionStage,
            durationDays = durationDays,
            startsAt = startsAt,
            endsAt = endsAt,
            dungeonContracts = dungeons,
            projectId = normalizedId(config.string("$projectRoot.id", ""), "$projectRoot.id"),
            projectTitle = printable(config.string("$projectRoot.title", ""), "$projectRoot.title"),
            projectStages = stages,
        )
    }

    private inline fun <reified E : Enum<E>> strictEnum(path: String, default: E): E {
        val raw = config.stringOrNull(path)?.trim() ?: return default
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Contract enum '$path' must be one of ${enumValues<E>().joinToString { it.name.lowercase() }}",
            )
    }

    private fun normalizedKeys(path: String, maximum: Int): List<String> {
        val keys = config.keys(path).toList()
        require(keys.isNotEmpty() && keys.size <= maximum) { "$path must contain 1..$maximum entries" }
        keys.forEach { require(normalizedId(it, path) == it) { "$path keys must already be normalized" } }
        return keys
    }

    private fun normalizedList(path: String): Set<String> {
        val values = config.stringListOrNull(path) ?: throw IllegalArgumentException("$path must be an explicit list")
        val normalized = values.map { normalizedId(it, path) }
        require(normalized.size == normalized.toSet().size) { "$path must not contain duplicates" }
        return normalized.toSet()
    }

    private fun positiveQuantityMap(path: String, namespacedKeys: Boolean = false): Map<String, Long> =
        config.keys(path).associateWith { key ->
            val normalized = if (namespacedKeys) namespacedKey(key, path) else normalizedId(key, path)
            require(normalized == key) { "$path keys must already be normalized" }
            positiveLong("$path.$key")
        }

    private fun positiveInt(path: String): Int {
        val value = positiveLong(path)
        require(value <= Int.MAX_VALUE) { "$path exceeds the supported integer range" }
        return value.toInt()
    }

    private fun positiveLong(path: String): Long {
        val raw = config.stringOrNull(path) ?: throw IllegalArgumentException("$path must be present")
        val value = raw.toLongOrNull() ?: throw IllegalArgumentException("$path must be an integer")
        require(value > 0L) { "$path must be positive" }
        return value
    }

    private fun maximumConcurrentBudget(definitions: List<ResourceContractDefinition>): Long =
        definitions.map { it.windowStartsAt }.maxOfOrNull { instant ->
            definitions.filter { instant >= it.windowStartsAt && instant < it.windowEndsAt }
                .fold(0L) { total, definition -> Math.addExact(total, definition.budgetMinor) }
        } ?: 0L

    private fun strictShare(path: String): Double {
        val raw = config.stringOrNull(path) ?: throw IllegalArgumentException("$path must be present")
        val value = raw.toDoubleOrNull() ?: throw IllegalArgumentException("$path must be numeric")
        require(value.isFinite() && value > 0.0 && value <= 1.0) { "$path must be in (0, 1]" }
        return value
    }

    private fun normalizedId(raw: String, path: String): String {
        val normalized = raw.trim().lowercase()
        require(raw.trim() == normalized && ID_PATTERN.matches(normalized)) { "$path must be a normalized id" }
        return normalized
    }

    private fun namespacedKey(raw: String, path: String): String {
        val normalized = raw.trim().lowercase()
        require(raw.trim() == normalized && NAMESPACED_KEY_PATTERN.matches(normalized)) {
            "$path must be a normalized namespaced key"
        }
        return normalized
    }

    private fun printable(raw: String, path: String): String {
        val value = raw.trim()
        require(value.isNotBlank() && value.length <= 96 && value.none(Char::isISOControl)) {
            "$path must be 1..96 printable characters"
        }
        return value
    }

    companion object {
        /** Public Origin NPCs that own the three resource-contract desks. */
        val DEFAULT_SUBMISSION_NPC_ROUTES: Map<Int, String> = linkedMapOf(
            350 to "forge_orders",
            367 to "bank_orders",
            390 to "guild_orders",
            439 to "food_orders",
        )

        const val MAX_CONFIGURED_ORDERS = 64
        const val MAX_SEASON_DUNGEONS = 16
        const val MAX_PROJECT_STAGES = 16
        const val SEASON_CATALOG_SCHEMA_VERSION = 4
        private const val MILLIS_PER_DAY = 86_400_000L
        private val SERVER_ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{2,31}")
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{2,63}")
        private val NAMESPACED_KEY_PATTERN = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

        fun fromFile(dataPath: Path): ContractsConfig =
            ContractsConfig(ConfigManager.of(dataPath, "modules/contracts.yml"))

        fun load(): ContractsConfig = fromFile(ContractsRuntime.legacyDataPath)

        internal fun moneyMinor(raw: String, path: String, allowZero: Boolean = false): Long {
            val value =
                runCatching { BigDecimal(raw.trim()).setScale(2, RoundingMode.UNNECESSARY) }
                    .getOrElse { throw IllegalArgumentException("Contract money '$path' must have at most two decimals") }
            val minor =
                runCatching { value.movePointRight(2).longValueExact() }
                    .getOrElse { throw IllegalArgumentException("Contract money '$path' is outside the supported range") }
            require(if (allowZero) minor >= 0L else minor > 0L) {
                "Contract money '$path' must be ${if (allowZero) "non-negative" else "positive"}"
            }
            return minor
        }

        private fun instant(raw: String, path: String): Long =
            runCatching { Instant.parse(raw.trim()).toEpochMilli() }
                .getOrElse { throw IllegalArgumentException("Contract timestamp '$path' must be an ISO-8601 instant") }
    }
}

package ru.arc.contracts

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.config.ConfigManager
import ru.arc.contracts.api.ArcContractsHostConfiguration
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import ru.arc.paper.player.PaperOfflinePlayerData
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.menu.PaperDialogScreen
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CompletableFuture

class NpcContractDepositGuiTest {
    private lateinit var paper: MockBukkitTestRuntime
    private lateinit var plugin: JavaPlugin
    private lateinit var dataRoot: Path

    private val server get() = paper.server

    @BeforeEach
    fun initializeContractMenus() {
        ConfigManager.clear()
        paper = MockBukkitTestRuntime.open()
        plugin = paper.createSimplePlugin("NpcContractDepositGuiTest")
        PaperArcRuntime.installScheduling(plugin)
        dataRoot = Files.createTempDirectory("arc-contracts-npc-menus")
        copyResource("guis/menus.yml", dataRoot.resolve("guis/menus.yml"))
        copyResource("guis/contracts.yml", dataRoot.resolve("guis/contracts.yml"))
        ContractsRuntime.install(plugin, TestHostConfiguration(dataRoot), null, null, ru.arc.config.ConfigManager.empty())
        ContractsMenus.initialize(plugin, dataRoot)
        mockkObject(ContractWeeklyLeaderboardGui)
        every { ContractWeeklyLeaderboardGui.load(any(), any()) } returns CompletableFuture.completedFuture(
            ContractWeeklyLeaderboardGui.Loaded(ContractWeeklyLeaderboard(
                ContractRotation.weekStart(System.currentTimeMillis()), emptyList(), emptyMap()), 0, emptyMap()),
        )
    }

    @AfterEach
    fun closeContractMenus() {
        NpcContractDepositGui.shutdown()
        unmockkObject(ContractWeeklyLeaderboardGui)
        ContractsMenus.close()
        ContractsRuntime.close()
        Tasks.reset()
        ConfigManager.clear()
        if (::paper.isInitialized) paper.close()
        if (::dataRoot.isInitialized) {
            Files.walk(dataRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun copyResource(name: String, destination: Path) {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream(name))
        Files.createDirectories(destination.parent)
        resource.use { input -> Files.newOutputStream(destination).use { output -> input.copyTo(output) } }
    }

    private class TestHostConfiguration(private val dataRoot: Path) : ArcContractsHostConfiguration {
        override fun ready(): java.util.concurrent.CompletableFuture<Void> = java.util.concurrent.CompletableFuture.completedFuture(null)
        override fun legacyDataPath(): Path = dataRoot
        override fun serverName(): String = "test-server"
        override fun sharedRedisAvailable(): Boolean = false
        override fun sharedRedisGeneration(): Long = 0L
        override fun commandText(key: String, fallback: String): Component = Component.text(fallback)
    }

    @Test
    fun `completed selected orders stay as barriers with their own weekly leaders and cannot be submitted`() {
        val player = server.addPlayer("Visitor")
        val orderLeader = server.addPlayer("StoneLeader")
        val globalLeader = server.addPlayer("GlobalLeader")
        val base = foodOrder(playerRemaining = 0)
        val completed = base.copy(contract = base.contract.copy(status = ContractStatus.COMPLETED.label,
            acceptedQuantity = 1536, remainingQuantity = 0), playerAcceptedQuantity = 10)
        every { ContractWeeklyLeaderboardGui.load(any(), any()) } returns CompletableFuture.completedFuture(
            ContractWeeklyLeaderboardGui.Loaded(ContractWeeklyLeaderboard(
                ContractRotation.weekStart(System.currentTimeMillis()),
                listOf(ContractWeeklyLeaderboard.Entry(globalLeader.uniqueId.toString(), 9000)),
                mapOf(completed.contract.id to listOf(ContractWeeklyLeaderboard.Entry(orderLeader.uniqueId.toString(), 1500))),
            ), 0, emptyMap()),
        )
        mockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
        mockkStatic(ContractsManager::class)
        var submissionCalls = 0
        try {
            every { ContractOriginGate.canSubmit(player, "food_orders") } returns true
            every { NativePaperPlayerDataPersistence.persist(player) } returns Unit
            every { ContractsManager.currentPlayerViews(player.uniqueId, "food_orders", any(), any()) } returns listOf(completed)
            every { ContractsManager.quote(player, any(), any()) } returns null
            every { ContractsManager.submit(player, any(), null, any()) } answers {
                submissionCalls++
                CompletableFuture.completedFuture(ContractSubmissionOutcome.Unavailable("unexpected"))
            }
            NpcContractDepositGui.start(offlinePlayers = PaperOfflinePlayerData(dataRoot))
            NpcContractDepositGui.open(player, "food_orders")
            server.scheduler.performTicks(2)
            val view = player.openInventory
            val item = view.topInventory.getItem(0)!!
            item.type shouldBe Material.BARRIER
            item.plainDisplayName() shouldBe "Коричневые грибы"
            val lore = item.plainLore().joinToString("\n")
            lore.contains("Сейчас недоступен") shouldBe true
            lore.contains("1536 / 1536") shouldBe true
            lore.contains("Вы сдали: 10") shouldBe true
            lore.contains("StoneLeader — 1500") shouldBe true
            lore.contains("GlobalLeader") shouldBe false
            view.topInventory.getItem(49)!!.plainDisplayName() shouldBe "Топ поставщиков недели"
            view.setCursor(ItemStack(Material.BROWN_MUSHROOM, 8))
            server.pluginManager.callEvent(InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                5, ClickType.LEFT, InventoryAction.PLACE_ALL))
            server.pluginManager.callEvent(InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                4, ClickType.LEFT, InventoryAction.PICKUP_ALL))
            server.scheduler.performTicks(2)
            submissionCalls shouldBe 0
            view.topInventory.getItem(5) shouldBe ItemStack(Material.BROWN_MUSHROOM, 8)
            exportContractPreview("completed-order", mapOf(
                "name" to previewText(item.itemMeta.displayName()!!),
                "lore" to item.itemMeta.lore()!!.map(::previewText), "material" to item.type.name,
            ))
            every { ContractWeeklyLeaderboardGui.load(any(), any()) } returns CompletableFuture.failedFuture(IllegalStateException("fixture refresh failure"))
            server.scheduler.performTicks(101)
            val unavailableLore = view.topInventory.getItem(0)!!.plainLore().joinToString("\n")
            unavailableLore.contains("Топ временно недоступен") shouldBe true
            unavailableLore.contains("StoneLeader") shouldBe false
            every { ContractWeeklyLeaderboardGui.load(any(), any()) } returns CompletableFuture.completedFuture(
                ContractWeeklyLeaderboardGui.Loaded(ContractWeeklyLeaderboard(
                    ContractRotation.weekStart(System.currentTimeMillis()), emptyList(), emptyMap()), 0, emptyMap()),
            )
            var leaderboard: PaperDialogScreen? = null
            mockkObject(ContractsMenus)
            every { ContractsMenus.openDialog(player, any(), any(), any()) } answers {
                leaderboard = secondArg()
            }
            server.pluginManager.callEvent(InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                49, ClickType.LEFT, InventoryAction.PICKUP_ALL))
            server.scheduler.performTicks(2)
            ContractDeskStorage(player, "food_orders", { false }).snapshot().all { it == null } shouldBe true
            player.inventory.contents.filterNotNull().filter { it.type == Material.BROWN_MUSHROOM }.sumOf { it.amount } shouldBe 8
            leaderboard!!.buttons.first().onClick.handle(mockk())
            player.openInventory.topInventory.size shouldBe 54
            player.openInventory.topInventory.getItem(0)!!.type shouldBe Material.BARRIER
            unmockkObject(ContractsMenus)
        } finally {
            NpcContractDepositGui.shutdown()
            unmockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
            unmockkStatic(ContractsManager::class)
            unmockkObject(ContractsMenus)
        }
    }

    @Test
    @Suppress("DEPRECATION") // Verify the existing integer custom-model-data contract used by the resource pack.
    fun `all NPC groups stage input then sell only offered items and keep the same chest open`() {
        val player = server.addPlayer()
        mockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
        every { NativePaperPlayerDataPersistence.persist(player) } returns Unit // The platform port is verified separately.
        mockkStatic(ContractsManager::class)
        try {
            server.scheduler.cancelTasks(plugin) // Isolate the desk from unrelated boot-time NPC tasks.
            NpcContractDepositGui.start(offlinePlayers = PaperOfflinePlayerData(dataRoot))
            for (group in listOf("food_orders", "forge_orders", "bank_orders", "guild_orders")) {
                val definition = ResourceContractDefinition("order_$group", "Треска", "minecraft:cod",
                    ContractFunding.SERVER_ENVELOPE, 1000, Long.MAX_VALUE, 100, 500_000, 1536, 1536,
                    maxSubmissionQuantity = 1536, group = group)
                val view = ResourceContractPlayerView(ResourceContractView(definition.id, definition.displayName,
                    definition.itemKey, "server-envelope", "open", definition.windowStartsAt, definition.windowEndsAt,
                    100, 500_000, 0, 0, 1536, 0, 0, 1536, 0, group), 1, 1536, 1536, 0, 0, 1536, 100,
                    10_000, 10_000, definition, 0, 1500)
                every { ContractOriginGate.canSubmit(player, group) } returns true
                every { ContractsManager.currentPlayerViews(player.uniqueId, group, any(), any()) } returns if (group == "food_orders") (1..23).map { index ->
                    view.copy(contract = view.contract.copy(id = "order_${group}_$index", displayName = "Треска $index"))
                } else listOf(view)
                every { ContractsManager.quote(player, any(), any()) } answers {
                    ContractSubmissionQuote(secondArg(), 1000, player.uniqueId.toString(), thirdArg(),
                        thirdArg<Int>() * 100L, 0, 1500)
                }
                var saleCalls = 0
                every { ContractsManager.submit(player, any(), null, any()) } answers {
                    saleCalls++
                    val quote = secondArg<ContractSubmissionQuote>()
                    val prepared = arg<PreparedContractInventory>(3)
                    runBlocking { prepared.removeExact() } shouldBe ContractInventoryMutation.Confirmed
                    CompletableFuture.completedFuture(ContractSubmissionOutcome.Committed(
                        ContractSubmissionReceipt("test-$saleCalls", player.uniqueId.toString(), quote.quantity.toLong(), quote.payoutMinor, 1600)))
                }
                player.inventory.clear()
                player.inventory.setItem(0, ItemStack(Material.COD, 64)) // Unoffered stock must remain.
                NpcContractDepositGui.open(player, group)
                server.scheduler.performTicks(1) // Complete the independent, read-only leaderboard refresh.
                val inventoryView = player.openInventory
                val top = inventoryView.topInventory
                top.size shouldBe 54
                PlainTextComponentSerializer.plainText().serialize(inventoryView.title()) shouldBe when (group) {
                    "food_orders" -> "Матео · продукты для бара"
                    "forge_orders" -> "Заказы кузницы"
                    "bank_orders" -> "Закупки палаты сделок"
                    else -> "Снабжение гильдии"
                }
                val layout = ContractsMenus.current().catalog.require(ContractsMenuSchema.CONTRACT_DESKS.getValue(6))
                val deposits = layout.region(ContractsMenuSchema.CONTRACT_DEPOSIT).map { it.index }
                deposits shouldBe (0 until 6).flatMap { row -> (5..8).map { row * 9 + it } }
                deposits.forEachIndexed { index, slot ->
                    val offered = ItemStack(Material.COD, index + 1)
                    inventoryView.setCursor(offered)
                    server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                        slot, ClickType.LEFT, InventoryAction.PLACE_ALL))
                    top.getItem(slot) shouldBe offered
                    inventoryView.cursor.type.isAir shouldBe true
                }
                ContractDeskStorage(player, group, { false }).snapshot().map { it?.amount } shouldBe (1..24).toList()
                saleCalls shouldBe 0
                fun assertArrows() {
                    top.getItem(45)!!.itemMeta.customModelData shouldBe 11009
                    top.getItem(48)!!.itemMeta.customModelData shouldBe 11008
                }
                fun clickPage(slot: Int) {
                    server.pluginManager.callEvent(InventoryClickEvent(inventoryView,
                        InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                    server.scheduler.performTicks(2) // Core dispatches the click, then the top completes on its own tick.
                }
                assertArrows()
                val firstOrder = top.getItem(0)!!.clone()
                clickPage(45)
                top.getItem(0) shouldBe firstOrder
                assertArrows()
                if (group == "food_orders") {
                    for ((slot, index) in listOf(46 to 21, 47 to 22)) {
                        top.getItem(slot)!!.type shouldBe Material.COD
                        PlainTextComponentSerializer.plainText().serialize(top.getItem(slot)!!.itemMeta.displayName()!!)
                            .contains("Треска $index") shouldBe true
                    }
                    clickPage(48)
                    PlainTextComponentSerializer.plainText().serialize(top.getItem(0)!!.itemMeta.displayName()!!)
                        .contains("Треска 23") shouldBe true
                    top.getItem(19)!!.type shouldBe Material.LIGHT_GRAY_STAINED_GLASS_PANE
                    assertArrows()
                    ContractDeskStorage(player, group, { false }).snapshot().map { it?.amount } shouldBe (1..24).toList()
                    saleCalls shouldBe 0
                }
                val lastPageOrder = top.getItem(0)!!.clone()
                clickPage(48)
                top.getItem(0) shouldBe lastPageOrder
                assertArrows()
                ContractDeskStorage(player, group, { false }).snapshot().map { it?.amount } shouldBe (1..24).toList()
                saleCalls shouldBe 0
                top.getItem(46)!!.type shouldBe Material.LIGHT_GRAY_STAINED_GLASS_PANE
                top.getItem(46)!!.itemMeta.hasCustomModelData() shouldBe false
                fun sell() = server.pluginManager.callEvent(InventoryClickEvent(inventoryView,
                    InventoryType.SlotType.CONTAINER, layout.slot("sell").index, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                sell()
                server.scheduler.performTicks(5)
                saleCalls shouldBe 1
                deposits.forEach { slot -> top.getItem(slot) shouldBe null }
                player.inventory.getItem(0) shouldBe ItemStack(Material.COD, 64)
                (player.openInventory.topInventory === top) shouldBe true
                val soldButton = top.getItem(layout.slot("sell").index)!!
                soldButton.assertDeskTooltip()
                val soldTitle = soldButton.plainDisplayName()
                soldTitle.contains("Сдано") shouldBe true
                soldTitle.contains("+300") shouldBe true
                soldButton.itemMeta.lore().orEmpty().any { line ->
                    line.containsTranslation(Material.COD.translationKey()) &&
                        PlainTextComponentSerializer.plainText().serialize(line).contains("300")
                } shouldBe true
                val completedName = soldButton.itemMeta.displayName()
                val completedLore = soldButton.itemMeta.lore()
                server.scheduler.performTicks(100)
                top.getItem(layout.slot("sell").index)!!.itemMeta.displayName() shouldBe completedName
                top.getItem(layout.slot("sell").index)!!.itemMeta.lore() shouldBe completedLore
                sell()
                server.scheduler.performTicks(5)
                saleCalls shouldBe 1
                player.closeInventory()
            }
        } finally {
            NpcContractDepositGui.shutdown()
            unmockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
            unmockkStatic(ContractsManager::class)
            ContractsMenus.close()
        }
    }

    @Test
    fun `staged mushrooms show the exhausted player limit without submitting`() {
        val player = server.addPlayer()
        var submitCalls = 0
        mockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
        every { NativePaperPlayerDataPersistence.persist(player) } returns Unit
        mockkStatic(ContractsManager::class)
        try {
            server.scheduler.cancelTasks(plugin)
            NpcContractDepositGui.start(offlinePlayers = PaperOfflinePlayerData(dataRoot))
            every { ContractOriginGate.canSubmit(player, "food_orders") } returns true
            every { ContractsManager.currentPlayerViews(player.uniqueId, "food_orders", any(), any()) } answers {
                listOf(foodOrder(playerRemaining = 0))
            }
            every { ContractsManager.quote(player, any(), any()) } returns null
            every { ContractsManager.submit(player, any(), null, any()) } answers {
                submitCalls++
                CompletableFuture.completedFuture(ContractSubmissionOutcome.Unavailable("unexpected"))
            }
            player.inventory.setItem(0, ItemStack(Material.BROWN_MUSHROOM, 64))
            NpcContractDepositGui.open(player, "food_orders")
            val inventoryView = player.openInventory
            val top = inventoryView.topInventory
            val layout = ContractsMenus.current().catalog.require(ContractsMenuSchema.CONTRACT_DESKS.getValue(6))
            val depositSlot = layout.region(ContractsMenuSchema.CONTRACT_DEPOSIT).first().index

            inventoryView.setCursor(ItemStack(Material.BROWN_MUSHROOM, 16))
            server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                depositSlot, ClickType.LEFT, InventoryAction.PLACE_ALL))
            server.scheduler.performTicks(1)
            top.getItem(depositSlot) shouldBe ItemStack(Material.BROWN_MUSHROOM, 16)

            val button = top.getItem(layout.slot("sell").index)!!
            button.assertDeskTooltip()
            button.plainDisplayName().contains("Лимит исчерпан") shouldBe true
            button.itemMeta.lore().orEmpty().any { line -> line.containsTranslation(Material.BROWN_MUSHROOM.translationKey()) } shouldBe true
            button.plainLore().any { it.contains("16") } shouldBe true
            button.plainLore().any { it.contains("Ваш лимит по заказу исчерпан") } shouldBe true

            server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                layout.slot("sell").index, ClickType.LEFT, InventoryAction.PICKUP_ALL))
            server.scheduler.performTicks(5)
            submitCalls shouldBe 0
            ContractDeskStorage(player, "food_orders", { false }).snapshot()[0] shouldBe ItemStack(Material.BROWN_MUSHROOM, 16)
            player.inventory.getItem(0) shouldBe ItemStack(Material.BROWN_MUSHROOM, 64)
            top.getItem(layout.slot("sell").index)!!.plainDisplayName().contains("Лимит исчерпан") shouldBe true
        } finally {
            NpcContractDepositGui.shutdown()
            unmockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
            unmockkStatic(ContractsManager::class)
            ContractsMenus.close()
        }
    }

    @Test
    fun `cursor and native save failures preserve staged goods and allow one successful retry`() {
        val player = server.addPlayer()
        val staged = ItemStack(Material.BROWN_MUSHROOM, 8)
        val untouchedStock = ItemStack(Material.DIAMOND, 3)
        var failPersistence = false
        var submitCalls = 0
        mockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
        every { NativePaperPlayerDataPersistence.persist(player) } answers {
            if (failPersistence) throw IllegalStateException("injected player-data save failure")
            Unit
        }
        mockkStatic(ContractsManager::class)
        try {
            server.scheduler.cancelTasks(plugin)
            NpcContractDepositGui.start(offlinePlayers = PaperOfflinePlayerData(dataRoot))
            every { ContractOriginGate.canSubmit(player, "food_orders") } returns true
            every { ContractsManager.currentPlayerViews(player.uniqueId, "food_orders", any(), any()) } returns
                listOf(foodOrder(playerRemaining = 10))
            every { ContractsManager.quote(player, any(), any()) } answers {
                val quantity = thirdArg<Int>()
                ContractSubmissionQuote(secondArg(), 1_000, player.uniqueId.toString(), quantity,
                    quantity * 100L, 0, 1_500)
            }
            every { ContractsManager.submit(player, any(), null, any()) } answers {
                submitCalls++
                val quote = secondArg<ContractSubmissionQuote>()
                val prepared = arg<PreparedContractInventory>(3)
                runBlocking { prepared.removeExact() } shouldBe ContractInventoryMutation.Confirmed
                CompletableFuture.completedFuture(ContractSubmissionOutcome.Committed(
                    ContractSubmissionReceipt("retry-sale", player.uniqueId.toString(), quote.quantity.toLong(),
                        quote.payoutMinor, 1_600),
                ))
            }

            player.inventory.clear()
            player.inventory.setItem(0, untouchedStock.clone())
            NpcContractDepositGui.open(player, "food_orders")
            val inventoryView = player.openInventory
            val top = inventoryView.topInventory
            val layout = ContractsMenus.current().catalog.require(ContractsMenuSchema.CONTRACT_DESKS.getValue(6))
            val depositSlot = layout.region(ContractsMenuSchema.CONTRACT_DEPOSIT).first().index
            val sellSlot = layout.slot("sell").index

            inventoryView.setCursor(staged.clone())
            server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                depositSlot, ClickType.LEFT, InventoryAction.PLACE_ALL))
            server.scheduler.performTicks(1)
            top.getItem(depositSlot) shouldBe staged
            inventoryView.cursor.type.isAir shouldBe true

            fun assertStagedAndUntouched() {
                val snapshot = ContractDeskStorage(player, "food_orders", { false }).snapshot()
                snapshot[0] shouldBe staged
                snapshot.drop(1).all { it == null } shouldBe true
                top.getItem(depositSlot) shouldBe staged
                player.inventory.getItem(0) shouldBe untouchedStock
            }

            fun clickSell() {
                server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                    sellSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                server.scheduler.performTicks(5)
            }

            inventoryView.setCursor(ItemStack(Material.STICK, 1))
            clickSell()
            submitCalls shouldBe 0
            inventoryView.cursor shouldBe ItemStack(Material.STICK, 1)
            PlainTextComponentSerializer.plainText().serialize(requireNotNull(player.nextActionBar()))
                .contains("курсора") shouldBe true
            assertStagedAndUntouched()
            top.getItem(sellSlot)!!.plainDisplayName().contains("Принимаем товары") shouldBe false

            inventoryView.setCursor(null)
            failPersistence = true
            clickSell()
            submitCalls shouldBe 0
            assertStagedAndUntouched()
            PlainTextComponentSerializer.plainText().serialize(requireNotNull(player.nextActionBar()))
                .contains("сохранить инвентарь") shouldBe true
            top.getItem(sellSlot)!!.plainDisplayName().contains("Не удалось сдать") shouldBe true

            failPersistence = false
            clickSell()
            submitCalls shouldBe 1
            ContractDeskStorage(player, "food_orders", { false }).snapshot().all { it == null } shouldBe true
            top.getItem(depositSlot) shouldBe null
            player.inventory.getItem(0) shouldBe untouchedStock
            (player.openInventory.topInventory === top) shouldBe true

            clickSell()
            submitCalls shouldBe 1
        } finally {
            NpcContractDepositGui.shutdown()
            unmockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
            unmockkStatic(ContractsManager::class)
            ContractsMenus.close()
        }
    }

    @Test
    fun `mixed ready and capped goods have grouped sell lore and one final action`() {
        val player = server.addPlayer()
        val orders = listOf(
            foodOrder(playerRemaining = 64, id = "berries-order", itemKey = "minecraft:glow_berries",
                displayName = "Светящиеся ягоды", playerCap = 64),
            foodOrder(playerRemaining = 0),
        )
        var submitCalls = 0
        mockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
        every { NativePaperPlayerDataPersistence.persist(player) } returns Unit
        mockkStatic(ContractsManager::class)
        try {
            server.scheduler.cancelTasks(plugin)
            NpcContractDepositGui.start(offlinePlayers = PaperOfflinePlayerData(dataRoot))
            every { ContractOriginGate.canSubmit(player, "food_orders") } returns true
            every { ContractsManager.currentPlayerViews(player.uniqueId, "food_orders", any(), any()) } returns orders
            every { ContractsManager.quote(player, any(), any()) } answers {
                val quantity = thirdArg<Int>()
                ContractSubmissionQuote(secondArg(), 1_000, player.uniqueId.toString(), quantity,
                    quantity * 100L, 0, 1_500)
            }
            every { ContractsManager.submit(player, any(), null, any()) } answers {
                submitCalls++
                CompletableFuture.completedFuture(ContractSubmissionOutcome.Unavailable("unexpected"))
            }
            NpcContractDepositGui.open(player, "food_orders")
            val inventoryView = player.openInventory
            val top = inventoryView.topInventory
            val layout = ContractsMenus.current().catalog.require(ContractsMenuSchema.CONTRACT_DESKS.getValue(6))
            val deposits = layout.region(ContractsMenuSchema.CONTRACT_DEPOSIT).map { it.index }
            val staged = listOf(
                deposits[0] to ItemStack(Material.GLOW_BERRIES, 64),
                deposits[1] to ItemStack(Material.BROWN_MUSHROOM, 64),
                deposits[2] to ItemStack(Material.BROWN_MUSHROOM, 64),
            )
            staged.forEach { (slot, item) ->
                inventoryView.setCursor(item)
                server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                    slot, ClickType.LEFT, InventoryAction.PLACE_ALL))
                server.scheduler.performTicks(1)
                top.getItem(slot) shouldBe item
            }

            val button = top.getItem(layout.slot("sell").index)!!
            button.assertDeskTooltip()
            button.plainDisplayName().contains("Продать доступные товары") shouldBe true
            val lore = button.itemMeta.lore().orEmpty()
            val plain = lore.map(PlainTextComponentSerializer.plainText()::serialize)
            plain.takeWhile { it.isBlank() }.size shouldBe 1
            plain.count { it.isBlank() } shouldBe 3
            val berriesIndex = lore.indices.single { lore[it].containsTranslation(Material.GLOW_BERRIES.translationKey()) }
            val mushroomIndex = lore.indices.single { lore[it].containsTranslation(Material.BROWN_MUSHROOM.translationKey()) }
            berriesIndex shouldBe 1
            plain[berriesIndex].startsWith("•") shouldBe true
            plain[berriesIndex].contains("64") shouldBe true
            plain[berriesIndex + 1].contains("Можно сдать сейчас") shouldBe true
            plain[berriesIndex + 2].isBlank() shouldBe true
            mushroomIndex shouldBe berriesIndex + 3
            plain[mushroomIndex].startsWith("•") shouldBe true
            plain[mushroomIndex].contains("128") shouldBe true
            plain[mushroomIndex + 1].contains("Ваш лимит по заказу исчерпан") shouldBe true
            plain[mushroomIndex + 2].isBlank() shouldBe true
            plain.last().startsWith("[▶] ЛКМ — сдать доступные товары") shouldBe true
            plain.none { it.startsWith("Страница ") } shouldBe true
            submitCalls shouldBe 0
        } finally {
            NpcContractDepositGui.shutdown()
            unmockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
            unmockkStatic(ContractsManager::class)
            ContractsMenus.close()
        }
    }

    @Test
    fun `partial mushroom sale reports accepted payout and capped remainder then resets on new input`() {
        val player = server.addPlayer()
        var playerRemaining = 10L
        var submitCalls = 0
        mockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
        every { NativePaperPlayerDataPersistence.persist(player) } returns Unit
        mockkStatic(ContractsManager::class)
        try {
            server.scheduler.cancelTasks(plugin)
            NpcContractDepositGui.start(offlinePlayers = PaperOfflinePlayerData(dataRoot))
            every { ContractOriginGate.canSubmit(player, "food_orders") } returns true
            every { ContractsManager.currentPlayerViews(player.uniqueId, "food_orders", any(), any()) } answers {
                listOf(foodOrder(playerRemaining = playerRemaining))
            }
            every { ContractsManager.quote(player, any(), any()) } answers {
                val quantity = minOf(thirdArg<Int>().toLong(), playerRemaining).toInt()
                if (quantity == 0) null else ContractSubmissionQuote(
                    secondArg(), 1_000, player.uniqueId.toString(), quantity, quantity * 100L, 0, 1_500,
                )
            }
            every { ContractsManager.submit(player, any(), null, any()) } answers {
                submitCalls++
                val quote = secondArg<ContractSubmissionQuote>()
                val prepared = arg<PreparedContractInventory>(3)
                runBlocking { prepared.removeExact() } shouldBe ContractInventoryMutation.Confirmed
                playerRemaining -= quote.quantity
                CompletableFuture.completedFuture(ContractSubmissionOutcome.Committed(
                    ContractSubmissionReceipt("mushroom-sale", player.uniqueId.toString(), quote.quantity.toLong(),
                        quote.payoutMinor, 1_600),
                ))
            }
            player.inventory.setItem(0, ItemStack(Material.BROWN_MUSHROOM, 64))
            NpcContractDepositGui.open(player, "food_orders")
            val inventoryView = player.openInventory
            val top = inventoryView.topInventory
            val layout = ContractsMenus.current().catalog.require(ContractsMenuSchema.CONTRACT_DESKS.getValue(6))
            val deposits = layout.region(ContractsMenuSchema.CONTRACT_DEPOSIT).map { it.index }
            inventoryView.setCursor(ItemStack(Material.BROWN_MUSHROOM, 16))
            server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                deposits[0], ClickType.LEFT, InventoryAction.PLACE_ALL))
            server.scheduler.performTicks(1)
            top.getItem(deposits[0]) shouldBe ItemStack(Material.BROWN_MUSHROOM, 16)

            server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                layout.slot("sell").index, ClickType.LEFT, InventoryAction.PICKUP_ALL))
            server.scheduler.performTicks(5)
            submitCalls shouldBe 1
            val button = top.getItem(layout.slot("sell").index)!!
            button.assertDeskTooltip()
            button.plainDisplayName().contains("Сдано частично") shouldBe true
            button.plainDisplayName().contains("+10") shouldBe true
            button.itemMeta.lore().orEmpty().any { line ->
                line.containsTranslation(Material.BROWN_MUSHROOM.translationKey()) &&
                    PlainTextComponentSerializer.plainText().serialize(line).contains("10")
            } shouldBe true
            button.itemMeta.lore().orEmpty().any { line ->
                line.containsTranslation(Material.BROWN_MUSHROOM.translationKey()) &&
                    PlainTextComponentSerializer.plainText().serialize(line).contains("6")
            } shouldBe true
            button.plainLore().any { it.contains("Ваш лимит по заказу исчерпан") } shouldBe true
            ContractDeskStorage(player, "food_orders", { false }).snapshot()[0] shouldBe ItemStack(Material.BROWN_MUSHROOM, 6)
            player.inventory.getItem(0) shouldBe ItemStack(Material.BROWN_MUSHROOM, 64)

            inventoryView.setCursor(ItemStack(Material.BROWN_MUSHROOM, 2))
            server.pluginManager.callEvent(InventoryClickEvent(inventoryView, InventoryType.SlotType.CONTAINER,
                deposits[1], ClickType.LEFT, InventoryAction.PLACE_ALL))
            server.scheduler.performTicks(1)
            top.getItem(deposits[1]) shouldBe ItemStack(Material.BROWN_MUSHROOM, 2)
            top.getItem(layout.slot("sell").index)!!.plainDisplayName().contains("Сдано частично") shouldBe false
            ContractDeskStorage(player, "food_orders", { false }).snapshot()[1] shouldBe ItemStack(Material.BROWN_MUSHROOM, 2)
            submitCalls shouldBe 1
        } finally {
            NpcContractDepositGui.shutdown()
            unmockkObject(ContractOriginGate, NativePaperPlayerDataPersistence)
            unmockkStatic(ContractsManager::class)
            ContractsMenus.close()
        }
    }
}

private fun foodOrder(
    playerRemaining: Long,
    id: String = "mushroom-order",
    itemKey: String = "minecraft:brown_mushroom",
    displayName: String = "Коричневые грибы",
    playerCap: Long = 10,
): ResourceContractPlayerView {
    val definition = ResourceContractDefinition(
        id = id,
        displayName = displayName,
        itemKey = itemKey,
        funding = ContractFunding.SERVER_ENVELOPE,
        windowStartsAt = 1_000,
        windowEndsAt = Long.MAX_VALUE,
        payoutMinorPerUnit = 100,
        budgetMinor = 500_000,
        targetQuantity = 1_536,
        perPlayerQuantityCap = playerCap,
        maxSubmissionQuantity = 1_536,
        group = "food_orders",
    )
    return ResourceContractPlayerView(
        contract = ResourceContractView(
            id = definition.id,
            displayName = definition.displayName,
            itemKey = definition.itemKey,
            funding = definition.funding.label,
            status = ContractStatus.OPEN.label,
            windowStartsAt = definition.windowStartsAt,
            windowEndsAt = definition.windowEndsAt,
            payoutMinorPerUnit = definition.payoutMinorPerUnit,
            budgetMinor = definition.budgetMinor,
            spentMinor = 0,
            reservedMinor = 0,
            targetQuantity = definition.targetQuantity,
            acceptedQuantity = 0,
            reservedQuantity = 0,
            remainingQuantity = definition.targetQuantity,
            contributors = 0,
            group = definition.group,
        ),
        minSubmissionQuantity = 1,
        maxSubmissionQuantity = definition.maxSubmissionQuantity,
        perPlayerQuantityCap = definition.perPlayerQuantityCap,
        playerAcceptedQuantity = definition.perPlayerQuantityCap - playerRemaining,
        playerReservedQuantity = 0,
        playerRemainingQuantity = playerRemaining,
        playerPayoutMinorPerUnit = definition.payoutMinorPerUnit,
        capBasisPoints = 10_000,
        payoutBasisPoints = 10_000,
        pricingDefinition = definition,
        pricingSupply = 0,
        pricingAt = 1_500,
    )
}

private fun ItemStack.plainDisplayName(): String =
    PlainTextComponentSerializer.plainText().serialize(requireNotNull(itemMeta.displayName()))

private fun ItemStack.plainLore(): List<String> =
    itemMeta.lore().orEmpty().map(PlainTextComponentSerializer.plainText()::serialize)

private fun ItemStack.assertDeskTooltip() {
    requireNotNull(itemMeta.displayName()).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
    val lines = itemMeta.lore().orEmpty()
    lines.forEach { it.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE }
    val plain = lines.map(PlainTextComponentSerializer.plainText()::serialize)
    if (plain.isNotEmpty()) {
        plain.first().isBlank() shouldBe true
        plain.getOrNull(1)?.isBlank() shouldBe false
        plain.last().isBlank() shouldBe false
        plain.zipWithNext().none { (first, second) -> first.isBlank() && second.isBlank() } shouldBe true
    }
}

private fun Component.containsTranslation(key: String): Boolean =
    (this as? TranslatableComponent)?.key() == key || children().any { it.containsTranslation(key) }

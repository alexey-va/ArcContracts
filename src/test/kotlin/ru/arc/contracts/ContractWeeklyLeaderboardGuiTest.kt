package ru.arc.contracts

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.player.PaperOfflinePlayerData
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ContractWeeklyLeaderboardGuiTest {
    private lateinit var paper: MockBukkitTestRuntime
    private lateinit var plugin: JavaPlugin
    private lateinit var root: Path
    private val plain = PlainTextComponentSerializer.plainText()

    @BeforeEach
    fun setUp() {
        ConfigManager.clear()
        paper = MockBukkitTestRuntime.open()
        plugin = paper.createSimplePlugin("ContractWeeklyLeaderboardGuiTest")
        PaperArcRuntime.installScheduling(plugin)
        root = Files.createTempDirectory("contract-weekly-top")
        Files.createDirectories(root.resolve("guis"))
        javaClass.classLoader.getResourceAsStream("guis/contracts.yml")!!.use {
            Files.copy(it, root.resolve("guis/contracts.yml"))
        }
        ContractWeeklyLeaderboardGui.start(ConfigManager.of(root, "guis/contracts.yml"), PaperOfflinePlayerData(root))
    }

    @AfterEach
    fun tearDown() {
        ContractWeeklyLeaderboardGui.shutdown()
        unmockkObject(ContractWeeklyLeaderboardGui, ContractsMenus, NpcContractDepositGui, ContractOriginGate)
        Tasks.reset()
        paper.close()
        ConfigManager.clear()
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder<Path>()).forEach(Files::deleteIfExists) }
    }

    @Test
    fun `populated top shows eight rows names quantities and real paging with the original desk return`() {
        val player = paper.server.addPlayer("Viewer")
        val ids = (1..9).map { UUID(0, it.toLong()) }
        val entries = ids.mapIndexed { index, id -> ContractWeeklyLeaderboard.Entry(id.toString(), 10000L - index * 100) }
        val loaded = ContractWeeklyLeaderboardGui.Loaded(ContractWeeklyLeaderboard(
            ContractRotation.weekStart(System.currentTimeMillis()), entries, emptyMap()), 0,
            ids.mapIndexed { index, id -> id to "Supplier_${index + 1}" }.toMap())
        val screen = ContractWeeklyLeaderboardGui.screen(player, "food_orders", 2, 0, loaded, false)
        screen.id shouldBe "contracts.weekly-top"
        val text = screen.body.joinToString("\n") { plain.serialize(it.text) }
        text.contains("Supplier_1") shouldBe true
        text.contains("10000") shouldBe true
        text.contains("Supplier_8") shouldBe true
        text.contains("Supplier_9") shouldBe false
        screen.buttons.map { it.id.value } shouldBe listOf("weekly_top_desk", "weekly_top_refresh", "weekly_top_next")
        screen.buttons.last().closeDialogBeforeAction shouldBe false
        screen.buttons.first().closeDialogBeforeAction shouldBe true
        mockkObject(ContractOriginGate, NpcContractDepositGui)
        every { ContractOriginGate.canSubmit(player, "food_orders") } returns true
        every { NpcContractDepositGui.open(player, "food_orders", 2) } returns Unit
        screen.buttons.first().onClick.handle(mockk())
        verify(exactly = 1) { NpcContractDepositGui.open(player, "food_orders", 2) }

        val last = ContractWeeklyLeaderboardGui.screen(player, "food_orders", 2, 1, loaded.copy(page = 1), false)
        last.buttons.map { it.id.value } shouldBe listOf("weekly_top_desk", "weekly_top_refresh", "weekly_top_previous")
        last.body.joinToString { plain.serialize(it.text) }.contains("Supplier_9") shouldBe true
        exportContractPreview("weekly-top", mapOf(
            "title" to previewText(screen.title),
            "body" to screen.body.map { mapOf("text" to previewText(it.text), "width" to it.width) },
            "buttons" to screen.buttons.map { mapOf("label" to previewText(it.label), "tooltip" to previewText(it.tooltip), "width" to it.width) },
            "columns" to screen.columns,
            "glyphs" to previewGlyphs(screen.body.map { it.text }),
        ))
    }

    @Test
    fun `empty failed and loading states remain readable and do not create unavailable page actions`() {
        val player = paper.server.addPlayer()
        val empty = ContractWeeklyLeaderboardGui.Loaded(ContractWeeklyLeaderboard(
            ContractRotation.weekStart(System.currentTimeMillis()), emptyList(), emptyMap()), 0, emptyMap())
        val screen = ContractWeeklyLeaderboardGui.screen(player, "food_orders", 0, 0, empty, false)
        screen.body.any { plain.serialize(it.text).contains("нет подтверждённых") } shouldBe true
        screen.buttons.size shouldBe 2
        val incomplete = ContractWeeklyLeaderboardGui.screen(player, "food_orders", 0, 0,
            empty.copy(leaderboard = empty.leaderboard.copy(incompleteContracts = setOf("stone"))), false)
        incomplete.body.any { plain.serialize(it.text).contains("Часть сдач ещё проверяется") } shouldBe true
        val failed = ContractWeeklyLeaderboardGui.screen(player, "food_orders", 0, 0, null, true)
        failed.body.any { plain.serialize(it.text).contains("Не удалось") } shouldBe true
        failed.buttons.size shouldBe 2
        val loading = ContractWeeklyLeaderboardGui.screen(player, "food_orders", 0, 0, null, false)
        loading.buttons.map { it.id.value } shouldBe listOf("weekly_top_desk")
    }

    @Test
    fun `dismissed loading visit ignores its late result`() {
        val player = paper.server.addPlayer()
        val future = CompletableFuture<ContractWeeklyLeaderboardGui.Loaded>()
        val screens = mutableListOf<PaperDialogScreen>()
        var dismiss: () -> Unit = {}
        mockkObject(ContractWeeklyLeaderboardGui, ContractsMenus)
        every { ContractWeeklyLeaderboardGui.load(any(), any()) } returns future
        every { ContractsMenus.beginDialogFlow(player) } returns Unit
        every { ContractsMenus.openDialog(player, any(), any(), any()) } answers {
            screens += secondArg<PaperDialogScreen>()
            dismiss = arg(3)
        }
        ContractWeeklyLeaderboardGui.open(player, "forge_orders", 0)
        screens.size shouldBe 1
        future.isDone shouldBe false
        dismiss()
        future.complete(ContractWeeklyLeaderboardGui.Loaded(ContractWeeklyLeaderboard(
            ContractRotation.weekStart(System.currentTimeMillis()), emptyList(), emptyMap()), 0, emptyMap()))
        paper.server.scheduler.performTicks(2)
        screens.size shouldBe 1
    }
}

internal fun previewText(component: Component): String = LegacyComponentSerializer.builder()
    .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build().serialize(component)

private fun previewGlyphs(components: List<Component>): List<Map<String, String>> {
    val glyphs = linkedMapOf<String, String>()
    fun visit(component: Component, inheritedFont: String) {
        val font = component.font()?.asString() ?: inheritedFont
        if (component is TextComponent) component.content().codePoints().filter { Character.getType(it) == Character.PRIVATE_USE.toInt() }
            .forEach { glyphs[String(Character.toChars(it))] = font }
        component.children().forEach { visit(it, font) }
    }
    components.forEach { visit(it, "minecraft:default") }
    return glyphs.map { (character, provider) -> mapOf("character" to character, "provider" to provider) }
}

/** Optional source-derived evidence; the ordinary test suite has no renderer or pack dependency. */
internal fun exportContractPreview(name: String, values: Map<String, Any>) {
    val directory = System.getenv("CONTRACTS_PREVIEW_DIR")?.let(Path::of) ?: return
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("$name.json"), com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(values))
}

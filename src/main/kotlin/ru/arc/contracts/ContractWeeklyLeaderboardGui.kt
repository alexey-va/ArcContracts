package ru.arc.contracts

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogNavigation
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.player.PaperOfflinePlayerData
import ru.arc.util.TextUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** The inventory remains the deposit surface; this dialog only reads committed contributions. */
internal object ContractWeeklyLeaderboardGui {
    const val PAGE_SIZE = 8
    private const val WIDTH = 320
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneId.of("Europe/Moscow"))
    private val generations = mutableMapOf<UUID, UUID>()
    private var tasks: LifecycleTaskScope? = null
    private lateinit var config: Config
    private lateinit var offlinePlayers: PaperOfflinePlayerData

    data class Loaded(val leaderboard: ContractWeeklyLeaderboard, val page: Int, val names: Map<UUID, String?>) {
        val pages: Int get() = ((leaderboard.entries.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val entries get() = leaderboard.entries.drop(page * PAGE_SIZE).take(PAGE_SIZE)

        /** At most one native online lookup per visible row; offline IO already ran on the worker. */
        fun name(entry: ContractWeeklyLeaderboard.Entry): Component {
            val id = runCatching { UUID.fromString(entry.playerId) }.getOrNull()
            return Component.text(id?.let { Bukkit.getPlayer(it)?.name ?: names[it] } ?: entry.playerId.take(8))
        }
    }

    fun start(configuration: Config, playerData: PaperOfflinePlayerData) {
        shutdown()
        config = configuration
        offlinePlayers = playerData
        tasks = LifecycleTaskScope()
    }

    fun shutdown() {
        generations.clear()
        tasks?.close()
        tasks = null
    }

    /** Names are loaded only for the visible page and up to three leaders per visible order. */
    fun load(requestedPage: Int = 0, contractIds: List<String> = emptyList()): CompletableFuture<Loaded> {
        val playerData = offlinePlayers
        return ContractsManager.weeklyLeaderboard().thenCompose { leaderboard ->
            ContractsRuntime.background {
                val pages = ((leaderboard.entries.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
                val page = requestedPage.coerceIn(0, pages - 1)
                val entries = leaderboard.entries.drop(page * PAGE_SIZE).take(PAGE_SIZE) +
                    contractIds.flatMap { leaderboard.byContract[it].orEmpty().take(3) }
                val ids = entries.mapNotNull { runCatching { UUID.fromString(it.playerId) }.getOrNull() }.toSet()
                val resolved = playerData.names(ids)
                if (resolved.failures.isNotEmpty()) ContractsRuntime.logWarning(
                    "Contract leaderboard could not read ${resolved.failures.size} offline player name(s): ${resolved.failures}",
                )
                Loaded(leaderboard, page, resolved.names)
            }
        }
    }

    fun open(player: Player, group: String, deskPage: Int) {
        ContractsMenus.beginDialogFlow(player)
        show(player, group, deskPage, 0)
    }

    private fun show(player: Player, group: String, deskPage: Int, requestedPage: Int) {
        val scope = tasks ?: return
        val generation = UUID.randomUUID()
        generations[player.uniqueId] = generation
        fun render(loaded: Loaded?, failed: Boolean = false) {
            ContractsMenus.openDialog(player, screen(player, group, deskPage, requestedPage, loaded, failed),
                reopen = { show(player, group, deskPage, requestedPage) },
                onDismiss = { generations.remove(player.uniqueId, generation) })
        }
        render(null)
        load(requestedPage).whenCompleteSync(scope) { loaded, failure ->
            if (!player.isOnline || generations[player.uniqueId] != generation) return@whenCompleteSync
            if (failure != null) {
                ContractsRuntime.logWarning("Contract leaderboard load failed for ${player.uniqueId}", failure)
                render(null, failed = true)
            } else if (requireNotNull(loaded).leaderboard.weekStartsAt != ContractRotation.weekStart(System.currentTimeMillis())) {
                show(player, group, deskPage, 0)
            } else render(loaded)
        }
    }

    internal fun screen(
        player: Player,
        group: String,
        deskPage: Int,
        requestedPage: Int,
        loaded: Loaded?,
        failed: Boolean,
    ): PaperDialogScreen {
        val body = mutableListOf<PaperDialogBody>()
        if (loaded == null) {
            body += panel(text(if (failed) "failed" else "loading",
                if (failed) "<#ff6b61>Не удалось загрузить топ. Попробуйте ещё раз."
                else "<#e8dfd2>Загружаем поставщиков недели…"))
        } else {
            val start = loaded.leaderboard.weekStartsAt
            var period = text("period", "<#e8dfd2><start>–<end> · страница <page>/<pages>",
                "start" to Component.text(dateFormat.format(Instant.ofEpochMilli(start))),
                "end" to Component.text(dateFormat.format(Instant.ofEpochMilli(start + ContractRotation.WEEK_MILLIS - 1))),
                "page" to Component.text(loaded.page + 1), "pages" to Component.text(loaded.pages))
            if (loaded.leaderboard.incompleteContracts.isNotEmpty()) period = period.append(Component.newline())
                .append(text("incomplete", "<#ffb277>Часть сдач ещё проверяется."))
            body += PaperDialogBody(period, WIDTH)
            body += if (loaded.entries.isEmpty()) panel(text("empty", "<#e8dfd2>Пока нет подтверждённых сдач."))
            else DialogTables.filledBody(
                loaded.entries.mapIndexed { index, entry -> listOf(
                    text("place", "<#f4d87a><place>", "place" to Component.text(loaded.page * PAGE_SIZE + index + 1)),
                    text("player", "<#ffffff><player>", "player" to loaded.name(entry)),
                    text("quantity", "<#f4d87a><quantity>", "quantity" to Component.text(entry.quantity)),
                ) },
                listOf(text("headers.place", "<#e8dfd2>№"), text("headers.player", "<#e8dfd2>Игрок"),
                    text("headers.quantity", "<#e8dfd2>Сдано, шт.")),
                WIDTH, listOf(1, 5, 3), DialogTables.PanelSkin.COCOA,
            )
        }
        val buttons = mutableListOf(PaperDialogButton(
            action("desk"), text("back", "<#ffffff>‹ К сдаче товаров"),
            text("back-tooltip", "<#e8dfd2>Вернуться к этому столу контрактов."), width = 158,
            closeDialogBeforeAction = true,
        ) {
            if (ContractOriginGate.canSubmit(player, group)) NpcContractDepositGui.open(player, group, deskPage)
            else player.sendActionBar(TextUtils.mm(config.string("desk.unavailable", "<#ff9f0f>Снова обратитесь к NPC, чтобы сдать товары.")))
        })
        if (loaded != null || failed) buttons += PaperDialogButton(
            action("refresh"), text("refresh", "<#ffffff>Обновить топ"),
            text("refresh-tooltip", "<#e8dfd2>Показать последние успешные сдачи."), width = 158,
        ) { show(player, group, deskPage, loaded?.page ?: requestedPage) }
        fun pageButton(previous: Boolean) = PaperDialogButton(
            action(if (previous) "previous" else "next"), Component.empty(), width = 158,
        ) { show(player, group, deskPage, requireNotNull(loaded).page + if (previous) -1 else 1) }.let { button ->
            if (previous) PaperDialogNavigation.previous(button, text("previous", "<#92bed8>Предыдущая страница"))
            else PaperDialogNavigation.next(button, text("next", "<#92bed8>Следующая страница"))
        }
        return PaperDialogNavigation.bottom(PaperDialogScreen(
            id = "contracts.weekly-top",
            title = text("title", "<#f4d87a><bold>Топ поставщиков недели"),
            body = body, buttons = buttons, columns = 2,
        ), if (loaded != null && loaded.page > 0) pageButton(true) else null,
            if (loaded != null && loaded.page + 1 < loaded.pages) pageButton(false) else null)
    }

    private fun panel(content: Component) = DialogTables.filledBody(
        listOf(listOf(content)), null, WIDTH, listOf(1), DialogTables.PanelSkin.COCOA,
    )

    private fun action(key: String) = PaperDialogActionId.of("weekly_top_$key")
    private fun text(key: String, fallback: String, vararg values: Pair<String, Component>): Component = TextUtils.mm(
        config.string("weekly-top.$key", fallback),
        TagResolver.resolver(values.map { (name, value) -> Placeholder.component(name, value) }),
    ).decoration(TextDecoration.ITALIC, false)
}

package ru.arc.contracts

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogNumberRangeInput
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.util.TextUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object NpcContractsGui {
    private val groupPattern = Regex("[a-z0-9][a-z0-9_-]{2,47}")
    private val asyncGenerations = mutableMapOf<UUID, UUID>()

    private lateinit var contractGuiConfig: Config

    private var tracking: ContractTrackingRuntime? = null
    private var tasks: LifecycleTaskScope? = null

    fun start(configuration: Config = ConfigManager.of(ContractsRuntime.legacyDataPath, "guis/contracts.yml")) {
        shutdown()
        contractGuiConfig = configuration
        tasks = LifecycleTaskScope()
        NpcContractDepositGui.start(configuration)
        val redis = ContractsRuntime.redis ?: return
        tracking = ContractTrackingRuntime(
            ContractsRuntime.plugin, RedisContractTrackingStore(redis, ContractsRuntime.gson),
            currentViews = { playerId, now ->
                val player = ContractsRuntime.plugin.server.getPlayer(playerId)
                if (player == null) emptyList() else ContractsManager.currentPlayerViews(
                    playerId, now = now, policy = ContractRankPolicyResolver.resolve(player),
                )
            },
            presentation = ContractTrackingPresentation(
                showProgress = { player, status ->
                    val view = ContractsManager.currentViews().firstOrNull { it.id == status.state.contractId }
                    if (view != null) player.sendActionBar(trackingText("progress", view.displayName, status))
                },
                notifyCompleted = { player, status ->
                    val view = ContractsManager.currentViews().firstOrNull { it.id == status.state.contractId }
                    if (view != null) player.sendMessage(trackingText("completed", view.displayName, status))
                },
            ),
        ).also { it.start() }
    }

    fun shutdown() {
        NpcContractDepositGui.shutdown()
        tasks?.close()
        tasks = null
        tracking?.close()
        tracking = null
        asyncGenerations.clear()
    }

    private fun trackingText(key: String, name: String, status: ContractTrackingStatus): Component {
        val path = "tracking.$key"
        val fallback = if (key == "completed")
            "<green>Ресурсы собраны: {name} · {current}/{target}. <gray>Проверьте условия в книге заказов."
        else "<aqua>{name} <white>{current}/{target} <gray>для сдачи"
        return localizedContractsText(
            messagePath = path,
            configuredText = boardString("all", path, fallback),
            fallback = fallback,
            values = mapOf(
                "name" to Component.text(name),
                "current" to Component.text(status.currentQuantity),
                "target" to Component.text(status.targetQuantity),
            ),
        )
    }

    fun openSources(player: Player) {
        ContractsMenus.beginDialogFlow(player)
        showSources(player)
    }

    /** Browsing never grants permission to hand in items at an NPC desk. */
    fun openList(player: Player, group: String = "all") {
        if (!groupPattern.matches(group)) {
            player.sendActionBar(message("all", "messages.invalid-group", "<red>Эта книга заказов настроена неверно."))
            return
        }
        ContractsMenus.beginDialogFlow(player)
        showList(player, group, 0)
    }

    fun openDetail(player: Player, group: String, contractId: String, requestedQuantity: Int? = null) {
        if (!groupPattern.matches(group) || !groupPattern.matches(contractId)) {
            player.sendActionBar(message("all", "messages.invalid-group", "<red>Эта книга заказов настроена неверно."))
            return
        }
        ContractsMenus.beginDialogFlow(player)
        showDetail(player, group, contractId, requestedQuantity)
    }

    private data class CatalogEntry(
        val view: ResourceContractPlayerView,
        val available: Int,
        val selection: ContractQuantitySelection,
        val availability: ContractBookAvailability,
    )

    private fun showSources(player: Player) {
        val entries = catalogEntries(player, "all", System.currentTimeMillis())
        val counts = entries.groupingBy { it.view.contract.group }.eachCount()
        val buttons = SOURCES.map { group ->
            val count = if (group == "all") entries.size else counts[group] ?: 0
            PaperDialogButton(
                action("source_$group"),
                light("${groupName(group)} ›", sourceColor(group)),
                tooltip(dialogText(
                    group,
                    "source.tooltip",
                    "<#e8dfd2>Открыть заказы этого источника · открыто: <orders>.",
                    "orders" to light(count.toString(), sourceColor(group)),
                )),
                width = HALF_BUTTON_WIDTH,
            ) { showList(player, group, 0) }
        }
        val screen = PaperDialogScreen(
            id = "contracts.sources",
            title = dialogText("all", "source.title", "<#f4d87a><bold>Источники заказов"),
            body = listOf(PaperDialogBody(dialogText(
                "all",
                "source.intro",
                "<#e8dfd2>Выберите, чьи заказы открыть. Общий список вынесен в отдельный пункт.",
            ), BODY_WIDTH)),
            buttons = buttons,
            columns = 2,
        )
        showDialog(player, screen) { showSources(player) }
    }

    private fun showList(player: Player, group: String, requestedPage: Int) {
        if (ContractOriginGate.canSubmit(player, group)) {
            NpcContractDepositGui.open(player, group)
            return
        }
        val now = System.currentTimeMillis()
        val entries = catalogEntries(player, group, now)
        val pages = ((entries.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val pageEntries = entries.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val ready = entries.count { it.availability == ContractBookAvailability.READY }
        val withItems = entries.count { it.available >= it.view.minSubmissionQuantity }
        val buttons = mutableListOf<PaperDialogButton>()
        pageEntries.forEachIndexed { index, entry ->
            buttons += PaperDialogButton(
                action("order_$index"),
                light("○ ${plainName(entry.view)} ›", WHITE),
                orderTooltip(entry),
                width = HALF_BUTTON_WIDTH,
            ) { showDetail(player, group, entry.view.contract.id, null) }
        }
        if (page > 0) buttons += PaperDialogButton(
            action("previous"), light("‹ ${dialogPlain("all", "pagination.previous", "Предыдущая страница")}", PAGE_COLOR),
            tooltip(dialogText("all", "catalog.page-tooltip", "<#e8dfd2>Перейти к другой странице заказов.")),
            width = HALF_BUTTON_WIDTH,
        ) { showList(player, group, page - 1) }
        if (page + 1 < pages) buttons += PaperDialogButton(
            action("next"), light("${dialogPlain("all", "pagination.next", "Следующая страница")} ›", PAGE_COLOR),
            tooltip(dialogText("all", "catalog.page-tooltip", "<#e8dfd2>Перейти к другой странице заказов.")),
            width = HALF_BUTTON_WIDTH,
        ) { showList(player, group, page + 1) }
        if (pageEntries.isEmpty()) buttons += PaperDialogButton(
            action("refresh"), dialogText(group, "buttons.refresh", "<#f4d87a>Обновить заказы"),
            tooltip(dialogText(group, "catalog.refresh-tooltip", "<#e8dfd2>Проверить, появились ли новые заказы.")),
            width = HALF_BUTTON_WIDTH,
        ) { showList(player, group, page) }

        val screen = PaperDialogScreen(
            id = "contracts.catalog",
            title = dialogText(group, "catalog.title", "<#f4d87a><bold>Книга заказов"),
            body = listOf(PaperDialogBody(dialogText(
                group,
                if (entries.isEmpty()) "catalog.empty" else "catalog.summary",
                if (entries.isEmpty()) "<#e8dfd2>Сейчас открытых заказов нет. Обновите книгу немного позже."
                else "<#e8dfd2>Открыто <orders> заказов · можно сдать сейчас: <ready> · нужная партия уже в инвентаре: <with_items>.\nВыберите заказ — карточка покажет точные условия.",
                "orders" to light(entries.size.toString(), WHITE),
                "ready" to light(ready.toString(), SUCCESS_COLOR),
                "with_items" to light(withItems.toString(), TRADE_COLOR),
            ), BODY_WIDTH)),
            buttons = buttons,
            columns = 2,
        )
        showDialog(player, screen) { showList(player, group, page) }
    }

    private fun catalogEntries(player: Player, group: String, now: Long): List<CatalogEntry> {
        val originAllowed = mutableMapOf<String, Boolean>()
        return ContractsManager.currentPlayerViews(
            player.uniqueId,
            group.takeUnless { it == "all" },
            now = now,
            policy = ContractRankPolicyResolver.resolve(player),
        ).asSequence()
            .filter { it.contract.status != ContractStatus.EXPIRED.label }
            .map { view ->
                val available = PaperContractItems.countPlain(player, view.contract.itemKey)
                val selection = ContractQuantitySelector.select(view, available)
                CatalogEntry(
                    view,
                    available,
                    selection,
                    ContractBookAvailability.resolve(
                        view,
                        available,
                        originAllowed.getOrPut(view.contract.group) {
                            ContractOriginGate.canSubmit(player, view.contract.group)
                        },
                        now = now,
                    ),
                )
            }
            .filter { it.availability.isCatalogVisible() }
            .sortedBy { entry -> when (entry.availability) {
                ContractBookAvailability.READY -> 0
                ContractBookAvailability.ORIGIN_REQUIRED -> 1
                else -> 2
            } }
            .toList()
    }

    private fun orderTooltip(entry: CatalogEntry): Component {
        val selection = entry.selection
        val status = if (entry.availability == ContractBookAvailability.ITEMS_MISSING) {
            dialogText(
                entry.view.contract.group,
                "catalog.items-missing",
                "<#ff6b61>Не хватает: <missing> шт.",
                "missing" to light((selection.minimum - entry.available).coerceAtLeast(0).toString(), ERROR_COLOR),
            )
        } else availabilityComponent(entry.view.contract.group, entry.availability)
        return tooltip(
            light("В инвентаре: ${entry.available} шт.", BODY_COLOR),
            if (selection.canSubmit) light("Можно сдать: ${selection.minimum}–${selection.maximum} шт.", SUCCESS_COLOR)
            else light("Минимум: ${selection.minimum} шт.", WARM_COLOR),
            null,
            light("Цена: ", BODY_COLOR).append(price(entry.view.playerPayoutMinorPerUnit)),
            null,
            status,
            light("Приём до: ${formatDeadline(entry.view.contract.windowEndsAt)}", PAGE_COLOR),
        )
    }

    private fun showDetail(
        player: Player,
        browseGroup: String,
        contractId: String,
        requestedQuantity: Int?,
        notice: Component? = null,
    ) {
        val view = currentView(player, contractId) ?: return showList(player, browseGroup, 0)
        val group = view.contract.group
        if (ContractOriginGate.canSubmit(player, group)) {
            NpcContractDepositGui.open(player, group)
            return
        }
        val available = PaperContractItems.countPlain(player, view.contract.itemKey)
        val selection = ContractQuantitySelector.select(view, available, requestedQuantity)
        val originAllowed = ContractOriginGate.canSubmit(player, group)
        val availability = ContractBookAvailability.resolve(view, available, originAllowed)
        val status = tracking?.status(player, view)
        val trackingTarget = trackingTarget(view, selection)
        val canTrack = tracking != null && trackingTarget != null
        var selectedForReopen = selection.selected.takeIf { selection.canSubmit } ?: requestedQuantity
        val rows = listOf(
            dialogText(group, "labels.resource", "<#e8dfd2>Ресурс") to light(plainName(view), WHITE),
            dialogText(group, "labels.inventory", "<#e8dfd2>В инвентаре") to light("$available шт.", WHITE),
            dialogText(
                group,
                if (selection.canSubmit) "labels.batch" else "labels.minimum",
                if (selection.canSubmit) "<#e8dfd2>Можно сдать" else "<#e8dfd2>Минимум для сдачи",
            ) to if (selection.canSubmit) {
                light("${selection.minimum}–${selection.maximum} шт.", SUCCESS_COLOR)
            } else light("${selection.minimum} шт.", ERROR_COLOR),
            dialogText(group, "labels.personal-left", "<#e8dfd2>Личный лимит") to light("${view.playerRemainingQuantity} шт.", WHITE),
            dialogText(group, "labels.price", "<#e8dfd2>Цена за штуку") to price(view.playerPayoutMinorPerUnit),
            dialogText(group, "labels.deadline", "<#e8dfd2>Приём до") to light(formatDeadline(view.contract.windowEndsAt), PAGE_COLOR),
        )
        val body = mutableListOf(
            PaperDialogBody(dialogText(
                group,
                "detail.intro",
                "<#e8dfd2>Выберите количество ползунком. Точная выплата появится после проверки условий.",
            ), BODY_WIDTH),
            PaperDialogBody(dialogText(group, "detail.purpose", "<#e8dfd2>Ресурсы для поселения."), BODY_WIDTH),
            DialogTables.body(rows, frame = DialogTables.Frame.LEGENDARY, width = TABLE_WIDTH,
                columns = DialogTables.Columns.BALANCED),
        )
        notice?.let { body += PaperDialogBody(it, BODY_WIDTH) }
        if (availability != ContractBookAvailability.READY) {
            body += PaperDialogBody(availabilityComponent(group, availability), BODY_WIDTH)
        }
        val buttons = mutableListOf<PaperDialogButton>()
        buttons += PaperDialogButton(
            action("continue"),
            if (availability == ContractBookAvailability.READY) {
                dialogText(group, "buttons.continue", "<#f4d87a>Проверить сдачу ›")
            } else dialogText(group, "buttons.unavailable", "<#ffffff>[Недоступно] Сдать ресурсы"),
            if (availability == ContractBookAvailability.READY) {
                tooltip(dialogText(group, "detail.continue-tooltip", "<#e8dfd2>Проверить выбранное количество и точную выплату."))
            } else tooltip(availabilityComponent(group, availability)),
            width = BUTTON_WIDTH,
        ) { context ->
            if (availability == ContractBookAvailability.READY) {
                val value = context.number(QUANTITY_INPUT)
                ContractDialogRules.quantity(value, selection)?.let { selectedForReopen = it }
                prepareConfirmation(context.player, browseGroup, contractId, value)
            } else showDetail(context.player, browseGroup, contractId, requestedQuantity, availabilityComponent(group, availability))
        }
        buttons += PaperDialogButton(
            action("tracking"),
            when {
                status != null -> dialogText(group, "buttons.tracked", "<#9bd48d>✔ Отслеживается")
                canTrack -> dialogText(group, "buttons.track", "<#ffffff>○ Отслеживать")
                else -> dialogText(group, "buttons.track-unavailable", "<#ffffff>[Недоступно] Отслеживать")
            },
            when {
                status != null -> tooltip(
                    light("Цель: ${status.currentQuantity}/${status.targetQuantity} шт.", SUCCESS_COLOR),
                    dialogText(group, "tracking.stop-tooltip", "<#e8dfd2>Нажмите, чтобы перестать отслеживать."),
                )
                canTrack -> tooltip(
                    light("Цель: $trackingTarget шт.", WHITE),
                    dialogText(group, "tracking.start-tooltip", "<#e8dfd2>Показывать прогресс сбора в HUD."),
                )
                else -> tooltip(dialogText(group, "tracking.unavailable-tooltip", "<#ff6b61>Для этого заказа нельзя поставить цель."))
            },
            width = BUTTON_WIDTH,
        ) { context ->
            if (status != null || canTrack) {
                val value = context.number(QUANTITY_INPUT)
                ContractDialogRules.quantity(value, selection)?.let { selectedForReopen = it }
                toggleTracking(context.player, browseGroup, contractId, value)
            } else showDetail(context.player, browseGroup, contractId, requestedQuantity)
        }
        val screen = PaperDialogScreen(
            id = "contracts.detail",
            title = light(plainName(view), sourceColor(group)).decorate(TextDecoration.BOLD),
            body = body,
            numberInputs = if (selection.canSubmit) listOf(PaperDialogNumberRangeInput(
                id = QUANTITY_INPUT,
                label = dialogText(group, "detail.quantity-label", "<#ffffff>Количество"),
                start = selection.minimum.toFloat(),
                end = selection.maximum.toFloat(),
                initial = selection.selected.toFloat(),
                step = 1f,
                width = BODY_WIDTH,
                labelFormat = "%s: %s",
            )) else emptyList(),
            buttons = buttons,
            columns = 1,
        )
        showDialog(player, screen) { showDetail(player, browseGroup, contractId, selectedForReopen) }
    }

    private fun prepareConfirmation(player: Player, browseGroup: String, contractId: String, rawQuantity: Float?) {
        val view = currentView(player, contractId) ?: return showList(player, browseGroup, 0)
        val available = PaperContractItems.countPlain(player, view.contract.itemKey)
        val selection = ContractQuantitySelector.select(view, available)
        val quantity = ContractDialogRules.quantity(rawQuantity, selection)
            ?: return showDetail(player, browseGroup, contractId, null,
                dialogText(view.contract.group, "detail.changed", "<#ff6b61>Инвентарь или условия изменились. Выберите количество ещё раз."))
        val exactSelection = ContractQuantitySelector.select(view, available, quantity)
        val availability = ContractBookAvailability.resolve(
            view,
            available,
            ContractOriginGate.canSubmit(player, view.contract.group),
        )
        if (!exactSelection.canSubmit || exactSelection.selected != quantity || availability != ContractBookAvailability.READY) {
            return showDetail(player, browseGroup, contractId, null, availabilityComponent(view.contract.group, availability))
        }
        val quote = ContractsManager.quote(player, contractId, quantity)
            ?: return showDetail(player, browseGroup, contractId, quantity,
                dialogText(view.contract.group, "detail.changed", "<#ff6b61>Инвентарь или условия изменились. Выберите количество ещё раз."))
        showConfirmation(player, browseGroup, view, available, quote)
    }

    private fun showConfirmation(
        player: Player,
        browseGroup: String,
        view: ResourceContractPlayerView,
        available: Int,
        quote: ContractSubmissionQuote,
    ) {
        val group = view.contract.group
        val rows = listOf(
            dialogText(group, "labels.resource", "<#e8dfd2>Ресурс") to light(plainName(view), WHITE),
            dialogText(group, "labels.selected", "<#e8dfd2>Сдать") to light("${quote.quantity} шт.", WHITE),
            dialogText(group, "labels.inventory-after", "<#e8dfd2>В инвентаре после") to light("${available - quote.quantity} шт.", WHITE),
            dialogText(group, "labels.payout", "<#e8dfd2>Выплата") to price(quote.payoutMinor),
            dialogText(group, "labels.personal-after", "<#e8dfd2>Ваш остаток лимита") to
                light("${(view.playerRemainingQuantity - quote.quantity).coerceAtLeast(0)} шт.", WHITE),
        )
        val screen = PaperDialogScreen(
            id = "contracts.confirm",
            title = dialogText(group, "confirm.title", "<#f4d87a><bold>Подтверждение сдачи"),
            body = listOf(
                PaperDialogBody(dialogText(group, "confirm.intro", "<#e8dfd2>Проверьте точное количество и выплату. Перед сдачей условия будут сверены ещё раз."), BODY_WIDTH),
                DialogTables.body(rows, frame = DialogTables.Frame.LEGENDARY, width = TABLE_WIDTH,
                    columns = DialogTables.Columns.BALANCED),
            ),
            buttons = listOf(PaperDialogButton(
                action("submit"),
                dialogText(
                    group,
                    "buttons.submit",
                    "<#9bd48d>Сдать <quantity> шт. · <payout> <#ffffff>💰",
                    "quantity" to light(quote.quantity.toString(), SUCCESS_COLOR),
                    "payout" to light(formatContractMoney(quote.payoutMinor), TRADE_COLOR),
                ),
                tooltip(dialogText(group, "confirm.submit-tooltip", "<#e8dfd2>Предметы и выплата будут обработаны одной защищённой операцией.")),
                width = BUTTON_WIDTH,
            ) { submitConfirmed(it.player, browseGroup, quote) }),
            columns = 1,
        )
        showDialog(player, screen) {
            prepareConfirmation(player, browseGroup, quote.contractId, quote.quantity.toFloat())
        }
    }

    private fun submitConfirmed(player: Player, browseGroup: String, shown: ContractSubmissionQuote) {
        val view = currentView(player, shown.contractId)
            ?: return showList(player, browseGroup, 0)
        val group = view.contract.group
        val available = PaperContractItems.countPlain(player, view.contract.itemKey)
        val current = if (available >= shown.quantity && ContractOriginGate.canSubmit(player, group)) {
            ContractsManager.quote(player, shown.contractId, shown.quantity)
        } else null
        if (current == null || !ContractDialogRules.sameQuote(shown, current)) {
            return showDetail(player, browseGroup, shown.contractId, null,
                dialogText(group, "detail.changed", "<#ff6b61>Инвентарь или условия изменились. Выберите количество ещё раз."))
        }
        submit(player, group, current, browseGroup)
    }

    private fun toggleTracking(
        player: Player,
        browseGroup: String,
        contractId: String,
        rawQuantity: Float?,
    ) {
        val active = tasks ?: return
        val runtime = tracking ?: return showDetail(player, browseGroup, contractId, null)
        val view = currentView(player, contractId) ?: return showList(player, browseGroup, 0)
        val selection = ContractQuantitySelector.select(view, PaperContractItems.countPlain(player, view.contract.itemKey))
        val currentStatus = runtime.status(player, view)
        val quantity = ContractDialogRules.quantity(rawQuantity, selection)
        val target = trackingTarget(view, selection, quantity)
        if (currentStatus == null && target == null) return showDetail(player, browseGroup, contractId, null)
        val change = runCatching {
            if (currentStatus != null) runtime.clear(player) else runtime.toggle(player, view, requireNotNull(target))
        }.getOrElse {
            player.sendActionBar(message("all", "tracking.failure", "<yellow>Не удалось сохранить цель. Попробуйте ещё раз."))
            return showDetail(player, browseGroup, contractId, selection.selected)
        }
        val generation = beginAsync(player)
        showProcessing(player, "tracking")
        change.whenCompleteSync(active) { _, failure ->
            if (!player.isOnline) {
                finishAsync(player, generation)
                return@whenCompleteSync
            }
            if (!finishAsync(player, generation)) return@whenCompleteSync
            if (failure != null) {
                player.sendActionBar(message("all", "tracking.failure", "<yellow>Не удалось сохранить цель. Попробуйте ещё раз."))
            }
            showDetail(
                player,
                browseGroup,
                contractId,
                selection.selected,
                dialogText(view.contract.group, if (failure == null) "tracking.saved" else "tracking.failed",
                    if (failure == null) "<#9bd48d>Цель отслеживания обновлена." else "<#ff6b61>Не удалось сохранить цель. Попробуйте ещё раз."),
            )
        }
    }

    private fun trackingTarget(
        view: ResourceContractPlayerView,
        selection: ContractQuantitySelection,
        requested: Int? = null,
    ): Long? {
        val preferred = requested?.toLong()
            ?: selection.selected.takeIf { selection.canSubmit }?.toLong()
            ?: (PaperContractItems.material(view.contract.itemKey)?.maxStackSize ?: 64).toLong()
        val now = System.currentTimeMillis()
        if (tracking == null || now !in view.contract.windowStartsAt until view.contract.windowEndsAt ||
            view.contract.status != ContractStatus.OPEN.label) return null
        return runCatching { ContractTrackingLogic.targetQuantity(view, preferred) }.getOrNull()
    }

    private fun currentView(player: Player, contractId: String): ResourceContractPlayerView? =
        ContractsManager.currentPlayerViews(
            player.uniqueId,
            policy = ContractRankPolicyResolver.resolve(player),
        ).firstOrNull { it.contract.id == contractId }

    private fun groupName(group: String): String = boardString(group, "name", when (group) {
        "all" -> "Все заказы"
        "forge_orders" -> "Кузница"
        "bank_orders" -> "Банк"
        "guild_orders" -> "Гильдия"
        "food_orders" -> "Бар Матео"
        else -> "Заказы"
    })

    private fun submit(
        player: Player,
        group: String,
        quote: ContractSubmissionQuote,
        browseGroup: String = group,
    ) {
        if (!ContractOriginGate.canSubmit(player, group)) {
            return showDetail(player, browseGroup, quote.contractId, null,
                availabilityComponent(group, ContractBookAvailability.ORIGIN_REQUIRED))
        }
        val active = tasks ?: return
        player.sendActionBar(message(group, "messages.processing", "<gray>Проверяем ресурсы и запись в книге…"))
        val generation = beginAsync(player)
        showProcessing(player, "submission")
        ContractsManager.submit(player, quote).whenCompleteSync(active) { outcome, failure ->
            if (!player.isOnline) {
                finishAsync(player, generation)
                return@whenCompleteSync
            }
            if (failure != null || outcome == null) {
                player.sendMessage(
                    message(
                        group,
                        "messages.failure",
                        "<red>Заказ остановлен для проверки. <gray>Предметы повторно не сдавайте.",
                    ),
                )
                if (finishAsync(player, generation)) showResult(
                    player,
                    group,
                    "result.review-title",
                    "<#ff6b61><bold>Операция на проверке",
                    "result.review-body",
                    "<#ff6b61>Не повторяйте сдачу. Администратор должен проверить состояние операции.",
                )
                return@whenCompleteSync
            }
            player.sendMessage(ContractPlayerMessages.render(outcome, contractGuiConfig, group))
            if (!finishAsync(player, generation)) return@whenCompleteSync
            when (outcome) {
                is ContractSubmissionOutcome.Committed -> showDetail(
                    player,
                    browseGroup,
                    quote.contractId,
                    null,
                    dialogText(
                        group,
                        "result.committed",
                        "<#9bd48d>Сдано <quantity> шт. · получено <payout> <#ffffff>💰",
                        "quantity" to light(outcome.receipt.quantity.toString(), SUCCESS_COLOR),
                        "payout" to light(formatContractMoney(outcome.receipt.payoutMinor), TRADE_COLOR),
                    ),
                )
                is ContractSubmissionOutcome.ManualReview -> showResult(
                    player,
                    group,
                    "result.review-title",
                    "<#ff6b61><bold>Операция на проверке",
                    "result.review-body",
                    "<#ff6b61>Не повторяйте сдачу. Администратор должен проверить состояние операции.",
                )
                else -> showDetail(
                    player,
                    browseGroup,
                    quote.contractId,
                    null,
                    dialogText(group, "result.not-committed", "<#ff6b61>Заказ не принят. Условия и инвентарь обновлены."),
                )
            }
        }
    }

    private fun showProcessing(player: Player, operation: String) {
        val screen = PaperDialogScreen(
            id = "contracts.processing",
            title = dialogText("all", "processing.title", "<#f4d87a><bold>Проверяем условия"),
            body = listOf(PaperDialogBody(dialogText(
                "all",
                "processing.$operation",
                if (operation == "tracking") "<#e8dfd2>Сохраняем цель отслеживания…"
                else "<#e8dfd2>Проверяем предметы и записываем операцию. Не повторяйте сдачу.",
            ), BODY_WIDTH)),
            buttons = emptyList(),
        )
        showDialog(player, screen) { showProcessing(player, operation) }
    }

    private fun showResult(
        player: Player,
        group: String,
        titlePath: String,
        titleFallback: String,
        bodyPath: String,
        bodyFallback: String,
    ) {
        ContractsMenus.beginDialogFlow(player)
        val screen = PaperDialogScreen(
            id = "contracts.result",
            title = dialogText(group, titlePath, titleFallback),
            body = listOf(PaperDialogBody(dialogText(group, bodyPath, bodyFallback), BODY_WIDTH)),
            buttons = emptyList(),
        )
        showDialog(player, screen) { showResult(player, group, titlePath, titleFallback, bodyPath, bodyFallback) }
    }

    private fun showDialog(player: Player, screen: PaperDialogScreen, reopen: () -> Unit) =
        ContractsMenus.openDialog(player, screen, reopen = reopen, onDismiss = { invalidateGeneration(player) })

    private fun beginAsync(player: Player): UUID =
        UUID.randomUUID().also { asyncGenerations[player.uniqueId] = it }

    private fun finishAsync(player: Player, generation: UUID): Boolean {
        if (asyncGenerations[player.uniqueId] != generation) return false
        asyncGenerations.remove(player.uniqueId)
        return true
    }

    private fun invalidateGeneration(player: Player) {
        asyncGenerations.remove(player.uniqueId)
    }

    private fun availabilityComponent(group: String, availability: ContractBookAvailability): Component {
        val value = dialogPlain(
            group,
            "availability.${availability.messageKey}",
            availability.fallback,
            legacyPath = true,
        )
        val color = when (availability) {
            ContractBookAvailability.READY, ContractBookAvailability.COMPLETED -> SUCCESS_COLOR
            ContractBookAvailability.ORIGIN_REQUIRED, ContractBookAvailability.NOT_STARTED,
            ContractBookAvailability.CLOSED -> WARM_COLOR
            else -> ERROR_COLOR
        }
        return light(value, color)
    }

    private fun dialogText(
        group: String,
        path: String,
        fallback: String,
        vararg tags: Pair<String, Component>,
    ): Component {
        val configPath = "dialog.$path"
        return localizedContractsText(
            messagePath = configPath,
            configuredText = boardString(group, configPath, fallback),
            fallback = fallback,
            values = tags.toMap(),
        ).decoration(TextDecoration.ITALIC, false)
    }

    private fun dialogPlain(group: String, path: String, fallback: String, legacyPath: Boolean = false): String {
        val configPath = if (legacyPath) path else "dialog.$path"
        return PlainTextComponentSerializer.plainText().serialize(TextUtils.mm(boardString(group, configPath, fallback))).trim()
    }

    private fun tooltip(vararg lines: Component?): Component {
        var result = Component.newline()
        lines.forEach { line ->
            if (line != null) result = result.append(light("  ", BODY_COLOR)).append(line)
            result = result.append(Component.newline())
        }
        return result
    }

    private fun plainName(view: ResourceContractPlayerView): String =
        PlainTextComponentSerializer.plainText().serialize(TextUtils.mm(view.contract.displayName, true)).trim().ifBlank { "Заказ" }

    private fun light(value: String, color: TextColor = BODY_COLOR): Component =
        Component.text(value, color).decoration(TextDecoration.ITALIC, false)

    private fun price(amountMinor: Long): Component =
        light(formatContractMoney(amountMinor), TRADE_COLOR).append(light(" 💰", WHITE))

    private fun sourceColor(group: String): TextColor = when (group) {
        "bank_orders" -> TRADE_COLOR
        "forge_orders" -> ACTIVITY_COLOR
        "guild_orders" -> PROGRESSION_COLOR
        "all" -> WHITE
        else -> WARM_COLOR
    }

    private fun action(value: String): PaperDialogActionId = PaperDialogActionId.of("contracts_$value")

    private fun message(group: String, path: String, fallback: String): Component =
        localizedContractsText(path, boardString(group, path, fallback), fallback)

    private fun boardString(group: String, path: String, fallback: String): String =
        contractGuiConfig.string("boards.$group.$path", contractGuiConfig.string("defaults.$path", fallback))

    private fun formatDeadline(timestamp: Long): String = formatContractDeadline(timestamp)

    private val QUANTITY_INPUT = PaperDialogInputId.of("quantity")
    private const val PAGE_SIZE = 6
    private const val BODY_WIDTH = 320
    private const val TABLE_WIDTH = 380
    private const val BUTTON_WIDTH = 320
    private const val HALF_BUTTON_WIDTH = 158
    private val SOURCES = listOf("bank_orders", "forge_orders", "guild_orders", "food_orders", "all")
    private val WHITE = TextColor.color(0xFFFFFF)
    private val BODY_COLOR = TextColor.color(0xE8DFD2)
    private val WARM_COLOR = TextColor.color(0xD7B486)
    private val TRADE_COLOR = TextColor.color(0xF4D87A)
    private val ACTIVITY_COLOR = TextColor.color(0xFFB277)
    private val PROGRESSION_COLOR = TextColor.color(0xC4ABFF)
    private val PAGE_COLOR = TextColor.color(0x92BED8)
    private val SUCCESS_COLOR = TextColor.color(0x9BD48D)
    private val ERROR_COLOR = TextColor.color(0xFF6B61)
}

private val CONTRACT_DEADLINE_FORMAT =
    DateTimeFormatter.ofPattern("EEEE, HH:mm", java.util.Locale.forLanguageTag("ru-RU"))
        .withZone(ZoneId.of("Europe/Moscow"))

/** Contract windows are end-exclusive, so present the final accepting minute. */
internal fun formatContractDeadline(windowEndsAt: Long): String =
    ru.arc.contracts.api.ArcContractPresentation.formatDeadline(windowEndsAt)

internal object ContractDialogRules {
    fun quantity(value: Float?, selection: ContractQuantitySelection): Int? {
        if (value == null || !value.isFinite() || !selection.canSubmit) return null
        val quantity = value.toInt()
        return quantity.takeIf { value == quantity.toFloat() && quantity in selection.minimum..selection.maximum }
    }

    fun sameQuote(expected: ContractSubmissionQuote, current: ContractSubmissionQuote): Boolean =
        expected.contractId == current.contractId &&
            expected.windowStartsAt == current.windowStartsAt &&
            expected.playerId == current.playerId &&
            expected.quantity == current.quantity &&
            expected.payoutMinor == current.payoutMinor &&
            expected.expectedRevision == current.expectedRevision
}

object ContractPlayerMessages {
    fun render(outcome: ContractSubmissionOutcome, config: Config, group: String): Component =
        when (outcome) {
            is ContractSubmissionOutcome.Committed ->
                message(
                    config,
                    "messages.committed",
                    group,
                    "<gold><speaker> <dark_gray>» <green>Заказ принят. <gray>Сдано <white><quantity><gray>, выплата <gold><payout> <white>💰</white><gray>.",
                    "quantity" to outcome.receipt.quantity,
                    "payout" to formatContractMoney(outcome.receipt.payoutMinor),
                )
            is ContractSubmissionOutcome.Duplicate ->
                message(
                    config,
                    "messages.duplicate",
                    group,
                    "<gold><speaker> <dark_gray>» <yellow>Эта сдача уже учтена. <gray>Повторной выплаты не было.",
                )
            is ContractSubmissionOutcome.Rejected ->
                message(
                    config,
                    "messages.rejected",
                    group,
                    "<gold><speaker> <dark_gray>» <yellow>Заказ не принят: <gray><reason>.",
                    "reason" to rejection(config, group, outcome.reason),
                )
            is ContractSubmissionOutcome.Cancelled ->
                message(
                    config,
                    "messages.cancelled",
                    group,
                    "<gold><speaker> <dark_gray>» <yellow>Инвентарь изменился. <gray>Предметы и деньги не менялись.",
                )
            is ContractSubmissionOutcome.Refunded ->
                message(
                    config,
                    "messages.refunded",
                    group,
                    "<gold><speaker> <dark_gray>» <yellow>Выплата не прошла. <gray>Сданные предметы возвращены.",
                )
            is ContractSubmissionOutcome.ManualReview ->
                message(
                    config,
                    "messages.manual-review",
                    group,
                    "<red>Операция остановлена для проверки. <gray>Не повторяйте сдачу до разбора администратором.",
                )
            is ContractSubmissionOutcome.Unavailable ->
                message(
                    config,
                    "messages.unavailable",
                    group,
                    "<yellow>Книга заказов сейчас недоступна. <gray>Предметы и деньги не менялись.",
                )
        }

    private fun rejection(config: Config, group: String, reason: SubmissionRejection): String =
        config.string(
            "boards.$group.messages.rejections.${reason.label}",
            config.string(
                "defaults.messages.rejections.${reason.label}",
                when (reason) {
                    SubmissionRejection.INVALID_REQUEST -> "неверные параметры"
                    SubmissionRejection.CONTRACT_NOT_OPEN -> "заказ сейчас закрыт"
                    SubmissionRejection.WINDOW_MISMATCH -> "период заказа изменился"
                    SubmissionRejection.STALE_STATE -> "состояние заказа изменилось"
                    SubmissionRejection.QUANTITY_EXHAUSTED -> "нужный объём уже собран"
                    SubmissionRejection.BUDGET_EXHAUSTED -> "бюджет заказа исчерпан"
                    SubmissionRejection.PLAYER_CAP_REACHED -> "ваш лимит исчерпан"
                    SubmissionRejection.CONTRIBUTOR_LIMIT_REACHED -> "достигнут лимит участников"
                    SubmissionRejection.BELOW_MINIMUM -> "количество меньше минимальной партии"
                    SubmissionRejection.PROJECT_STAGE_LOCKED -> "этот этап ещё не открыт"
                    SubmissionRejection.INVENTORY_UNAVAILABLE -> "не хватает обычных предметов без модификаций"
                    SubmissionRejection.JOURNAL_CAPACITY_REACHED -> "журнал операций временно заполнен"
                    SubmissionRejection.SUBMISSION_IN_PROGRESS -> "предыдущая сдача ещё обрабатывается"
                },
            ),
        )

    private fun message(
        config: Config,
        path: String,
        group: String,
        fallback: String,
        vararg tags: Pair<String, Any>,
    ): Component {
        val values = buildMap {
            put("speaker", TextUtils.mm(config.string("boards.$group.speaker", "Приёмщик")))
            tags.forEach { (name, value) -> put(name, Component.text(value.toString())) }
        }
        return localizedContractsText(
            messagePath = path,
            configuredText = config.string("boards.$group.$path", config.string("defaults.$path", fallback)),
            fallback = fallback,
            values = values,
        )
    }

}

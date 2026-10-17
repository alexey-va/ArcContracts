package ru.arc.contracts

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.menu.MenuElementId
import ru.arc.paper.menu.PaperCloudStorageButton
import ru.arc.paper.menu.PaperCloudStorageContent
import ru.arc.paper.menu.PaperCloudStorageSession
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.TextUtils
import java.util.UUID

/** Common NPC desk: native cloud-chest input, explicit sale, durable contract escrow. */
object NpcContractDepositGui : Listener {
    private val sessions = mutableMapOf<UUID, Desk>()
    private val processing = mutableSetOf<UUID>()
    private var tasks: LifecycleTaskScope? = null
    private lateinit var config: ru.arc.config.Config
    private class Desk(val player: Player, val group: String, val storage: ContractDeskStorage, val rows: Int) {
        lateinit var session: PaperCloudStorageSession
        val menu = ContractsMenuSchema.CONTRACT_DESKS.getValue(rows)
        var page = 0
        var result: SaleResult? = null
    }
    private data class SaleResult(
        val quantity: Long,
        val payout: Long,
        val items: Map<Material, Int>,
        val remaining: List<ItemStack?>,
        val review: Boolean,
        val rejection: Component?,
    )
    fun start(configuration: ru.arc.config.Config = ConfigManager.of(ContractsRuntime.legacyDataPath, "guis/contracts.yml")) {
        config = configuration
        tasks = LifecycleTaskScope()
        Bukkit.getPluginManager().registerEvents(this, ContractsRuntime.plugin)
        tasks?.runTimer(100, 100) { sessions.values.toList().forEach(::render) }
    }
    fun shutdown() {
        sessions.values.toList().forEach { it.session.close() }
        tasks?.close()
        tasks = null
        HandlerList.unregisterAll(this)
        sessions.clear()
        processing.clear()
    }
    fun open(player: Player, group: String) {
        if (!ContractOriginGate.canSubmit(player, group)) return
        if (player.uniqueId in processing) { feedback(player, "processing", "<#ff9f0f>Принимаем товары…"); return }
        sessions[player.uniqueId]?.takeIf { it.group == group && it.session.isOpen }?.let { render(it); return }
        val orders = views(player, group)
        val geometry = ContractDeskLayout.calculate(orders.size)
        val storage = ContractDeskStorage(player, group, { offered -> matchingOrders(offered, views(player, group)).isNotEmpty() })
        val desk = Desk(player, group, storage, geometry.rows)
        ContractsMenus.closeDialog(player)
        desk.session = ContractsMenus.openStorage(player, desk.menu, ContractsMenuSchema.CONTRACT_DEPOSIT, storage,
            PaperCloudStorageContent(
                title = TextUtils.mm(config.string("boards.$group.desk-title", "<#20252b>Заказы")),
                background = ContractsMenus.background(desk.menu),
                buttons = mapOf(
                    MenuElementId.of("sell") to PaperCloudStorageButton(sellButton(desk, orders, geometry.pageCount)) { sell(desk) },
                    MenuElementId.of("previous") to PaperCloudStorageButton(ContractsMenus.item(desk.menu, "previous")) { turnPage(desk, -1) },
                    MenuElementId.of("next") to PaperCloudStorageButton(ContractsMenus.item(desk.menu, "next")) { turnPage(desk, 1) },
                ),
                onFailure = { viewer, _ ->
                    if (storage.pending) feedback(viewer, "processing", "<#ff9f0f>Принимаем товары…")
                    else feedback(viewer, "transfer-rejected", "<#ff9f0f>Нет места или этот товар не входит в список заказов.")
                },
            ))
        sessions[player.uniqueId] = desk
        render(desk)
    }
    private fun views(player: Player, group: String) = ContractsManager.currentPlayerViews(
        player.uniqueId, group, policy = ContractRankPolicyResolver.resolve(player),
    )
    private fun turnPage(desk: Desk, step: Int) {
        if (desk.storage.pending) return
        desk.page += step
        render(desk)
    }
    private fun render(desk: Desk) {
        if (!desk.session.isOpen) return
        val orders = views(desk.player, desk.group)
        // A live catalog generation closes these sessions on reload. Between
        // weekly rotations retain the open chest size and paginate its capacity.
        val geometry = ContractDeskLayout.calculate(orders.size, desk.page)
        val pages = geometry.pageCount
        desk.page = geometry.page
        val visible = geometry.visibleOrderRange.map(orders::get)
        val layout = ContractsMenus.current().catalog.require(desk.menu)
        val inventory = desk.session.inventory
        val empty = ContractsMenus.item("contract-desk-empty")
        val leftSlots = layout.region(ContractsMenuSchema.CONTRACT_DESK_ORDERS).map { it.index } +
            (layout.slot("previous").index..layout.slot("next").index)
        leftSlots.forEach { inventory.setItem(it, empty.clone()) }
        visible.zip(geometry.orderSlots).forEach { (view, slot) ->
            val material = PaperContractItems.material(view.contract.itemKey) ?: return@forEach
            val definition = view.pricingDefinition ?: return@forEach
            val market = ContractMarketPricing.unitPayoutMinor(definition, view.pricingSupply, view.pricingAt)
            val availability = ContractBookAvailability.resolve(view, view.maxSubmissionQuantity,
                ContractOriginGate.canSubmit(desk.player, desk.group))
            inventory.setItem(slot, ContractsMenus.item("contract-desk-order", context(
                "name" to Component.text(view.contract.displayName),
                "accepted" to if (view.contract.itemKey == PaperContractItems.ANY_RAW_FISH)
                    text("fish", "<#e6fff3>Любая сырая рыба: треска, лосось, тропическая, иглобрюх.")
                    else text("plain-item", "<#e6fff3>Принимается обычный предмет без переименования."),
                "price" to Component.text(formatContractMoney(view.playerPayoutMinorPerUnit)),
                "base" to Component.text(formatContractMoney(definition.payoutMinorPerUnit)),
                "growth" to Component.text(contractPriceGrowth(definition.payoutMinorPerUnit, market)),
                "rank" to Component.text(contractPriceGrowth(10_000L, view.payoutBasisPoints.toLong())),
                "remaining" to Component.text(view.playerRemainingQuantity),
                "state" to if (availability == ContractBookAvailability.READY)
                    text("accepting", "<#2bba43>Положите товар в ячейки справа и нажмите «Продать».")
                    else TextUtils.mm(config.string("defaults.availability.${availability.messageKey}", availability.fallback)),
            )).withType(material))
        }
        inventory.setItem(layout.slot("sell").index, sellButton(desk, orders, pages))
        inventory.setItem(layout.slot("previous").index, ContractsMenus.item(desk.menu, "previous"))
        inventory.setItem(layout.slot("next").index, ContractsMenus.item(desk.menu, "next"))
        desk.session.refresh()
    }

    private fun sellButton(desk: Desk, orders: List<ResourceContractPlayerView>, pages: Int): ItemStack {
        val snapshot = desk.storage.snapshot()
        if (!desk.storage.pending && desk.result?.remaining != snapshot) desk.result = null
        val result = desk.result
        val offered = snapshot.filterNotNull().groupBy { it.type }
        val originAllowed = ContractOriginGate.canSubmit(desk.player, desk.group)
        val states = offered.mapValues { (_, stacks) ->
            val amount = stacks.sumOf { it.amount }
            val candidates = matchingOrders(stacks.first(), orders)
            candidates.map { view ->
                val availability = ContractBookAvailability.resolve(view, amount, originAllowed)
                if (!desk.storage.pending && availability == ContractBookAvailability.READY &&
                    ContractsManager.quote(desk.player, view.contract.id, minOf(amount, view.maxSubmissionQuantity)) == null)
                    ContractBookAvailability.UNAVAILABLE else availability
            }.let { reasons ->
                if (ContractBookAvailability.READY in reasons) ContractBookAvailability.READY
                else reasons.firstOrNull() ?: ContractBookAvailability.CLOSED
            }
        }
        val groups = mutableListOf<List<Component>>()
        if (pages > 1) groups += listOf(text("button.page", "<#b8b8b8>Страница <current> / <pages>",
            "current" to Component.text(desk.page + 1), "pages" to Component.text(pages)))
        val name = when {
            desk.storage.pending -> text("processing", "<#ff9f0f>Принимаем товары…")
            result?.review == true -> text("button.review", "<#c42323>Сдача на проверке")
            result != null && result.quantity > 0 -> text(
                if (offered.isEmpty()) "button.sold" else "button.partial",
                if (offered.isEmpty()) "<#2bba43>Сдано <quantity> шт. +<price> <white>💰</white>"
                else "<#ff9f0f>Сдано частично +<price> <white>💰</white>",
                "quantity" to Component.text(result.quantity), "price" to Component.text(formatContractMoney(result.payout)),
            )
            result?.rejection != null -> text("button.rejected", "<#ff9f0f>Не удалось сдать")
            offered.isEmpty() -> text("button.empty", "<#2bba43>Положите товары справа")
            states.values.all { it == ContractBookAvailability.PLAYER_CAP } -> text("button.cap", "<#ff9f0f>Лимит исчерпан")
            states.values.none { it == ContractBookAvailability.READY } -> text("button.blocked", "<#ff9f0f>Товары не принимаются")
            states.values.any { it != ContractBookAvailability.READY } -> text("button.mixed", "<#2bba43>Продать доступные товары")
            else -> text("button.sell", "<#2bba43>Продать")
        }
        if (desk.storage.pending) {
            groups += listOf(text("processing", "<#ff9f0f>Принимаем товары…"))
        } else {
            if (result != null && result.quantity > 0) {
                groups += listOf(text("success", "<#2bba43>Принято <quantity> шт. +<price> <white>💰</white>",
                    "quantity" to Component.text(result.quantity), "price" to Component.text(formatContractMoney(result.payout))))
                groups += result.items.map { (material, amount) ->
                    itemLine("accepted", "<#8c8c8c>• <#b8b8b8>Сдано: <#e6fff3><item> <#92bed8>× <quantity>", material, amount)
                }
            }
            if (result?.review == true) groups += listOf(text("review", "<#c42323>Сдача остановлена для проверки. Не повторяйте её до разбора администратором."))
            result?.rejection?.let { groups += listOf(it) }
            offered.forEach { (material, stacks) ->
                val item = if (result == null) itemLine("offered", "<#8c8c8c>• <#b8b8b8>К сдаче: <#e6fff3><item> <#92bed8>× <quantity>", material, stacks.sumOf { it.amount })
                    else itemLine("remaining", "<#8c8c8c>• <#b8b8b8>Осталось: <#e6fff3><item> <#92bed8>× <quantity>", material, stacks.sumOf { it.amount })
                val state = states.getValue(material)
                val reason = if (state == ContractBookAvailability.UNAVAILABLE)
                    text("button.unavailable", "<#ff9f0f>Приём сейчас недоступен. Попробуйте позже.")
                else TextUtils.mm(config.string("defaults.availability.${state.messageKey}", state.fallback))
                groups += listOf(item, text("button.state", "  <state>", "state" to reason))
            }
            if (result == null && offered.isEmpty()) groups += listOf(if (orders.isEmpty())
                text("empty", "<#ff9f0f>Сейчас открытых заказов нет.")
                else text("instruction", "<#e6fff3>Слева — заказы. Справа — товары для сдачи."))
            if (result?.review != true && ContractBookAvailability.READY in states.values)
                groups += listOf(text("button.action", "<#8c8c8c>[<#2bba43>▶<#8c8c8c>] <#2bba43>ЛКМ <#b8b8b8>— сдать доступные товары"))
        }
        return ContractsMenus.item(desk.menu, "sell", PaperMenuItemRenderContext(
            values = mapOf("name" to name, "status" to name,
                "page" to if (pages > 1) Component.text("Страница ${desk.page + 1} / $pages") else Component.empty()),
            repeats = mapOf("details" to groups.filter { it.isNotEmpty() }
                .flatMap { listOf(Component.empty()) + it }.map { mapOf("line" to it) }),
        ))
    }

    private fun itemLine(key: String, fallback: String, material: Material, amount: Int) = text("button.$key", fallback,
        "item" to Component.translatable(material.translationKey()), "quantity" to Component.text(amount))

    internal fun matchingOrders(offered: ItemStack, orders: List<ResourceContractPlayerView>) = orders.filter { view ->
        PaperContractItems.material(view.contract.itemKey)?.let { material ->
            PaperContractItems.isPlainExact(offered, material, view.contract.itemKey)
        } == true
    }
    internal fun nextQuote(offered: ItemStack, amount: Int, orders: List<ResourceContractPlayerView>,
        quote: (String, Int) -> ContractSubmissionQuote?): ContractSubmissionQuote? =
        matchingOrders(offered, orders).mapNotNull { view ->
            // A shared network budget may fund a smaller batch than the order's
            // own budget. Never sweep unoffered items to fill that batch.
            quote(view.contract.id, minOf(amount, view.maxSubmissionQuantity))
                ?.let { view to it }
        }.sortedWith { a, b ->
            val price = (b.second.payoutMinor * a.second.quantity).compareTo(a.second.payoutMinor * b.second.quantity)
            if (price != 0) price else (a.first.contract.itemKey == PaperContractItems.ANY_RAW_FISH)
                .compareTo(b.first.contract.itemKey == PaperContractItems.ANY_RAW_FISH)
        }.firstOrNull()?.second

    private fun sell(desk: Desk) {
        if (desk.storage.pending || !desk.session.isOpen) return
        if (desk.storage.snapshot().all { it == null }) { render(desk); return }
        if (!ContractOriginGate.canSubmit(desk.player, desk.group)) {
            feedback(desk.player, "unavailable", "<#ff9f0f>Снова обратитесь к NPC, чтобы сдать товары.")
            return
        }
        if (desk.player.itemOnCursor.let { !it.type.isAir && it.amount > 0 }) {
            feedback(desk.player, "cursor-not-empty", "<#ff9f0f>Сначала положите предмет с курсора в инвентарь.")
            return
        }
        desk.storage.pending = true
        desk.result = null
        processing += desk.player.uniqueId
        render(desk)
        val acceptedItems = linkedMapOf<Material, Int>()
        fun finish(accepted: Long, payout: Long, review: Boolean = false, rejection: Component? = null) {
            desk.storage.pending = false
            processing -= desk.player.uniqueId
            desk.result = SaleResult(accepted, payout, acceptedItems.toMap(), desk.storage.snapshot(), review, rejection)
            if (desk.player.isOnline) {
                if (accepted > 0) desk.player.sendMessage(text("success", "<#2bba43>Принято <quantity> шт. +<price> <white>💰</white>",
                    "quantity" to Component.text(accepted), "price" to Component.text(formatContractMoney(payout))))
                if (review) feedback(desk.player, "review", "<#c42323>Сдача остановлена для проверки. Не повторяйте её до разбора администратором.")
                else if (rejection != null) desk.player.sendActionBar(rejection)
                else if (desk.storage.snapshot().any { it != null }) feedback(desk.player, "not-accepted", "<#ff9f0f>Остаток не принят: проверьте лимиты заказов.")
                if (desk.session.isOpen) render(desk) else returnItems(desk)
            }
        }
        fun submitNext(accepted: Long, payout: Long) {
            val orders = views(desk.player, desk.group)
            val offeredItems = desk.storage.snapshot().filterNotNull()
            val quote = offeredItems.firstNotNullOfOrNull { offered ->
                nextQuote(offered, offeredItems.filter { it.isSimilar(offered) }.sumOf { it.amount }, orders)
                    { id, quantity -> ContractsManager.quote(desk.player, id, quantity) }
            }
            if (quote == null) { finish(accepted, payout); return }
            val view = orders.first { it.contract.id == quote.contractId }
            val prepared = try {
                desk.storage.prepare(view.contract.itemKey, quote.quantity)
            } catch (failure: Exception) {
                ContractsRuntime.logWarning("Could not save contract desk before submission for ${desk.player.uniqueId}", failure)
                finish(accepted, payout, rejection = text("save-failed", "<#ff9f0f>Не удалось сохранить инвентарь. Попробуйте ещё раз."))
                return
            }
            if (prepared == null) { finish(accepted, payout); return }
            ContractsManager.submit(desk.player, quote, offeredInventory = prepared).whenCompleteSync(requireNotNull(tasks)) { outcome, failure ->
                if (failure != null || outcome is ContractSubmissionOutcome.ManualReview || outcome is ContractSubmissionOutcome.Unavailable)
                    finish(accepted, payout, review = true)
                else if (outcome is ContractSubmissionOutcome.Committed) {
                    val remaining = desk.storage.snapshot().filterNotNull().groupBy { it.type }
                    offeredItems.groupBy { it.type }.forEach { (material, stacks) ->
                        val removed = stacks.sumOf { it.amount } - remaining[material].orEmpty().sumOf { it.amount }
                        if (removed > 0) acceptedItems[material] = acceptedItems.getOrDefault(material, 0) + removed
                    }
                    desk.session.refresh()
                    submitNext(accepted + outcome.receipt.quantity, payout + outcome.receipt.payoutMinor)
                } else finish(accepted, payout, rejection = outcome?.let { ContractPlayerMessages.render(it, config, desk.group) })
            }
        }
        submitNext(0L, 0L)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun afterTransfer(event: InventoryClickEvent) {
        val desk = sessions[event.whoClicked.uniqueId] ?: return
        if (event.view.topInventory !== desk.session.inventory) return
        desk.storage.persistTransfer()
        refreshAfterTransfer(desk)
    }
    private fun refreshAfterTransfer(desk: Desk) {
        tasks?.runLater(1L) {
            if (sessions[desk.player.uniqueId] === desk && desk.session.isOpen) render(desk)
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun close(event: InventoryCloseEvent) {
        val desk = sessions[event.player.uniqueId] ?: return
        if (event.inventory !== desk.session.inventory) return
        sessions.remove(event.player.uniqueId, desk)
        returnItems(desk)
    }
    private fun returnItems(desk: Desk) {
        desk.storage.returnItems()
        if (!desk.storage.pending && desk.storage.snapshot().any { it != null }) desk.player.sendMessage(text("return-full",
            "<#ff9f0f>Инвентарь полон. Остаток сохранён у этого NPC — заберите его при следующем открытии."))
    }
    private fun feedback(player: Player, key: String, fallback: String) = player.sendActionBar(text(key, fallback))
    private fun context(vararg values: Pair<String, Component>) = PaperMenuItemRenderContext(values = mapOf(*values))
    private fun text(key: String, fallback: String, vararg values: Pair<String, Component>): Component =
        TextUtils.mm(config.string("desk.$key", fallback), TagResolver.resolver(values.map { (name, value) -> Placeholder.component(name, value) }))
}

internal fun contractPriceGrowth(base: Long, current: Long): String {
    return ru.arc.contracts.api.ArcContractPresentation.priceGrowth(base, current)
}

package ru.arc.contracts

import ru.arc.config.Config
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuConfigurationParser
import ru.arc.paper.menu.PaperMenuTextContract
import java.nio.file.Path

/** ArcContracts-owned validation for layouts retained in the legacy ARC menu file. */
internal object ContractsMenuSchema {
    const val RESOURCE = "guis/menus.yml"
    private const val LAYOUT_ROOT = "menus.layouts"
    private const val TEMPLATE_ROOT = "menus.templates"

    val CONTRACTS_LIST = MenuId.of("contracts-list")
    val CONTRACTS_DETAIL = MenuId.of("contracts-detail")
    val CONTRACT_DESKS = (3..6).associateWith { MenuId.of("contracts-desk-$it") }
    val CONTRACT_ORDERS = MenuRegionId.of("orders")
    val CONTRACT_DEPOSIT = MenuRegionId.of("contract-deposit")
    val CONTRACT_DESK_ORDERS = MenuRegionId.of("contract-orders")

    private val layoutNames = CONTRACT_DESKS.values.mapTo(linkedSetOf()) { it.value }.apply {
        add(CONTRACTS_LIST.value)
        add(CONTRACTS_DETAIL.value)
    }
    /** Templates rendered directly by the contract screens instead of through a layout reference. */
    private val dynamicTemplates = setOf(
        "contracts-order",
        "contracts-tab",
        "contracts-refresh",
        "contracts-page",
        "contracts-quantity-preset",
        "contracts-track",
        "contracts-next-order",
        "contract-desk-order",
        "contract-desk-empty",
    )

    private fun elements(vararg ids: String) = ids.mapTo(linkedSetOf(), MenuElementId::of)

    val contracts: Map<MenuId, MenuContract> = linkedMapOf(
        *CONTRACT_DESKS.values.map { menu ->
            menu to MenuContract(
                requiredElements = if (menu == CONTRACT_DESKS.getValue(6)) elements("sell", "previous", "next", "weekly-top")
                    else elements("sell", "previous", "next"),
                requiredRegions = setOf(CONTRACT_DEPOSIT, CONTRACT_DESK_ORDERS),
            )
        }.toTypedArray(),
        CONTRACTS_LIST to MenuContract(
            requiredElements = elements("info"),
            optionalElements = elements("empty", "tab-all", "tab-forge", "tab-bank", "tab-guild", "refresh", "previous", "next"),
            requiredRegions = setOf(CONTRACT_ORDERS),
        ),
        CONTRACTS_DETAIL to MenuContract(
            requiredElements = elements("resource", "info", "payout", "quantity", "back", "confirm"),
            optionalElements = elements("quantity-min", "quantity-stack", "quantity-max", "track", "next-order"),
        ),
    )

    val textContracts: Map<String, PaperMenuTextContract> = mapOf(
        "contract-desk-info" to PaperMenuTextContract(values = setOf("status", "page")),
        "contract-desk-order" to PaperMenuTextContract(
            values = setOf("name", "accepted", "price", "base", "growth", "rank", "remaining", "state", "accepted-total", "target", "personal-accepted"),
            repeats = mapOf("weekly-leaders" to setOf("line")),
        ),
        "contract-desk-sell" to PaperMenuTextContract(
            values = setOf("name", "status", "page"), repeats = mapOf("details" to setOf("line")),
        ),
        "contract-desk-empty" to PaperMenuTextContract(),
        "contract-desk-previous" to PaperMenuTextContract(),
        "contract-desk-next" to PaperMenuTextContract(),
        "contract-desk-weekly-top" to PaperMenuTextContract(),
        "contracts-list-info" to PaperMenuTextContract(values = setOf("heading", "description-one", "description-two")),
        "contracts-list-empty" to PaperMenuTextContract(values = setOf("empty")),
        "contracts-order" to PaperMenuTextContract(
            values = setOf(
                "contract-name", "available", "remaining", "target", "player-remaining", "payout", "cap-bonus",
                "payout-bonus", "ends-at", "action", "can-submit-quantity", "batch-payout", "personal-accepted", "group-name",
            ),
        ),
        "contracts-tab" to PaperMenuTextContract(values = setOf("label"), flags = setOf("selected")),
        "contracts-refresh" to PaperMenuTextContract(),
        "contracts-page" to PaperMenuTextContract(values = setOf("page", "pages", "label"), flags = setOf("available")),
        "contracts-quantity-preset" to PaperMenuTextContract(values = setOf("quantity", "label"), flags = setOf("available")),
        "contracts-track" to PaperMenuTextContract(values = setOf("target", "status", "action"), flags = setOf("tracked")),
        "contracts-next-order" to PaperMenuTextContract(values = setOf("contract-name"), flags = setOf("available")),
        "contracts-detail-resource" to PaperMenuTextContract(values = setOf("contract-name", "available", "remaining", "player-remaining", "cap-bonus")),
        "contracts-detail-info" to PaperMenuTextContract(
            values = setOf("heading", "accepted", "target", "contributors", "personal-accepted", "purpose", "result", "can-submit-quantity"),
            flags = setOf("has-result"),
        ),
        "contracts-detail-payout" to PaperMenuTextContract(values = setOf("payout", "per-unit", "payout-bonus")),
        "contracts-detail-quantity" to PaperMenuTextContract(values = setOf("selected", "minimum", "maximum")),
        "contracts-detail-back" to PaperMenuTextContract(),
        "contracts-detail-confirm" to PaperMenuTextContract(
            values = setOf("selected", "payout", "unavailable-reason"),
            flags = setOf("can-submit", "origin-allowed", "quote-available"),
        ),
        "background" to PaperMenuTextContract(),
    )

    fun load(legacyDataRoot: Path): PaperMenuConfiguration {
        val config = Config(legacyDataRoot, RESOURCE)
        config.keys(LAYOUT_ROOT)
            .filterNot(layoutNames::contains)
            .forEach { config.removeKey("$LAYOUT_ROOT.$it") }

        val referencedTemplates = buildSet {
            layoutNames.forEach { layout ->
                val path = "$LAYOUT_ROOT.$layout"
                config.stringOrNull("$path.background.template")?.let(::add)
                config.keys("$path.elements").forEach { element ->
                    config.stringOrNull("$path.elements.$element.template")?.let(::add)
                }
                config.keys("$path.legend").forEach { symbol ->
                    config.stringOrNull("$path.legend.$symbol.template")?.let(::add)
                }
            }
        } + dynamicTemplates
        config.keys(TEMPLATE_ROOT)
            .filterNot(referencedTemplates::contains)
            .forEach { config.removeKey("$TEMPLATE_ROOT.$it") }

        return PaperMenuConfigurationParser.require(
            config = config,
            layoutRoot = LAYOUT_ROOT,
            templateRoot = TEMPLATE_ROOT,
            contracts = contracts,
            requiredTemplates = dynamicTemplates,
            textContracts = textContracts,
        )
    }
}

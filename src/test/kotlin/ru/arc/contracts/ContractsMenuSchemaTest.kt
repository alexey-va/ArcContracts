package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuTemplateId
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class ContractsMenuSchemaTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "contract book keeps tabs separate and renders readable amount and disabled preset states" {
        val configuration = loadContractsMenuConfiguration()
        val layout = configuration.catalog.require(ContractsMenuSchema.CONTRACTS_LIST)
        layout.rows shouldBe 6
        layout.region("orders").size shouldBe 21
        val slots = layout.elements.keys.map { layout.slot(it).index }
        slots.distinct().size shouldBe slots.size

        val factory = PaperMenuItemFactory()
        val values = ContractsMenuSchema.textContracts.getValue("contracts-order").values
            .associateWith { Component.text("64") }
        val card = factory.create(
            configuration.template(MenuTemplateId.of("contracts-order")),
            PaperMenuItemRenderContext(values = values),
        )
        card.itemMeta.displayName()!!.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        card.itemMeta.lore()!!.forEach { it.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE }
        val plain = PlainTextComponentSerializer.plainText()
        card.itemMeta.lore()!!.any { plain.serialize(it).contains("примерно") } shouldBe true

        val preset = factory.create(
            configuration.template(MenuTemplateId.of("contracts-quantity-preset")),
            PaperMenuItemRenderContext(
                values = mapOf("label" to Component.text("Максимум"), "quantity" to Component.text(0)),
            ),
        )
        preset.itemMeta.lore()!!.any { plain.serialize(it).contains("ЛКМ") } shouldBe false
    }

    "NPC desks keep the six-row layout and render the legacy deposit templates" {
        val configuration = loadContractsMenuConfiguration()
        val layout = configuration.catalog.require(ContractsMenuSchema.CONTRACT_DESKS.getValue(6))
        layout.rows shouldBe 6
        layout.region(ContractsMenuSchema.CONTRACT_DEPOSIT).map { it.index } shouldBe
            (0 until 6).flatMap { row -> (5..8).map { row * 9 + it } }
        layout.region(ContractsMenuSchema.CONTRACT_DESK_ORDERS).map { it.index } shouldBe
            (0 until 5).flatMap { row -> (0..3).map { row * 9 + it } } + listOf(46, 47)
        layout.slot("sell").index shouldBe 4
        layout.slot("previous").index shouldBe 45
        layout.slot("next").index shouldBe 48
        layout.backgroundTemplate shouldBe MenuTemplateId.of("background")
        layout.pagination shouldBe null

        val factory = PaperMenuItemFactory()
        for ((template, model) in listOf("contract-desk-previous" to 11009, "contract-desk-next" to 11008)) {
            val arrow = factory.create(configuration.template(MenuTemplateId.of(template)), PaperMenuItemRenderContext())
            arrow.type shouldBe Material.BLUE_STAINED_GLASS_PANE
            arrow.legacyCustomModelData() shouldBe model
        }
        val filler = factory.create(
            configuration.template(MenuTemplateId.of("contract-desk-empty")),
            PaperMenuItemRenderContext(),
        )
        filler.type shouldBe Material.LIGHT_GRAY_STAINED_GLASS_PANE
        filler.legacyCustomModelData() shouldBe null

        val item = factory.create(
            configuration.template(MenuTemplateId.of("contract-desk-order")),
            PaperMenuItemRenderContext(values = mapOf(
                "name" to Component.text("Любая сырая рыба"),
                "price" to Component.text("1.20"),
                "remaining" to Component.text("64"),
                "state" to Component.text("Положите товар в пустой ряд"),
                "base" to Component.text("1.20"),
                "growth" to Component.text("+25%"),
                "rank" to Component.text("+0%"),
                "accepted" to Component.text("Треска, лосось, тропическая рыба и иглобрюх"),
            )),
        ).withType(Material.COD)
        item.type shouldBe Material.COD
        item.itemMeta.displayName()!!.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        item.itemMeta.lore()!!.forEach { row -> row.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE }
        val text = item.itemMeta.lore()!!.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }
        text.contains("1.20 💰") shouldBe true
        text.contains("64 шт.") shouldBe true
        text.contains("+25% от базовой") shouldBe true
    }
})

private fun loadContractsMenuConfiguration() = run {
    val root = Files.createTempDirectory("arc-contract-menu-schema")
    val target = root.resolve(ContractsMenuSchema.RESOURCE)
    Files.createDirectories(target.parent)
    ContractsMenuSchemaTest::class.java.classLoader
        .getResourceAsStream(ContractsMenuSchema.RESOURCE)
        .use { source -> Files.copy(checkNotNull(source), target) }
    ContractsMenuSchema.load(root)
}

@Suppress("DEPRECATION")
private fun ItemStack.legacyCustomModelData(): Int? = itemMeta.customModelData

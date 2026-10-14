package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
        val root = Files.createTempDirectory("arc-contract-menu-schema")
        val target = root.resolve(ContractsMenuSchema.RESOURCE)
        Files.createDirectories(target.parent)
        javaClass.classLoader.getResourceAsStream(ContractsMenuSchema.RESOURCE).use { source ->
            Files.copy(checkNotNull(source), target)
        }

        val configuration = ContractsMenuSchema.load(root)
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
})

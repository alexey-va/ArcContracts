package ru.arc.contracts

import net.kyori.adventure.text.Component
import ru.arc.text.LocaleCatalog
import ru.arc.text.LocalizedMiniMessage

/** Small adapter that preserves the legacy Russian fallback while using core's safe Component renderer. */
internal fun localizedContractsText(
    messagePath: String,
    configuredText: String,
    fallback: String,
    values: Map<String, Component> = emptyMap(),
): Component {
    val text = values.keys.fold(configuredText) { current, name -> current.replace("{$name}", "<$name>") }
    val fallbackText = values.keys.fold(fallback) { current, name -> current.replace("{$name}", "<$name>") }
    val catalog = object : LocaleCatalog {
        override fun scalar(path: String): String? = text.takeIf { path == messagePath }

        override fun lines(path: String): List<String>? = null
    }
    return LocalizedMiniMessage(
        catalogs = mapOf("ru-RU" to catalog),
        defaultLocale = { "ru-RU" },
        missingMessage = { fallbackText },
    ).render(messagePath, "ru-RU", values)
}

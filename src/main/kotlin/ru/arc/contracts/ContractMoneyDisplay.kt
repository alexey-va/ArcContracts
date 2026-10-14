package ru.arc.contracts

import ru.arc.contracts.api.ArcContractPresentation

/**
 * Formats exact minor currency units without hiding meaningful cents or
 * retaining redundant trailing zeroes on player-facing contract surfaces.
 */
internal fun formatContractMoney(minor: Long): String =
    ArcContractPresentation.formatMoney(minor)

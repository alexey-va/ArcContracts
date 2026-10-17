package ru.arc.contracts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class ContractsCompletionTest : StringSpec({
    val commands = ContractsCommands(mockk())

    "console is offered only its supported status action" {
        val console = mockk<CommandSender>()
        commands.complete(console, arrayOf("")) shouldBe listOf("status")
        commands.complete(console, arrayOf("sta")) shouldBe listOf("status")
        commands.complete(console, arrayOf("donate", "")) shouldBe emptyList()
        commands.complete(console, arrayOf("launch", "")) shouldBe emptyList()
    }

    "player completions keep public actions and reject terminal or unknown branches" {
        val player = mockk<Player>()
        commands.complete(player, arrayOf("OP")) shouldBe listOf("open")
        commands.complete(player, arrayOf("unknown", "")) shouldBe emptyList()
        commands.complete(player, arrayOf("status", "")) shouldBe emptyList()
        commands.complete(player, arrayOf("pass", "dungeon", "")) shouldBe emptyList()
    }
})

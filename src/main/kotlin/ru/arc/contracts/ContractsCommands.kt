package ru.arc.contracts

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicesManager
import ru.arc.contracts.api.ArcContractsDungeonLaunch
import ru.arc.core.Tasks
import ru.arc.util.TextUtils
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The domain portion of `/arc contracts`; root command metadata stays in ARC. */
internal class ContractsCommands(private val services: ServicesManager) {
    fun execute(sender: CommandSender, args: Array<String>) {
        val action = args.firstOrNull()?.lowercase() ?: if (sender is Player) "open" else "status"
        when (action) {
            "status", "list" -> showBoard(sender)
            "open", "menu", "меню" -> openBoard(sender, args)
            "donate", "вклад" -> donateCash(sender, args)
            "pass", "пропуск" -> buyPass(sender, args)
            "launch", "экспедиция" -> launchDungeon(sender, args)
            "trophy", "трофей" -> submitTrophy(sender, args)
            else -> sendUsage(sender)
        }
    }

    fun complete(sender: CommandSender, args: Array<String>): List<String> = when (args.size) {
        1 -> listOf("open", "status", "donate", "pass", "launch", "trophy").matching(args[0])
        2 -> when (args[0].lowercase()) {
            "donate" -> ContractsManager.seasonProjectStageIds().matching(args[1])
            "pass", "launch" -> ContractsManager.seasonDungeonContractIds().matching(args[1])
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun openBoard(sender: CommandSender, args: Array<String>) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(TextUtils.mm("<red>Открыть книгу заказов может только игрок."))
            return
        }
        NpcContractsGui.openList(player, args.getOrNull(1)?.trim()?.lowercase() ?: "all")
    }

    private fun showBoard(sender: CommandSender) {
        val views = ContractsManager.currentViews()
        sender.sendMessage(ContractsRuntime.host.commandText("contracts.header", "<gold>═══ Доска контрактов ═══"))
        if (views.isEmpty()) {
            sender.sendMessage(
                ContractsRuntime.host.commandText(
                    "contracts.calibration",
                    "<gray>Идёт калибровка экономики. Первые ресурсные заказы появятся после чистого периода наблюдений.",
                ),
            )
            val weeklyBudget = (ContractsManager.summary()["serverWeeklyBudgetMinor"] as? Number)?.toLong() ?: 0L
            sender.sendMessage(
                TextUtils.mm("<dark_gray>Недельный бюджет: <gray>${formatContractMoney(weeklyBudget)} <white>💰</white>"),
            )
            return
        }
        views.forEach { view ->
            sender.sendMessage(
                TextUtils.mm(
                    "<yellow>${escape(view.displayName)} <dark_gray>[${status(view.status)}]\n" +
                        "<gray>  ${view.itemKey}: <white>${view.remainingQuantity}/${view.targetQuantity}" +
                        " <gray>· <green>${formatContractMoney(view.payoutMinorPerUnit)} <white>💰</white><gray>/шт." +
                        " <gray>· до <white>${formatTime(view.windowEndsAt)}",
                ),
            )
        }
        if (!ContractsManager.submissionsEnabled()) {
            sender.sendMessage(
                ContractsRuntime.host.commandText(
                    "contracts.observe-only",
                    "<yellow>Сдача предметов пока выключена: режим калибровки.",
                ),
            )
        }
    }

    private fun donateCash(sender: CommandSender, args: Array<String>) {
        val player = requireSeasonPlayer(sender) ?: return
        val stageId = args.getOrNull(1)?.trim()?.lowercase()
        val amountMinor = args.getOrNull(2)?.let(::parseMoneyMinor)
        if (stageId == null || amountMinor == null) {
            sendUsage(sender)
            return
        }
        player.sendMessage(TextUtils.mm("<gray>Фиксирую денежный вклад в общий проект…"))
        ContractsManager.submitSeasonMoney(player.uniqueId, SeasonMoneyActionRequest.ProjectCash(stageId, amountMinor))
            .whenComplete { outcome, failure ->
                syncReply(player) {
                    if (failure != null || outcome == null) seasonFailure() else renderSeasonMoneyOutcome(outcome)
                }
            }
    }

    private fun buyPass(sender: CommandSender, args: Array<String>) {
        val player = requireSeasonPlayer(sender) ?: return
        val dungeonId = args.getOrNull(1)?.trim()?.lowercase()
        if (dungeonId == null) {
            sendUsage(sender)
            return
        }
        player.sendMessage(TextUtils.mm("<gray>Проверяю доступ и оплачиваю одноразовый вход…"))
        ContractsManager.submitSeasonMoney(player.uniqueId, SeasonMoneyActionRequest.DungeonAdmission(dungeonId))
            .whenComplete { outcome, failure ->
                syncReply(player) {
                    if (failure != null || outcome == null) seasonFailure() else renderSeasonMoneyOutcome(outcome)
                }
            }
    }

    private fun submitTrophy(sender: CommandSender, args: Array<String>) {
        val player = requireSeasonPlayer(sender, trophy = true) ?: return
        val quantity = args.getOrNull(1)?.toIntOrNull()
        val itemKey = PaperSeasonTrophyItems.identity(player.inventory.itemInMainHand)
        val stageId = ContractsManager.seasonCompletionStageId()
        if (quantity == null || quantity <= 0 || itemKey == null || stageId == null) {
            player.sendMessage(TextUtils.mm("<yellow>Возьми связанный трофей в основную руку и укажи количество."))
            return
        }
        player.sendMessage(TextUtils.mm("<gray>Передаю точные связанные трофеи в музей…"))
        ContractsManager.submitSeasonTrophy(player.uniqueId, stageId, itemKey, quantity)
            .whenComplete { outcome, failure ->
                syncReply(player) {
                    if (failure != null || outcome == null) seasonFailure() else renderTrophyOutcome(outcome)
                }
            }
    }

    private fun launchDungeon(sender: CommandSender, args: Array<String>) {
        val player = requireSeasonPlayer(sender) ?: return
        val dungeonId = args.getOrNull(1)?.trim()?.lowercase()
        val blueprintWorld = dungeonId?.let(ContractsManager::seasonDungeonBlueprintWorld)
        val launch = services.load(ArcContractsDungeonLaunch::class.java)
        if (dungeonId == null || blueprintWorld == null || launch == null || !launch.canLaunch(player, blueprintWorld)) {
            player.sendMessage(TextUtils.mm("<yellow>Этот сезонный данж сейчас недоступен для запуска."))
            return
        }
        val participants = launch.localParticipants(player.uniqueId)
        if (participants == null) {
            player.sendMessage(TextUtils.mm("<yellow>Экспедицию запускает лидер пати."))
            return
        }
        player.sendMessage(TextUtils.mm("<gray>Проверяю одноразовые пропуски участников…"))
        ContractsManager.prepareSeasonDungeonLaunch(dungeonId, participants).whenComplete { outcome, failure ->
            Tasks.scheduler.runSync(
                Runnable {
                    if (!player.isOnline) {
                        if (outcome is SeasonDungeonLaunchPreparationOutcome.Ready) {
                            ContractsManager.cancelSeasonDungeonLaunch(outcome.reservation.token.tokenId)
                        }
                        return@Runnable
                    }
                    if (failure != null || outcome !is SeasonDungeonLaunchPreparationOutcome.Ready) {
                        val message = if (outcome is SeasonDungeonLaunchPreparationOutcome.Rejected) {
                            "<yellow>Экспедиция не запущена: <gray>${escape(outcome.code)}"
                        } else {
                            "<red>Запуск экспедиции сейчас недоступен."
                        }
                        player.sendMessage(TextUtils.mm(message))
                        return@Runnable
                    }
                    try {
                        if (!launch.canLaunch(player, blueprintWorld)) {
                            ContractsManager.cancelSeasonDungeonLaunch(outcome.reservation.token.tokenId)
                            player.sendMessage(TextUtils.mm("<yellow>Данж перестал быть доступен; пропуски освобождены."))
                            return@Runnable
                        }
                        launch.launch(player, blueprintWorld)
                    } catch (_: Throwable) {
                        ContractsManager.cancelSeasonDungeonLaunch(outcome.reservation.token.tokenId)
                        player.sendMessage(TextUtils.mm("<red>EliteMobs не принял запуск; пропуски освобождаются."))
                    }
                },
            )
        }
    }

    private fun requireSeasonPlayer(sender: CommandSender, trophy: Boolean = false): Player? {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(TextUtils.mm("<red>Это действие доступно только игроку."))
            return null
        }
        val enabled = if (trophy) ContractsManager.seasonTrophyEnabled() else ContractsManager.seasonMoneyEnabled()
        if (!enabled) {
            player.sendMessage(TextUtils.mm("<yellow>Сезонные операции пока выключены: идёт безопасная калибровка."))
            return null
        }
        return player
    }

    private fun syncReply(player: Player, message: () -> Component) {
        Tasks.scheduler.runSync(Runnable { if (player.isOnline) player.sendMessage(message()) })
    }

    private fun renderSeasonMoneyOutcome(outcome: SeasonMoneyActionOutcome): Component =
        TextUtils.mm(
            when (outcome) {
                is SeasonMoneyActionOutcome.Committed ->
                    "<green>Операция учтена: <white>${formatContractMoney(outcome.receipt.amountMinor)} <white>💰</white>"
                is SeasonMoneyActionOutcome.Duplicate -> "<yellow>Эта операция уже учтена; повторного списания не было."
                is SeasonMoneyActionOutcome.Rejected -> "<yellow>Операция отклонена: <gray>${seasonRejection(outcome.reason)}"
                is SeasonMoneyActionOutcome.Cancelled -> "<yellow>Списание не выполнено; баланс не изменился."
                is SeasonMoneyActionOutcome.ManualReview -> seasonFailureText()
                is SeasonMoneyActionOutcome.Unavailable -> "<red>Сезонный сервис сейчас недоступен; деньги не списывались."
            },
        )

    private fun renderTrophyOutcome(outcome: SeasonTrophyContributionOutcome): Component =
        TextUtils.mm(
            when (outcome) {
                is SeasonTrophyContributionOutcome.Committed -> "<green>Музей принял <white>${outcome.receipt.quantity}</white> троф."
                is SeasonTrophyContributionOutcome.Duplicate -> "<yellow>Этот вклад уже учтён; трофеи повторно не снимались."
                is SeasonTrophyContributionOutcome.Rejected -> "<yellow>Трофеи не приняты: <gray>${trophyRejection(outcome.reason)}"
                is SeasonTrophyContributionOutcome.Cancelled -> "<yellow>Инвентарь изменился; трофеи не сняты."
                is SeasonTrophyContributionOutcome.ManualReview -> seasonFailureText()
                is SeasonTrophyContributionOutcome.Unavailable -> "<red>Музей сейчас недоступен; трофеи не сняты."
            },
        )

    private fun seasonFailure() = TextUtils.mm(seasonFailureText())

    private fun seasonFailureText() =
        "<red>Операция остановлена для проверки. <gray>Не повторяй её до разбора администратором."

    private fun seasonRejection(reason: SeasonMoneyRejection): String = reason.label.replace('_', ' ')

    private fun trophyRejection(reason: SeasonTrophyContributionRejection): String = reason.label.replace('_', ' ')

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(
            TextUtils.mm("<gray>Использование: /arc contracts [open|status|donate <этап> <сумма>|pass <данж>|launch <данж>|trophy <количество>]"),
        )
    }

    private fun parseMoneyMinor(raw: String): Long? =
        runCatching {
            BigDecimal(raw.replace(',', '.')).setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2).longValueExact().takeIf { it > 0L }
        }.getOrNull()

    private fun formatTime(timestamp: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(timestamp))

    private fun escape(value: String): String = value.replace("<", "‹").replace(">", "›")

    private fun status(value: String): String = when (value) {
        "open" -> "открыт"
        "paused" -> "на паузе"
        "completed" -> "выполнен"
        "expired" -> "завершён"
        else -> "неизвестно"
    }

    private fun List<String>.matching(prefix: String): List<String> =
        filter { it.startsWith(prefix, ignoreCase = true) }.sorted()

    private companion object {
        val TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM HH:mm 'МСК'", java.util.Locale.forLanguageTag("ru-RU"))
                .withZone(ZoneId.of("Europe/Moscow"))
    }
}

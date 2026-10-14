package ru.arc.contractfixture

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.persistence.PersistentDataType
import org.bukkit.inventory.ItemStack
import ru.arc.contracts.*
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Properties
import java.util.UUID

/** CI-only artifact. The production shadow JAR never includes this source set. */
class ContractCrashFixturePlugin : JavaPlugin() {
    private data class Arm(val playerId: UUID, val phase: String)

    @Volatile private var armed: Arm? = null
    private lateinit var persistence: ContractSubmissionPersistence
    private lateinit var payment: ContractPaymentGateway

    override fun onEnable() {
        check(System.getenv("ARC_CONTRACT_CRASH_FIXTURE") == "1") { "Disposable crash fixture was not explicitly enabled" }
        check(server.ip == "127.0.0.1" && !server.onlineMode) { "Crash fixture requires the loopback offline CI server" }
        check(ContractsManager.currentViews().any { it.id == "e2e_stone" }) { "Synthetic contract missing" }
        dataFolder.mkdirs()
        val owner = ContractsManager::class.java.getDeclaredField("submissionCoordinator").apply { isAccessible = true }
        val original = requireNotNull(owner.get(ContractsManager)) as ContractSubmissionCoordinator
        persistence = port(original, "persistence")
        payment = port(original, "payment")
        val inventory: ContractInventoryGateway = port(original, "inventory")

        val observedPersistence = object : ContractSubmissionPersistence by persistence {
            override suspend fun persistJournal(record: ContractSubmissionJournalRecord) {
                if (record.status == ContractSubmissionJournalStatus.ITEMS_ESCROWED) {
                    // The storage removal is already durable, but the journal
                    // still records ITEM_REMOVAL_STARTED until this write.
                    haltAt(record.playerId, "removal_saved", record.submissionId)
                }
                persistence.persistJournal(record)
                val phase = when (record.status) {
                    ContractSubmissionJournalStatus.ITEM_REMOVAL_STARTED -> "removal_started"
                    ContractSubmissionJournalStatus.ITEMS_ESCROWED -> "escrow_saved"
                    ContractSubmissionJournalStatus.PAYMENT_FAILED -> "payment_failure_saved"
                    ContractSubmissionJournalStatus.PAID -> "paid_saved"
                    else -> null
                }
                if (phase != null) haltAt(record.playerId, phase, record.submissionId)
            }

            override suspend fun persistContract(definition: ResourceContractDefinition, state: ResourceContractState) {
                persistence.persistContract(definition, state)
                armed?.let { arm ->
                    val record = persistence.journalRecords().filter { it.playerId == arm.playerId.toString() }.maxByOrNull { it.createdAt }
                    if (record != null) haltAt(record.playerId, "contract_saved", record.submissionId)
                }
            }
        }
        val observedPayment = object : ContractPaymentGateway by payment {
            override suspend fun deposit(playerId: String, amountMinor: Long, reason: String): ContractPaymentEvidence {
                if (armed?.let { it.playerId.toString() == playerId && it.phase == "payment_failure_saved" } == true) {
                    return ContractPaymentEvidence(false, payment.balanceMinor(playerId), "fixture_provider_rejected")
                }
                val evidence = payment.deposit(playerId, amountMinor, reason)
                if (evidence.providerAccepted == true) {
                    val record = persistence.journalRecords().single { it.payoutReason == reason }
                    haltAt(playerId, "payment_succeeded", record.submissionId)
                }
                return evidence
            }
        }
        // NPC desk sales pass a prepared storage-backed inventory directly to
        // the coordinator, so the old player-inventory gateway hook cannot see
        // item removal on this public path.
        owner.set(ContractsManager, ContractSubmissionCoordinator(observedPersistence, inventory, observedPayment))
        logger.info("CONTRACT_CRASH_FIXTURE_READY")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return false
        if (!player.isOp || args.size != 2 || !args[1].matches(Regex("[a-z_]{1,40}"))) return false
        when (args[0]) {
            "arm" -> {
                check(args[1] in PHASES) { "Unknown phase" }
                check(armed == null) { "A crash is already armed" }
                armed = Arm(player.uniqueId, args[1])
                player.saveData()
                writeProperties("armed", mapOf("phase" to args[1], "playerId" to player.uniqueId.toString()))
                player.sendMessage("CONTRACT_CRASH_ARMED:${args[1]}")
            }
            "snapshot" -> {
                val records = persistence.journalRecords().filter { it.playerId == player.uniqueId.toString() }
                val crashFile = File(dataFolder, "crashed.properties")
                val crash = Properties()
                if (crashFile.isFile) crashFile.inputStream().use(crash::load)
                val crashMatchesArm = armed?.let { it.playerId == player.uniqueId && it.phase == crash.getProperty("phase") } ?: true
                val crashSubmissionId = crash.getProperty("submissionId").takeIf { crashMatchesArm }
                val record = crashSubmissionId?.let { id -> records.singleOrNull { it.submissionId == id } }
                val view = ContractsManager.currentViews().single { it.id == "e2e_stone" }
                val cursor = player.openInventory.cursor.takeUnless { it.type.isAir }
                writeProperties(args[1], mapOf(
                    "records" to records.size.toString(),
                    "items" to player.inventory.contents.filterNotNull().filter { it.type == Material.STONE }.sumOf { it.amount }.toString(),
                    "deskItems" to deskStoneCount(player).toString(),
                    "cursorItems" to (cursor?.amount ?: 0).toString(),
                    "status" to (record?.status?.label ?: "none"),
                    "reviewReason" to (record?.reviewReason?.label ?: "none"),
                    "quantity" to (record?.acceptedQuantity ?: 0).toString(),
                    "payoutMinor" to (record?.payoutMinor ?: 0).toString(),
                    "submissionId" to (record?.submissionId ?: "none"),
                    "acceptedQuantity" to view.acceptedQuantity.toString(),
                    "spentMinor" to view.spentMinor.toString(),
                    "reservedMinor" to view.reservedMinor.toString(),
                    "reservedQuantity" to view.reservedQuantity.toString(),
                    "manualReviewCount" to ContractsManager.journalSummary().manualReviewCount.toString(),
                ))
                player.sendMessage("CONTRACT_CRASH_SNAPSHOT:${args[1]}")
            }
            else -> return false
        }
        return true
    }

    private fun haltAt(playerId: String, phase: String, submissionId: String) {
        val arm = armed ?: return
        if (arm.playerId.toString() != playerId || arm.phase != phase) return
        writeProperties("crashed", mapOf(
            "phase" to phase,
            "submissionId" to submissionId,
            "playerId" to playerId,
            "processId" to ProcessHandle.current().pid().toString(),
        ))
        Runtime.getRuntime().halt(86)
    }

    private fun writeProperties(name: String, values: Map<String, String>) {
        FileOutputStream(File(dataFolder, "$name.properties")).use { out ->
            Properties().apply { putAll(values) }.store(out, "Synthetic contract crash fixture")
            out.fd.sync()
        }
    }

    private fun deskStoneCount(player: Player): Int {
        val bytes = player.persistentDataContainer.get(
            NamespacedKey("arc", "contract_desk_spawn"),
            PersistentDataType.BYTE_ARRAY,
        ) ?: return 0
        require(bytes.size <= 256 * 1024)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val first = input.readInt()
            val expanded = first == -24
            val size = if (expanded) 24 else 9
            var stones = 0
            repeat(size) { slot ->
                val payloadSize = if (expanded || slot > 0) input.readInt() else first
                if (payloadSize == -1) return@repeat
                require(payloadSize in 1..65536 && payloadSize <= input.available())
                val item = ItemStack.deserializeBytes(input.readNBytes(payloadSize))
                if (item.type == Material.STONE) stones += item.amount
            }
            require(input.available() == 0)
            stones
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> port(coordinator: ContractSubmissionCoordinator, name: String): T =
        coordinator.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(coordinator) as T

    companion object {
        private val PHASES = setOf("removal_started", "removal_saved", "escrow_saved", "payment_succeeded", "payment_failure_saved", "paid_saved", "contract_saved")
    }
}

package ru.arc.contracts

import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.contracts.api.ArcContractsService
import java.util.UUID

/** Stable Java-shaped adapter; implementation-owned domain values never cross ServicesManager. */
internal class ArcContractsServiceProvider(
    private val plugin: ArcContractsPlugin,
) : ArcContractsService {
    private val commands = ContractsCommands(plugin.server.servicesManager)

    override fun isAvailable(): Boolean = ContractsManager.isAvailable()

    override fun isEnabled(): Boolean = ContractsManager.enabled()

    override fun reload() = plugin.reloadContracts()

    override fun seasonCatalogSchema(): Int = ContractsConfig.SEASON_CATALOG_SCHEMA_VERSION

    override fun auditSnapshotJson(): String = ContractsRuntime.gson.toJson(ContractsManager.summary())

    override fun execute(sender: CommandSender, args: Array<String>) = commands.execute(sender, args)

    override fun complete(sender: CommandSender, args: Array<String>): List<String> = commands.complete(sender, args)

    override fun boardSnapshot(): ArcContractsService.ContractBoardSnapshot {
        val summary = ContractsManager.summary()
        return ArcContractsService.ContractBoardSnapshot(
            ContractsManager.mode().toApi(),
            summary["enabled"] as? Boolean ?: false,
            ContractsManager.isLeader(),
            ContractsManager.submissionsEnabled(),
            (summary["serverWeeklyBudgetMinor"] as? Number)?.toLong() ?: 0L,
            (summary["remainingWeeklyBudgetMinor"] as? Number)?.toLong() ?: 0L,
            ContractsManager.currentViews().map { it.toApi() },
        )
    }

    override fun canSubmit(player: Player, group: String): Boolean = ContractOriginGate.canSubmit(player, group)

    override fun itemMaterial(itemKey: String): Material? = PaperContractItems.material(itemKey)

    override fun openSources(player: Player) = NpcContractsGui.openSources(player)

    override fun openList(player: Player, group: String) = NpcContractsGui.openList(player, group)

    override fun openDetail(player: Player, group: String, contractId: String) =
        NpcContractsGui.openDetail(player, group, contractId)

    override fun openInvestigationContracts(player: Player, group: String) = NpcContractsGui.openList(player, group)

    override fun submissionReconciliations(limit: Int): List<ArcContractsService.SubmissionReconciliationRecord> =
        ContractsManager.reconciliationRecords(limit).map { it.toApi() }

    override fun submissionReconciliation(submissionId: String): ArcContractsService.SubmissionReconciliationRecord? =
        ContractsManager.reconciliationRecord(submissionId)?.toApi()

    override fun previewSubmissionReconciliation(
        request: ArcContractsService.SubmissionReconciliationRequest,
    ): ArcContractsService.SubmissionReconciliationPreview {
        val validated = request.toDomain().validated()
        return ContractsManager.previewReconciliation(validated).toApi()
    }

    override fun applySubmissionReconciliation(
        request: ArcContractsService.SubmissionReconciliationRequest,
        reviewDigest: String,
    ): ArcContractsService.SubmissionReconciliationResult {
        val validated = request.toDomain().validated()
        val result = ContractsManager.applyReconciliation(validated, reviewDigest)
        return ArcContractsService.SubmissionReconciliationResult(
            result.preview.toApi(),
            result.record.toApi(),
            result.receipt?.let {
                ArcContractsService.SubmissionReceipt(
                    it.submissionId,
                    it.playerId,
                    it.quantity,
                    it.payoutMinor,
                    it.committedAt,
                )
            },
            result.replayed,
        )
    }

    override fun seasonMoneyReconciliations(limit: Int): List<ArcContractsService.SeasonMoneyReconciliationRecord> =
        ContractsManager.seasonMoneyReconciliationRecords(limit).map { it.toApi() }

    override fun seasonMoneyReconciliation(actionId: String): ArcContractsService.SeasonMoneyReconciliationRecord? =
        ContractsManager.seasonMoneyReconciliationRecord(actionId)?.toApi()

    override fun previewSeasonMoneyReconciliation(
        request: ArcContractsService.SeasonMoneyReconciliationRequest,
    ): ArcContractsService.SeasonMoneyReconciliationPreview {
        val validated = request.toDomain().validated()
        return ContractsManager.previewSeasonMoneyReconciliation(validated).toApi()
    }

    override fun applySeasonMoneyReconciliation(
        request: ArcContractsService.SeasonMoneyReconciliationRequest,
        reviewDigest: String,
    ): ArcContractsService.SeasonMoneyReconciliationResult {
        val validated = request.toDomain().validated()
        val result = ContractsManager.applySeasonMoneyReconciliation(validated, reviewDigest)
        return ArcContractsService.SeasonMoneyReconciliationResult(
            result.preview.toApi(),
            result.record.toApi(),
            result.receipt?.let {
                ArcContractsService.SeasonMoneyReceipt(
                    it.actionId,
                    it.kind.label,
                    it.targetId,
                    it.playerId,
                    it.amountMinor,
                    it.committedAt,
                )
            },
            result.replayed,
        )
    }

    override fun authorizeInstance(blueprintWorld: String, instanceWorld: String): ArcContractsService.DungeonAdmission =
        when (ContractsManager.authorizeSeasonDungeonInstance(blueprintWorld, instanceWorld)) {
            SeasonDungeonInstanceDecision.NOT_PROTECTED -> ArcContractsService.DungeonAdmission.NOT_PROTECTED
            SeasonDungeonInstanceDecision.AUTHORIZED -> ArcContractsService.DungeonAdmission.AUTHORIZED
            SeasonDungeonInstanceDecision.DENIED -> ArcContractsService.DungeonAdmission.DENIED
        }

    override fun cancelInstance(instanceWorld: String) {
        ContractsManager.cancelAuthorizedSeasonDungeonInstance(instanceWorld)
    }

    override fun playerAuthorized(instanceWorld: String, playerId: UUID): Boolean? =
        ContractsManager.seasonDungeonPlayerAuthorized(instanceWorld, playerId)

    override fun runId(instanceWorld: String): String? = ContractsManager.seasonDungeonRunAuthorization(instanceWorld)?.runId

    override fun started(runId: String, world: String, participants: Set<String>, instanceWorld: String) {
        ContractsManager.observeDungeonStarted(runId, world, participants, instanceWorld)
    }

    override fun completed(runId: String, world: String, participants: Set<String>, instanceWorld: String) {
        ContractsManager.observeDungeonCompleted(runId, world, participants, instanceWorld)
    }

    override fun resumeRewards(playerId: UUID) = ContractsManager.resumeSeasonDungeonRewards(playerId)

    private fun ContractsMode.toApi(): ArcContractsService.ContractMode =
        ArcContractsService.ContractMode.valueOf(name)

    private fun ResourceContractView.toApi() = ArcContractsService.ContractView(
        id,
        displayName,
        itemKey,
        funding,
        status,
        windowStartsAt,
        windowEndsAt,
        payoutMinorPerUnit,
        budgetMinor,
        spentMinor,
        reservedMinor,
        targetQuantity,
        acceptedQuantity,
        reservedQuantity,
        remainingQuantity,
        contributors,
        group,
        basePayoutMinorPerUnit,
        minSubmissionQuantity,
        minimumSubmissionPayoutMinor,
    )

    private fun ArcContractsService.SubmissionReconciliationRequest.toDomain() =
        ContractSubmissionReconciliationRequest(
            submissionId(),
            expectedRevision(),
            ContractSubmissionReconciliationResolution.valueOf(resolution().name),
            operatorId(),
            operatorEvidence(),
            idempotencyKey(),
            providerBalanceAfterMinor(),
            providerTransactionId(),
            providerTransactionReason(),
        )

    private fun ContractSubmissionJournalRecord.toApi(): ArcContractsService.SubmissionReconciliationRecord {
        val evidence = reconciliation
        return ArcContractsService.SubmissionReconciliationRecord(
            submissionId,
            contractId,
            contractWindowStartsAt,
            itemKey,
            playerId,
            acceptedQuantity,
            payoutMinor,
            payoutReason,
            status.label,
            revision,
            updatedAt,
            reviewFromStatus?.label,
            reviewReason?.label,
            reviewEvidence,
            providerBalanceBeforeMinor,
            providerBalanceAfterMinor,
            providerTransactionId,
            evidence?.resolution?.label,
            evidence?.evidenceKind?.label,
            evidence?.operatorId,
            evidence?.operatorEvidence,
            evidence?.idempotencyKey,
            evidence?.reviewDigest,
            evidence?.reviewedRevision,
            evidence?.reviewFromStatus?.label,
            evidence?.reviewReason?.label,
            evidence?.originalReviewEvidence,
            evidence?.reconciledAt,
        )
    }

    private fun ContractSubmissionReconciliationPreview.toApi() =
        ArcContractsService.SubmissionReconciliationPreview(
            submissionId,
            reviewedRevision,
            reviewFromStatus.label,
            reviewReason.label,
            resolution.label,
            evidenceKind.label,
            proposedStatus.label,
            reviewDigest,
            alreadyApplied,
            commitsContractState,
        )

    private fun ArcContractsService.SeasonMoneyReconciliationRequest.toDomain() =
        SeasonMoneyReconciliationRequest(
            actionId(),
            expectedRevision(),
            SeasonMoneyReconciliationResolution.valueOf(resolution().name),
            operatorId(),
            operatorEvidence(),
            idempotencyKey(),
            providerHistoryCheckedAt(),
            providerBalanceAfterMinor(),
            providerTransactionId(),
            providerTransactionReason(),
        )

    private fun SeasonMoneyJournalRecord.toApi(): ArcContractsService.SeasonMoneyReconciliationRecord {
        val evidence = reconciliation
        return ArcContractsService.SeasonMoneyReconciliationRecord(
            actionId,
            seasonId,
            kind.label,
            targetId,
            playerId,
            amountMinor,
            withdrawalReason,
            status.label,
            revision,
            updatedAt,
            reviewFromStatus?.label,
            reviewReason?.label,
            reviewEvidence,
            balanceBeforeMinor,
            balanceAfterMinor,
            providerTransactionId,
            evidence?.resolution?.label,
            evidence?.evidenceKind?.label,
            evidence?.operatorId,
            evidence?.operatorEvidence,
            evidence?.idempotencyKey,
            evidence?.reviewDigest,
            evidence?.reviewedRevision,
            evidence?.reviewFromStatus?.label,
            evidence?.reviewReason?.label,
            evidence?.originalReviewEvidence,
            evidence?.providerHistoryCheckedAt,
            evidence?.providerBalanceAfterMinor,
            evidence?.providerTransactionId,
            evidence?.reconciledAt,
        )
    }

    private fun SeasonMoneyReconciliationPreview.toApi() =
        ArcContractsService.SeasonMoneyReconciliationPreview(
            actionId,
            reviewedRevision,
            reviewFromStatus.label,
            reviewReason.label,
            resolution.label,
            evidenceKind.label,
            proposedStatus.label,
            reviewDigest,
            alreadyApplied,
            commitsSeasonState,
        )
}

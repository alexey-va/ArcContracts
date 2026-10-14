package ru.arc.contracts.api;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Material;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime service registered by the ArcContracts plugin in Bukkit's ServicesManager.
 * Consumers must treat a missing service as unavailable and must not retain
 * implementation-owned types.
 */
public interface ArcContractsService {
    boolean isAvailable();

    boolean isEnabled();

    void reload();

    int seasonCatalogSchema();

    /** JSON encoding of the established operations audit snapshot. */
    String auditSnapshotJson();

    /** Execute the existing `/arc contracts` command contract. */
    void execute(CommandSender sender, String[] args);

    /** Complete the existing `/arc contracts` command contract. */
    List<String> complete(CommandSender sender, String[] args);

    ContractBoardSnapshot boardSnapshot();

    boolean canSubmit(Player player, String group);

    Material itemMaterial(String itemKey);

    void openSources(Player player);

    void openList(Player player, String group);

    void openDetail(Player player, String group, String contractId);

    /** Legacy investigation integration opens the configured resource group. */
    void openInvestigationContracts(Player player, String group);

    List<SubmissionReconciliationRecord> submissionReconciliations(int limit);

    SubmissionReconciliationRecord submissionReconciliation(String submissionId);

    SubmissionReconciliationPreview previewSubmissionReconciliation(SubmissionReconciliationRequest request);

    SubmissionReconciliationResult applySubmissionReconciliation(
            SubmissionReconciliationRequest request,
            String reviewDigest
    );

    List<SeasonMoneyReconciliationRecord> seasonMoneyReconciliations(int limit);

    SeasonMoneyReconciliationRecord seasonMoneyReconciliation(String actionId);

    SeasonMoneyReconciliationPreview previewSeasonMoneyReconciliation(SeasonMoneyReconciliationRequest request);

    SeasonMoneyReconciliationResult applySeasonMoneyReconciliation(
            SeasonMoneyReconciliationRequest request,
            String reviewDigest
    );

    DungeonAdmission authorizeInstance(String blueprintWorld, String instanceWorld);

    void cancelInstance(String instanceWorld);

    /** Returns null for an ordinary, unprotected world. */
    Boolean playerAuthorized(String instanceWorld, UUID playerId);

    String runId(String instanceWorld);

    void started(String runId, String world, Set<String> participants, String instanceWorld);

    void completed(String runId, String world, Set<String> participants, String instanceWorld);

    void resumeRewards(UUID playerId);

    enum DungeonAdmission {
        NOT_PROTECTED,
        AUTHORIZED,
        DENIED
    }

    enum ContractMode {
        DISABLED,
        OBSERVE,
        ENFORCE
    }

    record ContractBoardSnapshot(
            ContractMode mode,
            boolean enabled,
            boolean leader,
            boolean submissionsEnabled,
            long weeklyBudgetMinor,
            long remainingWeeklyBudgetMinor,
            List<ContractView> contracts
    ) {
        public ContractBoardSnapshot {
            contracts = List.copyOf(contracts);
        }
    }

    /** Stable, read-only board projection; all money values are integer minor units. */
    record ContractView(
            String id,
            String displayName,
            String itemKey,
            String funding,
            String status,
            long windowStartsAt,
            long windowEndsAt,
            long payoutMinorPerUnit,
            long budgetMinor,
            long spentMinor,
            long reservedMinor,
            long targetQuantity,
            long acceptedQuantity,
            long reservedQuantity,
            long remainingQuantity,
            int contributors,
            String group,
            long basePayoutMinorPerUnit,
            int minSubmissionQuantity,
            long minimumSubmissionPayoutMinor
    ) {}

    enum SubmissionResolution {
        NO_ITEMS_REMOVED,
        ITEMS_REFUNDED,
        PAYMENT_CONFIRMED
    }

    record SubmissionReconciliationRequest(
            String submissionId,
            long expectedRevision,
            SubmissionResolution resolution,
            String operatorId,
            String operatorEvidence,
            String idempotencyKey,
            Long providerBalanceAfterMinor,
            String providerTransactionId,
            String providerTransactionReason
    ) {}

    record SubmissionReconciliationRecord(
            String submissionId,
            String contractId,
            long contractWindowStartsAt,
            String itemKey,
            String playerId,
            long acceptedQuantity,
            long payoutMinor,
            String payoutReason,
            String status,
            long revision,
            long updatedAt,
            String reviewFromStatus,
            String reviewReason,
            String reviewEvidence,
            Long providerBalanceBeforeMinor,
            Long providerBalanceAfterMinor,
            String providerTransactionId,
            String reconciliationResolution,
            String reconciliationEvidenceKind,
            String operatorId,
            String operatorEvidence,
            String idempotencyKey,
            String reviewDigest,
            Long reviewedRevision,
            String reconciliationReviewFromStatus,
            String reconciliationReviewReason,
            String originalReviewEvidence,
            Long reconciledAt
    ) {}

    record SubmissionReconciliationPreview(
            String submissionId,
            long reviewedRevision,
            String reviewFromStatus,
            String reviewReason,
            String resolution,
            String evidenceKind,
            String proposedStatus,
            String reviewDigest,
            boolean alreadyApplied,
            boolean commitsContractState
    ) {}

    record SubmissionReceipt(String submissionId, String playerId, long quantity, long payoutMinor, long committedAt) {}

    record SubmissionReconciliationResult(
            SubmissionReconciliationPreview preview,
            SubmissionReconciliationRecord record,
            SubmissionReceipt receipt,
            boolean replayed
    ) {}

    enum SeasonMoneyResolution {
        WITHDRAWAL_NOT_APPLIED,
        WITHDRAWAL_CONFIRMED
    }

    record SeasonMoneyReconciliationRequest(
            String actionId,
            long expectedRevision,
            SeasonMoneyResolution resolution,
            String operatorId,
            String operatorEvidence,
            String idempotencyKey,
            long providerHistoryCheckedAt,
            long providerBalanceAfterMinor,
            String providerTransactionId,
            String providerTransactionReason
    ) {}

    record SeasonMoneyReconciliationRecord(
            String actionId,
            String seasonId,
            String kind,
            String targetId,
            String playerId,
            long amountMinor,
            String withdrawalReason,
            String status,
            long revision,
            long updatedAt,
            String reviewFromStatus,
            String reviewReason,
            String reviewEvidence,
            Long balanceBeforeMinor,
            Long balanceAfterMinor,
            String providerTransactionId,
            String reconciliationResolution,
            String reconciliationEvidenceKind,
            String operatorId,
            String operatorEvidence,
            String idempotencyKey,
            String reviewDigest,
            Long reviewedRevision,
            String reconciliationReviewFromStatus,
            String reconciliationReviewReason,
            String originalReviewEvidence,
            Long providerHistoryCheckedAt,
            Long providerBalanceAfterMinor,
            String reconciliationProviderTransactionId,
            Long reconciledAt
    ) {}

    record SeasonMoneyReconciliationPreview(
            String actionId,
            long reviewedRevision,
            String reviewFromStatus,
            String reviewReason,
            String resolution,
            String evidenceKind,
            String proposedStatus,
            String reviewDigest,
            boolean alreadyApplied,
            boolean commitsSeasonState
    ) {}

    record SeasonMoneyReceipt(
            String actionId,
            String kind,
            String targetId,
            String playerId,
            long amountMinor,
            long committedAt
    ) {}

    record SeasonMoneyReconciliationResult(
            SeasonMoneyReconciliationPreview preview,
            SeasonMoneyReconciliationRecord record,
            SeasonMoneyReceipt receipt,
            boolean replayed
    ) {}
}

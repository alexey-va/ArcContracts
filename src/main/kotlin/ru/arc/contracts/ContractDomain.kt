package ru.arc.contracts

import java.util.Locale

enum class ContractKind(val label: String) {
    RESOURCE("resource"),
    CRAFT("craft"),
    CONSTRUCTION("construction"),
    DUNGEON("dungeon"),
    PROJECT("project"),
    COLLECTION("collection"),
}

enum class ContractFunding(val label: String) {
    SERVER_ENVELOPE("server_envelope"),
    PLAYER_ESCROW("player_escrow"),
}

enum class ContractStatus(val label: String) {
    OPEN("open"),
    PAUSED("paused"),
    COMPLETED("completed"),
    EXPIRED("expired"),
}

/**
 * Immutable policy for one bounded resource order window.
 *
 * Money is always represented in minor units to keep quotas exact. Item keys
 * are namespaced and normalized before they reach the state machine; arbitrary
 * item/NBT data never enters this domain object.
 */
data class ResourceContractDefinition(
    val id: String,
    val displayName: String,
    val itemKey: String,
    val funding: ContractFunding,
    val windowStartsAt: Long,
    val windowEndsAt: Long,
    val payoutMinorPerUnit: Long,
    val budgetMinor: Long,
    val targetQuantity: Long,
    val perPlayerQuantityCap: Long,
    val minSubmissionQuantity: Int = 1,
    val maxSubmissionQuantity: Int = 2_304,
    val kind: ContractKind = ContractKind.RESOURCE,
    val group: String = DEFAULT_GROUP,
    val dynamicPricing: Boolean = false,
    val weeklyRecurring: Boolean = false,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid contract id: $id" }
        require(displayName.isNotBlank()) { "Contract displayName must not be blank" }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH && displayName.none(Char::isISOControl)) {
            "Contract displayName must be at most $MAX_DISPLAY_NAME_LENGTH printable characters"
        }
        require(normalizeItemKey(itemKey) == itemKey) { "Contract itemKey must be normalized: $itemKey" }
        require(itemKey.length <= MAX_ITEM_KEY_LENGTH && ITEM_KEY_PATTERN.matches(itemKey)) {
            "Invalid namespaced contract itemKey: $itemKey"
        }
        require(GROUP_PATTERN.matches(group)) { "Invalid contract group: $group" }
        require(windowStartsAt >= 0L) { "Contract windowStartsAt must be non-negative" }
        require(windowEndsAt > windowStartsAt) { "Contract window must be positive" }
        require(payoutMinorPerUnit > 0L) { "Contract payout must be positive" }
        require(budgetMinor > 0L) { "Contract budget must be positive" }
        require(targetQuantity > 0L) { "Contract targetQuantity must be positive" }
        require(perPlayerQuantityCap > 0L) { "Contract per-player cap must be positive" }
        require(minSubmissionQuantity > 0) { "Contract minimum submission must be positive" }
        require(maxSubmissionQuantity >= minSubmissionQuantity) {
            "Contract maximum submission must be at least the minimum"
        }
        require(maxSubmissionQuantity <= MAX_SUBMISSION_QUANTITY) {
            "Contract maximum submission must not exceed $MAX_SUBMISSION_QUANTITY"
        }
        require(targetQuantity >= minSubmissionQuantity) { "Contract target must accept at least one minimum submission" }
        require(perPlayerQuantityCap >= minSubmissionQuantity) { "Contract player cap must accept at least one minimum submission" }
        val minimumPayout = Math.multiplyExact(minSubmissionQuantity.toLong(), payoutMinorPerUnit)
        Math.multiplyExact(maxSubmissionQuantity.toLong(), payoutMinorPerUnit)
        require(budgetMinor >= minimumPayout) { "Contract budget must fund at least one minimum submission" }
    }

    fun isOpenAt(now: Long): Boolean = now in windowStartsAt until windowEndsAt

    companion object {
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{2,47}")
        private val GROUP_PATTERN = Regex("[a-z0-9][a-z0-9_-]{2,47}")
        private val ITEM_KEY_PATTERN = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        private const val MAX_DISPLAY_NAME_LENGTH = 96
        private const val MAX_ITEM_KEY_LENGTH = 128
        private const val MAX_SUBMISSION_QUANTITY = 2_304
        const val DEFAULT_GROUP = "general"

        fun normalizeItemKey(raw: String): String {
            val normalized = raw.trim().lowercase(Locale.ROOT)
            if (normalized.isEmpty()) return normalized
            return if (':' in normalized) normalized else "minecraft:$normalized"
        }
    }
}

data class ContractSubmissionReceipt(
    val submissionId: String,
    val playerId: String,
    val quantity: Long,
    val payoutMinor: Long,
    val committedAt: Long,
)

/**
 * Quota held by a durable submission journal that has not reached a terminal
 * state yet. Reservations are deliberately derived from that journal instead
 * of persisted in a second Redis record, so one durable write reserves both
 * item quantity and payout budget.
 */
data class ContractQuotaReservation(
    val submissionId: String,
    val playerId: String,
    val quantity: Long,
    val payoutMinor: Long,
) {
    init {
        require(SUBMISSION_ID_PATTERN.matches(submissionId)) {
            "Invalid contract reservation id"
        }
        require(playerId.isNotBlank() && playerId.length <= ResourceContractState.MAX_PLAYER_ID_LENGTH) {
            "Invalid contract reservation player id"
        }
        require(playerId.none(Char::isISOControl)) { "Invalid contract reservation player id" }
        require(quantity > 0L && payoutMinor > 0L) { "Contract reservation must be positive" }
    }

    companion object {
        const val MAX_ACTIVE_RESERVATIONS = 4_096
        private val SUBMISSION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,95}")
    }
}

data class ResourceContractState(
    val contractId: String,
    val windowStartsAt: Long,
    val windowEndsAt: Long,
    val status: ContractStatus = ContractStatus.OPEN,
    val acceptedQuantity: Long = 0L,
    val spentMinor: Long = 0L,
    val perPlayerQuantity: Map<String, Long> = emptyMap(),
    val recentReceipts: Map<String, ContractSubmissionReceipt> = emptyMap(),
    val revision: Long = 0L,
) {
    init {
        validateStructure()
    }

    fun validatedAgainst(definition: ResourceContractDefinition): ResourceContractState {
        validateStructure()
        require(contractId == definition.id) { "Contract state id does not match policy" }
        require(windowStartsAt == definition.windowStartsAt && windowEndsAt == definition.windowEndsAt) {
            "Contract state window does not match policy"
        }
        require(acceptedQuantity <= definition.targetQuantity) { "Contract state exceeds target quantity" }
        require(spentMinor <= definition.budgetMinor) { "Contract state exceeds budget" }
        require(
            acceptedQuantity == 0L && spentMinor == 0L ||
                ContractMarketPricing.payoutAllowed(definition, acceptedQuantity, spentMinor),
        ) {
            "Contract state quantity and spend disagree"
        }
        val maximumPlayerCap = ContractRankPolicy.MAXIMUM.playerCap(definition.perPlayerQuantityCap)
        require(perPlayerQuantity.values.all { it <= maximumPlayerCap }) {
            "Contract state exceeds a player cap"
        }
        recentReceipts.forEach { (key, receipt) ->
            require(key == receipt.submissionId && key.isNotBlank() && key.length <= MAX_SUBMISSION_ID_LENGTH) {
                "Invalid contract receipt id"
            }
            require(
                receipt.playerId.isNotBlank() && receipt.playerId.length <= MAX_PLAYER_ID_LENGTH &&
                    receipt.quantity > 0L && receipt.payoutMinor > 0L,
            ) {
                "Invalid contract receipt"
            }
            require(ContractMarketPricing.payoutAllowed(definition, receipt.quantity, receipt.payoutMinor)) {
                "Contract receipt quantity and payout disagree"
            }
            require(perPlayerQuantity[receipt.playerId].orZero() >= receipt.quantity) {
                "Contract receipt is not represented in player totals"
            }
        }
        return this
    }

    private fun validateStructure() {
        require(contractId.isNotBlank()) { "Contract state id must not be blank" }
        require(windowEndsAt > windowStartsAt) { "Contract state window must be positive" }
        require(ContractStatus.entries.contains(status)) { "Contract state status is invalid" }
        require(acceptedQuantity >= 0L) { "Accepted quantity must be non-negative" }
        require(spentMinor >= 0L) { "Spent amount must be non-negative" }
        require(perPlayerQuantity.size <= MAX_TRACKED_PLAYERS) {
            "Contract player history exceeds $MAX_TRACKED_PLAYERS entries"
        }
        require(
            perPlayerQuantity.all { (playerId, quantity) ->
                playerId.isNotBlank() && playerId.length <= MAX_PLAYER_ID_LENGTH && quantity >= 0L
            },
        ) {
            "Player quantities must have non-blank ids and non-negative values"
        }
        val playerTotal = perPlayerQuantity.values.fold(0L, Math::addExact)
        require(playerTotal == acceptedQuantity) { "Player quantities do not equal accepted quantity" }
        require(recentReceipts.size <= MAX_RECENT_RECEIPTS) {
            "Contract receipt history exceeds $MAX_RECENT_RECEIPTS entries"
        }
        require(revision >= 0L) { "Revision must be non-negative" }
    }

    companion object {
        const val MAX_RECENT_RECEIPTS = 512
        const val MAX_TRACKED_PLAYERS = 4_096
        const val MAX_PLAYER_ID_LENGTH = 64
        const val MAX_SUBMISSION_ID_LENGTH = 96

        fun empty(definition: ResourceContractDefinition): ResourceContractState =
            ResourceContractState(
                contractId = definition.id,
                windowStartsAt = definition.windowStartsAt,
                windowEndsAt = definition.windowEndsAt,
            )
    }
}

data class ResourceContractRecord(
    val stateId: String,
    val state: ResourceContractState,
    val definitionSnapshot: ResourceContractDefinition? = null,
) : ru.arc.repository.Entity {
    override fun id(): String = stateId

    fun validatedAgainst(definition: ResourceContractDefinition): ResourceContractRecord {
        require(stateId == ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)) {
            "Contract record id does not match policy"
        }
        definitionSnapshot?.let { require(it == definition) { "Contract record definition snapshot does not match policy" } }
        state.validatedAgainst(definition)
        return this
    }

    companion object {
        fun stateId(contractId: String, windowStartsAt: Long): String = "$contractId:$windowStartsAt"

        fun empty(definition: ResourceContractDefinition): ResourceContractRecord =
            ResourceContractRecord(
                stateId = stateId(definition.id, definition.windowStartsAt),
                state = ResourceContractState.empty(definition),
                definitionSnapshot = definition,
            )
    }
}

enum class SubmissionRejection(val label: String) {
    INVALID_REQUEST("invalid_request"),
    CONTRACT_NOT_OPEN("contract_not_open"),
    WINDOW_MISMATCH("window_mismatch"),
    STALE_STATE("stale_state"),
    QUANTITY_EXHAUSTED("quantity_exhausted"),
    BUDGET_EXHAUSTED("budget_exhausted"),
    PLAYER_CAP_REACHED("player_cap_reached"),
    CONTRIBUTOR_LIMIT_REACHED("contributor_limit_reached"),
    BELOW_MINIMUM("below_minimum"),
    PROJECT_STAGE_LOCKED("project_stage_locked"),
    INVENTORY_UNAVAILABLE("inventory_unavailable"),
    JOURNAL_CAPACITY_REACHED("journal_capacity_reached"),
    SUBMISSION_IN_PROGRESS("submission_in_progress"),
}

sealed interface ContractSubmissionPlan {
    data class Accepted(
        val submissionId: String,
        val playerId: String,
        val requestedQuantity: Int,
        val acceptedQuantity: Long,
        val payoutMinor: Long,
        val expectedRevision: Long,
        val plannedAt: Long,
        val playerCapBasisPoints: Int = ContractRankPolicy.BASE_BASIS_POINTS,
        val payoutBasisPoints: Int = ContractRankPolicy.BASE_BASIS_POINTS,
        val priceSupplyBefore: Long = 0L,
    ) : ContractSubmissionPlan

    data class Duplicate(
        val receipt: ContractSubmissionReceipt,
    ) : ContractSubmissionPlan

    data class Rejected(
        val reason: SubmissionRejection,
    ) : ContractSubmissionPlan
}

data class ContractCommitResult(
    val state: ResourceContractState,
    val receipt: ContractSubmissionReceipt,
    val changed: Boolean,
)

/** Pure state machine. Inventory and Vault side effects remain outside it. */
object ResourceContractEngine {
    fun plan(
        definition: ResourceContractDefinition,
        state: ResourceContractState,
        submissionId: String,
        playerId: String,
        requestedQuantity: Int,
        now: Long,
        reservations: Collection<ContractQuotaReservation> = emptyList(),
        policy: ContractRankPolicy = ContractRankPolicy.IDENTITY,
    ): ContractSubmissionPlan {
        if (submissionId.isBlank() || submissionId.length > ResourceContractState.MAX_SUBMISSION_ID_LENGTH ||
            playerId.isBlank() || playerId.length > ResourceContractState.MAX_PLAYER_ID_LENGTH ||
            requestedQuantity <= 0
        ) {
            return ContractSubmissionPlan.Rejected(SubmissionRejection.INVALID_REQUEST)
        }
        state.recentReceipts[submissionId]?.let { return ContractSubmissionPlan.Duplicate(it) }
        if (state.contractId != definition.id ||
            state.windowStartsAt != definition.windowStartsAt ||
            state.windowEndsAt != definition.windowEndsAt
        ) {
            return ContractSubmissionPlan.Rejected(SubmissionRejection.WINDOW_MISMATCH)
        }
        if (state.status != ContractStatus.OPEN || !definition.isOpenAt(now)) {
            return ContractSubmissionPlan.Rejected(SubmissionRejection.CONTRACT_NOT_OPEN)
        }
        if (requestedQuantity < definition.minSubmissionQuantity) {
            return ContractSubmissionPlan.Rejected(SubmissionRejection.BELOW_MINIMUM)
        }
        val activeReservations = validatedReservations(definition, state, reservations)
        if (activeReservations.any { it.submissionId == submissionId }) {
            return ContractSubmissionPlan.Rejected(SubmissionRejection.STALE_STATE)
        }
        val contributors = state.perPlayerQuantity.keys + activeReservations.map { it.playerId }
        if (playerId !in contributors &&
            contributors.size >= ResourceContractState.MAX_TRACKED_PLAYERS
        ) {
            return ContractSubmissionPlan.Rejected(SubmissionRejection.CONTRIBUTOR_LIMIT_REACHED)
        }

        val reservedQuantity = activeReservations.fold(0L) { total, reservation -> Math.addExact(total, reservation.quantity) }
        val reservedPayout = activeReservations.fold(0L) { total, reservation -> Math.addExact(total, reservation.payoutMinor) }
        val accountedQuantity = Math.addExact(state.acceptedQuantity, reservedQuantity)
        val accountedPayout = Math.addExact(state.spentMinor, reservedPayout)
        val quantityRemaining = (definition.targetQuantity - accountedQuantity).coerceAtLeast(0L)
        if (quantityRemaining == 0L) {
            return ContractSubmissionPlan.Rejected(SubmissionRejection.QUANTITY_EXHAUSTED)
        }
        val budgetRemaining = (definition.budgetMinor - accountedPayout).coerceAtLeast(0L)
        val playerReserved =
            activeReservations.asSequence()
                .filter { it.playerId == playerId }
                .fold(0L) { total, reservation -> Math.addExact(total, reservation.quantity) }
        val playerAccepted = Math.addExact(state.perPlayerQuantity[playerId].orZero(), playerReserved)
        val playerCap = policy.playerCap(definition.perPlayerQuantityCap)
        val playerRemaining = (playerCap - playerAccepted).coerceAtLeast(0L)
        if (playerRemaining == 0L) {
            return ContractSubmissionPlan.Rejected(SubmissionRejection.PLAYER_CAP_REACHED)
        }

        val upper = minOf(requestedQuantity.toLong(), definition.maxSubmissionQuantity.toLong(), quantityRemaining, playerRemaining)
        val priceSupplyBefore = accountedQuantity
        val accepted = ContractMarketPricing.affordableQuantity(
            definition, priceSupplyBefore, upper, budgetRemaining, now, policy,
        )
        if (accepted < definition.minSubmissionQuantity) {
            return ContractSubmissionPlan.Rejected(
                when {
                    playerRemaining < definition.minSubmissionQuantity -> SubmissionRejection.PLAYER_CAP_REACHED
                    ContractMarketPricing.payoutMinor(definition, priceSupplyBefore, definition.minSubmissionQuantity.toLong(), now, policy) > budgetRemaining -> SubmissionRejection.BUDGET_EXHAUSTED
                    quantityRemaining < definition.minSubmissionQuantity -> SubmissionRejection.QUANTITY_EXHAUSTED
                    else -> SubmissionRejection.BELOW_MINIMUM
                },
            )
        }
        val payout = ContractMarketPricing.payoutMinor(definition, priceSupplyBefore, accepted, now, policy)
        check(payout <= budgetRemaining) { "Planned payout exceeds remaining budget" }

        return ContractSubmissionPlan.Accepted(
            submissionId = submissionId,
            playerId = playerId,
            requestedQuantity = requestedQuantity,
            acceptedQuantity = accepted,
            payoutMinor = payout,
            expectedRevision = state.revision,
            plannedAt = now,
            playerCapBasisPoints = policy.playerCapBasisPoints,
            payoutBasisPoints = policy.payoutBasisPoints,
            priceSupplyBefore = priceSupplyBefore,
        )
    }

    fun commit(
        definition: ResourceContractDefinition,
        state: ResourceContractState,
        plan: ContractSubmissionPlan.Accepted,
        committedAt: Long,
    ): ContractCommitResult {
        state.recentReceipts[plan.submissionId]?.let {
            require(
                it.playerId == plan.playerId &&
                    it.quantity == plan.acceptedQuantity &&
                    it.payoutMinor == plan.payoutMinor,
            ) { "Committed submission replay disagrees with its receipt" }
            return ContractCommitResult(state, it, changed = false)
        }
        require(state.revision == plan.expectedRevision) { SubmissionRejection.STALE_STATE.label }
        require(state.contractId == definition.id) { SubmissionRejection.WINDOW_MISMATCH.label }
        require(plan.acceptedQuantity > 0L && plan.payoutMinor > 0L) { "Cannot commit an empty submission" }
        val policy = ContractRankPolicy(plan.playerCapBasisPoints, plan.payoutBasisPoints)
        require(
            ContractMarketPricing.payoutMinor(definition, plan.priceSupplyBefore, plan.acceptedQuantity, plan.plannedAt, policy) ==
                plan.payoutMinor,
        ) { "Submission payout does not match locked market quote" }
        require(
            Math.addExact(state.perPlayerQuantity[plan.playerId].orZero(), plan.acceptedQuantity) <=
                policy.playerCap(definition.perPlayerQuantityCap),
        ) { "Submission exceeds rank player cap" }

        return commitAccepted(
            definition = definition,
            state = state,
            submissionId = plan.submissionId,
            playerId = plan.playerId,
            quantity = plan.acceptedQuantity,
            payoutMinor = plan.payoutMinor,
            committedAt = committedAt,
        )
    }

    /**
     * Commits a journal-backed reservation without requiring the state revision
     * captured when it was planned. Other reserved submissions may have
     * committed first; the durable quota reservation still prevents overspend.
     */
    fun commitReserved(
        definition: ResourceContractDefinition,
        state: ResourceContractState,
        reservation: ContractQuotaReservation,
        committedAt: Long,
    ): ContractCommitResult {
        state.recentReceipts[reservation.submissionId]?.let {
            require(
                it.playerId == reservation.playerId &&
                    it.quantity == reservation.quantity &&
                    it.payoutMinor == reservation.payoutMinor,
            ) { "Committed reservation replay disagrees with its receipt" }
            return ContractCommitResult(state, it, changed = false)
        }
        require(state.contractId == definition.id) { SubmissionRejection.WINDOW_MISMATCH.label }
        require(ContractMarketPricing.payoutAllowed(definition, reservation.quantity, reservation.payoutMinor)) {
            "Reservation payout does not match contract policy"
        }
        return commitAccepted(
            definition = definition,
            state = state,
            submissionId = reservation.submissionId,
            playerId = reservation.playerId,
            quantity = reservation.quantity,
            payoutMinor = reservation.payoutMinor,
            committedAt = committedAt,
        )
    }

    private fun commitAccepted(
        definition: ResourceContractDefinition,
        state: ResourceContractState,
        submissionId: String,
        playerId: String,
        quantity: Long,
        payoutMinor: Long,
        committedAt: Long,
    ): ContractCommitResult {
        require(committedAt >= 0L) { "Contract commit timestamp must be non-negative" }

        val nextQuantity = Math.addExact(state.acceptedQuantity, quantity)
        val nextSpent = Math.addExact(state.spentMinor, payoutMinor)
        require(nextQuantity <= definition.targetQuantity) { "Submission exceeds contract quantity" }
        require(nextSpent <= definition.budgetMinor) { "Submission exceeds contract budget" }
        val playerQuantity = Math.addExact(state.perPlayerQuantity[playerId].orZero(), quantity)
        require(playerQuantity <= ContractRankPolicy.MAXIMUM.playerCap(definition.perPlayerQuantityCap)) {
            "Submission exceeds player cap"
        }
        require(ContractMarketPricing.payoutAllowed(definition, quantity, payoutMinor)) {
            "Submission payout does not match released policy bounds"
        }

        val receipt =
            ContractSubmissionReceipt(
                submissionId = submissionId,
                playerId = playerId,
                quantity = quantity,
                payoutMinor = payoutMinor,
                committedAt = committedAt,
            )
        val receipts = LinkedHashMap(state.recentReceipts)
        receipts[receipt.submissionId] = receipt
        while (receipts.size > ResourceContractState.MAX_RECENT_RECEIPTS) {
            receipts.remove(receipts.entries.first().key)
        }
        val nextStatus =
            if (definition.targetQuantity - nextQuantity < definition.minSubmissionQuantity ||
                definition.budgetMinor - nextSpent < ContractMarketPricing.minimumSubmissionPayout(definition)
            ) {
                ContractStatus.COMPLETED
            } else {
                ContractStatus.OPEN
            }
        val nextState =
            state.copy(
                status = nextStatus,
                acceptedQuantity = nextQuantity,
                spentMinor = nextSpent,
                perPlayerQuantity = state.perPlayerQuantity + (playerId to playerQuantity),
                recentReceipts = receipts,
                revision = Math.addExact(state.revision, 1L),
            )
        return ContractCommitResult(nextState, receipt, changed = true)
    }

    private fun validatedReservations(
        definition: ResourceContractDefinition,
        state: ResourceContractState,
        reservations: Collection<ContractQuotaReservation>,
    ): List<ContractQuotaReservation> {
        require(reservations.size <= ContractQuotaReservation.MAX_ACTIVE_RESERVATIONS) {
            "Contract reservation set exceeds the journal limit"
        }
        reservations.forEach { reservation ->
            state.recentReceipts[reservation.submissionId]?.let { receipt ->
                require(
                    receipt.playerId == reservation.playerId &&
                        receipt.quantity == reservation.quantity &&
                        receipt.payoutMinor == reservation.payoutMinor,
                ) { "Contract reservation disagrees with its committed receipt" }
            }
        }
        val active = reservations.filterNot { it.submissionId in state.recentReceipts }
        require(active.map { it.submissionId }.toSet().size == active.size) {
            "Contract reservation set contains duplicate ids"
        }
        active.forEach { reservation ->
            require(ContractMarketPricing.payoutAllowed(definition, reservation.quantity, reservation.payoutMinor)) {
                "Contract reservation payout does not match policy"
            }
        }
        return active
    }
}

private fun Long?.orZero(): Long = this ?: 0L

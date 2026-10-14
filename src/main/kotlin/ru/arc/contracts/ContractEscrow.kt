package ru.arc.contracts

enum class EscrowStatus(val label: String) {
    OPEN("open"),
    CLOSED("closed"),
}

data class EscrowReservation(
    val reservationId: String,
    val amountMinor: Long,
    val createdAt: Long,
)

data class EscrowAccount(
    val accountId: String,
    val ownerId: String,
    val status: EscrowStatus = EscrowStatus.OPEN,
    val fundedMinor: Long = 0L,
    val paidMinor: Long = 0L,
    val refundedMinor: Long = 0L,
    val reservations: Map<String, EscrowReservation> = emptyMap(),
    val settledReservationIds: Set<String> = emptySet(),
    val revision: Long = 0L,
) {
    init {
        require(accountId.isNotBlank() && accountId.length <= MAX_ID_LENGTH) { "Invalid escrow account id" }
        require(ownerId.isNotBlank() && ownerId.length <= MAX_ID_LENGTH) { "Invalid escrow owner id" }
        require(fundedMinor >= 0L && paidMinor >= 0L && refundedMinor >= 0L) {
            "Escrow amounts must be non-negative"
        }
        require(reservations.size <= MAX_ACTIVE_RESERVATIONS) {
            "Escrow active reservations exceed $MAX_ACTIVE_RESERVATIONS entries"
        }
        require(
            reservations.all { (id, reservation) ->
                id == reservation.reservationId && id.isNotBlank() && id.length <= MAX_ID_LENGTH &&
                    reservation.amountMinor > 0L
            },
        ) { "Escrow reservations must have valid ids and positive amounts" }
        require(settledReservationIds.size <= MAX_SETTLED_RESERVATIONS) {
            "Escrow settled-reservation history exceeds $MAX_SETTLED_RESERVATIONS entries"
        }
        require(settledReservationIds.all { it.isNotBlank() && it.length <= MAX_ID_LENGTH }) {
            "Escrow settled-reservation history contains an invalid id"
        }
        require(revision >= 0L) { "Escrow revision must be non-negative" }
        require(accountedMinor <= fundedMinor) { "Escrow is overdrawn" }
    }

    val reservedMinor: Long get() = reservations.values.fold(0L) { sum, reservation -> Math.addExact(sum, reservation.amountMinor) }

    val accountedMinor: Long get() = Math.addExact(Math.addExact(paidMinor, refundedMinor), reservedMinor)

    val availableMinor: Long get() = fundedMinor - accountedMinor

    companion object {
        const val MAX_SETTLED_RESERVATIONS = 4_096
        const val MAX_ACTIVE_RESERVATIONS = 4_096
        const val MAX_ID_LENGTH = 96
    }
}

/** Pure escrow state transitions; Vault/Redis persistence is performed by the caller. */
object ContractEscrowEngine {
    fun fund(account: EscrowAccount, amountMinor: Long): EscrowAccount {
        require(account.status == EscrowStatus.OPEN) { "Escrow is closed" }
        require(amountMinor > 0L) { "Funding amount must be positive" }
        return account.copy(
            fundedMinor = Math.addExact(account.fundedMinor, amountMinor),
            revision = Math.addExact(account.revision, 1L),
        )
    }

    fun reserve(
        account: EscrowAccount,
        reservationId: String,
        amountMinor: Long,
        createdAt: Long,
    ): EscrowAccount {
        require(account.status == EscrowStatus.OPEN) { "Escrow is closed" }
        require(reservationId.isNotBlank() && reservationId.length <= EscrowAccount.MAX_ID_LENGTH) {
            "Invalid reservation id"
        }
        require(amountMinor > 0L) { "Reservation amount must be positive" }
        account.reservations[reservationId]?.let {
            require(it.amountMinor == amountMinor) { "Reservation id already uses another amount" }
            return account
        }
        require(reservationId !in account.settledReservationIds) { "Reservation is already settled" }
        require(account.reservations.size < EscrowAccount.MAX_ACTIVE_RESERVATIONS) {
            "Escrow active-reservation history is full"
        }
        require(amountMinor <= account.availableMinor) { "Insufficient escrow balance" }
        return account.copy(
            reservations =
                account.reservations +
                    (reservationId to EscrowReservation(reservationId, amountMinor, createdAt)),
            revision = Math.addExact(account.revision, 1L),
        )
    }

    fun release(account: EscrowAccount, reservationId: String): EscrowAccount {
        if (reservationId !in account.reservations) return account
        return account.copy(
            reservations = account.reservations - reservationId,
            revision = Math.addExact(account.revision, 1L),
        )
    }

    fun settle(account: EscrowAccount, reservationId: String): EscrowAccount {
        if (reservationId in account.settledReservationIds) return account
        val reservation = requireNotNull(account.reservations[reservationId]) { "Unknown escrow reservation" }
        require(account.settledReservationIds.size < EscrowAccount.MAX_SETTLED_RESERVATIONS) {
            "Escrow settlement history is full; close this account before accepting more reservations"
        }
        return account.copy(
            paidMinor = Math.addExact(account.paidMinor, reservation.amountMinor),
            reservations = account.reservations - reservationId,
            settledReservationIds = account.settledReservationIds + reservationId,
            revision = Math.addExact(account.revision, 1L),
        )
    }

    fun refund(account: EscrowAccount, amountMinor: Long): EscrowAccount {
        require(account.status == EscrowStatus.OPEN) { "Escrow is closed" }
        require(amountMinor > 0L) { "Refund amount must be positive" }
        require(amountMinor <= account.availableMinor) { "Insufficient refundable escrow balance" }
        return account.copy(
            refundedMinor = Math.addExact(account.refundedMinor, amountMinor),
            revision = Math.addExact(account.revision, 1L),
        )
    }

    fun close(account: EscrowAccount): EscrowAccount {
        require(account.reservations.isEmpty()) { "Cannot close escrow with active reservations" }
        require(account.availableMinor == 0L) { "Cannot close escrow with refundable balance" }
        return if (account.status == EscrowStatus.CLOSED) {
            account
        } else {
            account.copy(status = EscrowStatus.CLOSED, revision = Math.addExact(account.revision, 1L))
        }
    }
}

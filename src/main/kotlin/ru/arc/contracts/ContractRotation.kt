package ru.arc.contracts

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Moscow weeks are also the durable budget boundary; viewing a menu never resets a quota. */
object ContractRotation {
    const val WEEK_MILLIS = 7 * 86_400_000L
    private val WEEK_ZONE = ZoneId.of("Europe/Moscow")

    fun weekStart(now: Long): Long = Instant.ofEpochMilli(now).atZone(WEEK_ZONE)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .toLocalDate().atStartOfDay(WEEK_ZONE).toInstant().toEpochMilli()

    fun at(definition: ResourceContractDefinition, now: Long): ResourceContractDefinition {
        if (!definition.weeklyRecurring || now < definition.windowStartsAt) return definition
        require(definition.windowStartsAt == weekStart(definition.windowStartsAt)) {
            "Recurring contracts start Monday 00:00 Europe/Moscow"
        }
        require(definition.windowEndsAt - definition.windowStartsAt == WEEK_MILLIS) { "Recurring contracts have a seven-day window" }
        val startsAt = weekStart(now)
        return definition.copy(windowStartsAt = startsAt, windowEndsAt = Math.addExact(startsAt, WEEK_MILLIS))
    }

    fun remainingBudget(
        limit: Long,
        records: Collection<ResourceContractRecord>,
        journals: Collection<ContractSubmissionJournalRecord>,
        now: Long,
    ): Long {
        require(limit >= 0L)
        val start = weekStart(now)
        val end = Math.addExact(start, WEEK_MILLIS)
        val states = records.associateBy { it.stateId }
        val spent = states.values.filter { it.state.windowStartsAt < end && it.state.windowEndsAt > start }
            .fold(0L) { total, record -> Math.addExact(total, record.state.spentMinor) }
        val held = journals.filter { record ->
            val state = states[ResourceContractRecord.stateId(record.contractId, record.contractWindowStartsAt)]?.state
            record.quotaReservation() != null && state?.recentReceipts?.containsKey(record.submissionId) != true &&
                (record.definitionSnapshot?.windowEndsAt ?: state?.windowEndsAt ?: Long.MAX_VALUE) > start &&
                record.contractWindowStartsAt < end
        }.fold(0L) { total, record -> Math.addExact(total, record.payoutMinor) }
        return (limit - Math.addExact(spent, held)).coerceAtLeast(0L)
    }
}

/** A server-created GUI quote; no player command accepts serialized quote data. */
data class ContractSubmissionQuote(
    val contractId: String,
    val windowStartsAt: Long,
    val playerId: String,
    val quantity: Int,
    val payoutMinor: Long,
    val expectedRevision: Long,
    val quotedAt: Long,
) {
    fun matches(definition: ResourceContractDefinition, plan: ContractSubmissionPlan.Accepted, now: Long): Boolean =
        now >= quotedAt && now - quotedAt <= MAX_AGE_MILLIS && definition.isOpenAt(now) &&
            contractId == definition.id && windowStartsAt == definition.windowStartsAt &&
            playerId == plan.playerId && quantity.toLong() == plan.acceptedQuantity &&
            payoutMinor == plan.payoutMinor && expectedRevision == plan.expectedRevision

    companion object { const val MAX_AGE_MILLIS = 30_000L }
}

package ru.arc.contracts.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Emitted after a resource submission is durably committed. */
public final class ResourceContractCommittedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String submissionId;
    private final UUID playerId;
    private final String contractId;
    private final long quantity;

    public ResourceContractCommittedEvent(String submissionId, UUID playerId, String contractId, long quantity) {
        super(false);
        this.submissionId = submissionId;
        this.playerId = playerId;
        this.contractId = contractId;
        this.quantity = quantity;
    }

    public String getSubmissionId() { return submissionId; }

    public UUID getPlayerId() { return playerId; }

    public String getContractId() { return contractId; }

    public long getQuantity() { return quantity; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}

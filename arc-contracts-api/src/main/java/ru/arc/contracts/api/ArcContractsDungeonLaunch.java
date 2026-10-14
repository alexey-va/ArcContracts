package ru.arc.contracts.api;

import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

/** Narrow ARC host bridge for native dungeon launch and party admission. */
public interface ArcContractsDungeonLaunch {
    boolean canLaunch(Player player, String blueprintWorld);

    /** The host delegates to its ArcDungeons service; throws if native launch is unavailable. */
    void launch(Player player, String blueprintWorld);

    /** Null means a party integration exists but this player is not its local launch leader. */
    Set<UUID> localParticipants(UUID leaderId);
}

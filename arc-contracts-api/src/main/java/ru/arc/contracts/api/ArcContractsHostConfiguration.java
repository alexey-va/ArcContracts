package ru.arc.contracts.api;

import net.kyori.adventure.text.Component;

import java.nio.file.Path;

/** Narrow ARC-owned settings needed by the ArcContracts runtime. */
public interface ArcContractsHostConfiguration {
    /** Existing operator data directory; contract config and player state stay in legacy ARC paths. */
    Path legacyDataPath();

    /** Current logical server ID used by leader gates. */
    String serverName();

    /** Whether ARC has an active primary Redis configuration. */
    boolean sharedRedisAvailable();

    /** Increments whenever ARC replaces or disables the primary Redis binding. */
    long sharedRedisGeneration();

    /** Existing ARC command wording used by the delegated `/arc contracts` command. */
    Component commandText(String key, String fallback);
}

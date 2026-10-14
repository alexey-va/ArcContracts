package ru.arc.contracts.api;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.Locale;

/** Stable item marker protocol shared with host auction/shop guards. */
public final class ArcContractTrophyMarkers {
    public static final String NAMESPACE = "arc";
    public static final String IDENTITY_KEY = "season_trophy";
    public static final String OWNER_KEY = "season_trophy_owner";
    public static final String IDENTITY_NAMESPACED_KEY = NAMESPACE + ":" + IDENTITY_KEY;
    public static final String OWNER_NAMESPACED_KEY = NAMESPACE + ":" + OWNER_KEY;

    private ArcContractTrophyMarkers() {}

    public static String identity(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        var key = NamespacedKey.fromString(IDENTITY_NAMESPACED_KEY);
        if (key == null) return null;
        String value = stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return value != null && normalizeIdentity(value).equals(value) ? value : null;
    }

    /** Mirrors the legacy item-key normalization used by resource contracts. */
    public static String normalizeIdentity(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return normalized;
        return normalized.indexOf(':') >= 0 ? normalized : "minecraft:" + normalized;
    }

    public static UUID owner(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        var key = NamespacedKey.fromString(OWNER_NAMESPACED_KEY);
        if (key == null) return null;
        String raw = stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static boolean isBound(ItemStack stack) {
        return identity(stack) != null && owner(stack) != null;
    }
}

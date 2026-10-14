package ru.arc.contracts.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Stable pure formatting helpers shared by the contract book and host surfaces. */
public final class ArcContractPresentation {
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter
            .ofPattern("EEEE, HH:mm", Locale.forLanguageTag("ru-RU"))
            .withZone(ZoneId.of("Europe/Moscow"));

    private ArcContractPresentation() {}

    public static String formatMoney(long minor) {
        return BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString();
    }

    public static String priceGrowth(long baseMinor, long currentMinor) {
        if (baseMinor <= 0) throw new IllegalArgumentException("Base contract price must be positive");
        BigDecimal change = BigDecimal.valueOf(currentMinor).subtract(BigDecimal.valueOf(baseMinor));
        String percent = change.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(baseMinor), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        return (currentMinor >= baseMinor ? "+" : "") + percent + "%";
    }

    /** Contract windows are end-exclusive; show the final accepting minute. */
    public static String formatDeadline(long windowEndsAtMillis) {
        return DEADLINE_FORMAT.format(Instant.ofEpochMilli(windowEndsAtMillis).minusMillis(1));
    }
}

package dev.tactical.engine.id;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Globally unique, human-readable match identifier: {@code MATCH-YYYYMMDD-NNNNNN}.
 * The numeric part is a per-day sequence persisted in the database, so ids never repeat
 * across restarts. Test matches use the {@code TEST-} prefix so they can never be confused
 * with real matches in logs or statistics.
 */
public record MatchId(String value) implements Comparable<MatchId> {

    private static final Pattern FORMAT = Pattern.compile("(MATCH|TEST)-\\d{8}-\\d{6}");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    public MatchId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid match id: " + value);
        }
    }

    public static MatchId of(LocalDate day, long sequence, boolean test) {
        if (sequence < 1 || sequence > 999_999) {
            throw new IllegalArgumentException("Daily match sequence out of range: " + sequence);
        }
        return new MatchId((test ? "TEST-" : "MATCH-") + DAY.format(day) + "-" + String.format("%06d", sequence));
    }

    public static boolean isValid(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }

    public boolean isTest() {
        return value.startsWith("TEST-");
    }

    @Override
    public int compareTo(MatchId o) {
        return value.compareTo(o.value);
    }

    @Override
    public String toString() {
        return value;
    }
}

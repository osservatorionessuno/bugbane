package org.osservatorionessuno.libmvt.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class Utils {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private Utils() {}
    public static String toIso(long epochMillis) {
        return ISO.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC));
    }
}

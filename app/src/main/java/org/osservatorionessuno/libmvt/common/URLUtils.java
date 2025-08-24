package org.osservatorionessuno.libmvt.common;

public final class URLUtils {
    private URLUtils() {}

    public static String normalize(String url) {
        if (url == null) return null;
        return url.trim().toLowerCase();
    }
}

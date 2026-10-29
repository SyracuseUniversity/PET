package com.atakmap.android.lingualinkledger.plugin;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Times for records: ISO 8601 in UTC, to the second, e.g.
 * 2026-10-12T14:03:27Z (D43). SimpleDateFormat with Locale.US and the UTC
 * zone, so the digits are ASCII whatever the phone's language, and it
 * works at minSdk 21 (java.time needs API 26 or desugaring).
 */
final class UtcTime {

    private UtcTime() {
    }

    static String format(long epochMillis) {
        return format("yyyy-MM-dd'T'HH:mm:ss'Z'", epochMillis);
    }

    /** The same instant in ISO 8601's basic form, e.g. 20261012T140327Z, for file names. */
    static String compact(long epochMillis) {
        return format("yyyyMMdd'T'HHmmss'Z'", epochMillis);
    }

    private static String format(String pattern, long epochMillis) {
        // a new formatter each time: SimpleDateFormat is not thread-safe
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(epochMillis));
    }
}

package com.atakmap.android.lingualinkledger.plugin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether the phone's position can go into a record, and how it
 * is labelled (V1.2, D43). Plain Java, so every rule is tested on the PC;
 * AtakPosition reads ATAK's values into a Reading.
 *
 * The position is the phone's own, from ATAK's location framework, never
 * the map. A record notes where it came from, its accuracy and the time of
 * the fix; a location set by hand in ATAK is never presented as GPS.
 * Every event needs a position, GPS or manual (S6): with neither, nothing
 * is saved and the message says what to do.
 */
final class PositionPolicy {

    // S5: constants in v1, settings later with A3a's settings screen.
    /** D43: a GPS fix OLDER than this is too old. Exactly 30 s is accepted. */
    static final long MAX_FIX_AGE_MS = 30_000;
    /**
     * A GPS fix timestamped up to this far ahead of the phone's clock counts
     * as "now" (small differences between GPS time and the phone's clock);
     * further ahead, it is refused, so a wrong clock cannot keep a fix fresh
     * for ever. Exactly 2 s is accepted. To be checked against what ATAK
     * reports on the S21+ (Day 1, step 5).
     */
    static final long MAX_FUTURE_MS = 2_000;
    /** D43: accuracy WORSE than this warns, but may still be saved. Exactly 50 m does not warn. */
    static final double WARN_ACCURACY_M = 50.0;

    static final String GPS_LABEL = "GPS fix";
    static final String MANUAL_LABEL = "Manual location - not GPS";
    static final String WAITING = "Waiting for GPS - move near a window or outside. To test "
            + "indoors, set your location in ATAK; it will be recorded as a manual location, not GPS.";

    /** Where the position came from (D43). */
    enum Source {
        /** A satellite fix: ATAK's GPS, DGPS, RTK or GPS_PPS. */
        GNSS,
        /** A location set by hand in ATAK ("USER"). */
        MANUAL,
        /** No position, or one of another kind. */
        NONE
    }

    private static final Set<String> GNSS_SOURCES =
            new HashSet<>(Arrays.asList("GPS", "DGPS", "RTK", "GPS_PPS"));
    private static final String USER = "USER";

    /**
     * The source, from what ATAK's location provider and the self marker
     * each report (GeoPointMetaData names). A hand-set location from either
     * is MANUAL. GNSS needs the provider to say so, with the self marker
     * agreeing or silent. Anything else, including a disagreement, is NONE.
     */
    static Source classify(String providerSource, String selfMarkerSource) {
        if (USER.equals(providerSource) || USER.equals(selfMarkerSource))
            return Source.MANUAL;
        if (GNSS_SOURCES.contains(providerSource)
                && (selfMarkerSource == null || GNSS_SOURCES.contains(selfMarkerSource)))
            return Source.GNSS;
        return Source.NONE;
    }

    /** What ATAK reported, as plain values. */
    static final class Reading {
        final Source source;
        final double latitude;
        final double longitude;
        /** ATAK's CE in metres; NaN (GeoPoint.UNKNOWN) when not reported. */
        final double accuracyM;
        /** When the fix was made, ms since 1970; 0 if unknown. */
        final long fixTimeMs;
        /** ATAK's own source names, kept in the record as reported. */
        final String providerSource;
        final String selfMarkerSource;

        Reading(Source source, double latitude, double longitude, double accuracyM, long fixTimeMs,
                String providerSource, String selfMarkerSource) {
            this.source = source;
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyM = accuracyM;
            this.fixTimeMs = fixTimeMs;
            this.providerSource = providerSource;
            this.selfMarkerSource = selfMarkerSource;
        }
    }

    enum Verdict { OK, OK_WITH_WARNING, REFUSED }

    /** The decision on one reading. */
    static final class Check {
        final Verdict verdict;
        /** Why it was refused, or the warning; null when simply OK. */
        final String message;
        final Reading reading;
        /** When the check was made (the capture time), ms since 1970. */
        final long checkedAtMs;

        private Check(Verdict verdict, String message, Reading reading, long checkedAtMs) {
            this.verdict = verdict;
            this.message = message;
            this.reading = reading;
            this.checkedAtMs = checkedAtMs;
        }

        boolean usable() {
            return verdict != Verdict.REFUSED;
        }

        /** "GPS fix" or "Manual location - not GPS"; null when refused. */
        String label() {
            if (!usable())
                return null;
            return reading.source == Source.MANUAL ? MANUAL_LABEL : GPS_LABEL;
        }

        /** The accuracy to record: metres for a GPS fix, null when unknown or manual. */
        Double accuracyM() {
            if (!usable() || reading.source != Source.GNSS || !knownAccuracy(reading.accuracyM))
                return null;
            return reading.accuracyM;
        }

        /** One line for the screen, e.g. "GPS fix, 43.037634, -76.135120, 8 m, 3 s old". */
        String summary() {
            if (!usable())
                return message;
            String s = label() + ", " + coord(reading.latitude) + ", " + coord(reading.longitude);
            if (reading.source == Source.GNSS) {
                Double acc = accuracyM();
                s += ", " + (acc == null ? "accuracy unknown" : Math.round(acc) + " m")
                        + ", " + Math.max(0, (checkedAtMs - reading.fixTimeMs) / 1000) + " s old";
            }
            return s;
        }

        /** The position block a record keeps (V1.2); only for a usable reading. */
        Map<String, Object> recordBlock() {
            if (!usable())
                throw new IllegalStateException("no position to record: " + message);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", reading.source == Source.MANUAL ? "manual" : "gnss");
            m.put("source_label", label());
            m.put("atak_provider_source", reading.providerSource);
            m.put("atak_self_marker_source", reading.selfMarkerSource);
            m.put("latitude", reading.latitude);
            m.put("longitude", reading.longitude);
            m.put("accuracy_m", accuracyM());
            m.put("accuracy_note", reading.source == Source.MANUAL
                    ? "none: a location set by hand"
                    : "ATAK's CE in metres; whether a 90% or a 68% radius is not yet confirmed");
            m.put("fix_time_utc", reading.fixTimeMs > 0 ? UtcTime.format(reading.fixTimeMs) : null);
            m.put("fix_age_s", reading.source == Source.GNSS
                    ? Math.max(0, (checkedAtMs - reading.fixTimeMs) / 1000) : null);
            m.put("warning", verdict == Verdict.OK_WITH_WARNING ? message : null);
            return m;
        }
    }

    private PositionPolicy() {
    }

    /** The rules of V1.2 and D43, applied at nowMs (the capture time). */
    static Check check(Reading r, long nowMs) {
        if (r == null || r.source == Source.NONE)
            return refuse(WAITING, r, nowMs);
        if (!finite(r.latitude) || !finite(r.longitude))
            return refuse("No usable position: ATAK's position is missing a coordinate.", r, nowMs);
        if (Math.abs(r.latitude) > 90.0 || Math.abs(r.longitude) > 180.0)
            return refuse("No usable position: ATAK reports an impossible position ("
                    + r.latitude + ", " + r.longitude + ").", r, nowMs);
        // compared as numbers, so -0.0 is caught too
        if (r.latitude == 0.0 && r.longitude == 0.0)
            return refuse("No usable position: ATAK reports 0, 0, which is not a real fix. "
                    + WAITING, r, nowMs);

        if (r.source == Source.MANUAL)
            return new Check(Verdict.OK, null, r, nowMs); // labelled manual; no age or accuracy rule

        // GNSS from here
        if (r.fixTimeMs <= 0)
            return refuse("No usable position: the GPS fix has no time, so its age cannot be "
                    + "checked. " + WAITING, r, nowMs);
        long ahead = r.fixTimeMs - nowMs;
        if (ahead > MAX_FUTURE_MS)
            return refuse("No usable position: the GPS fix is timestamped " + seconds(ahead)
                    + " s ahead of the phone's clock. Check the phone's date and time.", r, nowMs);
        long age = nowMs - r.fixTimeMs;
        if (age > MAX_FIX_AGE_MS)
            return refuse("Waiting for GPS - the last fix is " + seconds(age) + " s old (the limit is "
                    + MAX_FIX_AGE_MS / 1000 + " s). Move near a window or outside.", r, nowMs);
        if (!knownAccuracy(r.accuracyM))
            return new Check(Verdict.OK_WITH_WARNING, "GPS accuracy was not reported. The position "
                    + "will be saved with its accuracy unknown.", r, nowMs);
        if (r.accuracyM > WARN_ACCURACY_M)
            return new Check(Verdict.OK_WITH_WARNING, "GPS accuracy is " + Math.round(r.accuracyM)
                    + " m, worse than " + Math.round(WARN_ACCURACY_M) + " m. The position will be saved "
                    + "with that accuracy.", r, nowMs);
        return new Check(Verdict.OK, null, r, nowMs);
    }

    /** A real accuracy figure: finite and above zero (0 m or less is not a measurement). */
    static boolean knownAccuracy(double accuracyM) {
        return finite(accuracyM) && accuracyM > 0;
    }

    /** Not NaN or infinite. (Double.isFinite needs API 24; the plugin's minSdk is 21.) */
    static boolean finite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static Check refuse(String why, Reading r, long nowMs) {
        return new Check(Verdict.REFUSED, why, r, nowMs);
    }

    private static String seconds(long ms) {
        return String.format(Locale.US, "%.1f", ms / 1000.0);
    }

    private static String coord(double d) {
        return String.format(Locale.US, "%.6f", d);
    }
}

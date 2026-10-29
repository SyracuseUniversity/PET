package com.atakmap.android.lingualinkledger.plugin;

import static com.atakmap.android.lingualinkledger.plugin.PositionPolicy.Source.GNSS;
import static com.atakmap.android.lingualinkledger.plugin.PositionPolicy.Source.MANUAL;
import static com.atakmap.android.lingualinkledger.plugin.PositionPolicy.Source.NONE;
import static com.atakmap.android.lingualinkledger.plugin.PositionPolicy.Verdict.OK;
import static com.atakmap.android.lingualinkledger.plugin.PositionPolicy.Verdict.OK_WITH_WARNING;
import static com.atakmap.android.lingualinkledger.plugin.PositionPolicy.Verdict.REFUSED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * The position rules of V1.2 and D43, with each boundary decided on
 * purpose: exactly 30 s old is accepted (D43: OLDER than 30 s is too old);
 * exactly 50 m does not warn (D43: WORSE than 50 m warns); a fix up to 2 s
 * ahead of the phone's clock counts as now, further ahead is refused.
 */
public class PositionPolicyTest {

    private static final long NOW = 1_791_835_200_000L; // 2026-10-12T20:00:00Z, a fixed "now" (computed in Python)
    private static final double LAT = 43.037634, LON = -76.135120; // Syracuse University Quad (public)
    private static final double NO_CE = Double.NaN; // GeoPoint.UNKNOWN

    private static PositionPolicy.Reading gnss(double lat, double lon, double ce, long fixTime) {
        return new PositionPolicy.Reading(GNSS, lat, lon, ce, fixTime, "GPS", "GPS");
    }

    private static PositionPolicy.Reading manual(double lat, double lon, double ce, long fixTime) {
        return new PositionPolicy.Reading(MANUAL, lat, lon, ce, fixTime, "USER", "USER");
    }

    private static PositionPolicy.Check check(PositionPolicy.Reading r) {
        return PositionPolicy.check(r, NOW);
    }

    // --- a good fix ---

    @Test
    public void aFreshAccurateGpsFixIsUsed() {
        PositionPolicy.Check c = check(gnss(LAT, LON, 8.0, NOW - 3_000));
        assertEquals(OK, c.verdict);
        assertEquals("GPS fix", c.label());
        assertEquals(8.0, c.accuracyM(), 0);
        assertEquals("GPS fix, 43.037634, -76.135120, 8 m, 3 s old", c.summary());
    }

    // --- no position, or not a real one ---

    @Test
    public void noSourceIsRefusedWithTheWaitingMessage() {
        PositionPolicy.Check c = check(new PositionPolicy.Reading(NONE, LAT, LON, 5, NOW, "???", null));
        assertEquals(REFUSED, c.verdict);
        assertEquals(PositionPolicy.WAITING, c.message);
        assertNull(c.label());
        assertEquals(REFUSED, check(null).verdict);
    }

    @Test
    public void zeroZeroIsRefusedIncludingNegativeZero() {
        for (double[] p : new double[][] {{0.0, 0.0}, {-0.0, -0.0}, {0.0, -0.0}, {-0.0, 0.0}}) {
            assertEquals(p[0] + "," + p[1], REFUSED, check(gnss(p[0], p[1], 5, NOW)).verdict);
            assertEquals("manual " + p[0] + "," + p[1], REFUSED, check(manual(p[0], p[1], NO_CE, NOW)).verdict);
        }
        // only both zero is refused: the equator and the prime meridian are real places
        assertEquals(OK, check(gnss(0.0, 9.5, 5, NOW)).verdict);
        assertEquals(OK, check(gnss(51.48, 0.0, 5, NOW)).verdict);
    }

    @Test
    public void aMissingCoordinateIsRefused() {
        double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double b : bad) {
            assertEquals("lat " + b, REFUSED, check(gnss(b, LON, 5, NOW)).verdict);
            assertEquals("lon " + b, REFUSED, check(gnss(LAT, b, 5, NOW)).verdict);
            assertEquals("manual lat " + b, REFUSED, check(manual(b, LON, NO_CE, NOW)).verdict);
        }
    }

    @Test
    public void anImpossiblePositionIsRefused() {
        assertEquals(OK, check(gnss(90.0, LON, 5, NOW)).verdict);
        assertEquals(OK, check(gnss(-90.0, LON, 5, NOW)).verdict);
        assertEquals(REFUSED, check(gnss(90.000001, LON, 5, NOW)).verdict);
        assertEquals(REFUSED, check(gnss(-90.000001, LON, 5, NOW)).verdict);
        assertEquals(OK, check(gnss(LAT, 180.0, 5, NOW)).verdict);
        assertEquals(OK, check(gnss(LAT, -180.0, 5, NOW)).verdict);
        assertEquals(REFUSED, check(gnss(LAT, 180.000001, 5, NOW)).verdict);
        assertEquals(REFUSED, check(gnss(LAT, -180.000001, 5, NOW)).verdict);
        assertEquals(REFUSED, check(manual(123.0, LON, NO_CE, NOW)).verdict);
    }

    // --- the age of a GPS fix ---

    @Test
    public void exactlyThirtySecondsOldIsAcceptedAndOneMillisecondMoreIsNot() {
        assertEquals(OK, check(gnss(LAT, LON, 5, NOW - 30_000)).verdict);
        PositionPolicy.Check old = check(gnss(LAT, LON, 5, NOW - 30_001));
        assertEquals(REFUSED, old.verdict);
        assertTrue(old.message, old.message.startsWith("Waiting for GPS - the last fix is 30.0 s old"));
        assertEquals(REFUSED, check(gnss(LAT, LON, 5, NOW - 3_600_000)).verdict);
    }

    @Test
    public void aFixInTheFutureCannotStayFresh() {
        // up to 2 s ahead: clock jitter, counted as now
        assertEquals(OK, check(gnss(LAT, LON, 5, NOW + 2_000)).verdict);
        assertEquals("0 s old", tail(check(gnss(LAT, LON, 5, NOW + 2_000)).summary()));
        // further ahead: refused, however far
        for (long ahead : new long[] {2_001, 60_000, 3_600_000, 365L * 86_400_000}) {
            PositionPolicy.Check c = check(gnss(LAT, LON, 5, NOW + ahead));
            assertEquals("ahead " + ahead, REFUSED, c.verdict);
            assertTrue(c.message, c.message.contains("ahead of the phone's clock"));
        }
    }

    @Test
    public void aGpsFixWithNoTimeIsRefused() {
        assertEquals(REFUSED, check(gnss(LAT, LON, 5, 0)).verdict);
        assertEquals(REFUSED, check(gnss(LAT, LON, 5, -1)).verdict);
    }

    // --- accuracy ---

    @Test
    public void exactlyFiftyMetresDoesNotWarnAndAnyWorseDoes() {
        assertEquals(OK, check(gnss(LAT, LON, 50.0, NOW)).verdict);
        PositionPolicy.Check worse = check(gnss(LAT, LON, 50.01, NOW));
        assertEquals(OK_WITH_WARNING, worse.verdict);
        assertTrue(worse.usable());
        assertEquals(50.01, worse.accuracyM(), 0);
        assertTrue(worse.message, worse.message.startsWith("GPS accuracy is 50 m, worse than 50 m"));
        assertEquals(OK_WITH_WARNING, check(gnss(LAT, LON, 2_000.0, NOW)).verdict);
    }

    @Test
    public void aGpsFixWithNoAccuracyFigureWarnsAndRecordsUnknown() {
        for (double ce : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0.0, -3.0}) {
            PositionPolicy.Check c = check(gnss(LAT, LON, ce, NOW));
            assertEquals("ce " + ce, OK_WITH_WARNING, c.verdict);
            assertNull("ce " + ce, c.accuracyM());
            assertTrue(c.summary(), c.summary().contains("accuracy unknown"));
        }
    }

    // --- a location set by hand in ATAK ---

    @Test
    public void aManualLocationIsLabelledManualWithNoAccuracyWarning() {
        PositionPolicy.Check c = check(manual(LAT, LON, NO_CE, NOW - 5_000));
        assertEquals(OK, c.verdict); // not a 50 m warning
        assertNull(c.message);
        assertEquals("Manual location - not GPS", c.label());
        assertNull(c.accuracyM());
        assertEquals("Manual location - not GPS, 43.037634, -76.135120", c.summary());
        // a figure ATAK might attach to a hand-placed point is not recorded as accuracy
        assertNull(check(manual(LAT, LON, 500.0, NOW)).accuracyM());
        assertEquals(OK, check(manual(LAT, LON, 500.0, NOW)).verdict);
    }

    @Test
    public void aManualLocationHasNoAgeRule() {
        assertEquals(OK, check(manual(LAT, LON, NO_CE, NOW - 86_400_000)).verdict);
        assertEquals(OK, check(manual(LAT, LON, NO_CE, 0)).verdict); // time unknown
    }

    // --- the record's position block ---

    @Test
    public void theRecordBlockForAGpsFix() {
        Map<String, Object> m = check(gnss(LAT, LON, 8.0, NOW - 3_000)).recordBlock();
        assertEquals("gnss", m.get("source"));
        assertEquals("GPS fix", m.get("source_label"));
        assertEquals("GPS", m.get("atak_provider_source"));
        assertEquals(LAT, (Double) m.get("latitude"), 0);
        assertEquals(LON, (Double) m.get("longitude"), 0);
        assertEquals(8.0, (Double) m.get("accuracy_m"), 0);
        assertEquals("2026-10-12T19:59:57Z", m.get("fix_time_utc"));
        assertEquals(3L, m.get("fix_age_s"));
        assertNull(m.get("warning"));
        // it survives a record file unchanged
        assertEquals(Json.write(m), Json.write(Json.parse(Json.write(m))));
    }

    @Test
    public void theRecordBlockForAManualLocation() {
        Map<String, Object> m = check(manual(LAT, LON, NO_CE, 0)).recordBlock();
        assertEquals("manual", m.get("source"));
        assertEquals("Manual location - not GPS", m.get("source_label"));
        assertNull(m.get("accuracy_m"));
        assertNull(m.get("fix_time_utc"));
        assertNull(m.get("fix_age_s"));
    }

    @Test(expected = IllegalStateException.class)
    public void aRefusedReadingHasNoRecordBlock() {
        check(gnss(0.0, 0.0, 5, NOW)).recordBlock();
    }

    // --- naming the source from ATAK's values ---

    @Test
    public void classifyingAtaksSources() {
        for (String s : new String[] {"GPS", "DGPS", "RTK", "GPS_PPS"}) {
            assertEquals(s, GNSS, PositionPolicy.classify(s, s));
            assertEquals(s + " with no self-marker source", GNSS, PositionPolicy.classify(s, null));
        }
        assertEquals(MANUAL, PositionPolicy.classify("USER", "USER"));
        assertEquals("either says USER", MANUAL, PositionPolicy.classify("GPS", "USER"));
        assertEquals(MANUAL, PositionPolicy.classify(null, "USER"));
        for (String s : new String[] {"???", "CALC", "ESTIMATED", "COT", "DTED0", "", null})
            assertEquals(String.valueOf(s), NONE, PositionPolicy.classify(s, s));
        assertEquals("disagreeing: not GPS", NONE, PositionPolicy.classify("GPS", "???"));
        assertEquals("keys are exact", NONE, PositionPolicy.classify("gps", null));
    }

    private static String tail(String summary) {
        return summary.substring(summary.lastIndexOf(", ") + 2);
    }
}

package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class JsonTest {

    @Test
    @SuppressWarnings("unchecked")
    public void objectsArraysAndValues() {
        Map<String, Object> m = (Map<String, Object>) Json.parse(
                " {\"a\": 1, \"b\": [true, false, null], \"c\": {\"d\": -2.5e1}, \"e\": \"x\"} ");
        assertEquals(Arrays.asList("a", "b", "c", "e"), Arrays.asList(m.keySet().toArray()));
        assertEquals(1L, m.get("a"));
        assertEquals(Arrays.asList(true, false, null), m.get("b"));
        assertEquals(-25.0, ((Map<String, Object>) m.get("c")).get("d"));
        assertEquals("x", m.get("e"));
        assertEquals(Arrays.asList(), Json.parse("[]"));
        assertEquals(0, ((Map<?, ?>) Json.parse("{}")).size());
        assertNull(Json.parse("null"));
    }

    @Test
    public void stringEscapes() {
        assertEquals("tab\t nl\n quote\" back\\ slash/ nbsp\u00a0 dotless\u0131",
                Json.parse("\"tab\\t nl\\n quote\\\" back\\\\ slash\\/ nbsp\\u00a0 dotless\\u0131\""));
        // a character outside the BMP, as a surrogate pair
        assertEquals("\uD83C\uDF32", Json.parse("\"\\ud83c\\udf32\""));
    }

    @Test
    public void malformedInputIsRefused() {
        for (String bad : new String[] {"", "{", "[1,]", "{\"a\" 1}", "\"open", "tru", "{} x",
                "{\"a\":1,\"a\":2}", "\"\\q\"", "\"a\nb\""}) {
            try {
                Json.parse(bad);
                fail("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                // refused, as it should be
            }
        }
    }

    /**
     * Typed text (package name, description, author) must survive a record
     * file unchanged, whatever it holds: written, turned into UTF-8 bytes,
     * decoded strictly, read back, it is the same string, and the file
     * holds no raw control character that could split or break it.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void typedTextRoundTripsUnchanged() throws Exception {
        StringBuilder controls = new StringBuilder();
        for (char c = 0; c < 0x20; c++)
            controls.append(c);
        String[] awkward = {
                "", "plain", "say \"hello\"", "back\\slash", "C:\\atak\\tools\\", "\\\"", "\"\\\"",
                "line one\nline two", "crlf\r\nend", "tab\there", "\b\f", controls.toString(),
                "DEL\u007f", "separators \u2028 and \u2029", "slash / and </script>",
                "{\"looks\": [\"like\", \"json\"]}", "key=value|PET1|id=SAR-TEST-0001",
                "Épinette blanche", "Weißtanne", "红松", "صنوبر", "🌲 tree", "e\u0301 (combining)",
                "rtl\u200fmark", "\ufeffBOM first", "\uffff", "\u0000null char",
                "lone high \ud800", "lone low \udfff", "\udc00\ud800 reversed pair", "\ud800",
                "a".repeat(10_000)};
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        for (int i = 0; i < awkward.length; i++)
            record.put("field" + i, awkward[i]);
        // awkward keys too
        record.put("key with \"quotes\" and \\ and \n", "value");
        record.put("", "empty key");

        String written = Json.write(record);
        for (int i = 0; i < written.length(); i++) {
            char c = written.charAt(i);
            if (c < 0x20 || c == 0x7F)
                fail("raw control character " + (int) c + " at " + i);
        }
        byte[] utf8 = written.getBytes(StandardCharsets.UTF_8);
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(utf8)).toString();
        Map<String, Object> back = (Map<String, Object>) Json.parse(decoded);
        assertEquals(new ArrayList<>(record.keySet()), new ArrayList<>(back.keySet()));
        for (Map.Entry<String, Object> e : record.entrySet())
            assertEquals("value of " + Json.write(e.getKey()), e.getValue(), back.get(e.getKey()));
    }

    @Test
    public void numbersRoundTripExactly() {
        double[] values = {43.037634, -76.135120, 0.1, -0.0, 1e-7, 180.0, -90.0, 12.5,
                Double.MAX_VALUE, Double.MIN_VALUE};
        for (double v : values) {
            List<?> back = (List<?>) Json.parse(Json.write(Arrays.asList(v)));
            assertEquals("bits of " + v, Double.doubleToLongBits(v),
                    Double.doubleToLongBits((Double) back.get(0)));
        }
        assertEquals("[1,-2,30000]", Json.write(Arrays.asList(1, -2L, 30000)));
        // whole numbers come back whole, so a record re-written is byte-identical
        assertEquals(Arrays.asList(1L, -2L, 30000L, 9007199254740993L),
                Json.parse("[1,-2,30000,9007199254740993]"));
        assertEquals("[3,3.0,1.0E-7]", Json.write(Json.parse("[3,3.0,1.0E-7]")));
        // too big for a long: a Double
        assertEquals(1e20, (Double) ((List<?>) Json.parse("[100000000000000000000]")).get(0), 0);
    }

    @Test
    public void nanAndInfinityAreRefused() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            try {
                Json.write(Arrays.asList(bad));
                fail("wrote " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void nestedValuesRoundTrip() {
        Map<String, Object> inner = new java.util.LinkedHashMap<>();
        inner.put("lat", 43.07);
        inner.put("source", "manual");
        inner.put("accuracy_m", null);
        Map<String, Object> outer = new java.util.LinkedHashMap<>();
        outer.put("parents", Arrays.asList("SAR-TEST-0001", "SAR-TEST-0002"));
        outer.put("position", inner);
        outer.put("test", true);
        String once = Json.write(outer);
        assertEquals(once, Json.write(Json.parse(once)));
        assertEquals(inner, ((Map<String, Object>) Json.parse(once)).get("position"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void readsTheTestSetManifest() throws Exception {
        Map<String, Object> m = TestQrSet.manifest();
        List<Object> codes = (List<Object>) m.get("codes");
        assertEquals("PET1|id=SAR-TEST-0001|species=Red pine|test=1",
                ((Map<String, Object>) codes.get(0)).get("payload"));
    }
}

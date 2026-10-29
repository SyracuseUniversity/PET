package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The label reader against the user's test set, version 1.1 (D53): its 15
 * codes and its 26 text-only rule cases, each with its expected batch ID
 * and test flag, or its refusal's reason code.
 */
public class LabelReaderTest {

    @Test
    public void everyCodeInTheManifest() throws Exception {
        List<Map<String, Object>> codes = TestQrSet.list(TestQrSet.manifest(), "codes");
        assertEquals("codes in the manifest", 15, codes.size());
        checkAll(codes, "ref");
    }

    @Test
    public void everyTextOnlyRuleCase() throws Exception {
        List<Map<String, Object>> cases = TestQrSet.list(TestQrSet.manifest(), "string_cases");
        assertEquals("text-only cases in the manifest", 26, cases.size());
        checkAll(cases, "tests");
    }

    /** Checks every entry, then reports every one that failed, not just the first. */
    private static void checkAll(List<Map<String, Object>> entries, String nameKey) {
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> c : entries) {
            try {
                check((String) c.get(nameKey), c);
            } catch (AssertionError e) {
                failures.add(e.getMessage());
            }
        }
        if (!failures.isEmpty())
            fail(failures.size() + " of " + entries.size() + " wrong:\n  " + String.join("\n  ", failures));
    }

    /** One manifest entry: payload, expect, batch_id, test, reason. */
    private static void check(String name, Map<String, Object> c) {
        String payload = (String) c.get("payload");
        LabelReader.Result r = LabelReader.read(payload);
        if ("accept".equals(c.get("expect"))) {
            assertTrue(name + ": expected accepted, was refused as "
                    + (r.refusal == null ? "?" : r.refusal.code), r.accepted());
            assertEquals(name + ": batch ID", c.get("batch_id"), r.label.batchId);
            assertEquals(name + ": test flag", c.get("test"), r.label.test);
            // the codes list each label's species; the text-only cases do not
            if (c.containsKey("species"))
                assertEquals(name + ": species", c.get("species"), r.label.species);
        } else {
            assertEquals(name + ": expect", "refuse", c.get("expect"));
            assertFalse(name + ": expected refused, was accepted", r.accepted());
            assertEquals(name + ": reason code", c.get("reason"), r.refusal.code);
            assertNull(name + ": no label when refused", r.label);
        }
    }

    @Test
    public void speciesIsKeptAsPrinted() {
        LabelReader.Result r = LabelReader.read("PET1|id=SAR-TEST-0007|species=White spruce|test=1");
        assertEquals("White spruce", r.label.species);
        assertTrue(r.label.test);
    }

    @Test
    public void nullIsNotAPetLabel() {
        assertEquals(LabelReader.Refusal.NOT_PET_LABEL, LabelReader.read(null).refusal);
    }

    @Test
    public void emptyAfterThePrefixIsMalformed() {
        assertEquals(LabelReader.Refusal.MALFORMED, LabelReader.read("PET1|").refusal);
    }
}

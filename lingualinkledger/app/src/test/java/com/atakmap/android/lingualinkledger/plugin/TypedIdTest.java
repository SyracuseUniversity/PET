package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A typed ID (the last fallback, S3) goes through the same ID rule as a
 * scanned one, and with no label read its test status is recorded as
 * unknown, not as a real label (the user, Day 2 step 8).
 */
public class TypedIdTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Every case in the test set (v1.1) whose result depends on the ID
     * alone: typed, the ID gives exactly what the label reader gives.
     */
    @Test
    public void aTypedIdMeetsTheSameRuleAsAScannedOne() throws Exception {
        Map<String, Object> m = TestQrSet.manifest();
        List<Map<String, Object>> cases = new ArrayList<>(TestQrSet.list(m, "codes"));
        cases.addAll(TestQrSet.list(m, "string_cases"));
        int checked = 0;
        for (Map<String, Object> c : cases) {
            String payload = (String) c.get("payload");
            String rawId = idField(payload);
            LabelReader.Result scanned = LabelReader.read(payload);
            boolean idDecides = scanned.accepted() || scanned.refusal == LabelReader.Refusal.INVALID_ID;
            if (rawId == null || !idDecides)
                continue;
            Capture typed = Capture.typed(new File("photo.jpg"), rawId, TestCaptures.T0, TestCaptures.gps(TestCaptures.T0));
            String what = "id \"" + rawId + "\" from " + payload;
            if (scanned.accepted())
                assertEquals(what, scanned.label.batchId, typed.batchId);
            else
                assertNull(what + " should be refused typed, as it is scanned", typed);
            checked++;
        }
        assertTrue("checked " + checked, checked >= 15);
    }

    @Test
    public void aTypedIdIsTrimmedAndUpperCasedLikeAScannedOne() {
        Capture c = Capture.typed(new File("photo.jpg"), "  sar-test-0001 ", TestCaptures.T0, TestCaptures.gps(TestCaptures.T0));
        assertEquals("SAR-TEST-0001", c.batchId);
        assertNull(Capture.typed(new File("p.jpg"), "SAR TEST/0011", TestCaptures.T0, TestCaptures.gps(TestCaptures.T0)));
        assertNull(Capture.typed(new File("p.jpg"), "", TestCaptures.T0, TestCaptures.gps(TestCaptures.T0)));
    }

    @Test
    public void aTypedIdsTestStatusIsUnknownAllTheWayThrough() throws Exception {
        File camera = tmp.newFolder("camera");
        Capture read = TestCaptures.of(camera, "SAR-TEST-0001", TestCaptures.T0);
        Capture typed = Capture.typed(read.photo, "SAR-TEST-0001", TestCaptures.T0, TestCaptures.gps(TestCaptures.T0));
        assertEquals(Capture.IdRead.TYPED, typed.idRead);
        assertNull("unknown, not false", typed.testLabel);
        assertNull(typed.species);

        LedgerStore store = new LedgerStore(tmp.newFolder("lingualinkledger"));
        Map<String, Object> saved = store.record(EventDraft.collect(typed), "1.0 (test)", TestCaptures.T0);
        assertTrue("recorded, as null", saved.containsKey("test_label"));
        assertNull(saved.get("test_label"));
        @SuppressWarnings("unchecked")
        Map<String, Object> photo = ((List<Map<String, Object>>) saved.get("photos")).get(0);
        assertEquals("typed", photo.get("id_read"));
        assertNull(photo.get("test_label"));
        assertNull("unknown after a reload too", new LedgerStore(store.ledgerFile().getParentFile())
                .ledger().batch("SAR-TEST-0001").testLabel);
    }

    /** The value of the label's id field, as typed before any rule; null if it has none. */
    private static String idField(String payload) {
        if (payload == null || !payload.startsWith(LabelReader.PREFIX))
            return null;
        for (String field : payload.substring(LabelReader.PREFIX.length()).split("\\|", -1))
            if (field.startsWith("id="))
                return field.substring(3);
        return null;
    }
}

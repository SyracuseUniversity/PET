package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What Saved batches and Look up batch show (step 13; D59): each batch's
 * stage, what closed it, what it was combined from, its events with their
 * packages' full SHA-256, and the next stage from the stage table (S7),
 * on the test set's scenario (v1.1), saved as on the phone.
 */
public class BatchViewTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File camera, folder;
    private LedgerStore store;
    private long clock = TestCaptures.T0;

    @Before
    public void setUp() throws IOException {
        camera = tmp.newFolder("camera");
        folder = tmp.newFolder("lingualinkledger");
        store = new LedgerStore(folder);
        AtomicFile.renamer = (from, to) -> {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                return true;
            } catch (IOException e) {
                return false;
            }
        };
    }

    @After
    public void tearDown() {
        AtomicFile.renamer = File::renameTo;
    }

    @Test
    public void theNextStageFollowsTheStageTable() throws Exception {
        Ledger l = store.ledger();
        assertEquals("Not recorded on this phone yet: record it with Collecting.", BatchView.next(l, "SAR-TEST-0001"));
        collect("SAR-TEST-0001");
        assertEquals("Next: Cleaning.", BatchView.next(store.ledger(), "SAR-TEST-0001"));
        merge(Stage.CLEANING, "SAR-TEST-0004", "SAR-TEST-0001");
        assertEquals("Closed (combined into SAR-TEST-0004): it can take no more events.",
                BatchView.next(store.ledger(), "SAR-TEST-0001"));
        assertEquals("Next: Stored (one batch) or Stored (Combine batches).",
                BatchView.next(store.ledger(), "SAR-TEST-0004"));
        event(Stage.STORED, "SAR-TEST-0004");
        assertEquals("Next: Stored (one batch), Stored (Combine batches) or Planting.",
                BatchView.next(store.ledger(), "SAR-TEST-0004"));
        event(Stage.PLANTING, "SAR-TEST-0004");
        assertEquals("Closed (sown): it can take no more events.", BatchView.next(store.ledger(), "SAR-TEST-0004"));
    }

    @Test
    public void aBatchShowsItsStageLineageAndEveryEventsPackage() throws Exception {
        saveScenario();
        Ledger l = store.ledger();
        Ledger.Batch four = l.batch("SAR-TEST-0004");
        String d = BatchView.detail(l, four);
        assertTrue(d, d.startsWith("SAR-TEST-0004 (TEST LABEL)\nSpecies, as printed (not checked): Red pine"));
        assertTrue(d, d.contains("Stage: Stored; closed, combined into SAR-TEST-0006"));
        assertTrue(d, d.contains("Combined from: SAR-TEST-0001, SAR-TEST-0002, SAR-TEST-0003"));
        assertTrue(d, d.contains("Closed (combined into SAR-TEST-0006): it can take no more events."));
        assertTrue(d, d.contains("Events (3):"));
        assertTrue(d, d.contains("4. Cleaning: made by combining SAR-TEST-0001, SAR-TEST-0002, SAR-TEST-0003"));
        assertTrue(d, d.contains("7. Stored\n"));
        assertTrue(d, d.contains("8. Stored: combined into SAR-TEST-0006"));
        for (Map<String, Object> e : four.events)
            assertTrue("each event's full SHA-256", d.contains("SHA-256: " + e.get("package_sha256")));
        assertTrue(d, d.contains("Position: 43.037634, -76.13512 ("));
        assertTrue(d, d.contains(", 8 m)"));
        assertFalse(d.contains("Rebuilt from packages"));

        assertEquals("SAR-TEST-0006: closed, sown (TEST LABEL)", BatchView.line(l.batch("SAR-TEST-0006")));
        assertEquals("SAR-TEST-0013: closed, combined into SAR-TEST-0006 (TEST LABEL)",
                BatchView.line(l.batch("SAR-TEST-0013")));
        String six = BatchView.detail(l, l.batch("SAR-TEST-0006"));
        assertTrue(six, six.contains("9. Planting: sown"));
    }

    @Test
    public void aTypedIdShowsItsTestStatusAsUnknown() throws Exception {
        clock += 20_000;
        Capture c = TestCaptures.of(camera, "SAR-TEST-0005", clock);
        Capture typed = Capture.typed(c.photo, "sar-test-0005", clock, c.position);
        store.record(EventDraft.collect(typed), "1.0 (test)", clock);
        Ledger l = store.ledger();
        assertEquals("SAR-TEST-0005: Collecting, open (test status unknown: ID typed)",
                BatchView.line(l.batch("SAR-TEST-0005")));
    }

    @Test
    public void aRebuiltEventSaysSo() throws Exception {
        collect("SAR-TEST-0001");
        Files.write(new File(folder, "ledger.json").toPath(), "{ damaged".getBytes(StandardCharsets.UTF_8));
        LedgerStore after = new LedgerStore(folder);
        Ledger l = after.ledger();
        String d = BatchView.detail(l, l.batch("SAR-TEST-0001"));
        assertTrue(d, d.contains("Rebuilt from packages: its SHA-256 was recalculated from the stored file."));
    }

    @Test
    public void theToolsRecordsAreAListOfTheirOwn() throws Exception {
        Ledger l = store.ledger();
        assertEquals("Records from the tools (0):\nNone yet.", BatchView.tools(l));

        Map<String, Object> trace = new java.util.LinkedHashMap<>();
        trace.put("tool_seq", 1L);
        trace.put("format", ToolRecords.FORMAT);
        trace.put("kind", ToolRecords.KIND_TRACE);
        trace.put("package_file", "Red_pine_lot.zip");
        trace.put("package_sha256", "ab".repeat(32));
        trace.put("stage", "Stored");
        trace.put("traced_utc", "2026-10-13T20:30:00Z");
        l = l.withToolRecord(trace);
        Map<String, Object> ev = ToolRecords.stageEvent(trace, Stage.PLANTING, TestCaptures.gps(clock), "bed 4",
                clock, "1.0 (test)");
        ev.put("tool_seq", 2L);
        l = l.withToolRecord(ev);
        String t = BatchView.tools(l);
        assertTrue(t, t.startsWith("Records from the tools (2):\n\nTrace 1: Red_pine_lot.zip"));
        assertTrue(t, t.contains("stage, from its metadata: Stored"));
        assertTrue(t, t.contains("SHA-256: " + "ab".repeat(32)));
        assertTrue(t, t.contains("+ Stage event 2: Planting, 2026-10-12T22:15:00Z"));
        assertTrue(t, t.contains("Remarks (typed, not checked): bed 4"));
        assertEquals("the batches are untouched (T3)", 0, l.eventCount());
    }

    // --- helpers -------------------------------------------------------

    private void saveScenario() throws Exception {
        collect("SAR-TEST-0001");
        collect("SAR-TEST-0002");
        collect("SAR-TEST-0003");
        merge(Stage.CLEANING, "SAR-TEST-0004", "SAR-TEST-0001", "SAR-TEST-0002", "SAR-TEST-0003");
        collect("SAR-TEST-0005");
        merge(Stage.CLEANING, "SAR-TEST-0013", "SAR-TEST-0005");
        event(Stage.STORED, "SAR-TEST-0004");
        merge(Stage.STORED, "SAR-TEST-0006", "SAR-TEST-0004", "SAR-TEST-0013");
        event(Stage.PLANTING, "SAR-TEST-0006");
    }

    private Capture capture(String id) throws IOException {
        clock += 20_000;
        return TestCaptures.of(camera, id, clock);
    }

    private void collect(String id) throws Exception {
        store.record(EventDraft.collect(capture(id)), "1.0 (test)", clock);
    }

    private void event(Stage stage, String id) throws Exception {
        store.record(EventDraft.stageEvent(stage, capture(id)), "1.0 (test)", clock);
    }

    private void merge(Stage stage, String child, String... parents) throws Exception {
        List<Capture> ps = new ArrayList<>();
        for (String p : parents)
            ps.add(capture(p));
        store.record(EventDraft.merge(stage, ps, capture(child)), "1.0 (test)", clock);
    }
}

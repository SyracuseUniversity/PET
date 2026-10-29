package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ledger on the phone, saved through LedgerStore: the test set's
 * scenario (v1.1) end to end with real packages, and the user's
 * requirement 1 (Day 2 step 6): ledger.json is only ever replaced whole,
 * so a save cut short leaves it as it was, never half-written, and never
 * holding a merge's new batch while its parents are still open.
 */
public class LedgerStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File camera, folder;
    private long clock = TestCaptures.T0;

    @Before
    public void setUp() throws IOException {
        camera = tmp.newFolder("camera");
        folder = tmp.newFolder("lingualinkledger");
        // File.renameTo replaces its target on the phone, not on Windows: see AtomicFile
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
    public void theScenarioIsSavedAndEveryPackageMatchesItsRecord() throws Exception {
        saveScenario(new LedgerStore(folder));

        // read back from the disk, as after ATAK restarts
        LedgerStore again = new LedgerStore(folder);
        Ledger l = again.ledger();
        assertEquals(9, l.eventCount());
        assertEquals("sown", l.batch("SAR-TEST-0006").closedBy);
        assertEquals("combined into SAR-TEST-0006", l.batch("SAR-TEST-0013").closedBy);
        assertEquals(Arrays.asList("SAR-TEST-0004", "SAR-TEST-0013"), l.batch("SAR-TEST-0006").combinedFrom);

        // each package re-checked as a teammate would: its SHA-256 is the one recorded
        for (Map<String, Object> e : l.events()) {
            File pkg = new File(new File(folder, "packages"), (String) e.get("package_file"));
            assertEquals(e.get("package_file").toString(), e.get("package_sha256"),
                    TestCaptures.sha256(Files.readAllBytes(pkg.toPath())));
        }
        assertTrue(again.unrecordedPackages().isEmpty());
        assertNull("nothing to tell", again.takeNotice());
        assertEquals(Collections.singletonList("packages"), names(folder, "ledger.json"));
    }

    /** The test set's scenario, v1.1: 9 events. */
    private void saveScenario(LedgerStore store) throws Exception {
        collect(store, "SAR-TEST-0001");
        collect(store, "SAR-TEST-0002");
        collect(store, "SAR-TEST-0003");
        merge(store, Stage.CLEANING, "SAR-TEST-0004", "SAR-TEST-0001", "SAR-TEST-0002", "SAR-TEST-0003");
        collect(store, "SAR-TEST-0005");
        merge(store, Stage.CLEANING, "SAR-TEST-0013", "SAR-TEST-0005");
        event(store, Stage.STORED, "SAR-TEST-0004");
        merge(store, Stage.STORED, "SAR-TEST-0006", "SAR-TEST-0004", "SAR-TEST-0013");
        event(store, Stage.PLANTING, "SAR-TEST-0006");
    }

    /** Every batch the same: stage, open or closed and why, combined from and into. */
    private static void assertSameBatches(Ledger expected, Ledger actual) {
        assertEquals(expected.eventCount(), actual.eventCount());
        assertEquals(expected.batches().size(), actual.batches().size());
        for (Ledger.Batch b : expected.batches()) {
            Ledger.Batch r = actual.batch(b.id);
            assertEquals(b.id, b.describe(), r.describe());
            assertEquals(b.id, b.combinedFrom, r.combinedFrom);
            assertEquals(b.id, b.combinedInto, r.combinedInto);
            assertEquals(b.id, b.testLabel, r.testLabel);
            assertEquals(b.id, b.events.size(), r.events.size());
        }
    }

    @Test
    public void aRefusedEventWritesNothing() throws Exception {
        LedgerStore store = new LedgerStore(folder);
        collect(store, "SAR-TEST-0001");
        byte[] before = ledgerBytes();
        try {
            store.record(EventDraft.collect(capture("SAR-TEST-0001")), "1.0 (test)", clock);
            fail("recorded the same label twice");
        } catch (Ledger.Refused e) {
            assertTrue(e.getMessage().startsWith("SAR-TEST-0001 is already recorded on this phone"));
        }
        assertArrayEquals(before, ledgerBytes());
        assertEquals(1, new File(folder, "packages").list().length);
    }

    /** The ledger is never written in place: at the rename, the old file is still whole. */
    @Test
    public void theLedgerIsReplacedWholeNeverWrittenInPlace() throws Exception {
        LedgerStore store = new LedgerStore(folder);
        collect(store, "SAR-TEST-0001");
        final byte[] before = ledgerBytes();
        final AtomicFile.Renamer real = AtomicFile.renamer;
        final boolean[] checked = {false};
        AtomicFile.renamer = (from, to) -> {
            try {
                assertArrayEquals("ledger.json untouched until the rename", before, ledgerBytes());
                assertEquals("ledger.json.tmp", from.getName());
                assertEquals("the new ledger complete in the temporary file", 2,
                        Ledger.fromJson(Json.parse(new String(Files.readAllBytes(from.toPath()),
                                StandardCharsets.UTF_8))).eventCount());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            checked[0] = true;
            return real.rename(from, to);
        };
        collect(store, "SAR-TEST-0002");
        assertTrue(checked[0]);
        assertEquals(2, new LedgerStore(folder).eventCount());
        assertFalse(AtomicFile.tempFor(new File(folder, "ledger.json")).exists());
    }

    /**
     * A merge cut short before the rename (as if ATAK were killed): the
     * ledger keeps its parents open and has no new batch; its package is
     * left unrecorded, never deleted; the merge can then be made again.
     */
    @Test
    public void aMergeCutShortLeavesTheLedgerAsItWas() throws Exception {
        LedgerStore store = new LedgerStore(folder);
        collect(store, "SAR-TEST-0001");
        collect(store, "SAR-TEST-0002");
        byte[] before = ledgerBytes();
        List<Capture> parents = Arrays.asList(capture("SAR-TEST-0001"), capture("SAR-TEST-0002"));
        Capture child = capture("SAR-TEST-0004");

        AtomicFile.Renamer real = AtomicFile.renamer;
        AtomicFile.renamer = (from, to) -> false; // the save never completes
        try {
            store.record(EventDraft.merge(Stage.CLEANING, parents, child), "1.0 (test)", clock);
            fail("a save that could not be completed was reported as saved");
        } catch (IOException expected) {
            // reported, not hidden
        }
        AtomicFile.renamer = real;

        assertArrayEquals(before, ledgerBytes());
        assertFalse(AtomicFile.tempFor(new File(folder, "ledger.json")).exists());
        for (LedgerStore s : new LedgerStore[] {store, new LedgerStore(folder)}) {
            Ledger l = s.ledger();
            assertEquals(2, l.eventCount());
            assertTrue(l.batch("SAR-TEST-0001").open);
            assertTrue(l.batch("SAR-TEST-0002").open);
            assertNull(l.batch("SAR-TEST-0004"));
        }
        List<File> unrecorded = store.unrecordedPackages();
        assertEquals(1, unrecorded.size());
        assertEquals("SAR-TEST-0004_Cleaning_20261012T221640Z.zip", unrecorded.get(0).getName());

        // tried again: saved, under a new name, and the earlier package is still there
        Map<String, Object> saved = store.record(EventDraft.merge(Stage.CLEANING, parents, child), "1.0 (test)", clock);
        assertEquals("SAR-TEST-0004_Cleaning_20261012T221640Z_2.zip", saved.get("package_file"));
        Ledger l = new LedgerStore(folder).ledger();
        assertFalse(l.batch("SAR-TEST-0001").open);
        assertEquals("combined into SAR-TEST-0004", l.batch("SAR-TEST-0002").closedBy);
        assertTrue(unrecorded.get(0).exists());
    }

    @Test
    public void anUnfinishedWriteIsIgnoredAndCleared() throws Exception {
        LedgerStore store = new LedgerStore(folder);
        collect(store, "SAR-TEST-0001");
        byte[] good = ledgerBytes();
        // left by a kill: the start of a longer ledger, and an unfinished package
        File ledgerTmp = AtomicFile.tempFor(new File(folder, "ledger.json"));
        Files.write(ledgerTmp.toPath(), Arrays.copyOf(good, good.length / 2));
        File part = new File(new File(folder, "packages"), "SAR-TEST-0002_Collecting_20261012T221520Z.zip.part");
        Files.write(part.toPath(), new byte[] {1, 2, 3});

        LedgerStore again = new LedgerStore(folder);
        assertEquals(1, again.eventCount());
        assertFalse(ledgerTmp.exists());
        assertFalse(part.exists());
        assertArrayEquals(good, ledgerBytes());
    }

    /**
     * The user's rule: a damaged ledger is reported plainly, kept aside
     * unchanged, and the ledger rebuilt from the packages, without a crash;
     * saving then carries on.
     */
    @Test
    public void aDamagedLedgerIsKeptAsideAndRebuiltFromThePackages() throws Exception {
        LedgerStore store = new LedgerStore(folder);
        saveScenario(store);
        Ledger original = store.ledger();
        File ledgerFile = new File(folder, "ledger.json");
        byte[] damaged = Arrays.copyOf(ledgerBytes(), 700); // cut off part way
        Files.write(ledgerFile.toPath(), damaged);

        LedgerStore again = new LedgerStore(folder);
        Ledger rebuilt = again.ledger(); // no exception
        String notice = again.takeNotice();
        assertTrue(notice, notice.startsWith("The saved records on this phone were damaged and could not be read ("));
        assertTrue(notice, notice.contains("The records were rebuilt from the 9 packages on this phone: 9 events."));
        assertTrue(notice, notice.endsWith("Every rebuilt event is marked \"rebuilt from packages\": its SHA-256 "
                + "was recalculated from the stored file, so it cannot show the package is unchanged since it was captured."));
        assertNull("told once", again.takeNotice());

        // kept aside, byte for byte
        File[] aside = folder.listFiles((dir, name) -> name.startsWith("ledger.damaged-") && name.endsWith(".json"));
        assertEquals(1, aside.length);
        assertTrue(notice, notice.contains("The damaged file has been kept as " + aside[0].getName() + "."));
        assertArrayEquals(damaged, Files.readAllBytes(aside[0].toPath()));

        assertSameBatches(original, rebuilt);
        for (int i = 0; i < rebuilt.eventCount(); i++) {
            Map<String, Object> was = original.events().get(i), now = rebuilt.events().get(i);
            assertEquals(true, now.get("rebuilt_from_packages"));
            assertEquals(LedgerRebuild.NOTE, now.get("rebuilt_note"));
            assertEquals("in the order they happened", was.get("package_file"), now.get("package_file"));
            assertEquals("the files are unchanged, so the recalculated SHA-256 is the same",
                    was.get("package_sha256"), now.get("package_sha256"));
        }
        // the rebuilt ledger is saved, and saving carries on
        assertEquals(9, new LedgerStore(folder).eventCount());
        collect(again, "SAR-TEST-0007");
        Map<String, Object> next = new LedgerStore(folder).ledger().events().get(9);
        assertEquals(10L, next.get("seq"));
        assertFalse(next.containsKey("rebuilt_from_packages"));
    }

    @Test
    public void aLostLedgerIsRebuiltFromThePackages() throws Exception {
        LedgerStore store = new LedgerStore(folder);
        saveScenario(store);
        Ledger original = store.ledger();
        assertTrue(new File(folder, "ledger.json").delete());

        LedgerStore again = new LedgerStore(folder);
        assertSameBatches(original, again.ledger());
        String notice = again.takeNotice();
        assertTrue(notice, notice.startsWith("No saved records were found on this phone, but 9 packages and 0 metadata files were. "
                + "The records were rebuilt from the 9 packages on this phone: 9 events."));
    }

    /** A merge cut short then made again leaves two packages of it; the rebuild keeps one. */
    @Test
    public void aRebuildLeavesOutWhatTheRulesRefuse() throws Exception {
        LedgerStore store = new LedgerStore(folder);
        collect(store, "SAR-TEST-0001");
        List<Capture> parents = Collections.singletonList(capture("SAR-TEST-0001"));
        Capture child = capture("SAR-TEST-0004");
        AtomicFile.Renamer real = AtomicFile.renamer;
        AtomicFile.renamer = (from, to) -> false;
        try {
            store.record(EventDraft.merge(Stage.CLEANING, parents, child), "1.0 (test)", clock);
            fail("saved");
        } catch (IOException expected) {
            // cut short: its package stays, unrecorded
        }
        AtomicFile.renamer = real;
        store.record(EventDraft.merge(Stage.CLEANING, parents, child), "1.0 (test)", clock);
        // and a zip that is not a PET package
        Files.write(new File(new File(folder, "packages"), "holiday.zip").toPath(), new byte[] {1, 2, 3});
        assertTrue(new File(folder, "ledger.json").delete());

        LedgerStore again = new LedgerStore(folder);
        Ledger l = again.ledger();
        assertEquals(2, l.eventCount());
        assertEquals("combined into SAR-TEST-0004", l.batch("SAR-TEST-0001").closedBy);
        String notice = again.takeNotice();
        assertTrue(notice, notice.contains("from the 4 packages on this phone: 2 events. Left out:"));
        assertTrue(notice, notice.contains("\n- SAR-TEST-0004_Cleaning_20261012T221600Z_2.zip: SAR-TEST-0001 is closed "
                + "(combined into SAR-TEST-0004), so it cannot be combined again."));
        assertTrue(notice, notice.contains("\n- holiday.zip: "));
    }

    @Test
    public void aNewPhoneStartsEmpty() throws Exception {
        LedgerStore store = new LedgerStore(new File(folder, "not-yet-made"));
        assertEquals(0, store.eventCount());
        assertTrue(store.unrecordedPackages().isEmpty());
    }

    // --- the folder changed by hand while ATAK runs (PL-41) -------------

    /** Clearing the test records (the guide, section 10) with ATAK still running. */
    @Test
    public void aFolderDeletedWhileAtakRunsIsNotWrittenBack() throws Exception {
        LedgerStore store = new LedgerStore(folder);
        saveScenario(store);
        assertEquals(9, store.eventCount());

        deleteTree(folder); // as in the phone's Files app, ATAK still running

        assertEquals("the deleted records are not counted", 0, store.eventCount());
        collect(store, "SAR-TEST-0001"); // not refused as "already recorded"
        Ledger l = new LedgerStore(folder).ledger(); // as read back from the disk
        assertEquals("only the new event, none written back", 1, l.eventCount());
        assertEquals("SAR-TEST-0001", l.events().get(0).get("batch_id"));
        assertEquals(1, new File(folder, "packages").list().length);
    }

    @Test
    public void createEditAfterTheFolderIsDeletedDoesNotWriteTheRecordsBack() throws Exception {
        LedgerStore store = new LedgerStore(folder);
        collect(store, "SAR-TEST-0001");
        deleteTree(folder);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("format", "pet-tool-metadata-1");
        store.writeToolMetadata(metadata, "Step 10 test", clock);
        Ledger l = new LedgerStore(folder).ledger();
        assertEquals(0, l.eventCount());
        assertEquals(1, l.toolMetadata().size());
    }

    @Test
    public void aLedgerChangedOnDiskIsReadAgain() throws Exception {
        LedgerStore store = new LedgerStore(folder);
        collect(store, "SAR-TEST-0001");
        collect(store, "SAR-TEST-0002");
        byte[] two = ledgerBytes();
        collect(store, "SAR-TEST-0003");
        Files.write(new File(folder, "ledger.json").toPath(), two); // an older copy put back by hand
        assertEquals(2, store.eventCount());
        assertEquals(1, store.unrecordedPackages().size()); // 0003's package, now unrecorded
    }

    // --- helpers -------------------------------------------------------

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null)
            for (File c : children)
                deleteTree(c);
        assertTrue("could not delete " + f, f.delete());
    }

    private Capture capture(String id) throws IOException {
        clock += 20_000;
        return TestCaptures.of(camera, id, clock);
    }

    private void collect(LedgerStore store, String id) throws Exception {
        store.record(EventDraft.collect(capture(id)), "1.0 (test)", clock);
    }

    private void event(LedgerStore store, Stage stage, String id) throws Exception {
        store.record(EventDraft.stageEvent(stage, capture(id)), "1.0 (test)", clock);
    }

    private void merge(LedgerStore store, Stage stage, String child, String... parents) throws Exception {
        List<Capture> ps = new ArrayList<>();
        for (String p : parents)
            ps.add(capture(p));
        store.record(EventDraft.merge(stage, ps, capture(child)), "1.0 (test)", clock);
    }

    private byte[] ledgerBytes() throws IOException {
        return Files.readAllBytes(new File(folder, "ledger.json").toPath());
    }

    /** The folder's entries other than those named. */
    private static List<String> names(File dir, String... except) {
        List<String> out = new ArrayList<>(Arrays.asList(dir.list()));
        out.removeAll(Arrays.asList(except));
        Collections.sort(out);
        return out;
    }
}

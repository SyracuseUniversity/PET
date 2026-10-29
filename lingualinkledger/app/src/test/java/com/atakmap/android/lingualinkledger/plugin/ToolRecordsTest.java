package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The tools' records (T3; D63, step 12): Create Evidence Trace takes only
 * a data package holding a metadata file the plugin registered (T2), and
 * traces it once; Add Stage Event records a stage on a trace; both are kept
 * in ledger.json and as files in tool_records/, so a rebuild keeps them.
 */
public class ToolRecordsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File folder, datapackages;
    private LedgerStore store;
    private long clock = TestCaptures.T0;

    @Before
    public void setUp() throws IOException {
        folder = tmp.newFolder("lingualinkledger");
        datapackages = tmp.newFolder("datapackage");
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
    public void onlyAPackageHoldingARegisteredMetadataFileQualifies() throws Exception {
        File metadata = metadataFile("Red pine lot", Stage.STORED);
        byte[] bytes = Files.readAllBytes(metadata.toPath());

        File built = zip("Red_pine_lot.zip", "4f1c-uid/" + metadata.getName(), bytes);
        zip("Other.zip", "uid/Other_20261013T201500Z_metadata.json",
                "{\"format\":\"pet-tool-metadata-1\",\"stage\":\"Stored\"}".getBytes(StandardCharsets.UTF_8));
        byte[] changed = bytes.clone();
        changed[changed.length - 2] ^= 1; // one byte changed after it was registered
        zip("Changed.zip", "uid/" + metadata.getName(), changed);
        Files.write(new File(datapackages, "Broken.zip").toPath(), "not a zip".getBytes(StandardCharsets.UTF_8));
        zip("Photos.zip", "photo.jpg", new byte[] {1, 2, 3});

        List<ToolRecords.Qualifying> q = ToolRecords.qualifying(datapackages, store.ledger());
        assertEquals("only the package holding the registered file, byte for byte", 1, q.size());
        assertEquals(built, q.get(0).file);
        assertEquals(metadata.getName(), q.get(0).metadataEntry);
        assertEquals(TestCaptures.sha256(bytes), q.get(0).metadataSha256);
        assertEquals("Red pine lot", q.get(0).metadata.get("package_name"));
    }

    @Test
    public void aTraceCopiesTheStageAndPositionFromItsMetadata() throws Exception {
        Map<String, Object> trace = trace("Red pine lot", Stage.STORED);
        File pkg = new File(datapackages, (String) trace.get("package_file"));
        assertEquals(1L, trace.get("tool_seq"));
        assertEquals(ToolRecords.KIND_TRACE, trace.get("kind"));
        assertEquals("the package hashed as stored", TestCaptures.sha256(Files.readAllBytes(pkg.toPath())),
                trace.get("package_sha256"));
        assertEquals(pkg.length(), trace.get("package_bytes"));
        assertEquals("Stored", trace.get("stage"));
        assertEquals("Red pine lot", trace.get("package_name"));
        assertEquals("gnss", ((Map<?, ?>) trace.get("position")).get("source"));
        assertNull(trace.get("employee"));
        assertEquals("2026-10-12T22:15:20Z", trace.get("traced_utc"));
    }

    @Test
    public void aPackageIsTracedOnce() throws Exception {
        Map<String, Object> first = trace("Red pine lot", Stage.STORED);
        String sha = (String) first.get("package_sha256");
        assertTrue(ToolRecords.refuseTrace(store.ledger(), sha).startsWith("This data package was traced before"));
        ToolRecords.Qualifying q = ToolRecords.qualifying(datapackages, store.ledger()).get(0);
        try {
            store.recordTool(ToolRecords.trace(q, sha, q.file.length(), clock, "1.0 (test)"));
            fail("traced the same package twice");
        } catch (Ledger.Refused e) {
            assertTrue(e.getMessage(), e.getMessage().contains("already traced"));
        }
        assertEquals(1, store.ledger().toolRecords().size());
    }

    @Test
    public void aStageEventIsRecordedOnATrace() throws Exception {
        Map<String, Object> trace = trace("Red pine lot", Stage.STORED);
        Map<String, Object> ev = store.recordTool(ToolRecords.stageEvent(trace, Stage.PLANTING,
                TestCaptures.gps(clock), "  sown in bed 4  ", clock, "1.0 (test)"));
        assertEquals(2L, ev.get("tool_seq"));
        assertEquals(1L, ev.get("trace_tool_seq"));
        assertEquals(trace.get("package_sha256"), ev.get("trace_package_sha256"));
        assertEquals("Planting", ev.get("stage"));
        assertEquals("sown in bed 4", ev.get("remarks"));
        assertEquals(1, store.ledger().stageEventsOf(trace).size());
        // no remarks typed: none recorded
        Map<String, Object> bare = store.recordTool(ToolRecords.stageEvent(trace, Stage.STORED,
                TestCaptures.gps(clock), "   ", clock, "1.0 (test)"));
        assertNull(bare.get("remarks"));
        // the stage table does not apply to the tools' records (T3): no batch is touched
        assertEquals(0, store.ledger().eventCount());
    }

    @Test
    public void aStageEventNeedsAnEarlierTrace() throws Exception {
        Map<String, Object> notATrace = new java.util.LinkedHashMap<>();
        notATrace.put("tool_seq", 7L);
        notATrace.put("package_sha256", "00");
        try {
            store.recordTool(ToolRecords.stageEvent(notATrace, Stage.STORED, TestCaptures.gps(clock), null, clock,
                    "1.0 (test)"));
            fail("a stage event on no trace");
        } catch (Ledger.Refused e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not an earlier trace"));
        }
        assertEquals(0, store.ledger().toolRecords().size());
        assertFalse("nothing written", new File(folder, ToolRecords.FOLDER).exists()
                && new File(folder, ToolRecords.FOLDER).list().length > 0);
    }

    @Test
    public void theRecordsAreSavedInTheLedgerAndAsFiles() throws Exception {
        Map<String, Object> trace = trace("Red pine lot", Stage.STORED);
        store.recordTool(ToolRecords.stageEvent(trace, Stage.PLANTING, TestCaptures.gps(clock), null, clock,
                "1.0 (test)"));
        Ledger again = new LedgerStore(folder).ledger(); // read back, as after ATAK restarts
        assertEquals(2, again.toolRecords().size());
        assertNotNull(again.traceOf((String) trace.get("package_sha256")));
        String[] files = new File(folder, ToolRecords.FOLDER).list();
        java.util.Arrays.sort(files);
        assertEquals(2, files.length);
        assertTrue(files[0], files[0].startsWith("0001_evidence_trace_"));
        assertTrue(files[1], files[1].startsWith("0002_tool_stage_event_"));
    }

    @Test
    public void aDamagedLedgerRebuildsTheToolRecordsFromTheirFiles() throws Exception {
        Map<String, Object> trace = trace("Red pine lot", Stage.STORED);
        store.recordTool(ToolRecords.stageEvent(trace, Stage.PLANTING, TestCaptures.gps(clock), "r", clock,
                "1.0 (test)"));
        Files.write(new File(folder, "ledger.json").toPath(), "{ damaged".getBytes(StandardCharsets.UTF_8));

        LedgerStore after = new LedgerStore(folder);
        Ledger l = after.ledger();
        assertEquals(2, l.toolRecords().size());
        Map<String, Object> t = l.toolRecord(1);
        assertEquals(trace.get("package_sha256"), t.get("package_sha256"));
        assertEquals(Boolean.TRUE, t.get("rebuilt_from_files"));
        assertEquals(1L, l.toolRecord(2).get("trace_tool_seq"));
        assertEquals(1, l.toolMetadata().size()); // the metadata file re-registered too
        assertTrue(after.takeNotice().contains("2 records from the tools restored from their files"));
    }

    @Test
    public void theDataPackageIsNamedFromTheFormAndNeverReplacesAFile() throws Exception {
        assertEquals("Red_pine_lot.zip", ToolMetadata.dataPackageFile(datapackages, "Red pine lot").getName());
        assertTrue(new File(datapackages, "Red_pine_lot.zip").createNewFile());
        assertEquals("Red_pine_lot_2.zip", ToolMetadata.dataPackageFile(datapackages, "Red pine lot").getName());
        assertEquals("a name cannot leave the folder (PL-33)", datapackages,
                ToolMetadata.dataPackageFile(datapackages, "../../x").getParentFile());
    }

    // --- helpers -------------------------------------------------------

    /** A metadata file written and registered as Create/Edit Data Package does. */
    private File metadataFile(String name, Stage stage) throws IOException {
        clock += 20_000;
        Map<String, Object> m = ToolMetadata.build(name, "a description", "a tester", stage,
                TestCaptures.gps(clock), clock, "1.0 (test)");
        return store.writeToolMetadata(m, name, clock);
    }

    /** A package built around a registered metadata file, then traced. */
    private Map<String, Object> trace(String name, Stage stage) throws Exception {
        File metadata = metadataFile(name, stage);
        zip(ToolMetadata.dataPackageFile(datapackages, name).getName(), "uid/" + metadata.getName(),
                Files.readAllBytes(metadata.toPath()));
        ToolRecords.Qualifying q = ToolRecords.qualifying(datapackages, store.ledger()).get(0);
        String sha = TestCaptures.sha256(Files.readAllBytes(q.file.toPath()));
        assertNull(ToolRecords.refuseTrace(store.ledger(), sha));
        return store.recordTool(ToolRecords.trace(q, sha, q.file.length(), clock, "1.0 (test)"));
    }

    private File zip(String name, String entry, byte[] bytes) throws IOException {
        File f = new File(datapackages, name);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            z.putNextEntry(new ZipEntry("MANIFEST/manifest.xml"));
            z.write("<MissionPackageManifest/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry(entry));
            z.write(bytes);
            z.closeEntry();
        }
        return f;
    }
}

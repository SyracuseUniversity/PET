package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Create/Edit Data Package's metadata file (Day 2 step 8): its JSON (T5),
 * its safe file name (PL-33), and its SHA-256 registered on the phone and
 * kept through a reload or a rebuild (T2).
 */
public class ToolMetadataTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** PL-33: a ".." or "/" in a package name cannot leave the folder. */
    @Test
    public void aTypedNameCannotLeaveTheFolder() throws Exception {
        File folder = tmp.newFolder("lingualinkledger");
        String[] nasty = {"../../atak/prefs", "..", "a/b\\c", "/sdcard/x", ".hidden", "C:\\x", "name\u0000x",
                "\u2028line", "   ", "", "Red pine lot 3", "\u00e9t\u00e9 2026"};
        for (String n : nasty) {
            String safe = ToolMetadata.safeName(n);
            assertTrue(n + " -> " + safe, safe.matches("[A-Za-z0-9-]+(_[A-Za-z0-9-]+)*|package"));
            assertTrue(safe.length() <= ToolMetadata.MAX_NAME);
            File f = new File(folder, ToolMetadata.fileName(n, TestCaptures.T0));
            assertEquals(n, folder.getCanonicalFile(), f.getCanonicalFile().getParentFile());
        }
        assertEquals("atak_prefs", ToolMetadata.safeName("../../atak/prefs"));
        assertEquals("package", ToolMetadata.safeName(".."));
        assertEquals("Red_pine_lot_3", ToolMetadata.safeName("Red pine lot 3"));
        assertEquals("Red_pine_lot_3_20261012T221500Z_metadata.json",
                ToolMetadata.fileName("Red pine lot 3", TestCaptures.T0));
        assertEquals(ToolMetadata.MAX_NAME, ToolMetadata.safeName(new String(new char[200]).replace('\0', 'x')).length());
    }

    @Test
    public void theMetadataIsJsonWithTheStagePositionAndTypedFields() {
        Map<String, Object> m = ToolMetadata.build("../lot 3", "two sacks", "Sam", Stage.CLEANING,
                TestCaptures.gps(TestCaptures.T0), TestCaptures.T0, "1.0 (test)");
        assertEquals("pet-tool-metadata-1", m.get("format"));
        assertEquals("the typed name kept as typed", "../lot 3", m.get("package_name"));
        assertEquals("Sam", m.get("author"));
        assertEquals("typed, not checked", m.get("author_note"));
        assertEquals("Cleaning", m.get("stage"));
        assertEquals("2026-10-12T22:15:00Z", m.get("created_utc"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pos = (Map<String, Object>) m.get("position");
        assertEquals("gnss", pos.get("source"));
        assertEquals(Json.write(m), Json.write(Json.parse(Json.write(m))));
    }

    /** T2: the file is written, hashed as stored, and its SHA-256 registered, kept through a reload. */
    @Test
    public void eachMetadataFileIsRegisteredByItsHash() throws Exception {
        File folder = tmp.newFolder("lingualinkledger");
        LedgerStore store = new LedgerStore(folder);
        Map<String, Object> m = ToolMetadata.build("lot 3", "", "", Stage.COLLECTING,
                TestCaptures.gps(TestCaptures.T0), TestCaptures.T0, "1.0 (test)");
        File f = store.writeToolMetadata(m, "lot 3", TestCaptures.T0);

        assertEquals(folder, f.getParentFile());
        assertEquals("lot_3_20261012T221500Z_metadata.json", f.getName());
        String sha = TestCaptures.sha256(Files.readAllBytes(f.toPath()));
        assertTrue(store.ledger().isToolMetadata(sha));
        assertTrue("after a reload", new LedgerStore(folder).ledger().isToolMetadata(sha));
        assertEquals(Json.write(m), new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        assertFalse(store.ledger().isToolMetadata(TestCaptures.sha256(new byte[] {1})));

        // the same name and second: a new file, the first left as it was
        File second = store.writeToolMetadata(m, "lot 3", TestCaptures.T0);
        assertEquals("lot_3_20261012T221500Z_2_metadata.json", second.getName());
        assertEquals(sha, TestCaptures.sha256(Files.readAllBytes(f.toPath())));
        assertEquals(2, new LedgerStore(folder).ledger().toolMetadata().size());
        assertEquals("no events: the register is not the event record", 0, store.eventCount());
    }

    @Test
    public void theRegisterIsRebuiltFromTheFilesToo() throws Exception {
        File folder = tmp.newFolder("lingualinkledger");
        LedgerStore store = new LedgerStore(folder);
        File f = store.writeToolMetadata(ToolMetadata.build("lot 3", "", "", Stage.COLLECTING,
                TestCaptures.gps(TestCaptures.T0), TestCaptures.T0, "1.0 (test)"), "lot 3", TestCaptures.T0);
        String sha = TestCaptures.sha256(Files.readAllBytes(f.toPath()));
        assertTrue(new File(folder, "ledger.json").delete());

        LedgerStore again = new LedgerStore(folder);
        assertTrue(again.ledger().isToolMetadata(sha));
        Map<String, Object> entry = again.ledger().toolMetadata().get(0);
        assertEquals(true, entry.get("rebuilt_from_files"));
        String notice = again.takeNotice();
        assertTrue(notice, notice.startsWith("No saved records were found on this phone, but 0 packages and "
                + "1 metadata file were."));
        assertTrue(notice, notice.contains("1 metadata file from Create/Edit Data Package re-registered."));
    }
}

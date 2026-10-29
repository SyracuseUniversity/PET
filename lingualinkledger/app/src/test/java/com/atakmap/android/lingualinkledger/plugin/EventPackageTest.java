package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The package the plugin builds itself (R4; Package v1, V1.7), and the
 * user's requirement 2 (Day 2 step 6): the SHA-256 is of the file exactly
 * as stored, and an existing package is never written over.
 */
public class EventPackageTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void theHashIsOfTheFileAsStored() throws Exception {
        File photos = tmp.newFolder("camera"), packages = new File(tmp.getRoot(), "packages");
        EventPackage.Built p = EventPackage.build(packages,
                EventDraft.collect(TestCaptures.of(photos, "SAR-TEST-0001", TestCaptures.T0)), 1, "1.0 (test)");

        assertEquals("SAR-TEST-0001_Collecting_20261012T221500Z.zip", p.file.getName());
        assertEquals(packages, p.file.getParentFile());
        byte[] stored = Files.readAllBytes(p.file.toPath());
        assertEquals("independent SHA-256 of the stored file", TestCaptures.sha256(stored), p.sha256);
        assertEquals(p.sha256, FileHasher.generateFileHash(p.file.getAbsolutePath()));
        assertEquals("only the package is left", Arrays.asList(p.file.getName()), Arrays.asList(packages.list()));
    }

    @Test
    public void itHoldsThePhotoAndTheMetadata() throws Exception {
        File photos = tmp.newFolder("camera");
        Capture c = TestCaptures.of(photos, "SAR-TEST-0001", TestCaptures.T0);
        EventPackage.Built p = EventPackage.build(new File(tmp.getRoot(), "packages"), EventDraft.collect(c), 1, "1.0 (test)");

        try (ZipFile zip = new ZipFile(p.file)) {
            assertEquals(Arrays.asList("01_batch_SAR-TEST-0001.jpg", "metadata.json"), names(zip));
            byte[] photo = read(zip, "01_batch_SAR-TEST-0001.jpg");
            assertArrayEquals("the photo, byte for byte", Files.readAllBytes(c.photo.toPath()), photo);

            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) Json.parse(
                    new String(read(zip, "metadata.json"), StandardCharsets.UTF_8));
            assertEquals(Json.write(p.metadata), Json.write(m));
            assertEquals("pet-package-1", m.get("format"));
            assertEquals("its number in the ledger, so it can be rebuilt in order", 1L, m.get("seq"));
            assertEquals("collect", m.get("kind"));
            assertEquals("Collecting", m.get("stage"));
            assertEquals("SAR-TEST-0001", m.get("batch_id"));
            assertFalse("no parents outside a merge", m.containsKey("parent_ids"));
            assertEquals(true, m.get("test_label"));
            assertEquals("Red pine", m.get("species_as_printed"));
            assertEquals("2026-10-12T22:15:00Z", m.get("captured_utc"));
            assertTrue(m.containsKey("employee"));
            assertNull("no sign-in in v1 (D41)", m.get("employee"));
            assertEquals("1.0 (test)", m.get("app_version"));

            @SuppressWarnings("unchecked")
            Map<String, Object> pos = (Map<String, Object>) m.get("position");
            assertEquals("gnss", pos.get("source"));
            assertEquals(43.037634, (Double) pos.get("latitude"), 0);
            assertEquals("2026-10-12T22:14:59Z", pos.get("fix_time_utc"));

            @SuppressWarnings("unchecked")
            Map<String, Object> ph = ((List<Map<String, Object>>) m.get("photos")).get(0);
            assertEquals("01_batch_SAR-TEST-0001.jpg", ph.get("file"));
            assertEquals("batch", ph.get("role"));
            assertEquals("evidence_photo", ph.get("id_read"));
            assertEquals("each photo's own SHA-256", TestCaptures.sha256(photo), ph.get("sha256"));
        }
    }

    @Test
    public void aMergeIsOnePackageOfEveryPhoto() throws Exception {
        File photos = tmp.newFolder("camera");
        List<Capture> parents = new ArrayList<>();
        for (int i = 1; i <= 3; i++)
            parents.add(TestCaptures.of(photos, "SAR-TEST-000" + i, TestCaptures.T0 + i * 20_000));
        Capture child = TestCaptures.of(photos, "SAR-TEST-0004", TestCaptures.T0 + 80_000);
        EventPackage.Built p = EventPackage.build(new File(tmp.getRoot(), "packages"),
                EventDraft.merge(Stage.CLEANING, parents, child), 1, "1.0 (test)");

        assertEquals("named from the new batch and its photo's time",
                "SAR-TEST-0004_Cleaning_20261012T221620Z.zip", p.file.getName());
        try (ZipFile zip = new ZipFile(p.file)) {
            assertEquals(Arrays.asList("01_parent_SAR-TEST-0001.jpg", "02_parent_SAR-TEST-0002.jpg",
                    "03_parent_SAR-TEST-0003.jpg", "04_batch_SAR-TEST-0004.jpg", "metadata.json"), names(zip));
            assertArrayEquals(Files.readAllBytes(parents.get(1).photo.toPath()),
                    read(zip, "02_parent_SAR-TEST-0002.jpg"));
        }
        assertEquals("merge", p.metadata.get("kind"));
        assertEquals(Arrays.asList("SAR-TEST-0001", "SAR-TEST-0002", "SAR-TEST-0003"), p.metadata.get("parent_ids"));
        assertEquals("the event's time is the new label's photo", "2026-10-12T22:16:20Z", p.metadata.get("captured_utc"));
    }

    @Test
    public void anExistingPackageIsNeverWrittenOver() throws Exception {
        File photos = tmp.newFolder("camera"), packages = tmp.newFolder("packages");
        File taken = new File(packages, "SAR-TEST-0001_Collecting_20261012T221500Z.zip");
        Files.write(taken.toPath(), "an earlier package".getBytes(StandardCharsets.UTF_8));

        EventPackage.Built p = EventPackage.build(packages,
                EventDraft.collect(TestCaptures.of(photos, "SAR-TEST-0001", TestCaptures.T0)), 1, "1.0 (test)");

        assertEquals("SAR-TEST-0001_Collecting_20261012T221500Z_2.zip", p.file.getName());
        assertEquals("an earlier package", new String(Files.readAllBytes(taken.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void aPhotoThatCannotBeReadLeavesNothing() throws Exception {
        File photos = tmp.newFolder("camera"), packages = new File(tmp.getRoot(), "packages");
        Capture c = TestCaptures.of(photos, "SAR-TEST-0001", TestCaptures.T0);
        assertTrue(c.photo.delete());
        try {
            EventPackage.build(packages, EventDraft.collect(c), 1, "1.0 (test)");
            fail("built a package without its photo");
        } catch (IOException expected) {
            // refused
        }
        String[] left = packages.list();
        assertTrue("nothing left: " + Arrays.toString(left), left == null || left.length == 0);
    }

    @Test
    public void everyCaptureNeedsAPosition() throws Exception {
        PositionPolicy.Check none = PositionPolicy.check(null, TestCaptures.T0);
        try {
            new Capture(tmp.newFile("p.jpg"), "SAR-TEST-0001", Capture.IdRead.EVIDENCE_PHOTO, true, null,
                    TestCaptures.T0, none);
            fail("a capture with no position (S6)");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().startsWith("a capture needs a position (S6): Waiting for GPS"));
        }
    }

    private static List<String> names(ZipFile zip) {
        List<String> out = new ArrayList<>();
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); )
            out.add(e.nextElement().getName());
        return out;
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1)
                out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}

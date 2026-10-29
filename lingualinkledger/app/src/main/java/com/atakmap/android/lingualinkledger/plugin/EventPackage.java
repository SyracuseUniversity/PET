package com.atakmap.android.lingualinkledger.plugin;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds an event's data package itself (R4): a .zip holding the event's
 * photos and metadata.json, named from the batch ID, stage and UTC time
 * (R3), e.g. SAR-TEST-0004_Cleaning_20261012T221530Z.zip (Package v1,
 * V1.7).
 *
 * The hash covers the final file (the user's requirement 2, Day 2 step
 * 6): the package is written in full to <name>.part, flushed to the disk,
 * renamed to its final name (never over an existing file), and only then
 * hashed, by reading the stored file back. Nothing writes to it after
 * that, so anyone re-checking it later gets the same SHA-256. A .part
 * left by a kill was never hashed or recorded, and is deleted when the
 * ledger is next loaded.
 *
 * metadata.json holds a format version, the event's number on this phone
 * (seq, so the ledger can be rebuilt in order: LedgerRebuild), the kind
 * of event, the stage, the
 * batch ID, the parents for a merge, the capture time in UTC, the
 * position block (V1.2), the app version, and for each photo its file
 * name, role, batch ID and how it was read, TEST LABEL, species as
 * printed, its own capture time and position, and its SHA-256. No
 * employee: sign-in comes later (D41). Plain Java, tested on the PC.
 */
final class EventPackage {

    static final String FORMAT = "pet-package-1";
    static final String METADATA = "metadata.json";
    static final String PART = ".part";

    /** A package as stored: its file, the SHA-256 of that file, and its metadata. */
    static final class Built {
        final File file;
        final String sha256;
        final Map<String, Object> metadata;

        private Built(File file, String sha256, Map<String, Object> metadata) {
            this.file = file;
            this.sha256 = sha256;
            this.metadata = metadata;
        }
    }

    private EventPackage() {
    }

    /** The package of the event that will be number seq in the ledger. */
    static Built build(File folder, EventDraft draft, long seq, String appVersion) throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("could not make " + folder);

        List<Capture> captures = draft.captures();
        List<String> entries = new ArrayList<>();
        List<Map<String, Object>> photos = new ArrayList<>();
        for (int i = 0; i < captures.size(); i++) {
            Capture c = captures.get(i);
            boolean parent = i < draft.parentCaptures.size();
            String entry = String.format(Locale.US, "%02d_%s_%s.jpg", i + 1, parent ? "parent" : "batch", c.batchId);
            String sha256 = FileHasher.generateFileHash(c.photo.getAbsolutePath());
            if (sha256 == null)
                throw new IOException("could not read the photo " + c.photo.getName());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("file", entry);
            p.put("role", parent ? "parent" : "batch");
            p.put("batch_id", c.batchId);
            p.put("id_read", c.idRead.code);
            p.put("test_label", c.testLabel);
            p.put("species_as_printed", c.species);
            p.put("captured_utc", UtcTime.format(c.capturedAtMs));
            p.put("position", c.position.recordBlock());
            p.put("sha256", sha256);
            entries.add(entry);
            photos.add(p);
        }

        Capture own = draft.batchCapture;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("seq", seq);
        m.put("kind", draft.kind);
        m.put("stage", draft.stage.label);
        m.put("batch_id", draft.batchId);
        if (Ledger.KIND_MERGE.equals(draft.kind))
            m.put("parent_ids", new ArrayList<Object>(draft.parentIds));
        m.put("test_label", own.testLabel);
        m.put("species_as_printed", own.species);
        m.put("captured_utc", UtcTime.format(own.capturedAtMs));
        m.put("position", own.position.recordBlock());
        m.put("photos", photos);
        m.put("employee", null); // no sign-in in App v1 (D41)
        m.put("app_version", appVersion);
        byte[] metadata = Json.write(m).getBytes(StandardCharsets.UTF_8);

        String name = draft.batchId + "_" + draft.stage.label + "_" + UtcTime.compact(own.capturedAtMs);
        File target = freeName(folder, name);
        File part = new File(folder, target.getName() + PART);
        try (FileOutputStream fos = new FileOutputStream(part);
                ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fos))) {
            for (int i = 0; i < captures.size(); i++) {
                zip.putNextEntry(new ZipEntry(entries.get(i)));
                copy(captures.get(i).photo, zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(METADATA));
            zip.write(metadata);
            zip.closeEntry();
            zip.finish();
            zip.flush();
            fos.getFD().sync();
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        if (target.exists() || !part.renameTo(target)) {
            part.delete();
            throw new IOException("could not store the package as " + target.getName());
        }

        // hashed as stored, and never written again (requirement 2)
        String sha256 = FileHasher.generateFileHash(target.getAbsolutePath());
        if (sha256 == null)
            throw new IOException("could not read back " + target.getName());
        return new Built(target, sha256, m);
    }

    /** name.zip, or name_2.zip, name_3.zip ... if taken: an existing package is never replaced. */
    private static File freeName(File folder, String name) {
        File f = new File(folder, name + ".zip");
        for (int n = 2; f.exists() || new File(folder, f.getName() + PART).exists(); n++)
            f = new File(folder, name + "_" + n + ".zip");
        return f;
    }

    private static void copy(File from, ZipOutputStream to) throws IOException {
        try (InputStream in = new FileInputStream(from)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1)
                to.write(buf, 0, n);
        }
    }
}

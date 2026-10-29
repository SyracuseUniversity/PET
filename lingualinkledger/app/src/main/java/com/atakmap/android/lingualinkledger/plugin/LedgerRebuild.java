package com.atakmap.android.lingualinkledger.plugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Rebuilds the ledger from the packages alone, for when ledger.json is
 * damaged or lost (the user, Day 2 step 7). Every package carries its own
 * metadata.json, so it describes itself once it leaves the phone; that
 * metadata is everything an event's record holds except the package's
 * name and SHA-256, which come from the file itself, and the save time.
 *
 * The packages are replayed in the order they happened: by the event
 * number each one carries (seq), then its capture time, then its name;
 * each through StageRules, as at any load. A package the rules refuse, or
 * that has no readable metadata, is left out and listed with the reason:
 * for example the copy of a merge stored by a save that was cut short and
 * then made again.
 *
 * Every rebuilt event is marked "rebuilt from packages" (the user): its
 * SHA-256 is recalculated from the stored file, so it cannot show the
 * package is unchanged since capture, only what the file holds now. Plain
 * Java, tested on the PC.
 */
final class LedgerRebuild {

    static final String NOTE = "Rebuilt from packages: its SHA-256 was recalculated from the stored "
            + "file, so it cannot show the package is unchanged since it was captured.";

    /** How Ledger words a broken rule; the reason after it is what the user needs. */
    private static final String RULE = "breaks a rule: ";

    static final class Result {
        final Ledger ledger;
        /** How many .zip files were found. */
        final int packagesFound;
        /** Each package left out: "name: reason". */
        final List<String> leftOut;

        private Result(Ledger ledger, int packagesFound, List<String> leftOut) {
            this.ledger = ledger;
            this.packagesFound = packagesFound;
            this.leftOut = Collections.unmodifiableList(leftOut);
        }
    }

    private static final class Found {
        final File file;
        final Map<String, Object> metadata;
        final long seq;
        final String captured;

        Found(File file, Map<String, Object> metadata) {
            this.file = file;
            this.metadata = metadata;
            Object s = metadata.get("seq");
            this.seq = s instanceof Long ? (Long) s : Long.MAX_VALUE;
            Object c = metadata.get("captured_utc");
            this.captured = c instanceof String ? (String) c : "";
        }
    }

    private LedgerRebuild() {
    }

    /**
     * The ledger from the packages in packages/, and the register of
     * Create/Edit Data Package's metadata files (T2) from those in
     * toolFolder, each re-hashed and marked as rebuilt the same way.
     */
    static Result fromPackages(File packages, File toolFolder, long nowMs) {
        List<String> leftOut = new ArrayList<>();
        File[] zips = packages.listFiles((dir, name) -> name.endsWith(".zip"));
        if (zips == null)
            zips = new File[0];
        Arrays.sort(zips);

        List<Found> found = new ArrayList<>();
        for (File z : zips) {
            try {
                found.add(new Found(z, metadataOf(z)));
            } catch (IOException | RuntimeException e) {
                leftOut.add(z.getName() + ": " + e.getMessage());
            }
        }
        // the order they happened: event number, then capture time, then name
        Collections.sort(found, (a, b) -> a.seq != b.seq ? (a.seq < b.seq ? -1 : 1)
                : a.captured.equals(b.captured) ? a.file.getName().compareTo(b.file.getName())
                : a.captured.compareTo(b.captured));

        Ledger ledger = Ledger.empty();
        String rebuilt = UtcTime.format(nowMs);
        for (Found f : found) {
            String sha256 = FileHasher.generateFileHash(f.file.getAbsolutePath());
            if (sha256 == null) {
                leftOut.add(f.file.getName() + ": the file could not be read");
                continue;
            }
            long seq = ledger.eventCount() + 1;
            Map<String, Object> event = new LinkedHashMap<>(f.metadata);
            event.put("seq", seq);
            if (f.seq != seq)
                event.put("seq_when_saved", f.seq == Long.MAX_VALUE ? null : f.seq);
            event.put("package_file", f.file.getName());
            event.put("package_sha256", sha256);
            event.put("rebuilt_from_packages", true);
            event.put("rebuilt_utc", rebuilt);
            event.put("rebuilt_note", NOTE);
            try {
                ledger = ledger.with(event);
            } catch (Ledger.Refused e) {
                String why = e.getMessage();
                int rule = why.indexOf(RULE);
                leftOut.add(f.file.getName() + ": " + (rule >= 0 ? why.substring(rule + RULE.length()) : why));
            }
        }
        ledger = withToolMetadata(ledger, toolFolder, rebuilt, leftOut);
        ledger = withToolRecords(ledger, new File(toolFolder, ToolRecords.FOLDER), rebuilt, leftOut);
        return new Result(ledger, zips.length, leftOut);
    }

    /**
     * The tools' records (D63) from their own files in tool_records/, in the
     * order they were made (tool_seq, then time, then name), renumbered from
     * 1 and each stage event pointed at its trace's new number; each marked
     * rebuilt_from_files. A record the tool rules refuse, or whose trace was
     * not restored, is left out with the reason.
     */
    @SuppressWarnings("unchecked")
    private static Ledger withToolRecords(Ledger ledger, File dir, String rebuilt, List<String> leftOut) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null)
            return ledger;
        List<Object[]> found = new ArrayList<>(); // {name, record}
        for (File f : files) {
            Object parsed;
            try {
                parsed = Json.parse(new String(readAll(f), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                leftOut.add(f.getName() + ": " + e.getMessage());
                continue;
            }
            if (!(parsed instanceof Map) || !ToolRecords.FORMAT.equals(((Map<String, Object>) parsed).get("format"))) {
                leftOut.add(f.getName() + ": not a " + ToolRecords.FORMAT + " record");
                continue;
            }
            found.add(new Object[] {f.getName(), parsed});
        }
        Collections.sort(found, (a, b) -> {
            Map<String, Object> x = (Map<String, Object>) a[1], y = (Map<String, Object>) b[1];
            long sx = x.get("tool_seq") instanceof Long ? (Long) x.get("tool_seq") : Long.MAX_VALUE;
            long sy = y.get("tool_seq") instanceof Long ? (Long) y.get("tool_seq") : Long.MAX_VALUE;
            if (sx != sy)
                return sx < sy ? -1 : 1;
            int t = timeOf(x).compareTo(timeOf(y));
            return t != 0 ? t : ((String) a[0]).compareTo((String) b[0]);
        });
        Map<Object, Long> traceNumber = new java.util.HashMap<>(); // tool_seq when saved -> now
        for (Object[] f : found) {
            Map<String, Object> rec = (Map<String, Object>) f[1];
            Map<String, Object> copy = new LinkedHashMap<>(rec);
            long seq = ledger.toolRecords().size() + 1;
            copy.put("tool_seq", seq);
            if (!Long.valueOf(seq).equals(rec.get("tool_seq")))
                copy.put("tool_seq_when_saved", rec.get("tool_seq"));
            if (ToolRecords.KIND_STAGE_EVENT.equals(rec.get("kind"))) {
                Long trace = traceNumber.get(rec.get("trace_tool_seq"));
                if (trace == null) {
                    leftOut.add(f[0] + ": its trace, tool record " + rec.get("trace_tool_seq") + ", was not restored");
                    continue;
                }
                copy.put("trace_tool_seq", trace);
            }
            copy.put("rebuilt_from_files", true);
            copy.put("rebuilt_utc", rebuilt);
            try {
                ledger = ledger.withToolRecord(copy);
            } catch (Ledger.Refused e) {
                leftOut.add(f[0] + ": " + e.getMessage());
                continue;
            }
            if (ToolRecords.KIND_TRACE.equals(rec.get("kind")))
                traceNumber.put(rec.get("tool_seq"), seq);
        }
        return ledger;
    }

    private static String timeOf(Map<String, Object> rec) {
        Object t = rec.containsKey("traced_utc") ? rec.get("traced_utc") : rec.get("recorded_utc");
        return t instanceof String ? (String) t : "";
    }

    @SuppressWarnings("unchecked")
    private static Ledger withToolMetadata(Ledger ledger, File toolFolder, String rebuilt, List<String> leftOut) {
        File[] files = toolFolder.listFiles((dir, name) -> name.endsWith(ToolMetadata.SUFFIX));
        if (files == null)
            return ledger;
        Arrays.sort(files);
        for (File f : files) {
            Object parsed;
            try {
                parsed = Json.parse(new String(readAll(f), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                leftOut.add(f.getName() + ": " + e.getMessage());
                continue;
            }
            if (!(parsed instanceof Map) || !ToolMetadata.FORMAT.equals(((Map<String, Object>) parsed).get("format")))
                continue; // not written by Create/Edit Data Package in this format
            String sha256 = FileHasher.generateFileHash(f.getAbsolutePath());
            if (sha256 == null) {
                leftOut.add(f.getName() + ": the file could not be read");
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", f.getName());
            entry.put("sha256", sha256);
            entry.put("written_utc", ((Map<String, Object>) parsed).get("created_utc"));
            entry.put("rebuilt_from_files", true);
            entry.put("rebuilt_utc", rebuilt);
            ledger = ledger.withToolMetadata(entry);
        }
        return ledger;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1)
                out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadataOf(File zip) throws IOException {
        try (ZipFile z = new ZipFile(zip)) {
            ZipEntry e = z.getEntry(EventPackage.METADATA);
            if (e == null)
                throw new IOException("no " + EventPackage.METADATA + " inside");
            Object parsed;
            try (InputStream in = z.getInputStream(e)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1)
                    out.write(buf, 0, n);
                parsed = Json.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
            if (!(parsed instanceof Map) || !EventPackage.FORMAT.equals(((Map<String, Object>) parsed).get("format")))
                throw new IOException(EventPackage.METADATA + " is not " + EventPackage.FORMAT);
            return (Map<String, Object>) parsed;
        }
    }
}

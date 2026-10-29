package com.atakmap.android.lingualinkledger.plugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ledger on this phone: ledger.json, with every event's package in
 * packages/ beside it, under the plugin's folder (tools/lingualinkledger/,
 * PL-06). The older hashed_packages/ there is left alone.
 *
 * Saving one event:
 *   1. the rules are checked against the ledger as saved (StageRules);
 *   2. its package is built and stored, and the stored file hashed
 *      (EventPackage; requirement 2);
 *   3. the event's record, the package's metadata plus its file name and
 *      SHA-256 (PL-32), is added, and the whole ledger is written to a
 *      temporary file and renamed into place (AtomicFile; requirement 1).
 * The rename is the moment the event is saved. Killed before it, the
 * ledger is as it was, and at most an unrecorded package is left in
 * packages/ (never deleted: it is a photo someone took); killed after it,
 * the event is saved whole.
 *
 * If ledger.json is damaged (it cannot be parsed, or its events break a
 * rule), it is set aside as ledger.damaged-<UTC>.json, never deleted, and
 * the ledger is rebuilt from the packages (LedgerRebuild); the same if it
 * is missing while packages exist. Either way the user is told plainly,
 * once (takeNotice), and nothing is thrown (the user, Day 2 step 7). Only
 * if the file cannot be read at all, or cannot be set aside, is every
 * save refused with the reason. Plain Java, tested on the PC.
 */
final class LedgerStore {

    static final String LEDGER = "ledger.json";
    static final String PACKAGES = "packages";

    private final File folder;
    private Ledger ledger;
    private IOException unreadable;
    /** ledger.json as last read or written here; null when there was no file. */
    private byte[] onDisk;

    LedgerStore(File folder) {
        this.folder = folder;
    }

    File ledgerFile() {
        return new File(folder, LEDGER);
    }

    File packages() {
        return new File(folder, PACKAGES);
    }

    /** The ledger as saved; IOException if ledger.json cannot be read. */
    synchronized Ledger ledger() throws IOException {
        // PL-41: ATAK keeps this store for as long as it runs. If the folder
        // was cleared or ledger.json changed by hand meanwhile, follow the
        // disk, so the records held here are never written back.
        if ((ledger != null || unreadable != null) && changedOnDisk()) {
            ledger = null;
            unreadable = null;
        }
        if (ledger == null && unreadable == null)
            load();
        if (unreadable != null)
            throw unreadable;
        return ledger;
    }

    private boolean changedOnDisk() {
        File f = ledgerFile();
        if (!f.exists())
            return onDisk != null;
        if (onDisk == null || f.length() != onDisk.length)
            return true;
        try {
            return !Arrays.equals(read(f), onDisk);
        } catch (IOException e) {
            return true;
        }
    }

    private void save(Ledger next) throws IOException {
        byte[] bytes = Json.write(next.toJson()).getBytes(StandardCharsets.UTF_8);
        AtomicFile.write(ledgerFile(), bytes);
        ledger = next;
        onDisk = bytes;
    }

    synchronized int eventCount() throws IOException {
        return ledger().eventCount();
    }

    /**
     * Saves one event: Refused if a rule forbids it (nothing is written);
     * IOException if the phone could not store it (the ledger is as it was).
     * Returns the event's record as saved.
     */
    synchronized Map<String, Object> record(EventDraft draft, String appVersion, long nowMs)
            throws Ledger.Refused, IOException {
        Ledger current = ledger();
        current.check(draft);

        long seq = current.eventCount() + 1;
        EventPackage.Built pkg = EventPackage.build(packages(), draft, seq, appVersion);

        Map<String, Object> event = new LinkedHashMap<>(pkg.metadata); // seq first, as in the package
        event.put("package_file", pkg.file.getName());
        event.put("package_sha256", pkg.sha256);
        event.put("saved_utc", UtcTime.format(nowMs));

        save(current.with(event));
        return event;
    }

    /**
     * Writes Create/Edit Data Package's metadata file into the plugin's
     * folder, under a safe name never used before (PL-33; an existing file
     * is never replaced), hashes it as stored, and registers that SHA-256
     * in the ledger (T2), saved whole like every save. Returns the file.
     */
    synchronized File writeToolMetadata(Map<String, Object> metadata, String packageName, long nowMs)
            throws IOException {
        Ledger current = ledger();
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("could not make " + folder);
        String name = ToolMetadata.fileName(packageName, nowMs);
        File f = new File(folder, name);
        for (int n = 2; f.exists(); n++)
            f = new File(folder, name.substring(0, name.length() - ToolMetadata.SUFFIX.length()) + "_" + n
                    + ToolMetadata.SUFFIX);
        AtomicFile.write(f, Json.write(metadata).getBytes(StandardCharsets.UTF_8));
        String sha256 = FileHasher.generateFileHash(f.getAbsolutePath());
        if (sha256 == null)
            throw new IOException("could not read back " + f.getName());

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("file", f.getName());
        entry.put("sha256", sha256);
        entry.put("written_utc", UtcTime.format(nowMs));
        save(current.withToolMetadata(entry));
        return f;
    }

    /**
     * Saves one tool record (ToolRecords; T3): numbered next, written as
     * its own file in tool_records/ (so a rebuild keeps it, D63), then the
     * whole ledger, as every save. Refused if a tool rule forbids it
     * (nothing is written); IOException if the phone could not store it.
     * Returns the record as saved.
     */
    synchronized Map<String, Object> recordTool(Map<String, Object> draft) throws Ledger.Refused, IOException {
        Ledger current = ledger();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("tool_seq", (long) current.toolRecords().size() + 1);
        record.putAll(draft);
        Ledger next = current.withToolRecord(record); // the rules, before anything is written
        File dir = toolRecordsFolder();
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("could not make " + dir);
        String name = ToolRecords.fileName(record);
        File f = new File(dir, name);
        for (int n = 2; f.exists(); n++)
            f = new File(dir, name.substring(0, name.length() - 5) + "_" + n + ".json");
        AtomicFile.write(f, Json.write(record).getBytes(StandardCharsets.UTF_8));
        save(next);
        return record;
    }

    File toolRecordsFolder() {
        return new File(folder, ToolRecords.FOLDER);
    }

    /** Packages in packages/ that no saved event names: left by a save that never finished. */
    synchronized List<File> unrecordedPackages() throws IOException {
        Set<String> named = new HashSet<>();
        for (Map<String, Object> e : ledger().events())
            named.add(String.valueOf(e.get("package_file")));
        List<File> out = new ArrayList<>();
        File[] files = packages().listFiles((dir, name) -> name.endsWith(".zip"));
        if (files != null)
            for (File f : files)
                if (!named.contains(f.getName()))
                    out.add(f);
        return out;
    }

    /**
     * What the user must be told about the last load, once: the ledger was
     * found damaged or missing and was rebuilt from the packages. Null if
     * nothing happened. Cleared when read.
     */
    synchronized String takeNotice() {
        String n = notice;
        notice = null;
        return n;
    }

    private String notice;

    private void load() {
        // unfinished writes from a save that was killed: never part of the record
        AtomicFile.tempFor(ledgerFile()).delete();
        File[] parts = packages().listFiles((dir, name) -> name.endsWith(EventPackage.PART));
        if (parts != null)
            for (File f : parts)
                f.delete();

        File f = ledgerFile();
        if (!f.exists()) {
            File[] zips = packages().listFiles((dir, name) -> name.endsWith(".zip"));
            File[] tools = folder.listFiles((dir, name) -> name.endsWith(ToolMetadata.SUFFIX));
            File[] records = toolRecordsFolder().listFiles((dir, name) -> name.endsWith(".json"));
            int nz = zips == null ? 0 : zips.length, nt = tools == null ? 0 : tools.length;
            int nr = records == null ? 0 : records.length;
            if (nz == 0 && nt == 0 && nr == 0) {
                ledger = Ledger.empty(); // a phone with no records yet, or records cleared
                onDisk = null;
                return;
            }
            rebuild("No saved records were found on this phone, but " + nz
                    + (nz == 1 ? " package" : " packages") + (nr == 0 ? " and " : ", ") + nt
                    + (nt == 1 ? " metadata file" : " metadata files")
                    + (nr == 0 ? "" : " and " + nr + (nr == 1 ? " tool record" : " tool records")) + " were.");
            return;
        }
        byte[] bytes;
        try {
            bytes = read(f);
        } catch (IOException e) {
            unreadable = new IOException("The records on this phone could not be read (" + e.getMessage()
                    + "). Nothing will be saved until " + LEDGER + " can be read.", e);
            return;
        }
        try {
            ledger = Ledger.fromJson(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
            onDisk = bytes;
        } catch (Ledger.Refused | RuntimeException damaged) {
            // the user's rule: say so plainly, keep the damaged file aside, rebuild, never crash
            File aside = asideName();
            if (!f.renameTo(aside)) {
                unreadable = new IOException("The records on this phone are damaged (" + damaged.getMessage()
                        + "), and the damaged file could not be set aside. Nothing will be saved until "
                        + LEDGER + " is checked.", damaged);
                return;
            }
            rebuild("The saved records on this phone were damaged and could not be read ("
                    + damaged.getMessage() + "). The damaged file has been kept as " + aside.getName() + ".");
        }
    }

    /** Rebuilds the ledger from packages/, saves it, and leaves the notice for the user. */
    private void rebuild(String what) {
        long now = System.currentTimeMillis();
        LedgerRebuild.Result r = LedgerRebuild.fromPackages(packages(), folder, now);
        try {
            save(r.ledger);
        } catch (IOException e) {
            unreadable = new IOException(what + " Rebuilding the records from the packages failed ("
                    + e.getMessage() + "). Nothing will be saved until " + LEDGER + " is checked.", e);
            return;
        }
        StringBuilder sb = new StringBuilder(what);
        sb.append(" The records were rebuilt from the ").append(r.packagesFound)
                .append(r.packagesFound == 1 ? " package" : " packages").append(" on this phone: ")
                .append(r.ledger.eventCount()).append(r.ledger.eventCount() == 1 ? " event." : " events.");
        int tools = r.ledger.toolMetadata().size();
        if (tools > 0)
            sb.append(" ").append(tools).append(tools == 1 ? " metadata file" : " metadata files")
                    .append(" from Create/Edit Data Package re-registered.");
        int records = r.ledger.toolRecords().size();
        if (records > 0)
            sb.append(" ").append(records).append(records == 1 ? " record" : " records")
                    .append(" from the tools restored from their files.");
        if (!r.leftOut.isEmpty()) {
            sb.append(" Left out:");
            for (String s : r.leftOut)
                sb.append("\n- ").append(s);
            sb.append('\n');
        }
        sb.append(" Every rebuilt event is marked \"rebuilt from packages\": its SHA-256 was recalculated "
                + "from the stored file, so it cannot show the package is unchanged since it was captured.");
        notice = sb.toString();
    }

    private File asideName() {
        String stamp = UtcTime.compact(System.currentTimeMillis());
        File aside = new File(folder, "ledger.damaged-" + stamp + ".json");
        for (int n = 2; aside.exists(); n++)
            aside = new File(folder, "ledger.damaged-" + stamp + "_" + n + ".json");
        return aside;
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1)
                out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}

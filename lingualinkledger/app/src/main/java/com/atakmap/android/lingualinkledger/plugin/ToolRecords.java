package com.atakmap.android.lingualinkledger.plugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The tools' records (T3; D63, Day 3 step 12), kept apart from the
 * batches' events because they have no batch ID and the stage table (S7)
 * does not apply to them:
 *
 *   evidence_trace     Create Evidence Trace: a data package the plugin
 *                      built, hashed as stored. Only a package holding a
 *                      metadata file Create/Edit Data Package wrote and
 *                      registered (its SHA-256 in the ledger's
 *                      tool_metadata) can be traced, so no other zip can
 *                      be (T2, R4). The stage and position are copied from
 *                      that metadata (PL-32). A package is traced once.
 *   tool_stage_event   Add Stage Event (D44): a stage, a fresh position by
 *                      V1.2's rules and optional typed remarks, on one
 *                      trace, which it names by number and SHA-256.
 *
 * Each record is kept in ledger.json's tool_records, saved whole with
 * everything else (the user's rule 1), and also as a self-describing file
 * in tool_records/, so a damaged ledger can be rebuilt with them (D56,
 * D63). Nothing is sent (D45). Plain Java, tested on the PC.
 */
final class ToolRecords {

    static final String FORMAT = "pet-tool-record-1";
    static final String FOLDER = "tool_records";
    static final String KIND_TRACE = "evidence_trace";
    static final String KIND_STAGE_EVENT = "tool_stage_event";

    private ToolRecords() {
    }

    /** A package that holds a metadata file the plugin registered (T2). */
    static final class Qualifying {
        final File file;
        /** The metadata file's name inside the package, and its SHA-256 (the registered one). */
        final String metadataEntry;
        final String metadataSha256;
        /** The metadata as written by Create/Edit Data Package. */
        final Map<String, Object> metadata;

        Qualifying(File file, String metadataEntry, String metadataSha256, Map<String, Object> metadata) {
            this.file = file;
            this.metadataEntry = metadataEntry;
            this.metadataSha256 = metadataSha256;
            this.metadata = metadata;
        }
    }

    /**
     * Every .zip in dir that holds, byte for byte, a metadata file the
     * plugin registered; newest first. A zip that cannot be read, or holds
     * no registered metadata file, is not offered.
     */
    static List<Qualifying> qualifying(File dir, Ledger ledger) {
        List<Qualifying> out = new ArrayList<>();
        File[] zips = dir == null ? null : dir.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(".zip"));
        if (zips == null)
            return out;
        for (File z : zips) {
            Qualifying q = qualifies(z, ledger);
            if (q != null)
                out.add(q);
        }
        Collections.sort(out, (a, b) -> {
            long d = b.file.lastModified() - a.file.lastModified();
            return d != 0 ? (d < 0 ? -1 : 1) : a.file.getName().compareTo(b.file.getName());
        });
        return out;
    }

    /** The package if it holds a registered metadata file, else null. */
    @SuppressWarnings("unchecked")
    static Qualifying qualifies(File zip, Ledger ledger) {
        try (ZipFile z = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = z.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(ToolMetadata.SUFFIX))
                    continue;
                byte[] bytes = read(z, e);
                String sha256 = sha256(bytes);
                if (!ledger.isToolMetadata(sha256))
                    continue;
                Object parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
                if (!(parsed instanceof Map) || !ToolMetadata.FORMAT.equals(((Map<String, Object>) parsed).get("format")))
                    continue;
                String name = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                return new Qualifying(zip, name, sha256, (Map<String, Object>) parsed);
            }
        } catch (IOException | RuntimeException e) {
            return null; // not a zip this phone can read: not offered
        }
        return null;
    }

    /** Null if this package may be traced; else why not (a package is traced once). */
    static String refuseTrace(Ledger ledger, String packageSha256) {
        Map<String, Object> earlier = ledger.traceOf(packageSha256);
        if (earlier != null)
            return "This data package was traced before, as tool record " + earlier.get("tool_seq") + " ("
                    + earlier.get("traced_utc") + "). A package is traced once; use Add Stage Event to add "
                    + "a stage to it.";
        return null;
    }

    /** The record of a trace: the package as stored now, and what its metadata says. */
    static Map<String, Object> trace(Qualifying q, String packageSha256, long packageBytes, long nowMs,
            String appVersion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("kind", KIND_TRACE);
        m.put("tool", "Create Evidence Trace");
        m.put("package_file", q.file.getName());
        m.put("package_bytes", packageBytes);
        m.put("package_sha256", packageSha256);
        m.put("metadata_file", q.metadataEntry);
        m.put("metadata_sha256", q.metadataSha256);
        m.put("package_name", q.metadata.get("package_name"));
        m.put("stage", q.metadata.get("stage"));
        m.put("position", q.metadata.get("position"));
        m.put("metadata_created_utc", q.metadata.get("created_utc"));
        m.put("employee", null); // no sign-in in v1 (D41)
        m.put("traced_utc", UtcTime.format(nowMs));
        m.put("app_version", appVersion);
        return m;
    }

    /** The record of a stage event on a trace (D44): the stage, a fresh position, optional remarks. */
    static Map<String, Object> stageEvent(Map<String, Object> trace, Stage stage, PositionPolicy.Check position,
            String remarks, long nowMs, String appVersion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("kind", KIND_STAGE_EVENT);
        m.put("tool", "Add Stage Event");
        m.put("trace_tool_seq", trace.get("tool_seq"));
        m.put("trace_package_sha256", trace.get("package_sha256"));
        m.put("stage", stage.label);
        m.put("position", position.recordBlock());
        String r = remarks == null ? "" : remarks.trim();
        m.put("remarks", r.isEmpty() ? null : r);
        m.put("remarks_note", "typed, not checked");
        m.put("employee", null);
        m.put("recorded_utc", UtcTime.format(nowMs));
        m.put("app_version", appVersion);
        return m;
    }

    /** The record's file in tool_records/: its number, kind and time, e.g. 0001_evidence_trace_20261013T201500Z.json. */
    static String fileName(Map<String, Object> record) {
        Object time = KIND_TRACE.equals(record.get("kind")) ? record.get("traced_utc") : record.get("recorded_utc");
        String compact = String.valueOf(time).replace("-", "").replace(":", "");
        return String.format(Locale.US, "%04d_%s_%s.json", (Long) record.get("tool_seq"), record.get("kind"), compact);
    }

    /** SHA-256 as lowercase hex, as FileHasher writes it, of bytes read from inside a zip. */
    static String sha256(byte[] bytes) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
                sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java and Android has SHA-256
        }
    }

    private static byte[] read(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1)
                out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}

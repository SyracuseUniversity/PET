package com.atakmap.android.lingualinkledger.plugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The metadata file Create/Edit Data Package writes, which goes inside
 * the data package ATAK then builds (T5): JSON, through the same writer
 * as the capture flow, with the stage (D44), the position block by V1.2's
 * rules (never the map's centre, PL-18), the time in UTC, and the typed
 * name, description and author, the author marked "typed, not checked"
 * (ID-01). LedgerStore writes it under a safe file name (PL-33) and
 * registers its SHA-256 (T2). Plain Java, tested on the PC.
 */
final class ToolMetadata {

    static final String FORMAT = "pet-tool-metadata-1";
    static final String TOOL = "Create/Edit Data Package";
    static final String SUFFIX = "_metadata.json";
    static final int MAX_NAME = 48;

    private ToolMetadata() {
    }

    static Map<String, Object> build(String packageName, String description, String author, Stage stage,
            PositionPolicy.Check position, long createdAtMs, String appVersion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("tool", TOOL);
        m.put("package_name", packageName);
        m.put("description", description);
        m.put("author", author);
        m.put("author_note", "typed, not checked"); // no sign-in in v1 (ID-01, D41)
        m.put("stage", stage.label);
        m.put("created_utc", UtcTime.format(createdAtMs));
        m.put("position", position.recordBlock());
        m.put("app_version", appVersion);
        return m;
    }

    /**
     * A safe form of a typed package name, for a file name (PL-33): only
     * A-Z, a-z, 0-9, '-' and '_' are kept; anything else, including '.',
     * '/' and '\', becomes '_', so the name cannot leave its folder or
     * hide as a dot-file; runs of '_' become one; at most MAX_NAME
     * characters; "package" if nothing is left. The typed name itself is
     * kept, unchanged, inside the file.
     */
    static String safeName(String typed) {
        StringBuilder sb = new StringBuilder();
        if (typed != null) {
            for (int i = 0; i < typed.length() && sb.length() < MAX_NAME; i++) {
                char c = typed.charAt(i);
                boolean keep = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
                if (keep)
                    sb.append(c);
                else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_')
                    sb.append('_');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_')
            sb.setLength(sb.length() - 1);
        return sb.length() == 0 ? "package" : sb.toString();
    }

    /** The file name: the safe name and the UTC time, e.g. Red_pine_lot_20261012T221500Z_metadata.json. */
    static String fileName(String packageName, long createdAtMs) {
        return safeName(packageName) + "_" + UtcTime.compact(createdAtMs) + SUFFIX;
    }

    /**
     * The data package Create/Edit Data Package builds around the metadata
     * file (PL-39; D63): named from the form's Package Name by the same
     * safe rule, in ATAK's data package folder, never over an existing file
     * (Red_pine_lot.zip, then Red_pine_lot_2.zip, ...).
     */
    static File dataPackageFile(File dir, String packageName) {
        String base = safeName(packageName);
        File f = new File(dir, base + ".zip");
        for (int n = 2; f.exists(); n++)
            f = new File(dir, base + "_" + n + ".zip");
        return f;
    }
}

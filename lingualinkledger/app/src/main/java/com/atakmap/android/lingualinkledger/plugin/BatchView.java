package com.atakmap.android.lingualinkledger.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What this phone has recorded, as Saved batches and Look up batch show it
 * (V1.7; Day 3 step 13, D59): each batch with its stage, open or closed
 * and what closed it, what it was combined from, and its events (stage,
 * UTC time, position with its source and accuracy, the package and its
 * full SHA-256); "rebuilt from packages" on every event that was (D56);
 * a typed ID's test status as unknown (D57); the next stage, worked out
 * from the stage table itself (S7), so it cannot disagree with the rules;
 * and the tools' records in a section of their own (T3). Plain Java,
 * tested on the PC.
 */
final class BatchView {

    private BatchView() {
    }

    /** One line for the list: "SAR-TEST-0004: Cleaning, open (TEST LABEL)". */
    static String line(Ledger.Batch b) {
        return b.id + ": " + (b.open ? b.stage.label + ", open" : "closed, " + b.closedBy) + mark(b.testLabel);
    }

    /** Which stage to record next for this ID, from the stage table (S7). */
    static String next(Ledger ledger, String id) {
        Ledger.Batch b = ledger.batch(id);
        if (b == null)
            return "Not recorded on this phone yet: record it with Collecting.";
        if (!b.open)
            return "Closed (" + b.closedBy + "): it can take no more events.";
        List<String> ways = new ArrayList<>();
        for (Stage s : Stage.values()) {
            if (StageRules.takesEvent(s) && StageRules.refuseEvent(ledger, s, id) == null)
                ways.add(s == Stage.STORED ? "Stored (one batch)" : s.label);
            if (StageRules.takesMerge(s)
                    && StageRules.refuseParent(ledger, s, Collections.<String>emptyList(), id) == null)
                ways.add(s == Stage.CLEANING ? "Cleaning" : s.label + " (Combine batches)");
        }
        return ways.isEmpty() ? "No stage can be recorded for it now." : "Next: " + or(ways) + ".";
    }

    /** Everything this phone has recorded for one batch. */
    @SuppressWarnings("unchecked")
    static String detail(Ledger ledger, Ledger.Batch b) {
        StringBuilder sb = new StringBuilder();
        sb.append(b.id).append(mark(b.testLabel));
        if (b.species != null)
            sb.append("\nSpecies, as printed (not checked): ").append(b.species);
        sb.append("\nStage: ").append(b.stage.label).append(b.open ? ", open" : "; closed, " + b.closedBy);
        if (!b.combinedFrom.isEmpty())
            sb.append("\nCombined from: ").append(join(b.combinedFrom));
        sb.append("\n").append(next(ledger, b.id));
        sb.append("\n\nEvents (").append(b.events.size()).append("):");
        for (Map<String, Object> e : b.events) {
            sb.append("\n\n").append(e.get("seq")).append(". ").append(what(b, e));
            sb.append("\n   ").append(e.get("captured_utc"));
            Object pos = e.get("position");
            if (pos instanceof Map)
                sb.append("\n   ").append(position((Map<String, Object>) pos));
            sb.append("\n   Package: ").append(e.get("package_file"));
            sb.append("\n   SHA-256: ").append(e.get("package_sha256"));
            if (Boolean.TRUE.equals(e.get("rebuilt_from_packages")))
                sb.append("\n   Rebuilt from packages: its SHA-256 was recalculated from the stored file.");
        }
        return sb.toString();
    }

    /** The tools' records (T3): each trace with its stage events. */
    @SuppressWarnings("unchecked")
    static String tools(Ledger ledger) {
        List<Map<String, Object>> traces = ledger.traces();
        StringBuilder sb = new StringBuilder("Records from the tools (" + ledger.toolRecords().size() + "):");
        if (traces.isEmpty())
            return sb.append("\nNone yet.").toString();
        for (Map<String, Object> t : traces) {
            sb.append("\n\nTrace ").append(t.get("tool_seq")).append(": ").append(t.get("package_file"));
            sb.append("\n   Traced ").append(t.get("traced_utc")).append("; stage, from its metadata: ")
                    .append(t.get("stage"));
            Object pos = t.get("position");
            if (pos instanceof Map)
                sb.append("\n   ").append(position((Map<String, Object>) pos));
            sb.append("\n   SHA-256: ").append(t.get("package_sha256"));
            if (Boolean.TRUE.equals(t.get("rebuilt_from_files")))
                sb.append("\n   Restored from its record file.");
            for (Map<String, Object> e : ledger.stageEventsOf(t)) {
                sb.append("\n   + Stage event ").append(e.get("tool_seq")).append(": ").append(e.get("stage"))
                        .append(", ").append(e.get("recorded_utc"));
                Object p = e.get("position");
                if (p instanceof Map)
                    sb.append("\n     ").append(position((Map<String, Object>) p));
                if (e.get("remarks") != null)
                    sb.append("\n     Remarks (typed, not checked): ").append(e.get("remarks"));
            }
        }
        return sb.toString();
    }

    /** What an event was, for this batch. */
    @SuppressWarnings("unchecked")
    private static String what(Ledger.Batch b, Map<String, Object> e) {
        String kind = String.valueOf(e.get("kind"));
        String stage = String.valueOf(e.get("stage"));
        if (Ledger.KIND_COLLECT.equals(kind))
            return "Collecting: collected";
        if (Ledger.KIND_MERGE.equals(kind)) {
            if (b.id.equals(e.get("batch_id")))
                return stage + ": made by combining " + join((List<Object>) e.get("parent_ids"));
            return stage + ": combined into " + e.get("batch_id");
        }
        return stage + (Stage.PLANTING.label.equals(stage) ? ": sown" : "");
    }

    /** "43.037634, -76.13512 (GPS, 51 m)", or the manual location's label. */
    private static String position(Map<String, Object> p) {
        Object acc = p.get("accuracy_m");
        String accuracy = acc instanceof Number ? String.format(Locale.US, ", %.0f m", ((Number) acc).doubleValue()) : "";
        return "Position: " + p.get("latitude") + ", " + p.get("longitude") + " (" + p.get("source_label") + accuracy
                + ")";
    }

    /** TEST LABEL, nothing for a real label, or unknown when the ID was typed (D57). */
    static String mark(Boolean test) {
        if (test == null)
            return " (test status unknown: ID typed)";
        return test ? " (TEST LABEL)" : "";
    }

    private static String join(List<?> items) {
        StringBuilder sb = new StringBuilder();
        for (Object o : items) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(o);
        }
        return sb.toString();
    }

    private static String or(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0)
                sb.append(i == items.size() - 1 ? " or " : ", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}

package com.atakmap.android.lingualinkledger.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every event saved on this phone, in order, and the state of each batch
 * that follows from them (V1.7; D45: nothing is sent anywhere).
 *
 * The events are the record; a batch's stage, whether it is open, and
 * what it was combined from or into are never stored, only worked out by
 * replaying the events through StageRules. So a merge, one event, closes
 * its parents and creates its new batch together or not at all: there is
 * no state in which the new batch exists while its parents are still open
 * (the user's requirement 1, Day 2 step 6, with LedgerStore's atomic
 * save). A ledger whose events break the rules does not load.
 *
 * Immutable: with() returns a new ledger. Plain Java, tested on the PC.
 */
final class Ledger {

    static final String FORMAT = "pet-ledger-1";

    static final String KIND_COLLECT = "collect";
    static final String KIND_EVENT = "stage_event";
    static final String KIND_MERGE = "merge";

    /** A rule refused an event; the message is for the user. */
    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    /** What this phone holds for one batch. Read only outside Ledger. */
    static final class Batch {
        final String id;
        Stage stage;
        boolean open = true;
        /** What closed it: "combined into X" or "sown"; null while open. */
        String closedBy;
        /** The batch it was combined into, or null. */
        String combinedInto;
        /** The batches it was combined from, for a batch made by a merge. */
        final List<String> combinedFrom = new ArrayList<>();
        /** From its label: TRUE test=1 (TEST LABEL), FALSE real, null unknown (ID typed). */
        Boolean testLabel;
        /** As printed on its label; never trusted. */
        String species;
        /** Its events in order, including the merge that closed it. */
        final List<Map<String, Object>> events = new ArrayList<>();

        private Batch(String id, Stage stage) {
            this.id = id;
            this.stage = stage;
        }

        /** "at Cleaning, open", "combined into SAR-TEST-0004" or "sown". */
        String describe() {
            return open ? "at " + stage.label + ", open" : closedBy;
        }
    }

    private final List<Map<String, Object>> events;
    /**
     * T2: each metadata file Create/Edit Data Package wrote, with the
     * SHA-256 of the file as stored, so that Create Evidence Trace (Day 3,
     * step 12) takes only packages that hold one. Not events: the stage
     * rules do not apply to them.
     */
    private final List<Map<String, Object>> toolMetadata;
    /**
     * The tools' records (T3; ToolRecords): Create Evidence Trace's traces
     * and Add Stage Event's events, numbered by tool_seq. Not batch events:
     * the stage table does not apply to them.
     */
    private final List<Map<String, Object>> toolRecords;
    private final Map<String, Batch> batches = new LinkedHashMap<>();

    private Ledger(List<Map<String, Object>> events, List<Map<String, Object>> toolMetadata,
            List<Map<String, Object>> toolRecords) throws Refused {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.toolMetadata = Collections.unmodifiableList(new ArrayList<>(toolMetadata));
        this.toolRecords = Collections.unmodifiableList(new ArrayList<>(toolRecords));
        for (int i = 0; i < this.events.size(); i++)
            apply(i + 1, this.events.get(i));
        for (int i = 0; i < this.toolRecords.size(); i++)
            applyTool(i + 1, this.toolRecords.get(i));
    }

    static Ledger empty() {
        try {
            return new Ledger(Collections.<Map<String, Object>>emptyList(),
                    Collections.<Map<String, Object>>emptyList(), Collections.<Map<String, Object>>emptyList());
        } catch (Refused e) {
            throw new AssertionError(e);
        }
    }

    /** A ledger read back from its JSON; Refused if it breaks a rule or is malformed. */
    @SuppressWarnings("unchecked")
    static Ledger fromJson(Object parsed) throws Refused {
        if (!(parsed instanceof Map))
            throw new Refused("not a ledger");
        Map<String, Object> m = (Map<String, Object>) parsed;
        if (!FORMAT.equals(m.get("format")))
            throw new Refused("not a " + FORMAT + " ledger (format " + m.get("format") + ")");
        Object events = m.get("events");
        if (!(events instanceof List))
            throw new Refused("the ledger has no list of events");
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object e : (List<Object>) events) {
            if (!(e instanceof Map))
                throw new Refused("event " + (list.size() + 1) + " is not a record");
            list.add((Map<String, Object>) e);
        }
        List<Map<String, Object>> tools = new ArrayList<>();
        Object t = m.get("tool_metadata");
        if (t != null) {
            if (!(t instanceof List))
                throw new Refused("the ledger's tool_metadata is not a list");
            for (Object e : (List<Object>) t) {
                if (!(e instanceof Map) || !(((Map<String, Object>) e).get("sha256") instanceof String))
                    throw new Refused("tool_metadata entry " + (tools.size() + 1) + " has no sha256");
                tools.add((Map<String, Object>) e);
            }
        }
        List<Map<String, Object>> records = new ArrayList<>();
        Object r = m.get("tool_records");
        if (r != null) {
            if (!(r instanceof List))
                throw new Refused("the ledger's tool_records is not a list");
            for (Object e : (List<Object>) r) {
                if (!(e instanceof Map))
                    throw new Refused("tool record " + (records.size() + 1) + " is not a record");
                records.add((Map<String, Object>) e);
            }
        }
        return new Ledger(list, tools, records);
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("events", events);
        m.put("tool_metadata", toolMetadata);
        m.put("tool_records", toolRecords);
        return m;
    }

    /** This ledger with one more event, whose seq must be the next number; Refused if a rule forbids it. */
    Ledger with(Map<String, Object> event) throws Refused {
        List<Map<String, Object>> more = new ArrayList<>(events);
        more.add(event);
        return new Ledger(more, toolMetadata, toolRecords);
    }

    /**
     * This ledger with one more tool record (ToolRecords), whose tool_seq
     * must be the next number; Refused if it breaks a tool rule: a stage
     * event on a trace that does not exist, or a package traced twice.
     */
    Ledger withToolRecord(Map<String, Object> record) throws Refused {
        List<Map<String, Object>> more = new ArrayList<>(toolRecords);
        more.add(record);
        return new Ledger(events, toolMetadata, more);
    }

    List<Map<String, Object>> toolRecords() {
        return toolRecords;
    }

    /** The trace of the package with this SHA-256, or null. */
    Map<String, Object> traceOf(String packageSha256) {
        for (Map<String, Object> r : toolRecords)
            if (ToolRecords.KIND_TRACE.equals(r.get("kind")) && packageSha256.equals(r.get("package_sha256")))
                return r;
        return null;
    }

    /** The tool record with this number, or null. */
    Map<String, Object> toolRecord(long toolSeq) {
        return toolSeq >= 1 && toolSeq <= toolRecords.size() ? toolRecords.get((int) toolSeq - 1) : null;
    }

    /** Every trace, in the order made. */
    List<Map<String, Object>> traces() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : toolRecords)
            if (ToolRecords.KIND_TRACE.equals(r.get("kind")))
                out.add(r);
        return out;
    }

    /** A trace's stage events, in order. */
    List<Map<String, Object>> stageEventsOf(Map<String, Object> trace) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : toolRecords)
            if (ToolRecords.KIND_STAGE_EVENT.equals(r.get("kind")) && r.get("trace_tool_seq").equals(trace.get("tool_seq")))
                out.add(r);
        return out;
    }

    /** This ledger with one more registered metadata file (T2): {file, sha256, written_utc, ...}. */
    Ledger withToolMetadata(Map<String, Object> entry) {
        if (!(entry.get("sha256") instanceof String))
            throw new IllegalArgumentException("a tool_metadata entry needs its sha256");
        List<Map<String, Object>> more = new ArrayList<>(toolMetadata);
        more.add(entry);
        try {
            return new Ledger(events, more, toolRecords);
        } catch (Refused e) {
            throw new AssertionError(e); // the events are unchanged, and were valid
        }
    }

    /** Whether a file with this SHA-256 was written by Create/Edit Data Package (T2). */
    boolean isToolMetadata(String sha256) {
        for (Map<String, Object> e : toolMetadata)
            if (e.get("sha256").equals(sha256))
                return true;
        return false;
    }

    List<Map<String, Object>> toolMetadata() {
        return toolMetadata;
    }

    /** Whether the rules allow this event now; Refused with the message if not. */
    void check(EventDraft draft) throws Refused {
        String why;
        switch (draft.kind) {
            case KIND_COLLECT:
                why = StageRules.refuseCollect(this, draft.batchId);
                break;
            case KIND_EVENT:
                why = StageRules.refuseEvent(this, draft.stage, draft.batchId);
                break;
            default:
                why = StageRules.refuseMerge(this, draft.stage, draft.parentIds, draft.batchId);
        }
        if (why != null)
            throw new Refused(why);
    }

    int eventCount() {
        return events.size();
    }

    List<Map<String, Object>> events() {
        return events;
    }

    /** The batch with this ID, or null if it is not recorded. */
    Batch batch(String id) {
        return batches.get(id);
    }

    /** Every batch, in the order each was first recorded. */
    Collection<Batch> batches() {
        return Collections.unmodifiableCollection(batches.values());
    }

    private void apply(int seq, Map<String, Object> e) throws Refused {
        try {
            Object n = e.get("seq");
            if (!(n instanceof Long) || (Long) n != seq)
                throw new Refused("is numbered " + n);
            String kind = string(e, "kind");
            Stage stage = Stage.of(string(e, "stage"));
            String id = string(e, "batch_id");
            switch (kind) {
                case KIND_COLLECT:
                    if (stage != Stage.COLLECTING)
                        throw new Refused("is a collection at " + stage.label);
                    refuse(StageRules.refuseCollect(this, id));
                    created(id, stage, e);
                    break;
                case KIND_EVENT:
                    if (!StageRules.takesEvent(stage))
                        throw new Refused("is a single-batch event at " + stage.label);
                    refuse(StageRules.refuseEvent(this, stage, id));
                    Batch b = batches.get(id);
                    b.stage = stage;
                    b.events.add(e);
                    if (StageRules.closes(stage)) {
                        b.open = false;
                        b.closedBy = StageRules.SOWN;
                    }
                    break;
                case KIND_MERGE:
                    if (!StageRules.takesMerge(stage))
                        throw new Refused("is a merge at " + stage.label);
                    List<String> parents = strings(e, "parent_ids");
                    refuse(StageRules.refuseMerge(this, stage, parents, id));
                    for (String p : parents) {
                        Batch pb = batches.get(p);
                        pb.open = false;
                        pb.closedBy = "combined into " + id;
                        pb.combinedInto = id;
                        pb.events.add(e);
                    }
                    created(id, stage, e).combinedFrom.addAll(parents);
                    break;
                default:
                    throw new Refused("is of an unknown kind, " + kind);
            }
        } catch (Refused | RuntimeException x) {
            throw new Refused("Saved event " + seq + " " + x.getMessage());
        }
    }

    /** A tool record's own rules (T3): numbered in order; a trace's package traced once; an event on a trace. */
    private void applyTool(int toolSeq, Map<String, Object> r) throws Refused {
        try {
            Object n = r.get("tool_seq");
            if (!(n instanceof Long) || (Long) n != toolSeq)
                throw new Refused("is numbered " + n);
            if (!ToolRecords.FORMAT.equals(r.get("format")))
                throw new Refused("is not a " + ToolRecords.FORMAT + " record");
            String kind = string(r, "kind");
            Object stage = r.get("stage");
            if (stage != null)
                Stage.of(String.valueOf(stage));
            if (ToolRecords.KIND_TRACE.equals(kind)) {
                String sha256 = string(r, "package_sha256");
                for (int i = 0; i < toolSeq - 1; i++) {
                    Map<String, Object> o = toolRecords.get(i);
                    if (ToolRecords.KIND_TRACE.equals(o.get("kind")) && sha256.equals(o.get("package_sha256")))
                        throw new Refused("traces a package already traced, as tool record " + o.get("tool_seq"));
                }
            } else if (ToolRecords.KIND_STAGE_EVENT.equals(kind)) {
                Object t = r.get("trace_tool_seq");
                Map<String, Object> trace = null;
                if (t instanceof Long && (Long) t >= 1 && (Long) t < toolSeq)
                    trace = toolRecords.get((int) ((Long) t - 1));
                if (trace == null || !ToolRecords.KIND_TRACE.equals(trace.get("kind")))
                    throw new Refused("is a stage event on tool record " + t + ", which is not an earlier trace");
                if (stage == null)
                    throw new Refused("has no stage");
            } else {
                throw new Refused("is of an unknown kind, " + kind);
            }
        } catch (Refused | RuntimeException x) {
            throw new Refused("Saved tool record " + toolSeq + " " + x.getMessage());
        }
    }

    private Batch created(String id, Stage stage, Map<String, Object> e) {
        Batch b = new Batch(id, stage);
        Object test = e.get("test_label");
        b.testLabel = test instanceof Boolean ? (Boolean) test : null;
        Object species = e.get("species_as_printed");
        b.species = species instanceof String ? (String) species : null;
        b.events.add(e);
        batches.put(id, b);
        return b;
    }

    private static void refuse(String why) throws Refused {
        if (why != null)
            throw new Refused("breaks a rule: " + why);
    }

    private static String string(Map<String, Object> e, String key) throws Refused {
        Object v = e.get(key);
        if (!(v instanceof String))
            throw new Refused("has no " + key);
        return (String) v;
    }

    private static List<String> strings(Map<String, Object> e, String key) throws Refused {
        Object v = e.get(key);
        if (!(v instanceof List))
            throw new Refused("has no " + key);
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) v) {
            if (!(o instanceof String))
                throw new Refused("has a " + key + " entry that is not text");
            out.add((String) o);
        }
        return out;
    }
}

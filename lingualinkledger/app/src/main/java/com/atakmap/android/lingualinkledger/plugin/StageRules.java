package com.atakmap.android.lingualinkledger.plugin;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which event a batch may take, and which batches a merge may combine
 * (V1.7's stage table). These are the scope's ASSUMPTIONS, kept in this
 * one place so the nursery walkthrough can change them (scope Q1: merges
 * at cleaning only, or in storage too?; Q6: is any stage ever skipped?)
 * (S7):
 *
 *   Collecting  creates a batch; refused if the ID is already recorded.
 *   Cleaning    a merge: one or more open batches at Collecting into a new
 *               batch; the parents close.
 *   Stored      an event on an open batch at Cleaning or Stored; or a
 *               merge ("Combine batches") of open batches at Cleaning or
 *               Stored into a new batch at Stored (assumed, Q1).
 *   Planting    an event on an open batch at Stored; the batch closes,
 *               sown.
 *
 * A closed batch takes no event, and the message says what closed it. A
 * merge's parents must be recorded on this phone (S8), and its new batch
 * must be new: not one of its parents, and not any batch the ledger holds
 * (the user's rule, Day 2 step 6).
 *
 * Each check returns null when allowed, or a plain message saying why not.
 */
final class StageRules {

    /** Single-batch events: the stages the batch may be at. */
    private static final Map<Stage, Set<Stage>> EVENT_FROM = new EnumMap<>(Stage.class);
    /** Merges: the stages their parents may be at. */
    private static final Map<Stage, Set<Stage>> MERGE_FROM = new EnumMap<>(Stage.class);
    /** After an event at one of these stages the batch closes. */
    private static final Set<Stage> CLOSES = EnumSet.of(Stage.PLANTING);

    static {
        EVENT_FROM.put(Stage.STORED, EnumSet.of(Stage.CLEANING, Stage.STORED));
        EVENT_FROM.put(Stage.PLANTING, EnumSet.of(Stage.STORED));
        MERGE_FROM.put(Stage.CLEANING, EnumSet.of(Stage.COLLECTING));
        MERGE_FROM.put(Stage.STORED, EnumSet.of(Stage.CLEANING, Stage.STORED));
    }

    static final String SOWN = "sown";

    private StageRules() {
    }

    /** Whether a single-batch event can be recorded at this stage. */
    static boolean takesEvent(Stage stage) {
        return EVENT_FROM.containsKey(stage);
    }

    /** Whether batches can be combined at this stage. */
    static boolean takesMerge(Stage stage) {
        return MERGE_FROM.containsKey(stage);
    }

    static boolean closes(Stage stage) {
        return CLOSES.contains(stage);
    }

    /** Collecting: a new batch, under a label not already recorded. */
    static String refuseCollect(Ledger ledger, String id) {
        Ledger.Batch b = ledger.batch(id);
        if (b != null)
            return id + " is already recorded on this phone (" + b.describe()
                    + "). A new collection batch needs a new label.";
        return null;
    }

    /** A single-batch event (Stored or Planting) on a recorded batch. */
    static String refuseEvent(Ledger ledger, Stage stage, String id) {
        if (!takesEvent(stage))
            throw new IllegalArgumentException(stage.label + " takes no single-batch event");
        Ledger.Batch b = ledger.batch(id);
        if (b == null)
            return id + " is not recorded on this phone. A batch is recorded first at Collecting, "
                    + "and each phone keeps its own records.";
        if (!b.open)
            return id + " is closed (" + b.closedBy + "), so it can take no more events.";
        Set<Stage> from = EVENT_FROM.get(stage);
        if (!from.contains(b.stage))
            return id + " is at " + b.stage.label + ". " + stage.label + " needs a batch at "
                    + names(from) + ".";
        return null;
    }

    /**
     * One more parent for a merge at this stage, given those already
     * scanned: recorded on this phone, open, at an allowed stage, and not
     * scanned twice.
     */
    static String refuseParent(Ledger ledger, Stage stage, List<String> scanned, String id) {
        if (!takesMerge(stage))
            throw new IllegalArgumentException("batches are not combined at " + stage.label);
        if (scanned.contains(id))
            return id + " has already been scanned for this combination.";
        Ledger.Batch b = ledger.batch(id);
        if (b == null)
            return id + " is not recorded on this phone, so it cannot be combined here. "
                    + "Each phone keeps its own records.";
        if (!b.open)
            return id + " is closed (" + b.closedBy + "), so it cannot be combined again.";
        Set<Stage> from = MERGE_FROM.get(stage);
        if (!from.contains(b.stage))
            return id + " is at " + b.stage.label + ". Only batches at " + names(from)
                    + " can be combined at " + stage.label + ".";
        return null;
    }

    /**
     * The new batch's label (the user's rule): not one of its parents, and
     * not any batch the ledger holds, open or closed.
     */
    static String refuseNewBatch(Ledger ledger, List<String> parents, String id) {
        if (parents.contains(id))
            return id + " is one of the batches being combined. Scan the new batch's label.";
        Ledger.Batch b = ledger.batch(id);
        if (b != null)
            return id + " is already recorded on this phone (" + b.describe()
                    + "). The combined batch needs a new label.";
        return null;
    }

    /** A whole merge: at least one parent, each allowed, and a new label. */
    static String refuseMerge(Ledger ledger, Stage stage, List<String> parents, String id) {
        if (parents.isEmpty())
            return "Scan at least one batch to combine.";
        for (int i = 0; i < parents.size(); i++) {
            String why = refuseParent(ledger, stage, parents.subList(0, i), parents.get(i));
            if (why != null)
                return why;
        }
        return refuseNewBatch(ledger, parents, id);
    }

    private static String names(Set<Stage> stages) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Stage s : stages) {
            if (i > 0)
                sb.append(i == stages.size() - 1 ? " or " : ", ");
            sb.append(s.label);
            i++;
        }
        return sb.toString();
    }
}

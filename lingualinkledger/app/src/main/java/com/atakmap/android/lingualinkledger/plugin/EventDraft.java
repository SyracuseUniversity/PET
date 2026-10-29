package com.atakmap.android.lingualinkledger.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One event before it is saved: its kind, stage and batch, and the
 * captures it is built from. A merge's captures are one per parent, in
 * the order scanned, then the new batch's label; it becomes one package,
 * one hash and one event (V1.7, A9's app side).
 */
final class EventDraft {

    final String kind;
    final Stage stage;
    /** The batch created or acted on; for a merge, the new batch. */
    final String batchId;
    /** A merge's parents, in the order scanned; empty otherwise. */
    final List<String> parentIds;
    final List<Capture> parentCaptures;
    /** The capture of the event's own batch label: the event's time and position. */
    final Capture batchCapture;

    private EventDraft(String kind, Stage stage, List<Capture> parents, Capture batch) {
        this.kind = kind;
        this.stage = stage;
        this.batchId = batch.batchId;
        this.parentCaptures = Collections.unmodifiableList(new ArrayList<>(parents));
        List<String> ids = new ArrayList<>();
        for (Capture c : parents)
            ids.add(c.batchId);
        this.parentIds = Collections.unmodifiableList(ids);
        this.batchCapture = batch;
    }

    /** Collecting: a new collection batch. */
    static EventDraft collect(Capture batch) {
        return new EventDraft(Ledger.KIND_COLLECT, Stage.COLLECTING, Collections.<Capture>emptyList(), batch);
    }

    /** Stored or Planting, on one batch. */
    static EventDraft stageEvent(Stage stage, Capture batch) {
        if (!StageRules.takesEvent(stage))
            throw new IllegalArgumentException(stage.label + " takes no single-batch event");
        return new EventDraft(Ledger.KIND_EVENT, stage, Collections.<Capture>emptyList(), batch);
    }

    /** Cleaning, or Combine batches at Stored: the parents, then the new batch's label. */
    static EventDraft merge(Stage stage, List<Capture> parents, Capture newBatch) {
        if (!StageRules.takesMerge(stage))
            throw new IllegalArgumentException("batches are not combined at " + stage.label);
        return new EventDraft(Ledger.KIND_MERGE, stage, parents, newBatch);
    }

    /** Every capture, parents first, then the event's own batch. */
    List<Capture> captures() {
        List<Capture> all = new ArrayList<>(parentCaptures);
        all.add(batchCapture);
        return all;
    }
}

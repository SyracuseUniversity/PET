package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The stage table (S7) and the merge rules (S8, and the user's rule that a
 * merge's new label is new), on the test set's scenario, version 1.1
 * (docs\test-qr\README.txt): what it records, and each refusal it lists.
 */
public class StageRulesTest {

    private Ledger ledger = Ledger.empty();

    @Test
    public void theScenarioRecords() throws Exception {
        // 1-2. three collection batches, cleaned together into 0004
        collect("SAR-TEST-0001");
        collect("SAR-TEST-0002");
        collect("SAR-TEST-0003");
        merge(Stage.CLEANING, "SAR-TEST-0004", "SAR-TEST-0001", "SAR-TEST-0002", "SAR-TEST-0003");
        for (String p : new String[] {"SAR-TEST-0001", "SAR-TEST-0002", "SAR-TEST-0003"}) {
            Ledger.Batch b = ledger.batch(p);
            assertFalse(p + " closed", b.open);
            assertEquals("combined into SAR-TEST-0004", b.closedBy);
            assertEquals("SAR-TEST-0004", b.combinedInto);
        }
        Ledger.Batch four = ledger.batch("SAR-TEST-0004");
        assertTrue(four.open);
        assertEquals(Stage.CLEANING, four.stage);
        assertEquals(Arrays.asList("SAR-TEST-0001", "SAR-TEST-0002", "SAR-TEST-0003"), four.combinedFrom);

        // 3-4. 0005 collected, then cleaned alone into a new label, 0013
        collect("SAR-TEST-0005");
        merge(Stage.CLEANING, "SAR-TEST-0013", "SAR-TEST-0005");
        assertEquals("combined into SAR-TEST-0013", ledger.batch("SAR-TEST-0005").closedBy);

        // 5. a storage event on 0004; then 0004 and 0013 combined at Stored into 0006
        event(Stage.STORED, "SAR-TEST-0004");
        assertEquals(Stage.STORED, ledger.batch("SAR-TEST-0004").stage);
        merge(Stage.STORED, "SAR-TEST-0006", "SAR-TEST-0004", "SAR-TEST-0013");
        assertEquals(Stage.STORED, ledger.batch("SAR-TEST-0006").stage);

        // 6. planted: the batch closes, sown
        event(Stage.PLANTING, "SAR-TEST-0006");
        Ledger.Batch six = ledger.batch("SAR-TEST-0006");
        assertFalse(six.open);
        assertEquals("sown", six.closedBy);

        // 3 collections, 0004's merge, 0005's collection and cleaning, a storage event,
        // the combination into 0006, its planting
        assertEquals(9, ledger.eventCount());
        // the merge that closed a parent is in that parent's history too
        assertEquals(2, ledger.batch("SAR-TEST-0001").events.size());
    }

    // --- the scenario's "must be refused" list -------------------------

    @Test
    public void mergingABatchAgainIsRefused() throws Exception {
        collect("SAR-TEST-0001");
        collect("SAR-TEST-0002");
        merge(Stage.CLEANING, "SAR-TEST-0004", "SAR-TEST-0001", "SAR-TEST-0002");
        collect("SAR-TEST-0003");
        assertEquals("SAR-TEST-0001 is closed (combined into SAR-TEST-0004), so it cannot be combined again.",
                StageRules.refuseMerge(ledger, Stage.CLEANING, list("SAR-TEST-0003", "SAR-TEST-0001"),
                        "SAR-TEST-0007"));
    }

    @Test
    public void theSameParentTwiceIsRefused() throws Exception {
        collect("SAR-TEST-0001");
        assertEquals("SAR-TEST-0001 has already been scanned for this combination.",
                StageRules.refuseParent(ledger, Stage.CLEANING, list("SAR-TEST-0001"), "SAR-TEST-0001"));
        assertEquals("SAR-TEST-0001 has already been scanned for this combination.",
                StageRules.refuseMerge(ledger, Stage.CLEANING, list("SAR-TEST-0001", "SAR-TEST-0001"),
                        "SAR-TEST-0004"));
    }

    @Test
    public void storedBeforeCleaningIsRefused() throws Exception {
        collect("SAR-TEST-0007");
        assertEquals("SAR-TEST-0007 is at Collecting. Stored needs a batch at Cleaning or Stored.",
                StageRules.refuseEvent(ledger, Stage.STORED, "SAR-TEST-0007"));
    }

    @Test
    public void combiningAnUncleanedBatchAtStoredIsRefused() throws Exception {
        collect("SAR-TEST-0001");
        merge(Stage.CLEANING, "SAR-TEST-0004", "SAR-TEST-0001");
        collect("SAR-TEST-0005");
        assertEquals("SAR-TEST-0005 is at Collecting. Only batches at Cleaning or Stored can be combined at Stored.",
                StageRules.refuseMerge(ledger, Stage.STORED, list("SAR-TEST-0004", "SAR-TEST-0005"),
                        "SAR-TEST-0006"));
    }

    // --- the new batch's label must be new (the user's rule) -----------

    @Test
    public void theNewLabelCannotBeOneOfItsParents() throws Exception {
        collect("SAR-TEST-0001");
        collect("SAR-TEST-0002");
        assertEquals("SAR-TEST-0002 is one of the batches being combined. Scan the new batch's label.",
                StageRules.refuseMerge(ledger, Stage.CLEANING, list("SAR-TEST-0001", "SAR-TEST-0002"),
                        "SAR-TEST-0002"));
    }

    @Test
    public void theNewLabelCannotBeAnyBatchInTheLedger() throws Exception {
        collect("SAR-TEST-0001");
        collect("SAR-TEST-0002");
        collect("SAR-TEST-0003");
        // an open batch that is not a parent
        assertEquals("SAR-TEST-0003 is already recorded on this phone (at Collecting, open). "
                        + "The combined batch needs a new label.",
                StageRules.refuseMerge(ledger, Stage.CLEANING, list("SAR-TEST-0001"), "SAR-TEST-0003"));
        // a closed batch
        merge(Stage.CLEANING, "SAR-TEST-0004", "SAR-TEST-0001");
        assertEquals("SAR-TEST-0001 is already recorded on this phone (combined into SAR-TEST-0004). "
                        + "The combined batch needs a new label.",
                StageRules.refuseMerge(ledger, Stage.CLEANING, list("SAR-TEST-0002"), "SAR-TEST-0001"));
        // a batch made by a merge
        assertEquals("SAR-TEST-0004 is already recorded on this phone (at Cleaning, open). "
                        + "The combined batch needs a new label.",
                StageRules.refuseMerge(ledger, Stage.CLEANING, list("SAR-TEST-0002"), "SAR-TEST-0004"));
    }

    @Test
    public void collectingNeedsANewLabelToo() throws Exception {
        collect("SAR-TEST-0001");
        assertEquals("SAR-TEST-0001 is already recorded on this phone (at Collecting, open). "
                + "A new collection batch needs a new label.", StageRules.refuseCollect(ledger, "SAR-TEST-0001"));
    }

    // --- the rest of the table -----------------------------------------

    @Test
    public void plantingNeedsABatchInStorage() throws Exception {
        collect("SAR-TEST-0001");
        merge(Stage.CLEANING, "SAR-TEST-0004", "SAR-TEST-0001");
        assertEquals("SAR-TEST-0004 is at Cleaning. Planting needs a batch at Stored.",
                StageRules.refuseEvent(ledger, Stage.PLANTING, "SAR-TEST-0004"));
        event(Stage.STORED, "SAR-TEST-0004");
        event(Stage.STORED, "SAR-TEST-0004"); // a second storage event is allowed
        event(Stage.PLANTING, "SAR-TEST-0004");
        assertEquals("SAR-TEST-0004 is closed (sown), so it can take no more events.",
                StageRules.refuseEvent(ledger, Stage.STORED, "SAR-TEST-0004"));
    }

    @Test
    public void aBatchNotOnThisPhoneIsRefused() {
        assertEquals("SAR-TEST-0009 is not recorded on this phone. A batch is recorded first at Collecting, "
                + "and each phone keeps its own records.", StageRules.refuseEvent(ledger, Stage.STORED, "SAR-TEST-0009"));
        assertEquals("SAR-TEST-0009 is not recorded on this phone, so it cannot be combined here. "
                        + "Each phone keeps its own records.",
                StageRules.refuseParent(ledger, Stage.CLEANING, Collections.<String>emptyList(), "SAR-TEST-0009"));
    }

    @Test
    public void aMergeNeedsAParent() {
        assertEquals("Scan at least one batch to combine.",
                StageRules.refuseMerge(ledger, Stage.CLEANING, Collections.<String>emptyList(), "SAR-TEST-0004"));
    }

    @Test
    public void onlyTheTableStagesTakeEventsAndMerges() {
        assertFalse(StageRules.takesEvent(Stage.COLLECTING));
        assertFalse(StageRules.takesEvent(Stage.CLEANING)); // cleaning makes a new label: a merge
        assertTrue(StageRules.takesEvent(Stage.STORED));
        assertTrue(StageRules.takesEvent(Stage.PLANTING));
        assertTrue(StageRules.takesMerge(Stage.CLEANING));
        assertTrue(StageRules.takesMerge(Stage.STORED));
        assertFalse(StageRules.takesMerge(Stage.PLANTING));
    }

    // --- a saved ledger is checked again when it is read ---------------

    @Test
    public void aLedgerThatBreaksTheRulesDoesNotLoad() throws Exception {
        collect("SAR-TEST-0001");
        merge(Stage.CLEANING, "SAR-TEST-0004", "SAR-TEST-0001");
        // a copy of the same events, the merge moved before the collection
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) Json.parse(Json.write(ledger.toJson()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> swapped = (List<Map<String, Object>>) json.get("events");
        Collections.reverse(swapped);
        swapped.get(0).put("seq", 1L);
        swapped.get(1).put("seq", 2L);
        try {
            Ledger.fromJson(Json.parse(Json.write(json)));
            fail("loaded a merge of a batch not yet collected");
        } catch (Ledger.Refused e) {
            assertEquals("Saved event 1 breaks a rule: SAR-TEST-0001 is not recorded on this phone, so it "
                    + "cannot be combined here. Each phone keeps its own records.", e.getMessage());
        }
    }

    @Test
    public void eventsMustBeNumberedInOrder() throws Exception {
        collect("SAR-TEST-0001");
        Map<String, Object> next = record(Ledger.KIND_COLLECT, Stage.COLLECTING, "SAR-TEST-0002");
        next.put("seq", 5L);
        try {
            ledger.with(next);
            fail("took an event numbered 5 as the second");
        } catch (Ledger.Refused e) {
            assertEquals("Saved event 2 is numbered 5", e.getMessage());
        }
    }

    @Test
    public void aSavedLedgerReadsBackTheSame() throws Exception {
        collect("SAR-TEST-0001");
        collect("SAR-TEST-0002");
        merge(Stage.CLEANING, "SAR-TEST-0004", "SAR-TEST-0001", "SAR-TEST-0002");
        Ledger back = Ledger.fromJson(Json.parse(Json.write(ledger.toJson())));
        assertEquals(Json.write(ledger.toJson()), Json.write(back.toJson()));
        assertEquals("combined into SAR-TEST-0004", back.batch("SAR-TEST-0002").closedBy);
    }

    // --- helpers: each step checked by the rules, then added ------------

    private void collect(String id) throws Ledger.Refused {
        assertNull(StageRules.refuseCollect(ledger, id));
        ledger = ledger.with(record(Ledger.KIND_COLLECT, Stage.COLLECTING, id));
    }

    private void event(Stage stage, String id) throws Ledger.Refused {
        assertNull(StageRules.refuseEvent(ledger, stage, id));
        ledger = ledger.with(record(Ledger.KIND_EVENT, stage, id));
    }

    private void merge(Stage stage, String child, String... parents) throws Ledger.Refused {
        assertNull(StageRules.refuseMerge(ledger, stage, list(parents), child));
        Map<String, Object> r = record(Ledger.KIND_MERGE, stage, child);
        r.put("parent_ids", new ArrayList<Object>(Arrays.asList(parents)));
        ledger = ledger.with(r);
    }

    private Map<String, Object> record(String kind, Stage stage, String id) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("seq", (long) ledger.eventCount() + 1);
        r.put("kind", kind);
        r.put("stage", stage.label);
        r.put("batch_id", id);
        r.put("test_label", true);
        return r;
    }

    private static List<String> list(String... ids) {
        return Arrays.asList(ids);
    }
}

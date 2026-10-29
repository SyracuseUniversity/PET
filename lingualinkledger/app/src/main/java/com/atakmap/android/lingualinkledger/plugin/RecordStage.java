package com.atakmap.android.lingualinkledger.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The stage buttons of the capture flow (V1.7; Day 2 step 7, Day 3 step
 * 11): Collecting; Cleaning's merge; Stored, an event on one batch or
 * "Combine batches" (a merge at Stored); and Planting, an event on one
 * batch that closes it. Every label is captured the same way:
 *
 *   the phone's camera (CameraCapture, in the flow's PhotoSession);
 *   the position when the photo comes back (V1.2), and with no usable
 *     position nothing is saved and the message says what to do (S6);
 *   the label read from the photo itself (R2; PhotoLabelReader). With no
 *     code read: take it again, or, last of all, type the ID (S3); two
 *     codes are refused (S4); a refused label says why;
 *   the rules for this step (StageRules), checked as each label is
 *     scanned, so the user is stopped before going further.
 *
 * Collecting, Stored's one batch and Planting save one event. Cleaning
 * and Combine batches scan each batch being combined, then the new
 * batch's label, which must be new (the user's rule 3), and save one
 * merge. Nothing is saved until the user confirms; saving is
 * off the main thread (LedgerStore). The flow's photos are deleted when
 * it ends, saved or cancelled (T4). Every dialog is a PetDialogs one
 * (D46); nothing thrown here can close ATAK (PetThreads).
 */
final class RecordStage {

    private static final String TAG = "LinguaLinkLedger.Record";

    private final Context pluginContext;

    RecordStage(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    void collecting() {
        Flow f = Flow.open(this, Stage.COLLECTING);
        if (f != null)
            f.scan("Photograph the new collection batch's label.",
                    (ledger, id) -> StageRules.refuseCollect(ledger, id), f::confirmCollect);
    }

    void cleaning() {
        Flow f = Flow.open(this, Stage.CLEANING);
        if (f != null)
            f.mergeIntro();
    }

    /** Stored: a storage event on one batch, or "Combine batches" at Stored (Day 3 step 11). */
    void stored() {
        dialog(Stage.STORED.label, "Record storage of one batch, or combine batches in storage into a new "
                + "batch with a new label?")
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Combine batches", (d, w) -> {
                    Flow f = Flow.open(this, Stage.STORED);
                    if (f != null)
                        f.mergeIntro();
                })
                .setPositiveButton("One batch", (d, w) -> {
                    Flow f = Flow.open(this, Stage.STORED);
                    if (f != null)
                        f.scan("Photograph the label of the batch being stored.",
                                (ledger, id) -> StageRules.refuseEvent(ledger, Stage.STORED, id), f::confirmEvent);
                })
                .show();
    }

    /** Planting: sowing one batch, which then closes (Day 3 step 11). */
    void planting() {
        Flow f = Flow.open(this, Stage.PLANTING);
        if (f != null)
            f.scan("Photograph the label of the batch being planted.",
                    (ledger, id) -> StageRules.refuseEvent(ledger, Stage.PLANTING, id), f::confirmEvent);
    }

    /** A rule for one scan: null if the batch ID is allowed, else why not. */
    private interface Rule {
        String refuse(Ledger ledger, String id);
    }

    /** What to do with an accepted capture. */
    private interface Next {
        void with(Capture c);
    }

    /** One run of a stage button, from the first photo to the save or cancel. */
    private static final class Flow {
        final RecordStage ui;
        final Stage stage;
        final PhotoSession session;
        final List<Capture> parents = new ArrayList<>();

        private Flow(RecordStage ui, Stage stage, PhotoSession session) {
            this.ui = ui;
            this.stage = stage;
            this.session = session;
        }

        static Flow open(RecordStage ui, Stage stage) {
            try {
                return new Flow(ui, stage, PhotoSession.begin(CameraCapture.photoRoot()));
            } catch (IOException e) {
                Log.w(TAG, "no folder for the photos", e);
                ui.message(stage.label, "The phone could not make a folder for the photos. Check that it "
                        + "has free storage, then try again.");
                return null;
            }
        }

        String title() {
            return stage.label;
        }

        void cancel() {
            Log.d(TAG, stage.label + ": cancelled, nothing saved");
            session.end();
        }

        // --- one scan ----------------------------------------------------

        /** Takes one photo of a label, checks it, and passes it on. */
        void scan(final String prompt, final Rule rule, final Next next) {
            CameraCapture.start(session, new CameraCapture.Callback() {
                @Override
                public void onPhoto(File photo, long returnedAtMs) {
                    final PositionPolicy.Check position = PositionPolicy.check(AtakPosition.read(), returnedAtMs);
                    if (!position.usable()) {
                        // S6: every event needs a position
                        retryOrBack(prompt, rule, next, "Nothing was saved: this phone's position is "
                                + "not known.\n\n" + position.message);
                        return;
                    }
                    read(photo, returnedAtMs, position, prompt, rule, next);
                }

                @Override
                public void onCancelled() {
                    // the camera closed without a photo: quietly back (Day 1, c)
                    back();
                }

                @Override
                public void onProblem(String message) {
                    // back to the merge so far, if any, so its scans are not lost
                    ui.dialog(title(), message)
                            .setPositiveButton("OK", (d, w) -> back())
                            .setOnCancelListener(d -> back())
                            .show();
                }
            });
        }

        private void read(final File photo, final long at, final PositionPolicy.Check position,
                final String prompt, final Rule rule, final Next next) {
            final AlertDialog wait = ui.waiting(title(), "Reading the label...");
            PetThreads.background("read label", () -> {
                PhotoLabelReader.Result r = null;
                String refusal = null;
                Ledger ledger = null;
                String ledgerProblem = null;
                try {
                    ledger = PetFolders.ledger().ledger(); // first: a typed ID is checked against it too
                } catch (IOException e) {
                    ledgerProblem = e.getMessage();
                }
                if (ledger != null) {
                    try {
                        PhotoPixels pixels = new PhotoPixels(photo);
                        int[] size = pixels.size();
                        r = PhotoLabelReader.read(size[0], size[1], pixels);
                        Log.d(TAG, stage.label + ": photo " + size[0] + " x " + size[1] + ", sample sizes "
                                + r.decoded + ", " + (r.readable() ? r.scan.outcome : "unreadable"));
                        if (r.readable() && r.scan.label() != null)
                            refusal = rule.refuse(ledger, r.scan.label().batchId);
                    } catch (Throwable t) {
                        Log.e(TAG, "reading the label failed", t);
                        r = null; // shown as a photo that could not be read
                    }
                }
                final PhotoLabelReader.Result result = r;
                final String refused = refusal, problem = ledgerProblem;
                final Ledger checkedAgainst = ledger;
                PetThreads.onMain(() -> {
                    wait.dismiss();
                    if (problem != null) {
                        ui.message(title(), problem);
                        cancel();
                    } else if (result == null || !result.readable()) {
                        noCode(photo, at, position, prompt, rule, next, checkedAgainst,
                                "This photo could not be read.");
                    } else if (result.scan.label() != null) {
                        LabelReader.Label label = result.scan.label();
                        if (refused != null)
                            retryOrBack(prompt, rule, next, refused);
                        else
                            next.with(Capture.fromLabel(photo, label, at, position));
                    } else if (result.scan.outcome == LabelScan.Outcome.NO_CODE) {
                        noCode(photo, at, position, prompt, rule, next, checkedAgainst, result.scan.problem());
                    } else {
                        String why = result.scan.problem();
                        if (result.scan.outcome == LabelScan.Outcome.REFUSED_LABEL)
                            why += "\n(reason: " + result.scan.reading.refusal.code + ")";
                        retryOrBack(prompt, rule, next, why);
                    }
                });
            });
        }

        /** No code read: take it again, or type the ID (S3), or go back. */
        private void noCode(final File photo, final long at, final PositionPolicy.Check position,
                final String prompt, final Rule rule, final Next next, final Ledger ledger, String why) {
            ui.dialog(title(), why + "\n\n" + prompt + " Take the photo again, or, if the code cannot be "
                    + "read, type the batch ID printed on the label.")
                    .setNegativeButton("Back", (d, w) -> back())
                    .setNeutralButton("Type the ID", (d, w) -> type(photo, at, position, prompt, rule, next, ledger, null))
                    .setPositiveButton("Take again", (d, w) -> scan(prompt, rule, next))
                    .setOnCancelListener(d -> back())
                    .show();
        }

        /** The typed fallback: the photo stays the evidence, and the record says the ID was typed. */
        private void type(final File photo, final long at, final PositionPolicy.Check position,
                final String prompt, final Rule rule, final Next next, final Ledger ledger, String error) {
            LinearLayout box = new LinearLayout(ui.pluginContext);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = PetDialogs.dp(ui.pluginContext, 16);
            box.setPadding(pad, pad, pad, 0);
            TextView hint = new TextView(ui.pluginContext);
            hint.setText((error == null ? "" : error + "\n\n") + "Type the batch ID exactly as printed on the "
                    + "label, for example SAR-TEST-0001. The record will say it was typed, not read, and "
                    + "that whether it is a test label is unknown.");
            final EditText field = new EditText(ui.pluginContext);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setSingleLine(true);
            box.addView(hint);
            box.addView(field);
            PetDialogs.builder(ui.pluginContext, title(), box)
                    .setNegativeButton("Back", (d, w) -> back())
                    .setPositiveButton("Use this ID", (d, w) -> {
                        // the same ID rule as a scanned label (the user, Day 2 step 8)
                        Capture typed = Capture.typed(photo, field.getText().toString(), at, position);
                        if (typed == null) {
                            type(photo, at, position, prompt, rule, next, ledger, "That is not a valid batch ID: "
                                    + "4 to 40 letters, digits and '-', starting and ending with a letter or digit.");
                            return;
                        }
                        String refused = rule.refuse(ledger, typed.batchId);
                        if (refused != null) {
                            retryOrBack(prompt, rule, next, refused);
                            return;
                        }
                        next.with(typed);
                    })
                    .setOnCancelListener(d -> back())
                    .show();
        }

        private void retryOrBack(final String prompt, final Rule rule, final Next next, String why) {
            ui.dialog(title(), why + "\n\n" + prompt)
                    .setNegativeButton("Back", (d, w) -> back())
                    .setPositiveButton("Take again", (d, w) -> scan(prompt, rule, next))
                    .setOnCancelListener(d -> back())
                    .show();
        }

        /** Back from a scan: to the merge so far, or out of the flow if nothing is held. */
        void back() {
            if (parents.isEmpty())
                cancel();
            else
                mergeSoFar();
        }

        // --- Collecting --------------------------------------------------

        void confirmCollect(final Capture c) {
            ui.dialog(title(), "Record a new collection batch?\n\n" + describe(c)
                    + "\n\nThis saves a package of the photo and its record on this phone.")
                    .setNegativeButton("Cancel", (d, w) -> cancel())
                    .setPositiveButton("Save", (d, w) -> save(EventDraft.collect(c)))
                    .setOnCancelListener(d -> cancel())
                    .show();
        }

        // --- Stored or Planting: an event on one batch ----------------------

        void confirmEvent(final Capture c) {
            ui.dialog(title(), "Record " + stage.label + " for this batch?\n\n" + describe(c)
                    + (StageRules.closes(stage) ? "\n\n" + stage.label + " closes the batch: it can take no "
                    + "more events." : "")
                    + "\n\nThis saves a package of the photo and its record on this phone.")
                    .setNegativeButton("Cancel", (d, w) -> cancel())
                    .setPositiveButton("Save", (d, w) -> save(EventDraft.stageEvent(stage, c)))
                    .setOnCancelListener(d -> cancel())
                    .show();
        }

        // --- Cleaning, or Combine batches at Stored: a merge ---------------

        void mergeIntro() {
            String intro = stage == Stage.CLEANING
                    ? "Cleaning combines one or more collection batches into a new batch with a new label. "
                    + "The batches combined are closed.\n\nFirst photograph the label of each batch being "
                    + "cleaned, one at a time. Then photograph the new batch's label."
                    : "Combine batches puts one or more batches in storage (at Cleaning or Stored) into a new "
                    + "batch with a new label, at Stored. The batches combined are closed.\n\nFirst photograph "
                    + "the label of each batch being combined, one at a time. Then photograph the new batch's "
                    + "label.";
            ui.dialog(title(), intro)
                    .setNegativeButton("Cancel", (d, w) -> cancel())
                    .setPositiveButton("Photograph a batch", (d, w) -> scanParent())
                    .setOnCancelListener(d -> cancel())
                    .show();
        }

        void scanParent() {
            scan("Photograph a batch being " + verb() + ".",
                    (ledger, id) -> StageRules.refuseParent(ledger, stage, ids(), id),
                    c -> {
                        parents.add(c);
                        mergeSoFar();
                    });
        }

        void mergeSoFar() {
            StringBuilder sb = new StringBuilder("Batches to combine (" + parents.size() + "):");
            for (Capture c : parents)
                sb.append("\n  ").append(c.batchId).append(testMark(c.testLabel));
            sb.append("\n\nAdd another batch, or photograph the new batch's label.");
            ui.dialog(title(), sb)
                    .setNegativeButton("Cancel", (d, w) -> cancel())
                    .setNeutralButton("Add a batch", (d, w) -> scanParent())
                    .setPositiveButton("New label", (d, w) -> scanChild())
                    .setOnCancelListener(d -> cancel())
                    .show();
        }

        void scanChild() {
            scan("Photograph the new batch's label.",
                    (ledger, id) -> StageRules.refuseNewBatch(ledger, ids(), id), this::confirmMerge);
        }

        void confirmMerge(final Capture child) {
            StringBuilder sb = new StringBuilder("Combine at " + stage.label + "?\n\n");
            for (Capture c : parents)
                sb.append(c.batchId).append('\n');
            sb.append("into the new batch\n").append(describe(child))
                    .append("\n\nThe ").append(parents.size() == 1 ? "batch" : parents.size() + " batches")
                    .append(" combined will close. This saves one package of all ")
                    .append(parents.size() + 1).append(" photos and its record on this phone.");
            ui.dialog(title(), sb)
                    .setNegativeButton("Cancel", (d, w) -> cancel())
                    .setNeutralButton("Retake new label", (d, w) -> scanChild())
                    .setPositiveButton("Save", (d, w) -> save(EventDraft.merge(stage, parents, child)))
                    .setOnCancelListener(d -> cancel())
                    .show();
        }

        String verb() {
            return stage == Stage.CLEANING ? "cleaned" : "combined";
        }

        List<String> ids() {
            List<String> ids = new ArrayList<>();
            for (Capture c : parents)
                ids.add(c.batchId);
            return ids;
        }

        // --- saving ------------------------------------------------------

        void save(final EventDraft draft) {
            final AlertDialog wait = ui.waiting(title(), "Saving on this phone...");
            PetThreads.background("save " + stage.label, () -> {
                Map<String, Object> saved = null;
                String failed = null;
                try {
                    saved = PetFolders.ledger().record(draft, BuildConfig.VERSION_NAME, System.currentTimeMillis());
                } catch (Ledger.Refused e) {
                    failed = "Nothing was saved. " + e.getMessage();
                } catch (IOException e) {
                    Log.w(TAG, "save failed: " + e.getMessage()); // PL-14: one line
                    failed = "The event could not be saved on this phone (" + e.getMessage()
                            + "). Check that the phone has free storage, then try again.";
                } catch (Throwable t) {
                    Log.e(TAG, "save failed", t);
                    failed = "The event could not be saved on this phone (" + t + ").";
                }
                final Map<String, Object> record = saved;
                final String problem = failed;
                PetThreads.onMain(() -> {
                    wait.dismiss();
                    if (record != null) {
                        session.end(); // the photos are in the package now
                        ui.message(title(), savedText(record));
                    } else {
                        ui.dialog(title(), problem)
                                .setNegativeButton("Cancel", (d, w) -> cancel())
                                .setPositiveButton("Try again", (d, w) -> save(draft))
                                .setOnCancelListener(d -> cancel())
                                .show();
                    }
                });
            });
        }

        @SuppressWarnings("unchecked")
        String savedText(Map<String, Object> r) {
            StringBuilder sb = new StringBuilder("Saved on this phone. It will be sent when a gateway is "
                    + "connected.\n\n");
            sb.append("Batch: ").append(r.get("batch_id"));
            Object test = r.get("test_label");
            sb.append(testMark(test instanceof Boolean ? (Boolean) test : null));
            sb.append("\nStage: ").append(r.get("stage"));
            Object parents = r.get("parent_ids");
            if (parents instanceof List)
                sb.append("\nCombined from: ").append(join((List<Object>) parents));
            Map<String, Object> pos = (Map<String, Object>) r.get("position");
            sb.append("\nPosition: ").append(pos.get("source_label")).append(", ")
                    .append(pos.get("latitude")).append(", ").append(pos.get("longitude"));
            sb.append("\nEvent ").append(r.get("seq")).append(" on this phone, ").append(r.get("captured_utc"));
            sb.append("\n\nPackage: ").append(r.get("package_file"));
            sb.append("\nSHA-256: ").append(r.get("package_sha256"));
            return sb.toString();
        }

        String describe(Capture c) {
            return "Batch: " + c.batchId + testMark(c.testLabel)
                    + (c.idRead == Capture.IdRead.TYPED ? "\nID typed, not read from the label"
                    : "\nSpecies, as printed (not checked): " + (c.species == null ? "not on the label" : c.species))
                    + "\nPosition: " + c.position.summary()
                    + (c.position.verdict == PositionPolicy.Verdict.OK_WITH_WARNING ? "\n" + c.position.message : "");
        }

        /** TEST LABEL, nothing for a real label, or unknown when the ID was typed. */
        static String testMark(Boolean test) {
            if (test == null)
                return "  (test status unknown: ID typed)";
            return test ? "  (TEST LABEL)" : "";
        }

        private static String join(List<Object> items) {
            StringBuilder sb = new StringBuilder();
            for (Object o : items) {
                if (sb.length() > 0)
                    sb.append(", ");
                sb.append(o);
            }
            return sb.toString();
        }
    }

    // --- dialogs ----------------------------------------------------------

    private AlertDialog.Builder dialog(String title, CharSequence text) {
        return PetDialogs.builder(pluginContext, title, PetDialogs.text(pluginContext, text));
    }

    private void message(String title, CharSequence text) {
        PetDialogs.showMessage(pluginContext, title, text);
    }

    private AlertDialog waiting(String title, String text) {
        return dialog(title, text).setCancelable(false).show();
    }
}

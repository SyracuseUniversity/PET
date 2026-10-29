package com.atakmap.android.lingualinkledger.plugin;

import android.app.AlertDialog;
import android.content.Context;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.IOException;

/**
 * Look up batch (T1, as answered under D54): photographs one batch label
 * and RECORDS NOTHING. It shows the batch ID read from the label's QR
 * code, TEST LABEL for a simulated label, the species as printed (not
 * checked), and where the phone is, by V1.2's rules, at the moment the
 * photo came back from the camera.
 *
 * The photo is read in the background, smallest copy first
 * (PhotoLabelReader), and deleted once read (the user's requirement b).
 * It also shows what this phone has recorded for the batch, and its next
 * stage from the stage table (BatchView; Day 3 step 13, D59), or that it
 * is not recorded yet and starts at Collecting.
 */
final class LookUpBatch {

    private static final String TAG = "LinguaLinkLedger.LookUp";
    private static final String TITLE = "Look up batch";

    private final Context pluginContext;

    LookUpBatch(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    void start() {
        final PhotoSession session;
        try {
            session = PhotoSession.begin(CameraCapture.photoRoot());
        } catch (IOException e) {
            Log.w(TAG, "no folder for the photo", e);
            PetDialogs.showMessage(pluginContext, TITLE, "The phone could not make a folder for the photo. "
                    + "Check that it has free storage, then try again.");
            return;
        }
        CameraCapture.start(session, new CameraCapture.Callback() {
            @Override
            public void onPhoto(File photo, long returnedAtMs) {
                // the position at the capture time (V1.2; D51)
                PositionPolicy.Check position = PositionPolicy.check(AtakPosition.read(), returnedAtMs);
                read(session, photo, position);
            }

            @Override
            public void onCancelled() {
                // the user closed the camera: return quietly (requirement c)
                Log.d(TAG, "camera closed without a photo");
                session.end();
            }

            @Override
            public void onProblem(String message) {
                session.end();
                PetDialogs.showMessage(pluginContext, TITLE, message);
            }
        });
    }

    private void read(final PhotoSession session, final File photo, final PositionPolicy.Check position) {
        final AlertDialog wait = PetDialogs.builder(pluginContext, TITLE,
                PetDialogs.text(pluginContext, "Reading the label..."))
                .setCancelable(false)
                .show();
        new Thread(() -> {
            PhotoLabelReader.Result result = null;
            long started = System.currentTimeMillis();
            try {
                PhotoPixels pixels = new PhotoPixels(photo);
                int[] size = pixels.size();
                result = PhotoLabelReader.read(size[0], size[1], pixels);
                Log.d(TAG, "photo " + size[0] + " x " + size[1] + ", " + photo.length() + " bytes; sample sizes "
                        + result.decoded + "; " + (result.readable() ? result.scan.outcome : "unreadable")
                        + " in " + (System.currentTimeMillis() - started) + " ms");
            } catch (Throwable t) {
                // anything thrown here would end ATAK's process: report it instead
                Log.e(TAG, "reading the photo failed", t);
            } finally {
                session.end(); // records nothing, so the photo goes (requirement b)
            }
            // what this phone has recorded for the batch, and its next stage (T1, D59)
            String held = null;
            LabelReader.Label read = result != null && result.readable() ? result.scan.label() : null;
            if (read != null) {
                try {
                    Ledger l = PetFolders.ledger().ledger();
                    Ledger.Batch b = l.batch(read.batchId);
                    held = b == null ? BatchView.next(l, read.batchId) : BatchView.detail(l, b);
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "the records could not be read", e);
                    held = "What this phone holds could not be read (" + e.getMessage() + ").";
                }
            }
            final String onPhone = held;
            final PhotoLabelReader.Result done = result;
            MapView mapView = MapView.getMapView();
            if (mapView == null)
                return; // ATAK is closing
            mapView.post(() -> {
                try {
                    wait.dismiss();
                    show(done, position, onPhone);
                } catch (RuntimeException e) {
                    // e.g. ATAK's window closed while the photo was read
                    Log.e(TAG, "could not show the result", e);
                }
            });
        }, "PET look up batch").start();
    }

    private void show(PhotoLabelReader.Result result, PositionPolicy.Check position, String onPhone) {
        StringBuilder sb = new StringBuilder();
        LabelReader.Label label = result != null && result.readable() ? result.scan.label() : null;
        if (label != null) {
            sb.append("Batch ID: ").append(label.batchId).append('\n');
            if (label.test)
                sb.append("TEST LABEL: a simulated label, not a real nursery label\n");
            sb.append("Species, as printed (not checked): ")
                    .append(label.species == null ? "not on the label" : label.species).append('\n');
        } else if (result == null || !result.readable()) {
            sb.append("This photo could not be read. Take it again.\n");
        } else {
            sb.append(result.scan.problem()).append('\n');
            if (result.scan.outcome == LabelScan.Outcome.REFUSED_LABEL)
                sb.append("(reason: ").append(result.scan.reading.refusal.code).append(")\n");
        }

        sb.append("\nPosition: ").append(position.summary());
        if (position.verdict == PositionPolicy.Verdict.OK_WITH_WARNING)
            sb.append("\n").append(position.message);

        if (onPhone != null)
            sb.append("\n\nON THIS PHONE\n").append(onPhone);

        sb.append("\n\nLook up batch records nothing, and the photo has been deleted.");

        PetDialogs.builder(pluginContext, TITLE, PetDialogs.text(pluginContext, sb))
                .setNegativeButton("Close", null)
                .setPositiveButton(label != null ? "Look up another" : "Take again", (d, w) -> start())
                .show();
    }
}

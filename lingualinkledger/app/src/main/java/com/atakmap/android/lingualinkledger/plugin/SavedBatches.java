package com.atakmap.android.lingualinkledger.plugin;

import static com.atakmap.android.maps.MapView.getMapView;

import android.app.AlertDialog;
import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Saved batches (V1.7; Day 3 step 13): every batch this phone has recorded,
 * then the tools' records (T3), in a list; one tapped shows its detail
 * (BatchView), with a way back to the list. Read off the main thread; every
 * dialog against ATAK's activity, scrolling (D46). Records nothing.
 */
final class SavedBatches {

    private static final String TITLE = "Saved batches";

    private final Context pluginContext;

    SavedBatches(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    void show() {
        PetThreads.background("saved batches", () -> {
            Ledger found = null;
            String problem = null;
            try {
                found = PetFolders.ledger().ledger();
            } catch (IOException e) {
                problem = e.getMessage();
            }
            final Ledger ledger = found;
            final String p = problem;
            PetThreads.onMain(() -> {
                if (p != null)
                    PetDialogs.showMessage(pluginContext, TITLE, p);
                else
                    list(ledger);
            });
        });
    }

    private void list(final Ledger ledger) {
        final List<Ledger.Batch> batches = new ArrayList<>(ledger.batches());
        final int tools = ledger.toolRecords().size();
        if (batches.isEmpty() && tools == 0) {
            PetDialogs.showMessage(pluginContext, TITLE, "Nothing is saved on this phone yet. Record a stage "
                    + "with the buttons under RECORD A STAGE, or use the tools.");
            return;
        }
        String[] labels = new String[batches.size() + (tools > 0 ? 1 : 0)];
        for (int i = 0; i < batches.size(); i++)
            labels[i] = BatchView.line(batches.get(i));
        if (tools > 0)
            labels[labels.length - 1] = "Records from the tools (" + tools + ")";
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle(TITLE + " (" + batches.size() + ")")
                .setItems(labels, (d, which) -> {
                    boolean batch = which < batches.size();
                    String title = batch ? batches.get(which).id : "Records from the tools";
                    String text = batch ? BatchView.detail(ledger, batches.get(which)) : BatchView.tools(ledger);
                    PetDialogs.builder(pluginContext, title, PetDialogs.text(pluginContext, text))
                            .setNegativeButton("Close", null)
                            .setPositiveButton("Back to the list", (d2, w2) -> list(ledger))
                            .show();
                })
                .setNegativeButton("Close", null)
                .show();
    }
}

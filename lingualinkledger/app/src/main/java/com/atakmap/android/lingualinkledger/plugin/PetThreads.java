package com.atakmap.android.lingualinkledger.plugin;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

/**
 * Work off ATAK's main thread, and back onto it, without ever ending
 * ATAK's process: the plugin runs inside ATAK, so anything thrown on a
 * thread of its own, or on the main thread from a posted task, would close
 * ATAK. Both are caught and logged here instead.
 */
final class PetThreads {

    private static final String TAG = "LinguaLinkLedger.Threads";

    private PetThreads() {
    }

    /** Runs work on a thread of its own. */
    static void background(String name, Runnable work) {
        new Thread(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                Log.e(TAG, name + " failed", t);
            }
        }, "PET " + name).start();
    }

    /** Runs task on ATAK's main thread, for anything that touches the screen. */
    static void onMain(Runnable task) {
        MapView mapView = MapView.getMapView();
        if (mapView == null)
            return; // ATAK is closing
        mapView.post(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                // e.g. ATAK's window closed meanwhile
                Log.e(TAG, "could not update the screen", e);
            }
        });
    }
}

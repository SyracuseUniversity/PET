package com.atakmap.android.lingualinkledger.plugin;

import java.io.File;
import java.io.IOException;

/**
 * The photos of one flow (Look up batch, or the scans of one event, such
 * as a merge's several labels), kept together in a folder of their own
 * under the camera's temporary folder (CameraCapture.photoRoot()).
 *
 * Leftovers are cleared only when a NEW flow begins: the previous flow's
 * photos, and any left by a flow that never ended (ATAK closed mid-flow).
 * Taking another photo within a flow deletes nothing, so a merge keeps
 * its earlier photos (Day 2, step 7; the sweep in Day 1's CameraCapture
 * would have deleted them). When the flow ends, saved or cancelled, its
 * folder and every photo in it are deleted: nothing stays in a temporary
 * folder (T4; Day 1's requirement b). One flow at a time. Plain Java,
 * tested on the PC.
 */
final class PhotoSession {

    static final String FOLDER_PREFIX = "session_";
    static final String PHOTO_PREFIX = "pet_photo_";

    private static PhotoSession current;

    final File folder;
    private boolean ended;

    private PhotoSession(File folder) {
        this.folder = folder;
    }

    /**
     * Starts a flow's photos in a new folder under root, ending the
     * previous flow and deleting whatever earlier flows left there. Only
     * the plugin's own folders and photos are touched.
     */
    static PhotoSession begin(File root) throws IOException {
        synchronized (PhotoSession.class) {
            if (current != null)
                current.endLocked();
            File[] old = root.listFiles((dir, name) -> name.startsWith(FOLDER_PREFIX)
                    || name.startsWith(PHOTO_PREFIX));
            if (old != null)
                for (File f : old)
                    deleteTree(f);
            File folder = new File(root, FOLDER_PREFIX + System.currentTimeMillis());
            for (int n = 2; folder.exists(); n++)
                folder = new File(root, FOLDER_PREFIX + System.currentTimeMillis() + "_" + n);
            if (!folder.mkdirs())
                throw new IOException("could not make " + folder);
            current = new PhotoSession(folder);
            return current;
        }
    }

    /** A new, empty file for the next photo of this flow. */
    File newPhotoFile() throws IOException {
        synchronized (PhotoSession.class) {
            if (ended)
                throw new IOException("this flow has ended");
            return File.createTempFile(PHOTO_PREFIX, ".jpg", folder);
        }
    }

    /** Ends the flow: its folder and every photo in it are deleted. Safe to call twice. */
    void end() {
        synchronized (PhotoSession.class) {
            endLocked();
        }
    }

    boolean ended() {
        synchronized (PhotoSession.class) {
            return ended;
        }
    }

    /** Ends whichever flow is open, e.g. when the plugin stops. */
    static void endCurrent() {
        synchronized (PhotoSession.class) {
            if (current != null)
                current.endLocked();
        }
    }

    private void endLocked() {
        if (!ended) {
            ended = true;
            deleteTree(folder);
        }
        if (current == this)
            current = null;
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null)
            for (File c : children)
                deleteTree(c);
        f.delete();
    }
}

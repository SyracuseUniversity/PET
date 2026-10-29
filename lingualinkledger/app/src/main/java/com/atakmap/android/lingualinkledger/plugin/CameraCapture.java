package com.atakmap.android.lingualinkledger.plugin;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.util.FileProviderHelper;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.IOException;

/**
 * Takes one photo with the phone's own camera app (S2), started from
 * ATAK's activity the way ATAK's own image capture does
 * (com.atakmap.android.image.ImageActivity, in main.jar):
 * MediaStore.ACTION_IMAGE_CAPTURE, its output file shared through ATAK's
 * FileProviderHelper. ATAKActivity passes the result on as the
 * com.atakmap.android.ACTIVITY_FINISHED broadcast, with the request code,
 * the result code and the data, for every request code it does not handle
 * itself (0, 555, 559, 560 and 90402).
 *
 * The photo goes to a temporary file of the flow's PhotoSession, under
 * ATAK's own cache folder (Android/data/com.atakmap.app.civ/cache/
 * pet-camera/), which other apps cannot read and which ATAK's
 * FileProvider covers; never the plugin's folders (the user's requirement
 * b; D55). The session deletes its photos when the flow ends; leftovers
 * of a flow that never ended go when the next one begins.
 *
 * Closing the camera without a photo calls onCancelled, so the caller can
 * return quietly (requirement c).
 */
final class CameraCapture {

    interface Callback {
        /** A photo, and when it came back from the camera: the capture time. */
        void onPhoto(File photo, long returnedAtMs);

        /** The camera was closed without taking a photo. */
        void onCancelled();

        /** The camera could not be used, in a plain message for the user. */
        void onProblem(String message);
    }

    /** Not one of ATAKActivity's own codes, nor ImageActivity's 1999. */
    static final int REQUEST_CODE = 20549;

    private static final String TAG = "LinguaLinkLedger.Camera";
    private static final String ACTIVITY_FINISHED = "com.atakmap.android.ACTIVITY_FINISHED";
    private static final String FOLDER = "pet-camera";

    /** The capture waiting for the camera, if any, and its file. */
    private static BroadcastReceiver pending;
    private static File pendingFile;

    private CameraCapture() {
    }

    /**
     * Opens the camera for one more photo of the flow's session; the
     * callback is called on the main thread. Deletes no other photo.
     */
    static void start(PhotoSession session, final Callback callback) {
        cancelPending();
        Context atak = MapView.getMapView().getContext();

        final File photo;
        try {
            photo = session.newPhotoFile();
        } catch (IOException e) {
            Log.w(TAG, "no file for the photo", e);
            callback.onProblem("The phone could not make a file for the photo. Check that it has free storage, then try again.");
            return;
        }

        Uri uri = FileProviderHelper.fromFile(atak, photo);
        if (uri == null) {
            delete(photo);
            callback.onProblem("ATAK could not share a file with the camera app, so no photo can be taken.");
            return;
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        pendingFile = photo;
        pending = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent result) {
                if (result.getIntExtra("requestCode", -1) != REQUEST_CODE)
                    return;
                long returnedAt = System.currentTimeMillis();
                stopListening();
                int code = result.getIntExtra("resultCode", Activity.RESULT_CANCELED);
                boolean saved = photo.isFile() && photo.length() > 0;
                Log.d(TAG, "camera returned " + (code == Activity.RESULT_OK ? "OK" : "code " + code)
                        + ", photo " + (saved ? photo.length() + " bytes" : "not saved"));
                if (code == Activity.RESULT_OK && saved) {
                    callback.onPhoto(photo, returnedAt);
                } else {
                    delete(photo);
                    if (code == Activity.RESULT_OK)
                        callback.onProblem("The camera app did not save a photo. Try again.");
                    else
                        callback.onCancelled();
                }
            }
        };
        AtakBroadcast.getInstance().registerReceiver(pending,
                new AtakBroadcast.DocumentedIntentFilter(ACTIVITY_FINISHED,
                        "The phone's camera has returned a photo for PET"));

        try {
            ((Activity) atak).startActivityForResult(intent, REQUEST_CODE);
            Log.d(TAG, "camera opened, photo to " + photo.getAbsolutePath());
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no camera app", e);
            cancelPending();
            callback.onProblem("No camera app was found on this phone.");
        } catch (SecurityException e) {
            // ATAK declares the CAMERA permission, so Android refuses the
            // camera unless the permission has been granted
            Log.w(TAG, "camera not allowed", e);
            cancelPending();
            callback.onProblem("ATAK is not allowed to use the camera. Allow it in the phone's "
                    + "Settings > Apps > ATAK > Permissions > Camera, then try again.");
        }
    }

    /** Stops waiting for a capture, and deletes its file. Safe to call at any time. */
    static void cancelPending() {
        File f = pendingFile;
        stopListening();
        if (f != null)
            delete(f);
    }

    private static void stopListening() {
        if (pending != null)
            AtakBroadcast.getInstance().unregisterReceiver(pending);
        pending = null;
        pendingFile = null;
    }

    /**
     * Where flows keep their photos: pet-camera in ATAK's cache on external
     * storage, or in ATAK's tmp folder if that is not available. Made if
     * missing.
     */
    static File photoRoot() throws IOException {
        File cache = MapView.getMapView().getContext().getExternalCacheDir();
        File root = new File(cache != null ? cache : FileSystemUtils.getItem("tmp"), FOLDER);
        if (!root.isDirectory() && !root.mkdirs())
            throw new IOException("could not make " + root);
        return root;
    }

    static void delete(File f) {
        if (f.exists() && !f.delete())
            Log.w(TAG, "could not delete " + f.getAbsolutePath());
    }
}

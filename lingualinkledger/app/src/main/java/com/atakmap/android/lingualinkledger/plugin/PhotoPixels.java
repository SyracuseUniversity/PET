package com.atakmap.android.lingualinkledger.plugin;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.atakmap.coremap.log.Log;

import java.io.File;

/**
 * Makes the reduced copies of a photo file that PhotoLabelReader asks for,
 * with Android's BitmapFactory. Only the header is read to learn the size;
 * each copy is decoded straight at its reduced size (inSampleSize), so the
 * full-size photo is never in memory (the user's requirement a).
 */
final class PhotoPixels implements PhotoLabelReader.Source {

    private static final String TAG = "LinguaLinkLedger.Photo";

    private final File file;

    PhotoPixels(File file) {
        this.file = file;
    }

    /** The photo's {width, height} from its header, decoding no pixels; {0, 0} if unreadable. */
    int[] size() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        return new int[] {Math.max(0, o.outWidth), Math.max(0, o.outHeight)};
    }

    @Override
    public PhotoLabelReader.Pixels decode(int sampleSize) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleSize;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = null;
        try {
            b = BitmapFactory.decodeFile(file.getAbsolutePath(), o);
            if (b == null) {
                Log.w(TAG, "could not decode " + file.getName() + " at 1/" + sampleSize);
                return null;
            }
            int w = b.getWidth(), h = b.getHeight();
            int[] argb = new int[w * h];
            b.getPixels(argb, 0, w, 0, 0, w, h);
            return new PhotoLabelReader.Pixels(argb, w, h);
        } catch (OutOfMemoryError e) {
            // A copy capped at 2048 px needs about 25 MB and should never get
            // here; if it does, the photo is refused rather than ATAK crashing.
            Log.w(TAG, "not enough memory to decode " + file.getName() + " at 1/" + sampleSize, e);
            return null;
        } finally {
            if (b != null)
                b.recycle();
        }
    }
}

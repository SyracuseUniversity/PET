package com.atakmap.android.lingualinkledger.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the label in a photo without ever decoding the photo at full size
 * (the user's requirement a, Day 1 step 5). A 12 MP photo decoded whole,
 * with its pixels copied out for the decoder, takes about 100 MB: enough
 * to run a 4 GB phone such as the A15 out of memory and crash ATAK.
 *
 * So it decodes a reduced copy first, at most FIRST_PASS_MAX_SIDE pixels
 * on its longer side, and a larger one, at most LAST_PASS_MAX_SIDE, only if
 * the first holds no QR code. No copy is larger than that, whatever the
 * camera's resolution. Copies are reduced by powers of two, as Android's
 * BitmapFactory does (inSampleSize). Plain Java, so the order is tested on
 * the PC; PhotoPixels makes the copies on the phone.
 *
 * The first copy in which any code is found decides (LabelScan): one code
 * is read as a label, two or more are refused (S4). A second label so
 * small that only the larger copy would show it is therefore not seen when
 * the first copy already holds a code.
 */
final class PhotoLabelReader {

    static final int FIRST_PASS_MAX_SIDE = 1024;
    static final int LAST_PASS_MAX_SIDE = 2048;

    /** A decoded, reduced copy of a photo, as ARGB pixels row by row. */
    static final class Pixels {
        final int[] argb;
        final int width;
        final int height;

        Pixels(int[] argb, int width, int height) {
            if (argb.length != width * height)
                throw new IllegalArgumentException(argb.length + " pixels for " + width + " x " + height);
            this.argb = argb;
            this.width = width;
            this.height = height;
        }
    }

    /** Makes reduced copies of one photo. */
    interface Source {
        /**
         * The photo reduced by sampleSize, a power of two (1 is full size);
         * null if it cannot be decoded.
         */
        Pixels decode(int sampleSize);
    }

    /** What reading a photo gave. */
    static final class Result {
        /** The scan of the copy that decided; null if the photo could not be decoded. */
        final LabelScan scan;
        /** The sample size of each copy decoded, in order. */
        final List<Integer> decoded;

        private Result(LabelScan scan, List<Integer> decoded) {
            this.scan = scan;
            this.decoded = Collections.unmodifiableList(decoded);
        }

        boolean readable() {
            return scan != null;
        }
    }

    private PhotoLabelReader() {
    }

    /**
     * Reads the label in a photo of width x height pixels (from its header),
     * decoding copies from source, smallest first.
     */
    static Result read(int width, int height, Source source) {
        List<Integer> decoded = new ArrayList<>();
        if (width <= 0 || height <= 0)
            return new Result(null, decoded);
        List<String> texts = Collections.emptyList();
        for (int sampleSize : sampleSizes(width, height)) {
            Pixels copy = source.decode(sampleSize);
            decoded.add(sampleSize);
            if (copy == null)
                return new Result(null, decoded);
            texts = QrDecoder.decode(copy.argb, copy.width, copy.height);
            if (!texts.isEmpty())
                break; // a larger copy only if this one holds no code
        }
        return new Result(LabelScan.of(texts), decoded);
    }

    /**
     * The sample sizes to try, smallest copy first: the first copy no more
     * than FIRST_PASS_MAX_SIDE on its longer side, then the largest no more
     * than LAST_PASS_MAX_SIDE, if that is a different copy.
     */
    static List<Integer> sampleSizes(int width, int height) {
        int first = sampleSizeFor(width, height, FIRST_PASS_MAX_SIDE);
        int last = sampleSizeFor(width, height, LAST_PASS_MAX_SIDE);
        List<Integer> sizes = new ArrayList<>();
        sizes.add(first);
        if (last != first)
            sizes.add(last);
        return sizes;
    }

    /**
     * The smallest power of two that brings the longer side to maxSide or
     * less. Rounded up, since a decoder may round a reduced side up.
     */
    static int sampleSizeFor(int width, int height, int maxSide) {
        int longer = Math.max(width, height);
        int s = 1;
        while (s < (1 << 30) && ((long) longer + s - 1) / s > maxSide)
            s *= 2;
        return s;
    }
}

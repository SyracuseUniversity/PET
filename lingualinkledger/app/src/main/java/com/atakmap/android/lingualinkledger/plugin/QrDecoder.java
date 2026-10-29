package com.atakmap.android.lingualinkledger.plugin;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Binarizer;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Finds the QR codes in a still image (R2: the evidence photo itself), with
 * ZXing core 3.5.4. Plain Java: it takes the pixels, so it runs in PC unit
 * tests; the Android side turns a photo file into pixels.
 *
 * It looks for every code in the image, not just one, so that a photo
 * holding two labels can be refused (S4).
 */
final class QrDecoder {

    private QrDecoder() {
    }

    /**
     * The text of each distinct QR code found in an image, given as ARGB
     * pixels row by row. Empty if none is found.
     */
    static List<String> decode(int[] argb, int width, int height) {
        LuminanceSource source = new RGBLuminanceSource(width, height, argb);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));

        // The local-contrast binarizer suits photos; the global one sometimes
        // reads what the local one misses, so it is the second try.
        for (Binarizer binarizer : new Binarizer[] {
                new HybridBinarizer(source), new GlobalHistogramBinarizer(source)}) {
            List<Result> found = findAll(new BinaryBitmap(binarizer), hints);
            if (!found.isEmpty())
                return distinctTexts(found, Math.max(width, height));
        }
        return Collections.emptyList();
    }

    private static List<Result> findAll(BinaryBitmap bitmap, Map<DecodeHintType, Object> hints) {
        List<Result> found = new ArrayList<>();
        try {
            Collections.addAll(found, new QRCodeMultiReader().decodeMultiple(bitmap, hints));
        } catch (NotFoundException ignored) {
            // none by the multi-code search
        }
        if (found.isEmpty()) {
            // the single-code reader can find a code the multi-code search misses
            try {
                found.add(new QRCodeReader().decode(bitmap, hints));
            } catch (NotFoundException | ChecksumException | FormatException ignored) {
                // none
            }
        }
        return found;
    }

    /**
     * One text per code: the same text found twice at nearly the same place
     * is one code seen twice. The same text at two places is two labels.
     */
    private static List<String> distinctTexts(List<Result> found, int imageSize) {
        float near = imageSize / 20f;
        List<String> texts = new ArrayList<>();
        List<float[]> centres = new ArrayList<>();
        for (Result r : found) {
            float[] c = centre(r);
            boolean seen = false;
            for (int i = 0; i < texts.size(); i++) {
                if (texts.get(i).equals(r.getText()) && c != null && centres.get(i) != null
                        && Math.hypot(c[0] - centres.get(i)[0], c[1] - centres.get(i)[1]) < near) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                texts.add(r.getText());
                centres.add(c);
            }
        }
        return texts;
    }

    private static float[] centre(Result r) {
        ResultPoint[] points = r.getResultPoints();
        if (points == null || points.length == 0)
            return null;
        float x = 0, y = 0;
        for (ResultPoint p : points) {
            x += p.getX();
            y += p.getY();
        }
        return new float[] {x / points.length, y / points.length};
    }
}

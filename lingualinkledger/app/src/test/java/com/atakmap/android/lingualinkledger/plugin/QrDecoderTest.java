package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The decoder and LabelScan against the user's test set images, version
 * 1.1 (D53): every code's image gives exactly its manifest text and its
 * expected result; E7's two-code photo is refused (S4).
 */
public class QrDecoderTest {

    @Test
    public void everyCodeImageGivesItsText() throws Exception {
        List<Map<String, Object>> codes = TestQrSet.list(TestQrSet.manifest(), "codes");
        assertEquals(15, codes.size());
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> c : codes) {
            // the code-only image, and the labelled one shown on a monitor
            for (String key : new String[] {"file_bare", "file"}) {
                String file = (String) c.get(key);
                String why = checkOne(read(file), c);
                if (why != null)
                    failures.add(file + ": " + why);
            }
        }
        if (!failures.isEmpty())
            fail(failures.size() + " wrong:\n  " + String.join("\n  ", failures));
    }

    /** Smaller copies, as a phone's photo is scaled down before decoding. */
    @Test
    public void shrunkenCopiesStillRead() throws Exception {
        List<Map<String, Object>> codes = TestQrSet.list(TestQrSet.manifest(), "codes");
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> c : codes) {
            TestPng img = read((String) c.get("file_bare"));
            for (int width : new int[] {400, 250}) {
                String why = checkOne(img.scaledToWidth(width), c);
                if (why != null)
                    failures.add(c.get("file_bare") + " at " + width + " px: " + why);
            }
        }
        if (!failures.isEmpty())
            fail(failures.size() + " wrong:\n  " + String.join("\n  ", failures));
    }

    @Test
    public void twoCodesInOnePhotoAreRefused() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> e7 = (Map<String, Object>) TestQrSet.manifest().get("multi_code_photo");
        List<String> texts = decode(read((String) e7.get("file")));
        assertEquals("both codes found", new HashSet<>((List<?>) e7.get("payloads")), new HashSet<>(texts));
        assertEquals(2, texts.size());
        LabelScan scan = LabelScan.of(texts);
        assertEquals(LabelScan.Outcome.SEVERAL_CODES, scan.outcome);
        assertNull(scan.label());
        assertEquals("This photo shows 2 QR codes. Photograph one label at a time.", scan.problem());
    }

    @Test
    public void aBlankImageHasNoCode() {
        int[] white = new int[300 * 200];
        Arrays.fill(white, 0xFFFFFFFF);
        List<String> texts = QrDecoder.decode(white, 300, 200);
        assertEquals(0, texts.size());
        assertEquals(LabelScan.Outcome.NO_CODE, LabelScan.of(texts).outcome);
    }

    /** null if the image gives exactly the manifest's text and expected result. */
    private static String checkOne(TestPng img, Map<String, Object> c) {
        List<String> texts = decode(img);
        if (texts.size() != 1)
            return texts.size() + " codes found, expected 1";
        if (!texts.get(0).equals(c.get("payload")))
            return "read \"" + texts.get(0) + "\", expected \"" + c.get("payload") + "\"";
        LabelScan scan = LabelScan.of(texts);
        if ("accept".equals(c.get("expect"))) {
            if (scan.outcome != LabelScan.Outcome.LABEL)
                return "refused: " + scan.problem();
            if (!scan.label().batchId.equals(c.get("batch_id")))
                return "batch ID " + scan.label().batchId;
        } else if (scan.outcome != LabelScan.Outcome.REFUSED_LABEL
                || !scan.reading.refusal.code.equals(c.get("reason"))) {
            return "expected refused as " + c.get("reason") + ", was " + scan.outcome;
        }
        return null;
    }

    private static List<String> decode(TestPng img) {
        return QrDecoder.decode(img.argb, img.width, img.height);
    }

    private static TestPng read(String relative) throws Exception {
        return TestPng.read(new File(TestQrSet.dir(), relative));
    }
}

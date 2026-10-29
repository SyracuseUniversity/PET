package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A photo is never decoded at full size (the user's requirement a, Day 1
 * step 5): a reduced copy first, a larger one only if the first holds no
 * code, and no copy beyond the cap. The copies here are the test set's
 * images (v1.1, D53), handed out by a stand-in for the phone's decoder.
 */
public class PhotoLabelReaderTest {

    @Test
    public void sampleSizesForThePhonesCameras() {
        // S21+ 12 MP, either way up
        assertEquals(Arrays.asList(4, 2), PhotoLabelReader.sampleSizes(4000, 3000));
        assertEquals(Arrays.asList(4, 2), PhotoLabelReader.sampleSizes(3000, 4000));
        // A15: 12.5 MP by default, 50 MP when chosen
        assertEquals(Arrays.asList(4, 2), PhotoLabelReader.sampleSizes(4080, 3060));
        assertEquals(Arrays.asList(8, 4), PhotoLabelReader.sampleSizes(8160, 6120));
    }

    @Test
    public void sampleSizesAtTheBoundaries() {
        assertEquals(Collections.singletonList(1), PhotoLabelReader.sampleSizes(800, 600));
        assertEquals(Collections.singletonList(1), PhotoLabelReader.sampleSizes(1024, 768));
        assertEquals(Arrays.asList(2, 1), PhotoLabelReader.sampleSizes(1025, 768));
        assertEquals(Arrays.asList(2, 1), PhotoLabelReader.sampleSizes(2048, 1536));
        // a side of 2049 halves to 1025 (rounded up), still over 1024 and 2048 at those steps
        assertEquals(Arrays.asList(4, 2), PhotoLabelReader.sampleSizes(2049, 1537));
    }

    /** Every size up to 20,000 px: no copy over its cap, and the last the largest that fits. */
    @Test
    public void noCopyIsLargerThanTheCap() {
        for (int side = 1; side <= 20000; side++) {
            List<Integer> sizes = PhotoLabelReader.sampleSizes(side, side / 2 + 1);
            int first = sizes.get(0), last = sizes.get(sizes.size() - 1);
            assertTrue(side + ": first copy " + reduced(side, first),
                    reduced(side, first) <= PhotoLabelReader.FIRST_PASS_MAX_SIDE);
            assertTrue(side + ": last copy " + reduced(side, last),
                    reduced(side, last) <= PhotoLabelReader.LAST_PASS_MAX_SIDE);
            assertTrue(side + ": a larger copy would fit", last == 1
                    || reduced(side, last / 2) > PhotoLabelReader.LAST_PASS_MAX_SIDE);
            for (int s : sizes)
                assertEquals(side + ": " + s + " is not a power of two", 0, s & (s - 1));
            assertTrue(side + ": not smallest copy first", sizes.size() == 1 || first > last);
        }
    }

    @Test
    public void aCodeInTheFirstCopyStopsThere() throws Exception {
        Copies photo = new Copies(image("png_bare/SAR-TEST-0001.png"), null);
        PhotoLabelReader.Result r = PhotoLabelReader.read(4000, 3000, photo);
        assertEquals("never decoded larger", Collections.singletonList(4), r.decoded);
        assertEquals(LabelScan.Outcome.LABEL, r.scan.outcome);
        assertEquals("SAR-TEST-0001", r.scan.label().batchId);
    }

    @Test
    public void aLargerCopyOnlyWhenTheFirstHoldsNoCode() throws Exception {
        Copies photo = new Copies(blank(), image("png_bare/SAR-TEST-0002.png"));
        PhotoLabelReader.Result r = PhotoLabelReader.read(4000, 3000, photo);
        assertEquals(Arrays.asList(4, 2), r.decoded);
        assertEquals(LabelScan.Outcome.LABEL, r.scan.outcome);
        assertEquals("SAR-TEST-0002", r.scan.label().batchId);
    }

    @Test
    public void noCodeInAnyCopy() {
        Copies photo = new Copies(blank(), blank());
        PhotoLabelReader.Result r = PhotoLabelReader.read(4000, 3000, photo);
        assertEquals(Arrays.asList(4, 2), r.decoded);
        assertTrue(r.readable());
        assertEquals(LabelScan.Outcome.NO_CODE, r.scan.outcome);
    }

    @Test
    public void twoCodesInTheFirstCopyAreRefusedThere() throws Exception {
        Copies photo = new Copies(image("png/E7_two_codes.png"), null);
        PhotoLabelReader.Result r = PhotoLabelReader.read(4000, 3000, photo);
        assertEquals(Collections.singletonList(4), r.decoded);
        assertEquals(LabelScan.Outcome.SEVERAL_CODES, r.scan.outcome);
    }

    @Test
    public void aSmallPhotoIsDecodedOnceAtFullSize() throws Exception {
        Copies photo = new Copies(image("png_bare/SAR-TEST-0003.png"), null);
        photo.firstSampleSize = 1;
        PhotoLabelReader.Result r = PhotoLabelReader.read(800, 600, photo);
        assertEquals(Collections.singletonList(1), r.decoded);
        assertEquals("SAR-TEST-0003", r.scan.label().batchId);
    }

    @Test
    public void aPhotoThatCannotBeDecoded() {
        PhotoLabelReader.Result r = PhotoLabelReader.read(4000, 3000, sampleSize -> null);
        assertFalse(r.readable());
        assertEquals(Collections.singletonList(4), r.decoded);

        // no size in its header: nothing is decoded at all
        r = PhotoLabelReader.read(0, 0, sampleSize -> {
            throw new AssertionError("decoded a photo with no size");
        });
        assertFalse(r.readable());
        assertTrue(r.decoded.isEmpty());
    }

    /**
     * Stands in for the phone's decoder: the first copy asked for, then any
     * larger one. A null larger copy fails the test if it is asked for.
     */
    private static final class Copies implements PhotoLabelReader.Source {
        final TestPng first, larger;
        int firstSampleSize = 4;

        Copies(TestPng first, TestPng larger) {
            this.first = first;
            this.larger = larger;
        }

        @Override
        public PhotoLabelReader.Pixels decode(int sampleSize) {
            TestPng img = sampleSize == firstSampleSize ? first : larger;
            if (img == null)
                throw new AssertionError("decoded at 1/" + sampleSize + ", which should not be needed");
            return new PhotoLabelReader.Pixels(img.argb, img.width, img.height);
        }
    }

    private static int reduced(int side, int sampleSize) {
        return (side + sampleSize - 1) / sampleSize;
    }

    private static TestPng image(String relative) throws Exception {
        return TestPng.read(new File(TestQrSet.dir(), relative));
    }

    private static TestPng blank() {
        return TestPng.blank(1000, 750);
    }
}

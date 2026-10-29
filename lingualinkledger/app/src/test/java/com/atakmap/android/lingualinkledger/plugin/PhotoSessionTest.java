package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * A flow's photos (Day 2 step 7): a second photo in the same flow does not
 * delete the first, as Day 1's sweep would have, so a merge keeps all its
 * labels; leftovers go only when a new flow begins; and a flow that ends
 * leaves no photo behind (T4).
 */
public class PhotoSessionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void tearDown() {
        PhotoSession.endCurrent();
    }

    @Test
    public void everyPhotoOfAFlowIsKeptUntilItEnds() throws Exception {
        PhotoSession merge = PhotoSession.begin(tmp.getRoot());
        File[] photos = new File[4];
        for (int i = 0; i < photos.length; i++) {
            photos[i] = merge.newPhotoFile();
            Files.write(photos[i].toPath(), new byte[] {(byte) i});
        }
        for (File p : photos)
            assertTrue(p.getName() + " kept", p.isFile());

        merge.end();
        assertTrue(merge.ended());
        assertFalse("the flow's folder is gone", merge.folder.exists());
        for (File p : photos)
            assertFalse(p.exists());
    }

    @Test
    public void aNewFlowEndsTheOldOne() throws Exception {
        PhotoSession first = PhotoSession.begin(tmp.getRoot());
        File photo = first.newPhotoFile();
        PhotoSession second = PhotoSession.begin(tmp.getRoot());
        assertTrue(first.ended());
        assertFalse(photo.exists());
        assertTrue(second.folder.isDirectory());
        try {
            first.newPhotoFile();
            fail("an ended flow took a photo");
        } catch (IOException expected) {
            // refused
        }
    }

    /** Left by ATAK closing mid-flow, and by Day 1's build, which kept photos loose. */
    @Test
    public void leftoversAreClearedWhenAFlowBegins() throws Exception {
        File root = tmp.getRoot();
        File oldFlow = new File(root, "session_1791843300000");
        assertTrue(oldFlow.mkdirs());
        Files.write(new File(oldFlow, "pet_photo_1.jpg").toPath(), new byte[] {1});
        File loose = new File(root, "pet_photo_42.jpg");
        Files.write(loose.toPath(), new byte[] {2});
        File notOurs = new File(root, "notes.txt");
        Files.write(notOurs.toPath(), new byte[] {3});

        PhotoSession s = PhotoSession.begin(root);
        assertFalse(oldFlow.exists());
        assertFalse(loose.exists());
        assertTrue("only the plugin's own files are touched", notOurs.exists());
        assertEquals(Arrays.asList("notes.txt", s.folder.getName()), sorted(root.list()));
    }

    @Test
    public void endingTwiceIsHarmless() throws Exception {
        PhotoSession s = PhotoSession.begin(tmp.getRoot());
        s.end();
        s.end();
        PhotoSession.endCurrent();
        assertTrue(s.ended());
    }

    private static java.util.List<String> sorted(String[] names) {
        java.util.List<String> l = Arrays.asList(names);
        java.util.Collections.sort(l);
        return l;
    }
}

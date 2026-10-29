package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PL-43: no real position in the sources. Two positions recorded by the test
 * phone reached the tests and a comment (12 and 13 Oct); this reads every
 * source and resource text file and refuses any coordinate-shaped number
 * (four or more decimals) except the one public place the tests use, Hendricks
 * Chapel on the Syracuse University Quad (its longitude also as Saved batches
 * shows it, without the trailing zero), and the latitude and longitude
 * bounds the position rules are tested against.
 */
public class SourceCoordinatesTest {

    /** Hendricks Chapel, on the Shaw Quadrangle (Wikipedia, "Hendricks Chapel"). */
    static final double QUAD_LAT = 43.037634, QUAD_LON = -76.135120;

    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "43.037634", "76.135120", "76.13512", "90.000001", "180.000001"));
    private static final Pattern NUMBER = Pattern.compile("(?<![0-9A-Za-z_.])-?([0-9]{1,3}\\.[0-9]{4,})(?![0-9])");
    private static final Set<String> TEXT = new HashSet<>(Arrays.asList(".java", ".xml", ".txt", ".json", ".md"));

    @Test
    public void noCoordinateButThePublicOneAndTheBounds() throws IOException {
        List<String> found = new ArrayList<>();
        int files = 0;
        for (File root : new File[] {dir("main"), dir("test")}) {
            List<File> all = new ArrayList<>();
            walk(root, all);
            for (File f : all) {
                files++;
                List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = NUMBER.matcher(lines.get(i));
                    while (m.find())
                        if (!ALLOWED.contains(m.group(1)))
                            found.add(f.getName() + ":" + (i + 1) + " " + m.group());
                }
            }
        }
        assertTrue("no source files read", files > 50);
        assertTrue("coordinate-shaped numbers other than the Quad's: " + found, found.isEmpty());
    }

    private static void walk(File d, List<File> out) {
        File[] kids = d.listFiles();
        if (kids == null)
            return;
        for (File k : kids) {
            if (k.isDirectory())
                walk(k, out);
            else {
                String n = k.getName();
                int dot = n.lastIndexOf('.');
                if (dot >= 0 && TEXT.contains(n.substring(dot)))
                    out.add(k);
            }
        }
    }

    /** app\src\main or app\src\test, found by walking up from the test's working folder. */
    private static File dir(String which) {
        String rel = "src" + File.separator + which;
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null; d = d.getParentFile())
            for (File c : new File[] {new File(d, rel), new File(d, "app" + File.separator + rel)})
                if (new File(c, "java").isDirectory())
                    return c;
        throw new IllegalStateException(rel + " not found above " + System.getProperty("user.dir"));
    }
}

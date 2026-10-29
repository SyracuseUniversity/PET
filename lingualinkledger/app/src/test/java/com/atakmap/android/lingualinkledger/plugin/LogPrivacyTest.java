package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PL-40: the plugin never writes the phone's position to the system log.
 * ATAK copies the last 100 log lines into a crash report on shared storage
 * (PL-36), and teammates may test at home. Every log call in the plugin's
 * sources (Log.v/d/i/w/e, System.out/err) is read with its string literals
 * and comments removed, and refused if it uses a location value: lat, lon,
 * latitude, longitude, point, position, location (or a name ending in
 * Point, Position or Location), or getLatitude/getLongitude/getPoint.
 */
public class LogPrivacyTest {

    private static final Pattern CALL = Pattern.compile(
            "\\b(?:Log\\.[vdiwe]|System\\.(?:out|err)\\.print(?:ln|f)?)\\s*\\(");
    private static final Pattern LOCATION = Pattern.compile(
            "\\b(?:lat|lon|lng|latitude|longitude|point|position|pos|location|loc|reading)\\b"
                    + "|\\b\\w+(?:Point|Position|Location)\\b"
                    + "|\\bget(?:Latitude|Longitude|Point|Location)\\b");

    @Test
    public void noLogCallUsesALocation() throws Exception {
        List<File> sources = new ArrayList<>();
        collect(sourceDir(), sources);
        assertTrue("no sources found", sources.size() > 10);
        List<String> found = new ArrayList<>();
        int calls = 0;
        for (File f : sources) {
            String code = withoutLiteralsAndComments(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            Matcher m = CALL.matcher(code);
            while (m.find()) {
                calls++;
                String args = arguments(code, m.end());
                Matcher bad = LOCATION.matcher(args);
                if (bad.find())
                    found.add(f.getName() + ":" + line(code, m.start()) + " uses '" + bad.group() + "'");
            }
        }
        assertTrue("no log calls found: is the source folder right?", calls > 20);
        if (!found.isEmpty())
            fail("a log call uses a location value (PL-40):\n  " + String.join("\n  ", found));
    }

    @Test
    public void theCheckCatchesALoggedCoordinate() {
        String code = withoutLiteralsAndComments(
                "Log.d(TAG, \"at \" + p.getLatitude() + \",\" + p.getLongitude());\n"
                        + "Log.d(TAG, \"marker=\" + describe(markerPoint));\n"
                        + "Log.d(TAG, \"a point, in a string only, is fine\");");
        List<String> hits = new ArrayList<>();
        Matcher m = CALL.matcher(code);
        while (m.find()) {
            Matcher bad = LOCATION.matcher(arguments(code, m.end()));
            hits.add(bad.find() ? bad.group() : "-");
        }
        assertTrue(hits.toString(), hits.size() == 3 && hits.get(0).equals("getLatitude")
                && hits.get(1).equals("markerPoint") && hits.get(2).equals("-"));
    }

    /** The text from just after the call's "(" to its matching ")". */
    private static String arguments(String code, int from) {
        int depth = 1;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(')
                depth++;
            else if (c == ')' && --depth == 0)
                return code.substring(from, i);
        }
        return code.substring(from);
    }

    /** String and char literals and comments blanked, keeping line breaks so line numbers hold. */
    private static String withoutLiteralsAndComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n')
                    i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                for (int k = i; k < end; k++)
                    if (s.charAt(k) == '\n')
                        out.append('\n');
                i = end;
            } else if (c == '"' || c == '\'') {
                out.append(c).append(c);
                i++;
                while (i < s.length() && s.charAt(i) != c) {
                    if (s.charAt(i) == '\\')
                        i++;
                    i++;
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int line(String code, int at) {
        int n = 1;
        for (int i = 0; i < at; i++)
            if (code.charAt(i) == '\n')
                n++;
        return n;
    }

    private static void collect(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            if (f.isDirectory())
                collect(f, out);
            else if (f.getName().endsWith(".java"))
                out.add(f);
        }
    }

    /** app\src\main\java, found by walking up from the test's working folder. */
    private static File sourceDir() {
        String rel = "src" + File.separator + "main" + File.separator + "java";
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null;
                d = d.getParentFile()) {
            for (File candidate : new File[] {new File(d, rel), new File(d, "app" + File.separator + rel)}) {
                if (new File(candidate, "com").isDirectory())
                    return candidate;
            }
        }
        throw new IllegalStateException(rel + " not found above " + System.getProperty("user.dir"));
    }
}

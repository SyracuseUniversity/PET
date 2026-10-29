package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The four testers' simulated QR sets, in docs\test-qr\testers\ (the share
 * pack, part 2): every image, the code alone and the labelled card, decoded
 * with the app's own decoder, and every text run through the app's own label
 * reader, must give exactly its manifest's text and expected result. And the
 * sets must keep the testers' records apart: no batch ID in two sets, none
 * from the v1.1 set (SAR-TEST-0001 to 0013), a different species each, two
 * spares each, and no label the reader would take as a real one.
 */
public class TesterQrSetsTest {

    static final String[] PEOPLE = {"Dominick", "Melanie", "Will", "Darrel"};
    private static final Pattern ID_IN = Pattern.compile("id=\\s*([A-Za-z0-9-]+)");

    @Test
    public void everyImageGivesItsTextAndItsExpectedResult() throws Exception {
        List<String> failures = new ArrayList<>();
        int images = 0;
        for (String person : PEOPLE) {
            for (Map<String, Object> c : codes(person)) {
                for (String key : new String[] {"file_bare", "file"}) {
                    images++;
                    String why = check(read(person, (String) c.get(key)), c);
                    if (why != null)
                        failures.add(person + " " + c.get(key) + ": " + why);
                }
            }
        }
        if (!failures.isEmpty())
            fail(failures.size() + " wrong:\n  " + String.join("\n  ", failures));
        assertEquals("images checked", 2 * (15 + 15 + 15 + 13), images);
    }

    /** Smaller copies, as a phone's photo is scaled down before decoding. */
    @Test
    public void shrunkenCopiesStillGiveTheirResult() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String person : PEOPLE)
            for (Map<String, Object> c : codes(person)) {
                TestPng img = read(person, (String) c.get("file_bare"));
                for (int width : new int[] {400, 250}) {
                    if ("several_codes".equals(c.get("expect")))
                        width = width * 2; // three codes side by side need the room
                    String why = check(img.scaledToWidth(width), c);
                    if (why != null)
                        failures.add(person + " " + c.get("file_bare") + " at " + width + " px: " + why);
                }
            }
        if (!failures.isEmpty())
            fail(failures.size() + " wrong:\n  " + String.join("\n  ", failures));
    }

    @Test
    public void theSetsKeepTheTestersRecordsApart() throws Exception {
        Map<String, String> owner = new HashMap<>();
        Set<String> species = new HashSet<>();
        for (String person : PEOPLE) {
            Map<String, Object> m = manifest(person);
            assertTrue(person + ": a species of its own", species.add((String) m.get("species")));
            String prefix = "SAR-TEST-" + person.substring(0, 3).toUpperCase() + "-";
            int spares = 0;
            for (Map<String, Object> c : codes(person)) {
                if (String.valueOf(c.get("title")).startsWith("Spare"))
                    spares++;
                for (String text : texts(c)) {
                    LabelReader.Result r = LabelReader.read(text);
                    if (r.accepted())
                        assertTrue(person + ": " + text + " must be a simulated label (test=1)", r.label.test);
                    Matcher id = ID_IN.matcher(text);
                    if (!id.find())
                        continue;
                    String batch = id.group(1).toUpperCase();
                    if (batch.matches("SAR-TEST-[0-9]{4}"))
                        fail(person + ": " + batch + " belongs to the v1.1 set");
                    if (batch.startsWith("SAR-TEST-"))
                        assertTrue(person + ": " + batch + " must start " + prefix, batch.startsWith(prefix));
                    String before = owner.put(batch, person);
                    assertTrue(batch + " is in two sets: " + before + " and " + person,
                            before == null || before.equals(person));
                }
            }
            assertEquals(person + ": two spares", 2, spares);
        }
    }

    /** null if the image gives exactly the manifest's text(s) and expected result. */
    private static String check(TestPng img, Map<String, Object> c) {
        List<String> found = QrDecoder.decode(img.argb, img.width, img.height);
        LabelScan scan = LabelScan.of(found);
        String expect = (String) c.get("expect");
        switch (expect) {
            case "no_code":
                return found.isEmpty() && scan.outcome == LabelScan.Outcome.NO_CODE ? null
                        : found.size() + " code(s) read from a damaged label: " + found;
            case "several_codes":
                if (scan.outcome != LabelScan.Outcome.SEVERAL_CODES)
                    return "expected several codes, was " + scan.outcome + " " + found;
                if (!new HashSet<>(texts(c)).equals(new HashSet<>(found)))
                    return "read " + found + ", expected " + texts(c);
                return scan.codesFound == ((Number) c.get("codes_found")).intValue() ? null
                        : scan.codesFound + " codes, expected " + c.get("codes_found");
            default:
                if (found.size() != 1)
                    return found.size() + " codes found, expected 1";
                if (!found.get(0).equals(c.get("payload")))
                    return "read \"" + found.get(0) + "\", expected \"" + c.get("payload") + "\"";
                if ("accept".equals(expect)) {
                    if (scan.outcome != LabelScan.Outcome.LABEL)
                        return "refused: " + scan.problem();
                    LabelReader.Label l = scan.label();
                    if (!l.batchId.equals(c.get("batch_id")) || !l.test || !l.species.equals(c.get("species")))
                        return "read as " + l.batchId + ", test " + l.test + ", species " + l.species;
                    return null;
                }
                return scan.outcome == LabelScan.Outcome.REFUSED_LABEL
                        && scan.reading.refusal.code.equals(c.get("reason")) ? null
                        : "expected refused as " + c.get("reason") + ", was " + scan.outcome
                                + (scan.reading == null || scan.reading.refusal == null ? ""
                                        : " (" + scan.reading.refusal.code + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> texts(Map<String, Object> c) {
        List<String> t = new ArrayList<>();
        if (c.get("payloads") != null)
            t.addAll((List<String>) c.get("payloads"));
        else
            t.add((String) c.get("payload"));
        return t;
    }

    private static File dir(String person) {
        return new File(TestQrSet.dir(), "testers" + File.separator + person.toLowerCase());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> manifest(String person) throws IOException {
        File f = new File(dir(person), "PET_Test_QR_Manifest_" + person + ".json");
        return (Map<String, Object>) Json.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> codes(String person) throws IOException {
        return (List<Map<String, Object>>) manifest(person).get("codes");
    }

    private static TestPng read(String person, String relative) throws IOException {
        return TestPng.read(new File(dir(person), relative));
    }
}

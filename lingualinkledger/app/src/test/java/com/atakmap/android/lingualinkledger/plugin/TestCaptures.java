package com.atakmap.android.lingualinkledger.plugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Random;

/** Captures for tests: a stand-in photo file and a GPS fix, as the phone would give. */
final class TestCaptures {

    /** 2026-10-12T22:15:00Z */
    static final long T0 = 1791843300000L;

    private TestCaptures() {
    }

    static PositionPolicy.Check gps(long atMs) {
        return PositionPolicy.check(new PositionPolicy.Reading(PositionPolicy.Source.GNSS,
                43.037634, -76.135120, 8.0, atMs - 1000, "GPS", "GPS"), atMs);
    }

    /** A capture of a test label, its photo 150 KB of bytes that differ for each ID. */
    static Capture of(File dir, String id, long atMs) throws IOException {
        File photo = new File(dir, "photo_" + id + "_" + atMs + ".jpg");
        byte[] bytes = new byte[150 * 1024];
        new Random(id.hashCode() * 31L + atMs).nextBytes(bytes);
        try (FileOutputStream out = new FileOutputStream(photo)) {
            out.write(bytes);
        }
        return new Capture(photo, id, Capture.IdRead.EVIDENCE_PHOTO, true, "Red pine", atMs, gps(atMs));
    }

    /** SHA-256 computed here, independently of FileHasher, as a teammate would re-check. */
    static String sha256(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}

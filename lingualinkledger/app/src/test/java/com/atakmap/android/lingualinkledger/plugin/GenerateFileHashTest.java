package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Known-vector tests for generateFileHash, the plugin's SHA-256 of a file
 * (Handoff 1 Section 14: preserve it exactly; changes need a known-vector
 * test).
 *
 * Written first against the method where it was, a private method of
 * LinguaLinkLedger (commit 8c12429), so the vectors passed before it moved
 * to FileHasher, byte for byte.
 */
public class GenerateFileHashTest {

    // SHA-256 test vectors (FIPS 180-2 for "abc" and one million 'a')
    private static final String SHA256_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String SHA256_MILLION_A =
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0";
    // a1test.zip, built on the S21+ on 10 Oct; the plugin, sha256sum on the
    // phone and Get-FileHash on the PC all gave this (Corrections 3.8)
    private static final String SHA256_A1TEST =
            "89d0edfc3b8b2ff851fdcf189ae40cc36ae2d21b4621a862b8d0650a51e0089f";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void emptyFile() throws Exception {
        assertEquals(SHA256_EMPTY, hash(write(new byte[0])));
    }

    @Test
    public void abc() throws Exception {
        assertEquals(SHA256_ABC, hash(write("abc".getBytes(StandardCharsets.US_ASCII))));
    }

    /** Larger than the 1024-byte read buffer, so the read loop runs many times. */
    @Test
    public void oneMillionA() throws Exception {
        byte[] data = new byte[1_000_000];
        Arrays.fill(data, (byte) 'a');
        assertEquals(SHA256_MILLION_A, hash(write(data)));
    }

    @Test
    public void a1testPackage() throws Exception {
        assertEquals(SHA256_A1TEST, hash(resource("/a1test.zip")));
    }

    /** Today's behaviour on a missing file: null, not an exception. */
    @Test
    public void missingFileGivesNull() throws Exception {
        assertNull(hash(new File(tmp.getRoot(), "no-such-file").getAbsolutePath()));
    }

    /** PL-14: a file that cannot be read prints no stack trace; the caller reports it. */
    @Test
    public void aFileThatCannotBeReadPrintsNothing() throws Exception {
        PrintStream err = System.err, out = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(printed, true, "UTF-8"));
            System.setOut(new PrintStream(printed, true, "UTF-8"));
            assertNull(hash(new File(tmp.getRoot(), "no-such-file").getAbsolutePath()));
        } finally {
            System.setErr(err);
            System.setOut(out);
        }
        assertEquals("", printed.toString("UTF-8"));
    }

    private String write(byte[] data) throws IOException {
        File f = tmp.newFile();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f.getAbsolutePath();
    }

    private static String resource(String name) throws URISyntaxException {
        return new File(GenerateFileHashTest.class.getResource(name).toURI()).getAbsolutePath();
    }

    private static String hash(String path) {
        return FileHasher.generateFileHash(path);
    }
}

package com.atakmap.android.lingualinkledger.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * The user's simulated test QR set, version 1.1, in docs\test-qr\ (D53):
 * the manifest gives every code's exact text and expected result.
 */
final class TestQrSet {

    static final String MANIFEST = "PET_Test_QR_Manifest_v1.1.json";

    private TestQrSet() {
    }

    /** docs\test-qr, found by walking up from the test's working folder. */
    static File dir() {
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null;
                d = d.getParentFile()) {
            File candidate = new File(d, "docs" + File.separator + "test-qr");
            if (new File(candidate, MANIFEST).isFile())
                return candidate;
        }
        throw new IllegalStateException("docs\\test-qr\\" + MANIFEST + " not found above "
                + System.getProperty("user.dir"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> manifest() throws IOException {
        byte[] bytes = Files.readAllBytes(new File(dir(), MANIFEST).toPath());
        return (Map<String, Object>) Json.parse(new String(bytes, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> manifest, String key) {
        return (List<Map<String, Object>>) manifest.get(key);
    }
}

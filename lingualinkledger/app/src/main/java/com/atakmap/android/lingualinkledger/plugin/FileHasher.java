package com.atakmap.android.lingualinkledger.plugin;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * SHA-256 of a file, as lowercase hex: the plugin's cryptographic core.
 * Plain Java with no Android or ATAK dependency, so its known-vector tests
 * run on the PC (GenerateFileHashTest). Preserve it exactly; any change
 * needs those tests to pass (Handoff 1 Section 14).
 *
 * Moved byte for byte from LinguaLinkLedger (D50, Day 1 step 1); since
 * then only its e.printStackTrace() is gone (PL-14).
 */
final class FileHasher {

    private FileHasher() {
    }

    static String generateFileHash(String filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream fis = new FileInputStream(filePath);
            byte[] buffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            fis.close();

            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // PL-14: no stack trace here; every caller reports a null hash
            // itself, in one line or a message to the user
            return null;
        }
    }
}

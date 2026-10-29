package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D45: App v1 sends nothing to any gateway. BlockchainService stays in the
 * source for Track B, but its address is not set, every public call
 * refuses before making a request, and nothing else in the plugin calls
 * it. Read from the source, since its OkHttp comes from ATAK at run time.
 */
public class NoGatewayTest {

    @Test
    public void theGatewayAddressIsNotSet() throws Exception {
        String src = source("BlockchainService.java");
        assertTrue("GATEWAY_BASE_URL must be empty (D45)",
                src.contains("private static final String GATEWAY_BASE_URL = \"\";"));
    }

    @Test
    public void everyPublicCallRefusesBeforeARequest() throws Exception {
        String src = source("BlockchainService.java");
        Matcher m = Pattern.compile("public static void (\\w+)\\([^)]*\\)\\s*\\{\\s*([^\\n]*)").matcher(src);
        List<String> methods = new ArrayList<>();
        while (m.find()) {
            methods.add(m.group(1));
            assertTrue(m.group(1) + " must refuse first when no gateway is set",
                    m.group(2).startsWith("if (GATEWAY_BASE_URL.isEmpty())"));
        }
        assertEquals("[submitFileHash, verifyRecord, checkHealth]", methods.toString());
    }

    @Test
    public void nothingElseCallsIt() throws Exception {
        File dir = new File(sourceDir(), "com/atakmap/android/lingualinkledger/plugin".replace('/', File.separatorChar));
        File[] files = dir.listFiles((d, n) -> n.endsWith(".java") && !n.equals("BlockchainService.java"));
        assertTrue(files != null && files.length > 10);
        for (File f : files) {
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertTrue(f.getName() + " calls BlockchainService", !src.contains("BlockchainService."));
        }
    }

    private static String source(String name) throws Exception {
        File f = new File(sourceDir(), ("com/atakmap/android/lingualinkledger/plugin/" + name)
                .replace('/', File.separatorChar));
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
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

package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * PL-42: Create/Edit Data Package's package must survive ATAK's import.
 * ATAK's importer deletes whatever is at its destination, then copies the
 * file there unless it is already there (the S21+'s log, 15 Oct, and the
 * bytecode of ImportResolver.beginImport). importLikeAtak does the same, so
 * a package built where it lands is lost, and one built in the staging
 * folder is copied in.
 */
public class PackageStagingTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File staging, datapackages;
    private final byte[] bytes = "a data package".getBytes(StandardCharsets.UTF_8);

    @Before
    public void setUp() throws IOException {
        staging = new File(tmp.getRoot(), "cache" + File.separator + PackageStaging.FOLDER);
        datapackages = tmp.newFolder("datapackage");
    }

    /** ATAK's importer, as seen on the phone: delete the destination, then copy if it is elsewhere. */
    private static File importLikeAtak(File file, File destinationDir) throws IOException {
        File destination = new File(destinationDir, file.getName());
        if (destination.exists() && !destination.delete())
            throw new IOException("could not delete " + destination);
        if (!file.getAbsolutePath().equals(destination.getAbsolutePath()))
            Files.copy(file.toPath(), destination.toPath());
        return destination;
    }

    @Test
    public void aPackageBuiltByThePlanSurvivesTheImport() throws IOException {
        PackageStaging.sweep(staging);
        PackageStaging p = PackageStaging.plan(staging, datapackages, "Done test");
        Files.write(p.staged.toPath(), bytes);
        File landed = importLikeAtak(p.staged, datapackages);
        assertEquals(p.landed.getCanonicalFile(), landed.getCanonicalFile());
        assertTrue("the package must be in ATAK's folder after the import", p.landed.isFile());
        assertArrayEquals(bytes, Files.readAllBytes(p.landed.toPath()));
        assertEquals("Done_test.zip", p.landed.getName());
    }

    @Test
    public void builtWhereItLandsTheImportDeletesIt() throws IOException {
        // the old code (058e1e3): built in ATAK's folder, then imported into it
        File built = ToolMetadata.dataPackageFile(datapackages, "Done test");
        Files.write(built.toPath(), bytes);
        importLikeAtak(built, datapackages);
        assertFalse("ATAK's importer deletes a package built in its own folder", built.exists());
    }

    @Test
    public void theStagingFolderCannotBeTheDestinationOrInsideIt() throws IOException {
        for (File bad : new File[] {datapackages, new File(datapackages, "staging")}) {
            try {
                PackageStaging.plan(bad, datapackages, "Done test");
                fail("a staging folder at " + bad + " must be refused");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("ATAK's importer would delete it"));
            }
        }
    }

    @Test
    public void theLandedNameIsNotTakenAndAnExistingPackageIsUntouched() throws IOException {
        File existing = new File(datapackages, "Done_test.zip");
        byte[] old = "an earlier package".getBytes(StandardCharsets.UTF_8);
        Files.write(existing.toPath(), old);
        PackageStaging.sweep(staging);
        PackageStaging p = PackageStaging.plan(staging, datapackages, "Done test");
        assertEquals("Done_test_2.zip", p.landed.getName());
        assertEquals(p.landed.getName(), p.staged.getName());
        Files.write(p.staged.toPath(), bytes);
        importLikeAtak(p.staged, datapackages);
        assertArrayEquals(old, Files.readAllBytes(existing.toPath()));
        assertArrayEquals(bytes, Files.readAllBytes(p.landed.toPath()));
    }

    @Test
    public void sweepMakesTheFolderAndEmptiesIt() throws IOException {
        PackageStaging.sweep(staging);
        assertTrue(staging.isDirectory());
        Files.write(new File(staging, "left_from_a_kill.zip").toPath(), bytes);
        PackageStaging.sweep(staging);
        assertEquals(0, staging.listFiles().length);
    }

    @Test
    public void toolFlowsBuildsOnlyThroughTheStagingPlan() throws IOException {
        String src = toolFlows();
        assertTrue("ToolFlows must plan the build with PackageStaging", src.contains("PackageStaging.plan("));
        assertFalse("ToolFlows must not pick a file in ATAK's folder to build into",
                src.contains("ToolMetadata.dataPackageFile("));
        assertTrue("the manifest's path must be the staged file", src.contains("mpm.setPath(plan.staged.getAbsolutePath())"));
        assertTrue("the staged file is what is imported", src.contains("importer.beginImport(plan.staged,"));
        assertTrue("the landed copy is what is checked (PL-38's lesson)",
                src.contains("ToolRecords.qualifies(plan.landed,"));
    }

    private static String toolFlows() throws IOException {
        String rel = "src" + File.separator + "main" + File.separator + "java";
        for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null; d = d.getParentFile())
            for (File c : new File[] {new File(d, rel), new File(d, "app" + File.separator + rel)}) {
                File f = new File(c, "com/atakmap/android/lingualinkledger/plugin/ToolFlows.java"
                        .replace('/', File.separatorChar));
                if (f.isFile())
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        throw new IllegalStateException("ToolFlows.java not found above " + System.getProperty("user.dir"));
    }
}

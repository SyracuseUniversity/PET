package com.atakmap.android.lingualinkledger.plugin;

import java.io.File;
import java.io.IOException;

/**
 * Where Create/Edit Data Package builds its data package, and where ATAK's
 * importer puts it (PL-42).
 *
 * ATAK's importer first deletes whatever is at its destination, then copies
 * the file there (seen on the S21+, 15 Oct: "Deleted file:
 * atak/tmp/delete_Done_test.zip", then "attempting to copy: X to X"). A
 * package built in the destination folder is therefore deleted by its own
 * import. So the package is built in a staging folder outside that folder,
 * under the name it will have there, one not taken (never over a file), and
 * the importer copies it in, as the SDK's helloworld sample builds outside
 * the folder before importing. Plain Java, so the PC's tests run it.
 */
final class PackageStaging {

    /** The staging folder's name, in ATAK's external cache beside the photos' (D55). */
    static final String FOLDER = "pet-package";

    /** Built here. */
    final File staged;
    /** ATAK's importer copies it here, into its data packages. */
    final File landed;

    private PackageStaging(File staged, File landed) {
        this.staged = staged;
        this.landed = landed;
    }

    /**
     * The staged file and where it lands, for a package named from the form.
     * Refused if the staging folder is the destination or inside it, which
     * is what deleted the package (PL-42).
     */
    static PackageStaging plan(File stagingDir, File destinationDir, String packageName) throws IOException {
        File staging = stagingDir.getCanonicalFile();
        File destination = destinationDir.getCanonicalFile();
        for (File f = staging; f != null; f = f.getParentFile())
            if (f.equals(destination))
                throw new IOException("the package cannot be built in ATAK's data packages folder ("
                        + destination + "): ATAK's importer would delete it");
        File landed = ToolMetadata.dataPackageFile(destination, packageName);
        return new PackageStaging(new File(staging, landed.getName()), landed);
    }

    /** Empties the staging folder, making it if need be: leftovers of a build that never finished go. */
    static void sweep(File stagingDir) throws IOException {
        if (!stagingDir.isDirectory() && !stagingDir.mkdirs())
            throw new IOException("could not make " + stagingDir);
        File[] left = stagingDir.listFiles();
        if (left != null)
            for (File f : left)
                if (f.isFile() && !f.delete())
                    throw new IOException("could not delete " + f);
    }
}

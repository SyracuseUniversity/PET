package com.atakmap.android.lingualinkledger.plugin;

import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;

/**
 * Where the plugin's files are, found through ATAK's FileSystemUtils rather
 * than hard-coded /sdcard paths (PL-06).
 */
final class PetFolders {

    private PetFolders() {
    }

    /** The plugin's own folder: atak/tools/lingualinkledger. */
    static File root() {
        return FileSystemUtils.getItem("tools/lingualinkledger");
    }

    private static LedgerStore ledger;

    /** App v1's records, saved on the phone (D45, V1.7): ledger.json and packages/ in root(). */
    static synchronized LedgerStore ledger() {
        if (ledger == null)
            ledger = new LedgerStore(root());
        return ledger;
    }

    /** ATAK's data package folder: atak/tools/datapackage. */
    static File atakDataPackages() {
        return FileSystemUtils.getItem("tools/datapackage");
    }
}

package com.atakmap.android.lingualinkledger.plugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Replaces a file all at once (the user's requirement 1, Day 2 step 6):
 * the new contents go to <name>.tmp, are flushed to the disk (fsync), and
 * the temporary file is renamed over the old one. On Android (Linux) a
 * rename replaces its target in one step, so whenever ATAK is killed, the
 * file holds either all of the old contents or all of the new, never part
 * of either. A .tmp left by a kill is an unfinished write: whoever reads
 * the file ignores and deletes it.
 */
final class AtomicFile {

    /** How the temporary file is moved over the target. */
    interface Renamer {
        boolean rename(File from, File to);
    }

    /**
     * File.renameTo, which is rename(2) on Android and replaces the target.
     * (On Windows it does not replace an existing file, so the PC's unit
     * tests set a renamer that does; the phone uses this one.)
     */
    static Renamer renamer = File::renameTo;

    private AtomicFile() {
    }

    static File tempFor(File target) {
        return new File(target.getPath() + ".tmp");
    }

    static void write(File target, byte[] contents) throws IOException {
        File tmp = tempFor(target);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(contents);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (!renamer.rename(tmp, target)) {
            tmp.delete();
            throw new IOException("could not move " + tmp.getName() + " over " + target.getName());
        }
    }
}

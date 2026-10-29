package com.atakmap.android.lingualinkledger.plugin;

import java.io.File;

/**
 * One photo of one batch label, as it came back from the camera: the
 * evidence photo itself, the batch ID and how it was read, what the label
 * says besides, and the phone's position at that moment (V1.2: taken when
 * each photo comes back from the camera). Every capture needs a position,
 * GPS or manual (S6).
 */
final class Capture {

    /** How the batch ID was read; the record says which (V1.7). */
    enum IdRead {
        EVIDENCE_PHOTO("evidence_photo"),
        CLOSE_UP("close_up"),
        TYPED("typed");

        final String code;

        IdRead(String code) {
            this.code = code;
        }
    }

    final File photo;
    final String batchId;
    final IdRead idRead;
    /**
     * From the label: TRUE for test=1 (shown as TEST LABEL), FALSE for a
     * real label, null when unknown: a typed ID has no label read to say
     * which (the user, Day 2 step 8).
     */
    final Boolean testLabel;
    /** As printed on the label, or null; never trusted. */
    final String species;
    final long capturedAtMs;
    final PositionPolicy.Check position;

    Capture(File photo, String batchId, IdRead idRead, Boolean testLabel, String species,
            long capturedAtMs, PositionPolicy.Check position) {
        if (!position.usable())
            throw new IllegalArgumentException("a capture needs a position (S6): " + position.message);
        this.photo = photo;
        this.batchId = batchId;
        this.idRead = idRead;
        this.testLabel = testLabel;
        this.species = species;
        this.capturedAtMs = capturedAtMs;
        this.position = position;
    }

    /** From an accepted label read from the evidence photo. */
    static Capture fromLabel(File photo, LabelReader.Label label, long capturedAtMs,
            PositionPolicy.Check position) {
        return new Capture(photo, label.batchId, IdRead.EVIDENCE_PHOTO, label.test, label.species,
                capturedAtMs, position);
    }

    /**
     * From an ID typed by the user, the last fallback when no code can be
     * read (S3): through the same ID rule as a scanned label
     * (LabelReader.normaliseId), or null if that rule refuses it. The photo
     * stays the evidence; the record says the ID was typed; its test
     * status is unknown and it has no species.
     */
    static Capture typed(File photo, String typedText, long capturedAtMs, PositionPolicy.Check position) {
        String id = typedText == null ? null : LabelReader.normaliseId(typedText);
        if (id == null)
            return null;
        return new Capture(photo, id, IdRead.TYPED, null, null, capturedAtMs, position);
    }
}

package com.atakmap.android.lingualinkledger.plugin;

import java.util.List;

/**
 * What one photo of a label gives: exactly one QR code, read as a label
 * (LabelReader), or a plain reason why not. A photo with two or more codes
 * is refused, so a batch ID cannot come from the wrong label (S4).
 */
final class LabelScan {

    enum Outcome {
        /** One code, accepted as a label. */
        LABEL,
        /** One code, refused by the label reader. */
        REFUSED_LABEL,
        /** No code could be read. */
        NO_CODE,
        /** Two or more codes (S4). */
        SEVERAL_CODES
    }

    final Outcome outcome;
    /** The reader's result when exactly one code was found; else null. */
    final LabelReader.Result reading;
    final int codesFound;

    private LabelScan(Outcome outcome, LabelReader.Result reading, int codesFound) {
        this.outcome = outcome;
        this.reading = reading;
        this.codesFound = codesFound;
    }

    /** From the texts QrDecoder found in one photo. */
    static LabelScan of(List<String> texts) {
        if (texts.isEmpty())
            return new LabelScan(Outcome.NO_CODE, null, 0);
        if (texts.size() > 1)
            return new LabelScan(Outcome.SEVERAL_CODES, null, texts.size());
        LabelReader.Result r = LabelReader.read(texts.get(0));
        return new LabelScan(r.accepted() ? Outcome.LABEL : Outcome.REFUSED_LABEL, r, 1);
    }

    /** The accepted label, or null. */
    LabelReader.Label label() {
        return outcome == Outcome.LABEL ? reading.label : null;
    }

    /** What to tell the user when no label was accepted. */
    String problem() {
        switch (outcome) {
            case NO_CODE:
                return "No QR code could be read in this photo. Take it again with the label "
                        + "filling more of the photo, flat, and without glare.";
            case SEVERAL_CODES:
                return "This photo shows " + codesFound + " QR codes. Photograph one label at a time.";
            case REFUSED_LABEL:
                return reading.refusal.message;
            default:
                return null;
        }
    }
}

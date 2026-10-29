package com.atakmap.android.lingualinkledger.plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the text of a batch label's QR code. This is the ONE place that
 * knows the label format, so the real labels' format (scope Q3, still
 * open) can replace it without touching anything else.
 *
 * The format is the user's PROVISIONAL one, PET1 (D52, D53), as written in
 * docs\test-qr\README.txt and PET_Test_QR_Manifest_v1.1.json:
 *
 *     PET1|id=SAR-TEST-0001|species=Red pine|test=1
 *
 *  - "PET1|" is required. "PET" plus other digits, then "|", is an
 *    unsupported version; anything else is not a PET label.
 *  - Fields are key=value, separated by "|". A value is split at its first
 *    "=". Keys must match exactly, and unknown keys are ignored. A field
 *    with no "=", an empty field (a trailing "|" too), an empty key or a
 *    key given twice makes the label malformed.
 *  - id is required and the only field trusted. The space character
 *    (U+0020) is trimmed from both ends, nothing else; ASCII a-z is
 *    upper-cased, and any other non-ASCII character makes the id invalid.
 *    The result must match ^[A-Z0-9][A-Z0-9-]{2,38}[A-Z0-9]$ in full.
 *  - species is kept as printed, and never trusted.
 *  - test: "1" is a simulated label (shown as TEST LABEL), absent or "0" a
 *    real one; any other value is refused.
 */
final class LabelReader {

    /** Why a label was refused; codes as in the test set's manifest. */
    enum Refusal {
        NOT_PET_LABEL("not_pet_label", "This QR code is not a PET batch label."),
        UNSUPPORTED_VERSION("unsupported_version",
                "This label is in a PET label format this app cannot read. It reads PET1 labels."),
        MALFORMED("malformed", "This label's text is damaged or badly formed."),
        MISSING_ID("missing_id", "This label has no batch ID."),
        INVALID_ID("invalid_id", "This label's batch ID is not valid."),
        INVALID_TEST_VALUE("invalid_test_value", "This label's test marker is not valid.");

        final String code;
        final String message;

        Refusal(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    /** What a label says, once accepted. */
    static final class Label {
        /** The batch ID, normalised: the record's key. */
        final String batchId;
        /** test=1: a simulated label, shown as TEST LABEL. */
        final boolean test;
        /** As printed, or null. A convenience copy: recorded, never trusted. */
        final String species;

        Label(String batchId, boolean test, String species) {
            this.batchId = batchId;
            this.test = test;
            this.species = species;
        }
    }

    /** Either a label or the reason it was refused. */
    static final class Result {
        final Label label;
        final Refusal refusal;

        private Result(Label label, Refusal refusal) {
            this.label = label;
            this.refusal = refusal;
        }

        boolean accepted() {
            return label != null;
        }
    }

    static final String PREFIX = "PET1|";
    private static final Pattern OTHER_VERSION = Pattern.compile("^PET[0-9]+\\|.*", Pattern.DOTALL);
    private static final Pattern ID = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,38}[A-Z0-9]");

    private LabelReader() {
    }

    static Result read(String text) {
        if (text == null || !text.startsWith(PREFIX)) {
            return refuse(text != null && OTHER_VERSION.matcher(text).matches()
                    ? Refusal.UNSUPPORTED_VERSION : Refusal.NOT_PET_LABEL);
        }

        Map<String, String> fields = new HashMap<>();
        // limit -1 keeps empty fields, so a trailing "|" is seen
        for (String field : text.substring(PREFIX.length()).split("\\|", -1)) {
            int eq = field.indexOf('=');
            if (eq <= 0 || fields.containsKey(field.substring(0, eq)))
                return refuse(Refusal.MALFORMED); // no "=", empty key or field, or a repeat
            fields.put(field.substring(0, eq), field.substring(eq + 1));
        }

        String rawId = fields.get("id");
        if (rawId == null)
            return refuse(Refusal.MISSING_ID);
        String id = normaliseId(rawId);
        if (id == null)
            return refuse(Refusal.INVALID_ID);

        String test = fields.get("test");
        if (test != null && !test.equals("0") && !test.equals("1"))
            return refuse(Refusal.INVALID_TEST_VALUE);

        return new Result(new Label(id, "1".equals(test), fields.get("species")), null);
    }

    /** The id trimmed of spaces and upper-cased, or null if it is not valid. */
    static String normaliseId(String raw) {
        int start = 0, end = raw.length();
        while (start < end && raw.charAt(start) == ' ')
            start++;
        while (end > start && raw.charAt(end - 1) == ' ')
            end--;
        StringBuilder sb = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = raw.charAt(i);
            if (c > 0x7F)
                return null; // only ASCII; see the class comment
            sb.append(c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
        }
        String id = sb.toString();
        return ID.matcher(id).matches() ? id : null;
    }

    private static Result refuse(Refusal why) {
        return new Result(null, why);
    }
}

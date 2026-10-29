package com.atakmap.android.lingualinkledger.plugin;

/**
 * The four stages a seed batch passes through (D42), as in the
 * architecture diagram. The stage is chosen for each event, not fixed to
 * a person. What each one may follow is in StageRules (S7).
 */
enum Stage {
    COLLECTING("Collecting"),
    CLEANING("Cleaning"),
    STORED("Stored"),
    PLANTING("Planting");

    /** As shown and as written in records. */
    final String label;

    Stage(String label) {
        this.label = label;
    }

    /** The stage with this label; IllegalArgumentException if none. */
    static Stage of(String label) {
        for (Stage s : values())
            if (s.label.equals(label))
                return s;
        throw new IllegalArgumentException("no stage \"" + label + "\"");
    }
}

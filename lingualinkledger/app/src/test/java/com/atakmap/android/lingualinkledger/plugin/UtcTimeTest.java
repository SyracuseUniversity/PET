package com.atakmap.android.lingualinkledger.plugin;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Locale;
import java.util.TimeZone;

/** ISO 8601 UTC with ASCII digits, whatever the phone's language and zone (D43). */
public class UtcTimeTest {

    @Test
    public void formatsInUtc() {
        assertEquals("1970-01-01T00:00:00Z", UtcTime.format(0));
        assertEquals("2026-10-12T20:00:00Z", UtcTime.format(1_791_835_200_000L));
        assertEquals("2026-10-12T20:00:00Z", UtcTime.format(1_791_835_200_999L)); // to the second
    }

    @Test
    public void asciiDigitsAndUtcWhateverTheLocaleAndZone() {
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        try {
            for (String tag : new String[] {"ar-EG", "fa-IR", "hi-IN", "th-TH-u-nu-thai", "de-DE"}) {
                Locale.setDefault(Locale.forLanguageTag(tag));
                TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
                assertEquals(tag, "2026-10-12T20:00:00Z", UtcTime.format(1_791_835_200_000L));
            }
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
        }
    }
}

package com.mendozameteo.x10;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class OfficialAlertTimingTest {
    private static final long NOW = OfficialAlert.parseIsoMillis("2026-08-18T15:00:00-03:00");

    @Test public void futureSmnWindowShowsStartAndEnd() {
        OfficialAlert alert = new OfficialAlert("id", OfficialAlert.Source.SMN_CAP, OfficialAlert.Level.YELLOW,
                "Viento", "Alerta", "", "", "Mendoza", "2026-08-18T14:00:00-03:00",
                "2026-08-19T08:00:00-03:00", "2026-08-19T12:00:00-03:00", false, "");
        String timing = alert.timingText(NOW);
        assertTrue(timing.contains("Inicia 19/08 08:00"));
        assertTrue(timing.contains("hasta 19/08 12:00"));
    }

    @Test public void bulletinWithoutOfficialValidityShowsNoInventedTiming() {
        OfficialAlert bulletin = new OfficialAlert("dcc", OfficialAlert.Source.MENDOZA_DCC, OfficialAlert.Level.INFO,
                "Viento Zonda", "Boletín", "", "", "Mendoza", "2026-08-18T12:00:00-03:00",
                "", "", false, "");
        assertEquals("", bulletin.timingText(NOW));
    }
}

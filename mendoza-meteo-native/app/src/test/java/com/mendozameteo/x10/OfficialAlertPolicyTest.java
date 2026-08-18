package com.mendozameteo.x10;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OfficialAlertPolicyTest {
    private static final long NOW = OfficialAlert.parseIsoMillis("2026-08-18T15:00:00-03:00");

    @Test public void newerDuplicateReplacesOlderVersion() {
        OfficialAlert older = alert("same", OfficialAlert.Level.YELLOW, "2026-08-18T12:00:00-03:00", false, "");
        OfficialAlert newer = alert("same", OfficialAlert.Level.ORANGE, "2026-08-18T14:00:00-03:00", false, "");
        List<OfficialAlert> merged = OfficialAlertRepository.mergeActive(Arrays.asList(older, newer), Collections.emptyList(), NOW);
        assertEquals(1, merged.size());
        assertEquals(OfficialAlert.Level.ORANGE, merged.get(0).level);
    }

    @Test public void cancellationRemovesReferencedAlertEvenWhenInputOrderIsReversed() {
        OfficialAlert original = alert("target-id", OfficialAlert.Level.ORANGE, "2026-08-18T13:00:00-03:00", false, "");
        OfficialAlert cancel = new OfficialAlert("cancel-id", OfficialAlert.Source.SMN_CAP, OfficialAlert.Level.UNKNOWN,
                "Tormenta", "Cancelación", "", "", "Mendoza", "2026-08-18T14:30:00-03:00",
                "2026-08-18T14:30:00-03:00", "2026-08-18T20:00:00-03:00", true,
                "target-id,smn@smn.gov.ar,2026-08-18T13:00:00-03:00");
        assertTrue(OfficialAlertRepository.mergeActive(Arrays.asList(original, cancel), Collections.emptyList(), NOW).isEmpty());
        assertTrue(OfficialAlertRepository.mergeActive(Arrays.asList(cancel, original), Collections.emptyList(), NOW).isEmpty());
    }

    @Test public void provincialBulletinMustBeRecentAndContainRealHazard() {
        String valid = "<html><body><h1>Alerta Meteorológica</h1><p>martes 18 de agosto de 2026</p>"
                + "<p>Viento Zonda moderado a fuerte en precordillera y sectores del llano.</p></body></html>";
        List<OfficialAlert> alerts = MendozaOfficialBulletinSource.parseHtml(valid, NOW);
        assertEquals(1, alerts.size());
        assertEquals(OfficialAlert.Source.MENDOZA_DCC, alerts.get(0).source);
        assertEquals(OfficialAlert.Level.INFO, alerts.get(0).level);
        assertTrue(alerts.get(0).description.toLowerCase().contains("zonda"));

        String headerOnly = "<html><body><h1>Alerta Meteorológica</h1><p>martes 18 de agosto de 2026</p></body></html>";
        assertTrue(MendozaOfficialBulletinSource.parseHtml(headerOnly, NOW).isEmpty());

        String old = valid.replace("18 de agosto de 2026", "10 de agosto de 2026");
        assertTrue(MendozaOfficialBulletinSource.parseHtml(old, NOW).isEmpty());
    }

    @Test public void futureOfficialAlertRemainsAvailableUntilItsOfficialExpiry() {
        OfficialAlert tomorrow = new OfficialAlert("tomorrow", OfficialAlert.Source.SMN_CAP, OfficialAlert.Level.YELLOW,
                "Viento", "Alerta amarilla", "", "", "Mendoza", "2026-08-18T14:00:00-03:00",
                "2026-08-19T08:00:00-03:00", "2026-08-19T12:00:00-03:00", false, "");
        assertTrue(tomorrow.activeAt(NOW));
        assertFalse(tomorrow.startedAt(NOW));
        assertTrue(tomorrow.startedAt(OfficialAlert.parseIsoMillis("2026-08-19T08:00:00-03:00")));
        assertFalse(tomorrow.activeAt(OfficialAlert.parseIsoMillis("2026-08-19T12:00:00-03:00")));
    }

    private static OfficialAlert alert(String id, OfficialAlert.Level level, String sent, boolean cancel, String references) {
        return new OfficialAlert(id, OfficialAlert.Source.SMN_CAP, level, "Tormenta", "Alerta oficial",
                "", "", "Mendoza", sent, "2026-08-18T14:00:00-03:00",
                "2026-08-18T21:00:00-03:00", cancel, references);
    }
}

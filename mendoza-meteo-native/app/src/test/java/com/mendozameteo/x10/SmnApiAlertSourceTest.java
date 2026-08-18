package com.mendozameteo.x10;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class SmnApiAlertSourceTest {
    private static final long NOW = OfficialAlert.parseIsoMillis("2026-08-18T15:00:00-03:00");

    @Test public void extractsTemporaryJwtWithoutBundledCredential() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjk5OTk5OTk5OTl9.signature";
        assertEquals(jwt, SmnApiAlertSource.extractToken("<script>localStorage.setItem('token','" + jwt + "');</script>"));
        assertEquals(jwt, SmnApiAlertSource.extractToken("{\"token\":\"" + jwt + "\"}"));
    }

    @Test public void parsesOfficialLevelsPeriodsAndReportText() throws Exception {
        JSONObject root = new JSONObject("{"
                + "\"area_id\":762,\"updated\":\"2026-08-18T14:30:00-03:00\","
                + "\"warnings\":[{\"date\":\"2026-08-18\",\"events\":["
                + "{\"id\":47,\"max_level\":4,\"levels\":{\"early_morning\":null,\"morning\":null,\"afternoon\":4,\"night\":4}},"
                + "{\"id\":41,\"max_level\":2,\"levels\":{\"early_morning\":null,\"morning\":null,\"afternoon\":2,\"night\":null}}]}],"
                + "\"reports\":["
                + "{\"event_id\":47,\"levels\":[{\"level\":4,\"description\":\"Zonda fuerte\",\"instruction\":\"Asegurá objetos.\"}]},"
                + "{\"event_id\":41,\"levels\":[{\"level\":2,\"description\":\"Tormentas aisladas\",\"instruction\":\"Mantenete informado.\"}]}]}" );
        List<OfficialAlert> alerts = SmnApiAlertSource.parseApi(root, "Capital · Mendoza", NOW);
        assertEquals(2, alerts.size());
        OfficialAlert zonda = find(alerts, "Viento Zonda");
        assertNotNull(zonda);
        assertEquals(OfficialAlert.Source.SMN_API, zonda.source);
        assertEquals(OfficialAlert.Level.ORANGE, zonda.level);
        assertEquals("2026-08-18T12:00:00-03:00", zonda.startIso);
        assertEquals("2026-08-19T00:00:00-03:00", zonda.expiresIso);
        assertTrue(zonda.description.contains("Zonda"));

        OfficialAlert storm = find(alerts, "Tormenta");
        assertNotNull(storm);
        assertEquals(OfficialAlert.Level.ADVISORY, storm.level);
        assertEquals("2026-08-18T12:00:00-03:00", storm.startIso);
        assertEquals("2026-08-18T18:00:00-03:00", storm.expiresIso);
    }

    @Test public void levelOneEventsAreNotSurfacedAsAlerts() throws Exception {
        JSONObject root = new JSONObject("{\"updated\":\"2026-08-18T14:00:00-03:00\",\"warnings\":[{\"date\":\"2026-08-18\",\"events\":[{\"id\":37,\"max_level\":1,\"levels\":{\"afternoon\":1}}]}],\"reports\":[]}");
        assertTrue(SmnApiAlertSource.parseApi(root, "Mendoza", NOW).isEmpty());
    }

    @Test public void expiredPeriodDoesNotRemainOfficiallyActive() throws Exception {
        JSONObject root = new JSONObject("{\"updated\":\"2026-08-18T08:00:00-03:00\",\"warnings\":[{\"date\":\"2026-08-18\",\"events\":[{\"id\":37,\"max_level\":3,\"levels\":{\"early_morning\":3,\"morning\":null,\"afternoon\":null,\"night\":null}}]}],\"reports\":[]}");
        assertTrue(SmnApiAlertSource.parseApi(root, "Mendoza", NOW).isEmpty());
    }

    private static OfficialAlert find(List<OfficialAlert> alerts, String event) {
        for (OfficialAlert alert : alerts) if (event.equals(alert.event)) return alert;
        return null;
    }
}

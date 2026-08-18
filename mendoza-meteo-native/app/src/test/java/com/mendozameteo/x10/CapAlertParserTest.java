package com.mendozameteo.x10;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CapAlertParserTest {
    private static final long NOW = OfficialAlert.parseIsoMillis("2026-08-18T15:00:00-03:00");

    @Test public void parsesExplicitOrangeZondaInsidePolygon() throws Exception {
        String xml = cap("id-1", "Alert", "Viento Zonda - AVISO NARANJA",
                "2026-08-18T14:00:00-03:00", "2026-08-18T19:00:00-03:00",
                "Mendoza: Capital - Godoy Cruz", "-33.2,-69.1 -33.2,-68.5 -32.6,-68.5 -32.6,-69.1 -33.2,-69.1");
        List<OfficialAlert> alerts = CapAlertParser.parse(xml, -32.89, -68.84, NOW);
        assertEquals(1, alerts.size());
        OfficialAlert alert = alerts.get(0);
        assertEquals(OfficialAlert.Source.SMN_CAP, alert.source);
        assertEquals(OfficialAlert.Level.ORANGE, alert.level);
        assertEquals("Viento Zonda", alert.event);
        assertTrue(alert.activeAt(NOW));
    }

    @Test public void polygonPreventsProvinceWideFalsePositive() throws Exception {
        String xml = cap("id-2", "Alert", "Tormenta - AVISO AMARILLO",
                "2026-08-18T14:00:00-03:00", "2026-08-18T20:00:00-03:00",
                "Mendoza: San Rafael", "-35.0,-69.2 -35.0,-68.2 -34.0,-68.2 -34.0,-69.2 -35.0,-69.2");
        assertTrue(CapAlertParser.parse(xml, -32.89, -68.84, NOW).isEmpty());
    }

    @Test public void expiredAlertIsDiscarded() throws Exception {
        String xml = cap("id-3", "Alert", "Lluvia - AVISO AMARILLO",
                "2026-08-18T10:00:00-03:00", "2026-08-18T14:00:00-03:00",
                "Mendoza", "-33.2,-69.1 -33.2,-68.5 -32.6,-68.5 -32.6,-69.1 -33.2,-69.1");
        assertTrue(CapAlertParser.parse(xml, -32.89, -68.84, NOW).isEmpty());
    }

    @Test public void severeCapWordDoesNotInventSmnColor() throws Exception {
        String xml = capWithSeverity("id-4", "Alert", "Tormentas fuertes",
                "Severe", "2026-08-18T14:00:00-03:00", "2026-08-18T20:00:00-03:00",
                "Mendoza", "-33.2,-69.1 -33.2,-68.5 -32.6,-68.5 -32.6,-69.1 -33.2,-69.1");
        List<OfficialAlert> alerts = CapAlertParser.parse(xml, -32.89, -68.84, NOW);
        assertEquals(1, alerts.size());
        assertEquals(OfficialAlert.Level.UNKNOWN, alerts.get(0).level);
    }

    @Test public void fallbackAreaDescriptionUsesOperationalZoneConservatively() {
        assertTrue(CapAlertParser.fallbackAreaMatch("MENDOZA: CAPITAL - GODOY CRUZ", -32.89, -68.84));
        assertFalse(CapAlertParser.fallbackAreaMatch("MENDOZA: SAN RAFAEL - MALARGÜE", -32.89, -68.84));
        assertTrue(CapAlertParser.fallbackAreaMatch("Provincia de Mendoza", -32.89, -68.84));
    }

    @Test public void extractsOnlyHttpsXmlLinks() {
        String feed = "<a href='https://ssl.smn.gob.ar/feeds/CAP/a.xml'>a</a>"
                + "<a href='/feeds/CAP/b.xml'>b</a><a href='http://bad.test/c.xml'>c</a>";
        List<String> links = CapAlertParser.extractXmlLinks(feed, "https://ssl.smn.gob.ar/CAP/AR.php");
        assertEquals(2, links.size());
        assertTrue(links.get(0).startsWith("https://"));
        assertTrue(links.get(1).startsWith("https://"));
    }

    private static String cap(String id, String msgType, String headline, String onset,
                              String expires, String area, String polygon) {
        return capWithSeverity(id, msgType, headline, "Moderate", onset, expires, area, polygon);
    }

    private static String capWithSeverity(String id, String msgType, String headline, String severity,
                                          String onset, String expires, String area, String polygon) {
        return "<?xml version='1.0' encoding='UTF-8'?>"
                + "<alert xmlns='urn:oasis:names:tc:emergency:cap:1.2'>"
                + "<identifier>" + id + "</identifier><sender>smn@smn.gov.ar</sender>"
                + "<sent>2026-08-18T13:50:00-03:00</sent><status>Actual</status><msgType>" + msgType + "</msgType><scope>Public</scope>"
                + "<info><language>es-AR</language><category>Met</category><event>Viento Zonda</event>"
                + "<urgency>Expected</urgency><severity>" + severity + "</severity><certainty>Likely</certainty>"
                + "<onset>" + onset + "</onset><expires>" + expires + "</expires>"
                + "<headline>" + headline + "</headline><description>Descripción oficial</description>"
                + "<instruction>Seguí las indicaciones oficiales.</instruction>"
                + "<area><areaDesc>" + area + "</areaDesc><polygon>" + polygon + "</polygon></area>"
                + "</info></alert>";
    }
}

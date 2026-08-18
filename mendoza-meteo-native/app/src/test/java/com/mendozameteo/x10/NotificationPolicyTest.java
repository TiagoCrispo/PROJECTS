package com.mendozameteo.x10;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NotificationPolicyTest {
    private static final long NOW = OfficialAlert.parseIsoMillis("2026-08-18T16:00:00-03:00");

    @Test public void newOfficialAlertNotifies() {
        assertEquals(NotificationPolicy.OfficialChange.NEW,
                NotificationPolicy.officialChange(null, official(OfficialAlert.Level.YELLOW, "Tormenta", "A"), NOW));
    }

    @Test public void identicalOfficialAlertIsSuppressed() {
        OfficialAlert current = official(OfficialAlert.Level.YELLOW, "Tormenta", "A");
        NotificationPolicy.PreviousOfficial previous = previous(current, NOW - 60_000L);
        assertEquals(NotificationPolicy.OfficialChange.NONE,
                NotificationPolicy.officialChange(previous, current, NOW));
    }

    @Test public void escalationBypassesCooldown() {
        OfficialAlert before = official(OfficialAlert.Level.YELLOW, "Tormenta", "A");
        OfficialAlert current = official(OfficialAlert.Level.ORANGE, "Tormenta", "B");
        NotificationPolicy.PreviousOfficial previous = previous(before, NOW - 60_000L);
        assertEquals(NotificationPolicy.OfficialChange.ESCALATION,
                NotificationPolicy.officialChange(previous, current, NOW));
    }

    @Test public void sameLevelImportantUpdateWaitsForCooldown() {
        OfficialAlert before = official(OfficialAlert.Level.YELLOW, "Tormenta", "A");
        OfficialAlert current = official(OfficialAlert.Level.YELLOW, "Tormenta", "B");
        NotificationPolicy.PreviousOfficial recent = previous(before, NOW - 10L * 60L * 1000L);
        NotificationPolicy.PreviousOfficial old = previous(before, NOW - 31L * 60L * 1000L);
        assertEquals(NotificationPolicy.OfficialChange.NONE,
                NotificationPolicy.officialChange(recent, current, NOW));
        assertEquals(NotificationPolicy.OfficialChange.IMPORTANT_UPDATE,
                NotificationPolicy.officialChange(old, current, NOW));
    }

    @Test public void x10OnlyNotifiesImportantOrDanger() {
        AlertEngine.Event precaution = event(AlertEngine.Kind.RAIN, AlertEngine.Severity.PRECAUTION);
        AlertEngine.Event important = event(AlertEngine.Kind.RAIN, AlertEngine.Severity.IMPORTANT);
        assertFalse(NotificationPolicy.shouldNotifyX10(null, precaution, NOW));
        assertTrue(NotificationPolicy.shouldNotifyX10(null, important, NOW));
    }

    @Test public void officialEventSuppressesDuplicateHeuristicFamily() {
        OfficialAlert zonda = official(OfficialAlert.Level.YELLOW, "Viento Zonda", "A");
        assertTrue(NotificationPolicy.officialCoversX10(Collections.singletonList(zonda),
                event(AlertEngine.Kind.ZONDA, AlertEngine.Severity.DANGER)));
        assertFalse(NotificationPolicy.officialCoversX10(Collections.singletonList(zonda),
                event(AlertEngine.Kind.RAIN, AlertEngine.Severity.IMPORTANT)));
    }

    @Test public void smnCapAndApiCanBeRecognizedAsSameFamily() {
        OfficialAlert current = new OfficialAlert("api", OfficialAlert.Source.SMN_API, OfficialAlert.Level.YELLOW,
                "Tormenta", "", "", "", "Mendoza", "2026-08-18T15:00:00-03:00",
                "2026-08-18T17:00:00-03:00", "2026-08-18T22:00:00-03:00", false, "");
        NotificationPolicy.PreviousOfficial previous = new NotificationPolicy.PreviousOfficial(
                "old", "cap", OfficialAlert.Source.SMN_CAP, OfficialAlert.Level.YELLOW.rank, 1,
                NOW - 60_000L, OfficialAlert.parseIsoMillis("2026-08-18T22:00:00-03:00"), 10,
                "Tormenta", OfficialAlert.parseIsoMillis("2026-08-18T17:30:00-03:00"));
        assertTrue(NotificationPolicy.sameSmnFamily(previous, current));
    }

    private static NotificationPolicy.PreviousOfficial previous(OfficialAlert alert, long notifiedAt) {
        return new NotificationPolicy.PreviousOfficial("k", alert.id, alert.source, alert.level.rank,
                NotificationPolicy.officialContentHash(alert), notifiedAt, alert.expiresMillis, 42,
                alert.event, alert.startMillis);
    }

    private static OfficialAlert official(OfficialAlert.Level level, String event, String description) {
        return new OfficialAlert("id", OfficialAlert.Source.SMN_CAP, level, event, "Alerta oficial",
                description, "Seguí las indicaciones oficiales", "Mendoza", "2026-08-18T15:00:00-03:00",
                "2026-08-18T16:00:00-03:00", "2026-08-18T22:00:00-03:00", false, "");
    }

    private static AlertEngine.Event event(AlertEngine.Kind kind, AlertEngine.Severity severity) {
        return new AlertEngine.Event(kind, severity, AlertEngine.Source.HEURISTIC_X10,
                "2026-08-18T17:00", "2026-08-18T19:00", 2, 70, 1.2, 2.4, 55, 8);
    }
}

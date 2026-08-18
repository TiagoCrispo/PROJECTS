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

    @Test public void deescalationAlsoUpdatesImmediatelySoOldSeverityCannotLinger() {
        OfficialAlert before = official(OfficialAlert.Level.ORANGE, "Tormenta", "A");
        OfficialAlert current = official(OfficialAlert.Level.YELLOW, "Tormenta", "A");
        NotificationPolicy.PreviousOfficial previous = previous(before, NOW - 60_000L);
        assertEquals(NotificationPolicy.OfficialChange.DEESCALATION,
                NotificationPolicy.officialChange(previous, current, NOW));
    }

    @Test public void sameLevelImportantTextUpdateWaitsForCooldown() {
        OfficialAlert before = official(OfficialAlert.Level.YELLOW, "Tormenta", "A");
        OfficialAlert current = official(OfficialAlert.Level.YELLOW, "Tormenta", "B");
        NotificationPolicy.PreviousOfficial recent = previous(before, NOW - 10L * 60L * 1000L);
        NotificationPolicy.PreviousOfficial old = previous(before, NOW - 31L * 60L * 1000L);
        assertEquals(NotificationPolicy.OfficialChange.NONE,
                NotificationPolicy.officialChange(recent, current, NOW));
        assertEquals(NotificationPolicy.OfficialChange.IMPORTANT_UPDATE,
                NotificationPolicy.officialChange(old, current, NOW));
    }

    @Test public void officialExpiryChangeUpdatesImmediatelyInsideTextCooldown() {
        OfficialAlert before = officialWindow("2026-08-18T16:00:00-03:00", "2026-08-18T22:00:00-03:00");
        OfficialAlert extended = officialWindow("2026-08-18T16:00:00-03:00", "2026-08-18T23:30:00-03:00");
        NotificationPolicy.PreviousOfficial previous = previous(before, NOW - 60_000L);
        assertEquals(NotificationPolicy.OfficialChange.IMPORTANT_UPDATE,
                NotificationPolicy.officialChange(previous, extended, NOW));
    }

    @Test public void officialStartChangeUpdatesImmediatelyInsideTextCooldown() {
        OfficialAlert before = officialWindow("2026-08-18T17:00:00-03:00", "2026-08-18T22:00:00-03:00");
        OfficialAlert moved = officialWindow("2026-08-18T18:00:00-03:00", "2026-08-18T22:00:00-03:00");
        NotificationPolicy.PreviousOfficial previous = previous(before, NOW - 60_000L);
        assertEquals(NotificationPolicy.OfficialChange.IMPORTANT_UPDATE,
                NotificationPolicy.officialChange(previous, moved, NOW));
    }

    @Test public void untimedOfficialDoesNotInventTimingUpdate() {
        OfficialAlert untimed = new OfficialAlert("dcc", OfficialAlert.Source.MENDOZA_DCC, OfficialAlert.Level.INFO,
                "Viento Zonda", "Boletín oficial Mendoza", "Zonda", "", "Mendoza",
                "2026-08-18T15:00:00-03:00", "", "", false, "");
        NotificationPolicy.PreviousOfficial previous = previous(untimed, NOW - 60_000L);
        assertEquals(NotificationPolicy.OfficialChange.NONE,
                NotificationPolicy.officialChange(previous, untimed, NOW));
    }

    @Test public void x10OnlyNotifiesImportantOrDanger() {
        AlertEngine.Event precaution = event(AlertEngine.Kind.RAIN, AlertEngine.Severity.PRECAUTION);
        AlertEngine.Event important = event(AlertEngine.Kind.RAIN, AlertEngine.Severity.IMPORTANT);
        assertFalse(NotificationPolicy.shouldNotifyX10(null, precaution, NOW));
        assertTrue(NotificationPolicy.shouldNotifyX10(null, important, NOW));
    }

    @Test public void identicalX10EpisodeNeverTurnsIntoThreeHourReminderSpam() {
        AlertEngine.Event current = event(AlertEngine.Kind.ZONDA, AlertEngine.Severity.IMPORTANT);
        AlertCooldownPolicy.Previous previous = AlertCooldownPolicy.Previous.from(current,
                NOW - 5L * 60L * 60L * 1000L);
        assertFalse(NotificationPolicy.shouldNotifyX10(previous, current, NOW));
    }

    @Test public void x10EscalationStillNotifiesImmediately() {
        AlertEngine.Event before = event(AlertEngine.Kind.ZONDA, AlertEngine.Severity.IMPORTANT);
        AlertEngine.Event danger = event(AlertEngine.Kind.ZONDA, AlertEngine.Severity.DANGER);
        AlertCooldownPolicy.Previous previous = AlertCooldownPolicy.Previous.from(before, NOW - 60_000L);
        assertTrue(NotificationPolicy.shouldNotifyX10(previous, danger, NOW));
    }

    @Test public void sufficientlyShiftedX10WindowIsANewEpisode() {
        AlertEngine.Event before = event(AlertEngine.Kind.RAIN, AlertEngine.Severity.IMPORTANT,
                "2026-08-18T17:00", "2026-08-18T19:00");
        AlertEngine.Event later = event(AlertEngine.Kind.RAIN, AlertEngine.Severity.IMPORTANT,
                "2026-08-18T21:00", "2026-08-18T23:00");
        AlertCooldownPolicy.Previous previous = AlertCooldownPolicy.Previous.from(before, NOW - 60_000L);
        assertTrue(NotificationPolicy.shouldNotifyX10(previous, later, NOW));
    }

    @Test public void officialEventSuppressesDuplicateHeuristicFamilyOnlyWhenTimeOverlaps() {
        OfficialAlert zonda = officialWindow("2026-08-18T16:30:00-03:00", "2026-08-18T20:00:00-03:00");
        assertTrue(NotificationPolicy.officialCoversX10(Collections.singletonList(zonda),
                event(AlertEngine.Kind.ZONDA, AlertEngine.Severity.DANGER)));
        assertFalse(NotificationPolicy.officialCoversX10(Collections.singletonList(zonda),
                event(AlertEngine.Kind.RAIN, AlertEngine.Severity.IMPORTANT)));
    }

    @Test public void tomorrowOfficialAlertDoesNotHideTonightX10Episode() {
        OfficialAlert tomorrow = new OfficialAlert("tomorrow", OfficialAlert.Source.SMN_CAP, OfficialAlert.Level.YELLOW,
                "Viento Zonda", "Alerta amarilla", "Zonda", "", "Mendoza",
                "2026-08-18T15:00:00-03:00", "2026-08-19T08:00:00-03:00",
                "2026-08-19T12:00:00-03:00", false, "");
        assertFalse(NotificationPolicy.officialCoversX10(Collections.singletonList(tomorrow),
                event(AlertEngine.Kind.ZONDA, AlertEngine.Severity.IMPORTANT)));
    }

    @Test public void smnCapAndApiCanBeRecognizedAsSameFamily() {
        OfficialAlert current = new OfficialAlert("api", OfficialAlert.Source.SMN_API, OfficialAlert.Level.YELLOW,
                "Tormenta", "", "", "", "Mendoza", "2026-08-18T15:00:00-03:00",
                "2026-08-18T17:00:00-03:00", "2026-08-18T22:00:00-03:00", false, "");
        NotificationPolicy.PreviousOfficial previous = new NotificationPolicy.PreviousOfficial(
                "old", "cap", OfficialAlert.Source.SMN_CAP, OfficialAlert.Level.YELLOW.rank, 1,
                NOW - 60_000L, OfficialAlert.parseIsoMillis("2026-08-18T22:00:00-03:00"),
                OfficialAlert.parseIsoMillis("2026-08-18T22:00:00-03:00"), 10,
                "Tormenta", OfficialAlert.parseIsoMillis("2026-08-18T17:30:00-03:00"));
        assertTrue(NotificationPolicy.sameSmnFamily(previous, current));
    }

    private static NotificationPolicy.PreviousOfficial previous(OfficialAlert alert, long notifiedAt) {
        long staleAt = alert.expiresMillis > 0L
                ? alert.expiresMillis
                : (alert.sentMillis > 0L ? alert.sentMillis : notifiedAt) + 36L * 60L * 60L * 1000L;
        return new NotificationPolicy.PreviousOfficial("k", alert.id, alert.source, alert.level.rank,
                NotificationPolicy.officialContentHash(alert), notifiedAt, staleAt, alert.expiresMillis, 42,
                alert.event, alert.startMillis);
    }

    private static OfficialAlert official(OfficialAlert.Level level, String event, String description) {
        return new OfficialAlert("id", OfficialAlert.Source.SMN_CAP, level, event, "Alerta oficial",
                description, "Seguí las indicaciones oficiales", "Mendoza", "2026-08-18T15:00:00-03:00",
                "2026-08-18T16:00:00-03:00", "2026-08-18T22:00:00-03:00", false, "");
    }

    private static OfficialAlert officialWindow(String start, String end) {
        return new OfficialAlert("id", OfficialAlert.Source.SMN_CAP, OfficialAlert.Level.YELLOW,
                "Viento Zonda", "Alerta oficial", "Zonda", "Seguí las indicaciones oficiales", "Mendoza",
                "2026-08-18T15:00:00-03:00", start, end, false, "");
    }

    private static AlertEngine.Event event(AlertEngine.Kind kind, AlertEngine.Severity severity) {
        return event(kind, severity, "2026-08-18T17:00", "2026-08-18T19:00");
    }

    private static AlertEngine.Event event(AlertEngine.Kind kind, AlertEngine.Severity severity,
                                           String start, String end) {
        return new AlertEngine.Event(kind, severity, AlertEngine.Source.HEURISTIC_X10,
                start, end, 2, 70, 1.2, 2.4, 55, 8);
    }
}

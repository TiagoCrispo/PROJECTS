package com.mendozameteo.x10;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class AlertEngineTest {
    @Test public void probabilityOnlyDoesNotCreateFalseRainAlert() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(2, hour(2, 26, 90, 20, 30, 180, 45, 0, 0.0, 12.0));
        AlertEngine.Report report = AlertEngine.analyze(forecast(hours, 10));
        assertFalse(report.hasHazards());
    }

    @Test public void pastDailyMaximumIsIgnoredByFutureEngine() {
        AlertEngine.Report report = AlertEngine.analyze(forecast(clearHours(), 100));
        assertFalse(report.hasHazards());
    }

    @Test public void measurableFutureRainHasContiguousWindow() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(1, hour(1, 20, 70, 18, 30, 180, 55, 61, 1.0, 10.0));
        hours.set(2, hour(2, 19, 80, 20, 35, 190, 60, 63, 2.0, 9.0));
        AlertEngine.Event event = onlyKind(AlertEngine.analyze(forecast(hours, 20)), AlertEngine.Kind.RAIN);
        assertNotNull(event);
        assertEquals("2026-08-18T01:00", event.startIso);
        assertEquals("2026-08-18T03:00", event.endExclusiveIso);
        assertEquals(2, event.durationHours);
        assertEquals(80, event.peakProbability);
        assertEquals(AlertEngine.Source.HEURISTIC_X10, event.source);
    }

    @Test public void separateRainWindowsAreNotMergedAcrossGap() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(1, hour(1, 20, 70, 15, 25, 180, 50, 61, 1.0, 10.0));
        hours.set(3, hour(3, 20, 75, 15, 25, 180, 50, 61, 1.0, 10.0));
        AlertEngine.Report report = AlertEngine.analyze(forecast(hours, 20));
        int rainEvents = 0;
        for (AlertEngine.Event event : report.events) if (event.kind == AlertEngine.Kind.RAIN) rainEvents++;
        assertEquals(2, rainEvents);
    }

    @Test public void thunderstormIsSeparateKindAndAtLeastImportant() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(4, hour(4, 23, 75, 35, 65, 220, 55, 95, 3.0, 11.0));
        AlertEngine.Event event = onlyKind(AlertEngine.analyze(forecast(hours, 20)), AlertEngine.Kind.THUNDERSTORM);
        assertNotNull(event);
        assertTrue(event.severity.rank >= AlertEngine.Severity.IMPORTANT.rank);
    }

    @Test public void measuredRainStillWorksWhenProviderHasNoProbability() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(5, hour(5, 18, -1, 15, 25, 180, 60, 61, 1.2, 8.0));
        AlertEngine.Event event = onlyKind(AlertEngine.analyze(forecast(hours, -1)), AlertEngine.Kind.RAIN);
        assertNotNull(event);
        assertEquals(-1, event.peakProbability);
        assertFalse(event.detailText().contains("-%"));
    }

    @Test public void persistentDryWestWindCreatesZondaSignalWithoutFakeProbability() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(6, hour(6, 27, 10, 35, 70, 275, 20, 1, 0.0, 5.0));
        hours.set(7, hour(7, 29, 10, 40, 75, 285, 18, 1, 0.0, 4.0));
        AlertEngine.Event event = onlyKind(AlertEngine.analyze(forecast(hours, 10)), AlertEngine.Kind.ZONDA);
        assertNotNull(event);
        assertEquals(AlertEngine.Source.HEURISTIC_X10, event.source);
        assertTrue(event.detailText().startsWith("Posible Zonda"));
        assertTrue(event.detailText().contains("Gran Mendoza"));
        assertEquals(-1, event.peakProbability);
        assertFalse(event.detailText().toLowerCase().contains("probabilidad"));
    }

    @Test public void rainAndWetWeatherSuppressZondaClassification() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(6, hour(6, 27, 90, 35, 75, 275, 20, 61, 1.0, 5.0));
        hours.set(7, hour(7, 28, 80, 40, 80, 285, 18, 63, 1.2, 4.0));
        AlertEngine.Report report = AlertEngine.analyze(forecast(hours, 80));
        assertNotNull(onlyKind(report, AlertEngine.Kind.RAIN));
        assertEquals(null, onlyKind(report, AlertEngine.Kind.ZONDA));
    }

    @Test public void oneWeakDryWestHourDoesNotCreateZondaAlert() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(8, hour(8, 22, 10, 25, 50, 270, 30, 1, 0.0, 10.0));
        assertEquals(null, onlyKind(AlertEngine.analyze(forecast(hours, 10)), AlertEngine.Kind.ZONDA));
    }

    @Test public void humidWestWindIsNotZondaSignal() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(6, hour(6, 24, 10, 35, 75, 280, 62, 1, 0.0, 18.0));
        hours.set(7, hour(7, 24, 10, 40, 80, 285, 58, 1, 0.0, 17.0));
        assertEquals(null, onlyKind(AlertEngine.analyze(forecast(hours, 10)), AlertEngine.Kind.ZONDA));
    }

    @Test public void eastModerateSignalRequiresThreeHoursPersistence() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(6, hour(6, 27, 10, 30, 60, 280, 20, 1, 0.0, 5.0));
        hours.set(7, hour(7, 28, 10, 32, 60, 285, 20, 1, 0.0, 5.0));
        assertEquals(null, onlyKind(AlertEngine.analyze(forecast(hours, 10, -33.00, -68.20)), AlertEngine.Kind.ZONDA));

        hours.set(8, hour(8, 29, 10, 33, 60, 290, 18, 1, 0.0, 4.0));
        AlertEngine.Event event = onlyKind(AlertEngine.analyze(forecast(hours, 10, -33.00, -68.20)), AlertEngine.Kind.ZONDA);
        assertNotNull(event);
        assertEquals(3, event.durationHours);
        assertEquals("Zona Este", event.zoneLabel);
    }

    @Test public void extremeSingleHourCanBecomeDangerWithoutPretendingOfficialAlert() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(9, hour(9, 31, 5, 52, 96, 280, 14, 1, 0.0, 0.0));
        AlertEngine.Event event = onlyKind(AlertEngine.analyze(forecast(hours, 10)), AlertEngine.Kind.ZONDA);
        assertNotNull(event);
        assertEquals(AlertEngine.Severity.DANGER, event.severity);
        assertEquals(AlertEngine.Source.HEURISTIC_X10, event.source);
        assertFalse(event.detailText().contains("SMN"));
    }

    @Test public void thermalAndHumidityTrendAreKeptAsSupportingEvidence() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(5, hour(5, 20, 10, 10, 25, 180, 50, 1, 0.0, 12.0));
        hours.set(6, hour(6, 26, 10, 35, 68, 275, 25, 1, 0.0, 4.0));
        hours.set(7, hour(7, 29, 10, 40, 72, 285, 18, 1, 0.0, 3.0));
        AlertEngine.Event event = onlyKind(AlertEngine.analyze(forecast(hours, 10)), AlertEngine.Kind.ZONDA);
        assertNotNull(event);
        assertTrue(event.temperatureRise >= 9);
        assertTrue(event.humidityDrop >= 30);
        assertTrue(event.evidenceScore >= 9);
        assertTrue(event.detailText().contains("+T"));
        assertTrue(event.detailText().contains("HR -"));
    }

    @Test public void gustCalibrationKeepsInternalSeveritySeparateFromOfficialColors() {
        List<WeatherClient.Hour> hours = clearHours();
        hours.set(6, hour(6, 27, 10, 30, 64, 275, 20, 1, 0.0, 5.0));
        hours.set(7, hour(7, 28, 10, 32, 64, 280, 20, 1, 0.0, 5.0));
        AlertEngine.Event lower = onlyKind(AlertEngine.analyze(forecast(hours, 10)), AlertEngine.Kind.ZONDA);
        assertNotNull(lower);
        assertEquals(AlertEngine.Severity.PRECAUTION, lower.severity);

        hours.set(7, hour(7, 28, 10, 35, 65, 280, 20, 1, 0.0, 5.0));
        AlertEngine.Event stronger = onlyKind(AlertEngine.analyze(forecast(hours, 10)), AlertEngine.Kind.ZONDA);
        assertNotNull(stronger);
        assertEquals(AlertEngine.Severity.IMPORTANT, stronger.severity);
        assertFalse(stronger.severity.label.toLowerCase().contains("naranja"));
    }

    @Test public void cooldownSuppressesRepeatButAllowsEscalationAndDistinctWindow() {
        AlertEngine.Event first = event(AlertEngine.Kind.RAIN, AlertEngine.Severity.PRECAUTION, "2026-08-18T14:00");
        AlertCooldownPolicy.Previous previous = AlertCooldownPolicy.Previous.from(first, 1_000L);
        AlertEngine.Event same = event(AlertEngine.Kind.RAIN, AlertEngine.Severity.PRECAUTION, "2026-08-18T15:00");
        assertFalse(AlertCooldownPolicy.shouldNotify(previous, same, 2_000L));

        AlertEngine.Event escalated = event(AlertEngine.Kind.RAIN, AlertEngine.Severity.IMPORTANT, "2026-08-18T15:00");
        assertTrue(AlertCooldownPolicy.shouldNotify(previous, escalated, 2_000L));

        AlertEngine.Event laterWindow = event(AlertEngine.Kind.RAIN, AlertEngine.Severity.PRECAUTION, "2026-08-18T18:00");
        assertTrue(AlertCooldownPolicy.shouldNotify(previous, laterWindow, 2_000L));

        assertTrue(AlertCooldownPolicy.shouldNotify(previous, same,
                1_000L + AlertCooldownPolicy.DEFAULT_COOLDOWN_MILLIS));
    }

    private static AlertEngine.Event event(AlertEngine.Kind kind, AlertEngine.Severity severity, String start) {
        return new AlertEngine.Event(kind, severity, AlertEngine.Source.HEURISTIC_X10,
                start, start, 1, 70, 1.0, 1.0, 50, 0);
    }

    private static AlertEngine.Event onlyKind(AlertEngine.Report report, AlertEngine.Kind kind) {
        for (AlertEngine.Event event : report.events) if (event.kind == kind) return event;
        return null;
    }

    private static List<WeatherClient.Hour> clearHours() {
        ArrayList<WeatherClient.Hour> result = new ArrayList<>();
        for (int i = 0; i < 24; i++) result.add(hour(i, 20, 10, 15, 30, 180, 45, 1, 0.0, 12.0));
        return result;
    }

    private static WeatherClient.Hour hour(int hour, int temp, int probability, int wind, int gust,
                                           int direction, int humidity, int code, double precipitation,
                                           double dewPoint) {
        String hh = hour < 10 ? "0" + hour : Integer.toString(hour);
        return new WeatherClient.Hour("2026-08-18T" + hh + ":00", temp, temp,
                probability, wind, gust, direction, humidity, code, 20,
                precipitation, precipitation, 0.0, 0.0, dewPoint, 1015.0, 20000.0);
    }

    private static WeatherClient.Forecast forecast(List<WeatherClient.Hour> hours, int dailyRainProbability) {
        return forecast(hours, dailyRainProbability, -32.89, -68.84);
    }

    private static WeatherClient.Forecast forecast(List<WeatherClient.Hour> hours, int dailyRainProbability,
                                                    double latitude, double longitude) {
        WeatherClient.Current current = new WeatherClient.Current(20, 20, 45, 15, 30, 180, 1,
                10, 20, 0.0, 0.0, 12.0, 1015.0, 20000.0);
        ArrayList<WeatherClient.Day> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            days.add(new WeatherClient.Day("2026-08-" + (18 + i), i == 0 ? "Hoy" : "D" + i,
                    25, 10, dailyRainProbability, 20, 35, 180, 1,
                    dailyRainProbability >= 60 ? 10.0 : 0.0, 0.0, 0.0));
        }
        return new WeatherClient.Forecast("test", "test", WeatherClient.MENDOZA_TZ_ID,
                "2026-08-18T00:00", 1L, latitude, longitude, current, hours, days);
    }
}

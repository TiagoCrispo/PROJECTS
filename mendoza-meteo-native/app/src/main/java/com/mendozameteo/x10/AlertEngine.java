package com.mendozameteo.x10;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class AlertEngine {
    enum Kind { RAIN, THUNDERSTORM, ZONDA }
    enum Severity {
        PRECAUTION(1, "Precaución"),
        IMPORTANT(2, "Importante"),
        DANGER(3, "Peligro");

        final int rank;
        final String label;
        Severity(int rank, String label) { this.rank = rank; this.label = label; }
    }
    enum Source { HEURISTIC_X10, OFFICIAL_SMN }

    static final class Event {
        final Kind kind;
        final Severity severity;
        final Source source;
        final String startIso;
        final String endExclusiveIso;
        final int durationHours;
        final int peakProbability;
        final double peakHourlyPrecipitation;
        final double totalPrecipitation;
        final int peakGust;
        final int evidenceScore;

        Event(Kind kind, Severity severity, Source source, String startIso, String endExclusiveIso,
              int durationHours, int peakProbability, double peakHourlyPrecipitation,
              double totalPrecipitation, int peakGust, int evidenceScore) {
            this.kind = kind;
            this.severity = severity;
            this.source = source;
            this.startIso = startIso;
            this.endExclusiveIso = endExclusiveIso;
            this.durationHours = durationHours;
            this.peakProbability = peakProbability;
            this.peakHourlyPrecipitation = peakHourlyPrecipitation;
            this.totalPrecipitation = totalPrecipitation;
            this.peakGust = peakGust;
            this.evidenceScore = evidenceScore;
        }

        String title() {
            if (kind == Kind.THUNDERSTORM) return "Tormenta";
            if (kind == Kind.RAIN) return "Lluvia";
            return "Posible Zonda";
        }

        String windowText() {
            return WeatherClient.hourLabel(startIso) + "–" + WeatherClient.hourLabel(endExclusiveIso);
        }

        String detailText() {
            if (kind == Kind.ZONDA) {
                return title() + " · " + windowText() + " · ráfagas " + peakGust
                        + " km/h · señal " + evidenceLabel();
            }
            StringBuilder text = new StringBuilder(title()).append(" · ").append(windowText());
            if (peakProbability >= 0) text.append(" · pico ").append(peakProbability).append('%');
            if (totalPrecipitation >= 0.1) {
                text.append(" · ").append(formatMm(totalPrecipitation)).append(" mm");
            }
            if (kind == Kind.THUNDERSTORM && peakGust >= 45) {
                text.append(" · ráfagas ").append(peakGust).append(" km/h");
            }
            return text.toString();
        }

        String evidenceLabel() {
            if (evidenceScore >= 8) return "alta";
            if (evidenceScore >= 6) return "media";
            return "moderada";
        }

        String notificationFamilyKey() { return kind.name(); }
    }

    static final class Report {
        final List<Event> events;
        final Severity highestSeverity;

        Report(List<Event> events, Severity highestSeverity) {
            this.events = Collections.unmodifiableList(events);
            this.highestSeverity = highestSeverity;
        }

        boolean hasHazards() { return !events.isEmpty(); }
        boolean hasOfficialAlerts() {
            for (Event event : events) if (event.source == Source.OFFICIAL_SMN) return true;
            return false;
        }
    }

    private AlertEngine() { }

    static Report analyze(WeatherClient.Forecast forecast) {
        if (forecast == null || forecast.hours == null || forecast.hours.isEmpty()) {
            return new Report(Collections.emptyList(), null);
        }
        ArrayList<Event> events = new ArrayList<>();
        detectPrecipitation(forecast.hours, events);
        detectZonda(forecast.hours, events);
        events.sort(Comparator.comparing((Event e) -> e.startIso)
                .thenComparing((Event e) -> -e.severity.rank)
                .thenComparing(e -> e.kind.name()));
        Severity highest = null;
        for (Event event : events) {
            if (highest == null || event.severity.rank > highest.rank) highest = event.severity;
        }
        return new Report(events, highest);
    }

    private static void detectPrecipitation(List<WeatherClient.Hour> hours, List<Event> out) {
        int index = 0;
        while (index < hours.size()) {
            if (!rainEventHour(hours.get(index))) { index++; continue; }
            int start = index;
            int peakProbability = -1;
            int peakGust = 0;
            double peakHourly = 0.0;
            double total = 0.0;
            boolean storm = false;
            while (index < hours.size() && rainEventHour(hours.get(index))) {
                WeatherClient.Hour hour = hours.get(index);
                peakProbability = Math.max(peakProbability, hour.rainProbability);
                peakGust = Math.max(peakGust, hour.gust);
                peakHourly = Math.max(peakHourly, hour.precipitation);
                total += Math.max(0.0, hour.precipitation);
                storm |= WeatherClient.isStormCode(hour.code);
                index++;
            }
            int end = index - 1;
            Kind kind = storm ? Kind.THUNDERSTORM : Kind.RAIN;
            Severity severity = precipitationSeverity(storm, peakProbability, peakHourly, total, peakGust);
            out.add(new Event(kind, severity, Source.HEURISTIC_X10,
                    hours.get(start).iso, endExclusive(hours, end), end - start + 1,
                    peakProbability, peakHourly, total, peakGust, 0));
        }
    }

    private static boolean rainEventHour(WeatherClient.Hour hour) {
        double liquid = Math.max(hour.precipitation, hour.rain + hour.showers);
        boolean measurable = liquid >= 0.2 || WeatherClient.isRainCode(hour.code);
        if (WeatherClient.isStormCode(hour.code)) return true;
        if (liquid >= 0.5) return true;
        return hour.rainProbability >= 60 && measurable;
    }

    private static Severity precipitationSeverity(boolean storm, int peakProbability,
                                                   double peakHourly, double total, int peakGust) {
        if ((storm && (peakGust >= 90 || peakHourly >= 15.0)) || peakHourly >= 20.0 || total >= 40.0) {
            return Severity.DANGER;
        }
        if (storm || peakHourly >= 7.0 || total >= 15.0 || (peakProbability >= 85 && total >= 5.0)) {
            return Severity.IMPORTANT;
        }
        return Severity.PRECAUTION;
    }

    private static void detectZonda(List<WeatherClient.Hour> hours, List<Event> out) {
        int index = 0;
        while (index < hours.size()) {
            int score = zondaEvidence(hours.get(index));
            if (score < 5) { index++; continue; }
            int start = index;
            int peakGust = 0;
            int maxEvidence = 0;
            while (index < hours.size()) {
                int currentScore = zondaEvidence(hours.get(index));
                if (currentScore < 5) break;
                maxEvidence = Math.max(maxEvidence, currentScore);
                peakGust = Math.max(peakGust, hours.get(index).gust);
                index++;
            }
            int end = index - 1;
            int duration = end - start + 1;
            boolean persistentEnough = duration >= 2;
            boolean strongSingleHour = duration == 1 && maxEvidence >= 8 && peakGust >= 65;
            if (!persistentEnough && !strongSingleHour) continue;
            Severity severity;
            if (peakGust >= 90 && duration >= 2) severity = Severity.DANGER;
            else if (peakGust >= 70 || duration >= 4 || maxEvidence >= 8) severity = Severity.IMPORTANT;
            else severity = Severity.PRECAUTION;
            out.add(new Event(Kind.ZONDA, severity, Source.HEURISTIC_X10,
                    hours.get(start).iso, endExclusive(hours, end), duration,
                    -1, 0.0, 0.0, peakGust, maxEvidence));
        }
    }

    static int zondaEvidence(WeatherClient.Hour hour) {
        if (hour == null) return 0;
        int direction = ((hour.direction % 360) + 360) % 360;
        if (direction < 230 || direction > 330 || hour.gust < 45) return 0;
        double dewSpread = finite(hour.dewPoint) ? hour.temp - hour.dewPoint : Double.NaN;
        boolean dry = hour.humidity <= 35 || (finite(dewSpread) && dewSpread >= 10.0);
        if (!dry) return 0;
        double liquid = Math.max(hour.precipitation, hour.rain + hour.showers);
        if (liquid >= 0.5 || hour.snowfall >= 0.2 || WeatherClient.isRainCode(hour.code)
                || (hour.rainProbability >= 0 && hour.rainProbability >= 70)) return 0;

        int score = 2;
        score += 1; // gust >= 45, already required
        if (hour.gust >= 60) score++;
        if (hour.gust >= 80) score++;
        if (hour.wind >= 25) score++;
        if (hour.wind >= 40) score++;
        if (hour.humidity <= 35) score++;
        if (hour.humidity <= 25) score++;
        if (finite(dewSpread) && dewSpread >= 10.0) score++;
        if (finite(dewSpread) && dewSpread >= 15.0) score++;
        return Math.min(score, 10);
    }

    private static String endExclusive(List<WeatherClient.Hour> hours, int endIndex) {
        if (endIndex + 1 < hours.size()) return hours.get(endIndex + 1).iso;
        return plusOneHour(hours.get(endIndex).iso);
    }

    private static String plusOneHour(String iso) {
        if (iso == null || iso.length() < 16) return iso;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        format.setLenient(false);
        format.setTimeZone(WeatherClient.MENDOZA_TZ);
        try {
            Date date = format.parse(iso.substring(0, 16));
            if (date == null) return iso;
            return format.format(new Date(date.getTime() + 60L * 60L * 1000L));
        } catch (ParseException ignored) {
            return iso;
        }
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }

    private static String formatMm(double value) {
        return String.format(WeatherClient.ES_AR, "%.1f", value);
    }
}

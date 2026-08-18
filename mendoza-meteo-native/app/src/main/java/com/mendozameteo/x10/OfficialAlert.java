package com.mendozameteo.x10;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class OfficialAlert {
    enum Source {
        SMN_CAP("SMN"),
        MENDOZA_DCC("Contingencias Mendoza");

        final String label;
        Source(String label) { this.label = label; }
    }

    enum Level {
        UNKNOWN(0, "Oficial"),
        INFO(1, "Información"),
        YELLOW(2, "Amarillo"),
        ORANGE(3, "Naranja"),
        RED(4, "Rojo");

        final int rank;
        final String label;
        Level(int rank, String label) { this.rank = rank; this.label = label; }
    }

    final String id;
    final Source source;
    final Level level;
    final String event;
    final String headline;
    final String description;
    final String instruction;
    final String area;
    final String sentIso;
    final String startIso;
    final String expiresIso;
    final long sentMillis;
    final long startMillis;
    final long expiresMillis;
    final boolean cancellation;
    final String references;

    OfficialAlert(String id, Source source, Level level, String event, String headline,
                  String description, String instruction, String area, String sentIso,
                  String startIso, String expiresIso, boolean cancellation, String references) {
        this.id = safe(id);
        this.source = source == null ? Source.SMN_CAP : source;
        this.level = level == null ? Level.UNKNOWN : level;
        this.event = safe(event);
        this.headline = safe(headline);
        this.description = safe(description);
        this.instruction = safe(instruction);
        this.area = safe(area);
        this.sentIso = safe(sentIso);
        this.startIso = safe(startIso);
        this.expiresIso = safe(expiresIso);
        this.sentMillis = parseIsoMillis(this.sentIso);
        long parsedStart = parseIsoMillis(this.startIso);
        this.startMillis = parsedStart > 0 ? parsedStart : this.sentMillis;
        this.expiresMillis = parseIsoMillis(this.expiresIso);
        this.cancellation = cancellation;
        this.references = safe(references);
    }

    boolean activeAt(long nowMillis) {
        if (cancellation) return false;
        if (expiresMillis > 0 && nowMillis >= expiresMillis) return false;
        if (startMillis > 0 && nowMillis + 6L * 60L * 60L * 1000L < startMillis) return false;
        return true;
    }

    String title() {
        if (!headline.isEmpty()) return headline;
        if (!event.isEmpty()) return event;
        return "Alerta oficial";
    }

    String sourceLabel() { return source.label; }

    String fingerprint() {
        return source.name() + '|' + (!id.isEmpty() ? id : event + '|' + startIso + '|' + area);
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("source", source.name());
        object.put("level", level.name());
        object.put("event", event);
        object.put("headline", headline);
        object.put("description", description);
        object.put("instruction", instruction);
        object.put("area", area);
        object.put("sentIso", sentIso);
        object.put("startIso", startIso);
        object.put("expiresIso", expiresIso);
        object.put("cancellation", cancellation);
        object.put("references", references);
        return object;
    }

    static OfficialAlert fromJson(JSONObject object) throws JSONException {
        Source source;
        Level level;
        try { source = Source.valueOf(object.optString("source", Source.SMN_CAP.name())); }
        catch (IllegalArgumentException ignored) { source = Source.SMN_CAP; }
        try { level = Level.valueOf(object.optString("level", Level.UNKNOWN.name())); }
        catch (IllegalArgumentException ignored) { level = Level.UNKNOWN; }
        return new OfficialAlert(
                object.optString("id"), source, level, object.optString("event"),
                object.optString("headline"), object.optString("description"),
                object.optString("instruction"), object.optString("area"),
                object.optString("sentIso"), object.optString("startIso"),
                object.optString("expiresIso"), object.optBoolean("cancellation"),
                object.optString("references"));
    }

    static long parseIsoMillis(String value) {
        if (value == null || value.trim().isEmpty()) return -1L;
        String text = value.trim();
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mmXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm'Z'"
        };
        for (String pattern : patterns) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setLenient(false);
            if (pattern.endsWith("'Z'")) format.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                Date date = format.parse(text);
                if (date != null) return date.getTime();
            } catch (ParseException ignored) { }
        }
        return -1L;
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}

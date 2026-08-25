package com.mendozameteo.x10;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback to the SMN web application's own official API when the WMO-registered CAP
 * endpoint refuses automated access. The temporary JWT is obtained from ws2.smn.gob.ar;
 * no credential or secret is bundled with the app.
 */
final class SmnApiAlertSource {
    static final String TOKEN_URL = "https://ws2.smn.gob.ar/";
    static final String API_BASE = "https://ws1.smn.gob.ar/v1";
    private static final String COORD_URL = API_BASE + "/georef/location/coord";
    private static final String ALERT_URL = API_BASE + "/warning/alert/location";

    private static final Pattern[] TOKEN_PATTERNS = {
            Pattern.compile("localStorage\\.setItem\\(['\"]token['\"]\\s*,\\s*['\"]([^'\"]+)['\"]"),
            Pattern.compile("[\"']token[\"']\\s*:\\s*[\"']([^\"']+)[\"']"),
            Pattern.compile("token\\s*=\\s*[\"']([^\"']+)[\"']"),
            Pattern.compile("setItem\\(['\"]token['\"]\\s*,\\s*['\"]([^'\"]+)['\"]")
    };

    private final HttpTextTransport transport;

    SmnApiAlertSource(HttpTextTransport transport) { this.transport = transport; }

    List<OfficialAlert> load(double latitude, double longitude, long nowMillis) throws Exception {
        String token = extractToken(transport.get(TOKEN_URL, 2));
        if (token == null || !token.startsWith("eyJ") || token.split("\\.").length != 3) {
            throw new WeatherException(WeatherException.Kind.INVALID_DATA, "SMN web token not found or invalid");
        }
        Map<String,String> headers = new HashMap<>();
        headers.put("Authorization", "JWT " + token);
        headers.put("Accept", "application/json");

        String coordText = transport.get(COORD_URL + "?lat=" + latitude + "&lon=" + longitude, 2, headers);
        JSONObject location = new JSONObject(coordText);
        String locationId = String.valueOf(location.opt("id"));
        if (locationId == null || locationId.isEmpty() || "null".equals(locationId)) {
            throw new WeatherException(WeatherException.Kind.INVALID_DATA, "SMN location id missing");
        }
        String area = areaLabel(location);

        String alertText = transport.get(ALERT_URL + "/" + locationId, 2, headers);
        JSONObject root = new JSONObject(alertText);
        if (!root.has("warnings") || !root.has("reports")) {
            throw new WeatherException(WeatherException.Kind.INVALID_DATA, "SMN alert API contract changed");
        }
        return parseApi(root, area, nowMillis);
    }

    static List<OfficialAlert> parseApi(JSONObject root, String area, long nowMillis) throws JSONException {
        JSONArray warnings = root.optJSONArray("warnings");
        JSONArray reports = root.optJSONArray("reports");
        if (warnings == null) return Collections.emptyList();
        String updated = root.optString("updated", "");
        ArrayList<OfficialAlert> result = new ArrayList<>();
        for (int w = 0; w < warnings.length(); w++) {
            JSONObject warning = warnings.optJSONObject(w);
            if (warning == null) continue;
            String date = warning.optString("date", "");
            JSONArray events = warning.optJSONArray("events");
            if (events == null) continue;
            for (int e = 0; e < events.length(); e++) {
                JSONObject event = events.optJSONObject(e);
                if (event == null) continue;
                int eventId = event.optInt("id", -1);
                JSONObject levels = event.optJSONObject("levels");
                PeriodWindow window = periodWindow(date, levels);
                int rawLevel = window.maxLevel > 1 ? window.maxLevel : event.optInt("max_level", 1);
                if (rawLevel <= 1) continue;
                OfficialAlert.Level level = mapLevel(rawLevel);
                String name = eventName(eventId);
                Detail detail = reportDetail(reports, eventId, rawLevel);
                String sent = validIso(updated) ? updated : window.startIso;
                OfficialAlert alert = new OfficialAlert(
                        "smn-api-" + date + '-' + eventId + '-' + rawLevel,
                        OfficialAlert.Source.SMN_API,
                        level,
                        name,
                        name + " · " + level.label,
                        detail.description,
                        detail.instruction,
                        area,
                        sent,
                        window.startIso,
                        window.endIso,
                        false,
                        "");
                if (alert.activeAt(nowMillis)) result.add(alert);
            }
        }
        return result;
    }

    static String extractToken(String html) {
        if (html == null) return null;
        for (Pattern pattern : TOKEN_PATTERNS) {
            Matcher matcher = pattern.matcher(html);
            while (matcher.find()) {
                String token = matcher.group(1);
                if (token != null && token.startsWith("eyJ") && token.split("\\.").length == 3) return token;
            }
        }
        Matcher generic = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").matcher(html);
        return generic.find() ? generic.group() : null;
    }

    private static PeriodWindow periodWindow(String date, JSONObject levels) {
        String[] names = {"early_morning", "morning", "afternoon", "night"};
        int[] starts = {0, 6, 12, 18};
        int first = -1, last = -1, max = 1;
        if (levels != null) {
            for (int i = 0; i < names.length; i++) {
                if (levels.isNull(names[i])) continue;
                int value = levels.optInt(names[i], 1);
                if (value > 1) {
                    if (first < 0) first = i;
                    last = i;
                    max = Math.max(max, value);
                }
            }
        }
        if (first < 0) { first = 0; last = 3; }
        String start = date + String.format(Locale.US, "T%02d:00:00-03:00", starts[first]);
        String end = plusDaysOrHour(date, last == 3 ? 24 : starts[last] + 6);
        return new PeriodWindow(start, end, max);
    }

    private static String plusDaysOrHour(String date, int hour) {
        if (hour < 24) return date + String.format(Locale.US, "T%02d:00:00-03:00", hour);
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        day.setTimeZone(WeatherClient.MENDOZA_TZ);
        day.setLenient(false);
        try {
            Date parsed = day.parse(date);
            if (parsed != null) return day.format(new Date(parsed.getTime() + 24L * 60L * 60L * 1000L)) + "T00:00:00-03:00";
        } catch (ParseException ignored) { }
        return date + "T23:59:59-03:00";
    }

    private static Detail reportDetail(JSONArray reports, int eventId, int rawLevel) {
        if (reports == null) return new Detail("", "");
        for (int i = 0; i < reports.length(); i++) {
            JSONObject report = reports.optJSONObject(i);
            if (report == null || report.optInt("event_id", -1) != eventId) continue;
            JSONArray levels = report.optJSONArray("levels");
            if (levels == null) continue;
            for (int j = 0; j < levels.length(); j++) {
                JSONObject level = levels.optJSONObject(j);
                if (level != null && level.optInt("level", -1) == rawLevel) {
                    return new Detail(level.optString("description", ""), level.optString("instruction", ""));
                }
            }
        }
        return new Detail("", "");
    }

    private static OfficialAlert.Level mapLevel(int raw) {
        if (raw >= 5) return OfficialAlert.Level.RED;
        if (raw == 4) return OfficialAlert.Level.ORANGE;
        if (raw == 3) return OfficialAlert.Level.YELLOW;
        if (raw == 2) return OfficialAlert.Level.ADVISORY;
        return OfficialAlert.Level.UNKNOWN;
    }

    private static String eventName(int id) {
        switch (id) {
            case 37: return "Lluvia";
            case 39: return "Viento";
            case 40: return "Niebla";
            case 41: return "Tormenta";
            case 42: return "Nevada";
            case 43: return "Altas temperaturas";
            case 44: return "Bajas temperaturas";
            case 45: return "Ceniza volcánica";
            case 46: return "Polvo";
            case 47: return "Viento Zonda";
            case 54: return "Humo";
            default: return "Fenómeno meteorológico";
        }
    }

    private static String areaLabel(JSONObject location) {
        String name = location.optString("name", "");
        String department = location.optString("department", "");
        String province = location.optString("province", "Mendoza");
        StringBuilder value = new StringBuilder();
        if (!name.isEmpty()) value.append(name);
        if (!department.isEmpty() && !department.equalsIgnoreCase(name)) {
            if (value.length() > 0) value.append(" · ");
            value.append(department);
        }
        if (!province.isEmpty() && !containsIgnoreCase(value.toString(), province)) {
            if (value.length() > 0) value.append(" · ");
            value.append(province);
        }
        return value.length() == 0 ? "Mendoza" : value.toString();
    }

    private static boolean containsIgnoreCase(String value, String piece) {
        return value.toLowerCase(Locale.ROOT).contains(piece.toLowerCase(Locale.ROOT));
    }

    private static boolean validIso(String value) { return OfficialAlert.parseIsoMillis(value) > 0; }

    private static final class PeriodWindow {
        final String startIso, endIso;
        final int maxLevel;
        PeriodWindow(String startIso, String endIso, int maxLevel) { this.startIso = startIso; this.endIso = endIso; this.maxLevel = maxLevel; }
    }

    private static final class Detail {
        final String description, instruction;
        Detail(String description, String instruction) { this.description = description == null ? "" : description; this.instruction = instruction == null ? "" : instruction; }
    }
}

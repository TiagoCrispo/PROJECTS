package com.mendozameteo.x10;

import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MendozaOfficialBulletinSource {
    static final String ALERT_URL = "https://contingencias.mendoza.gov.ar/alerta/";
    private static final long MAX_AGE_MS = 36L * 60L * 60L * 1000L;
    private static final Pattern DATE_PATTERN = Pattern.compile("(?i)(?:lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)?\\s*(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)\\s+de\\s+(20\\d{2})");
    private static final String[] HAZARDS = {
            "zonda", "tormenta", "granizo", "nieve", "nevada", "viento fuerte", "rafaga", "ráfaga",
            "helada", "lluvia intensa", "lluvias intensas", "aluvion", "aluvión"
    };

    private final HttpTextTransport transport;

    MendozaOfficialBulletinSource(HttpTextTransport transport) {
        this.transport = transport;
    }

    List<OfficialAlert> load(long nowMillis) throws Exception {
        String html = transport.get(ALERT_URL, 2);
        return parseHtml(html, nowMillis);
    }

    static List<OfficialAlert> parseHtml(String html, long nowMillis) {
        ArrayList<OfficialAlert> result = new ArrayList<>();
        if (html == null || html.trim().isEmpty()) return result;
        String text = stripHtml(html);
        long issueMillis = parseSpanishDate(text);
        if (issueMillis <= 0 || issueMillis > nowMillis + 6L * 60L * 60L * 1000L || nowMillis - issueMillis > MAX_AGE_MS) {
            return result;
        }
        String hazardLine = firstHazardLine(text);
        if (hazardLine.isEmpty()) return result;
        String issueIso = formatIssue(issueMillis);
        String expiresIso = formatIssue(issueMillis + MAX_AGE_MS);
        result.add(new OfficialAlert(
                "mendoza-dcc-" + issueMillis,
                OfficialAlert.Source.MENDOZA_DCC,
                OfficialAlert.Level.INFO,
                hazardName(hazardLine),
                "Boletín oficial Mendoza",
                truncate(hazardLine, 520),
                "",
                "Provincia de Mendoza",
                issueIso,
                issueIso,
                expiresIso,
                false,
                ""));
        return result;
    }

    private static String stripHtml(String html) {
        String value = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>|</div>|</li>|</h[1-6]>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&aacute;", "á").replace("&eacute;", "é").replace("&iacute;", "í")
                .replace("&oacute;", "ó").replace("&uacute;", "ú").replace("&ntilde;", "ñ");
        return value.replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n").trim();
    }

    private static String firstHazardLine(String text) {
        String[] lines = text.split("\\n");
        for (String line : lines) {
            String clean = line.trim();
            if (clean.length() < 8) continue;
            String normalized = normalize(clean);
            for (String hazard : HAZARDS) {
                if (normalized.contains(normalize(hazard))) return clean;
            }
        }
        return "";
    }

    private static long parseSpanishDate(String text) {
        Matcher matcher = DATE_PATTERN.matcher(text);
        if (!matcher.find()) return -1L;
        int day = Integer.parseInt(matcher.group(1));
        int month = monthNumber(matcher.group(2));
        int year = Integer.parseInt(matcher.group(3));
        if (month < 1) return -1L;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        format.setTimeZone(WeatherClient.MENDOZA_TZ);
        format.setLenient(false);
        try {
            Date date = format.parse(String.format(Locale.US, "%04d-%02d-%02d 12:00", year, month, day));
            return date == null ? -1L : date.getTime();
        } catch (ParseException ignored) {
            return -1L;
        }
    }

    private static int monthNumber(String month) {
        String value = normalize(month);
        if (value.equals("ENERO")) return 1;
        if (value.equals("FEBRERO")) return 2;
        if (value.equals("MARZO")) return 3;
        if (value.equals("ABRIL")) return 4;
        if (value.equals("MAYO")) return 5;
        if (value.equals("JUNIO")) return 6;
        if (value.equals("JULIO")) return 7;
        if (value.equals("AGOSTO")) return 8;
        if (value.equals("SEPTIEMBRE") || value.equals("SETIEMBRE")) return 9;
        if (value.equals("OCTUBRE")) return 10;
        if (value.equals("NOVIEMBRE")) return 11;
        if (value.equals("DICIEMBRE")) return 12;
        return -1;
    }

    private static String hazardName(String line) {
        String n = normalize(line);
        if (n.contains("ZONDA")) return "Viento Zonda";
        if (n.contains("TORMENTA")) return "Tormenta";
        if (n.contains("GRANIZO")) return "Granizo";
        if (n.contains("NIEVE") || n.contains("NEVADA")) return "Nevada";
        if (n.contains("HELADA")) return "Helada";
        if (n.contains("LLUVIA")) return "Lluvia";
        if (n.contains("ALUVION")) return "Aluvión";
        return "Contingencia meteorológica";
    }

    private static String formatIssue(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        format.setTimeZone(WeatherClient.MENDOZA_TZ);
        return format.format(new Date(millis));
    }

    private static String normalize(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return text.toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("\\s+", " ");
        return clean.length() <= max ? clean : clean.substring(0, max - 1) + "…";
    }
}

package com.mendozameteo.x10;

import java.util.ArrayList;
import java.util.List;

final class SmnCapAlertSource {
    static final String INDEX_URL = "https://ssl.smn.gob.ar/CAP/AR.php";
    private static final int MAX_LINKED_ALERTS = 40;

    private final HttpTextTransport transport;

    SmnCapAlertSource(HttpTextTransport transport) {
        this.transport = transport;
    }

    List<OfficialAlert> load(double latitude, double longitude, long nowMillis) throws Exception {
        String index = transport.get(INDEX_URL, 2);
        ArrayList<OfficialAlert> result = new ArrayList<>();

        if (containsCapAlert(index)) {
            result.addAll(CapAlertParser.parse(index, latitude, longitude, nowMillis));
            return result;
        }

        List<String> links = CapAlertParser.extractXmlLinks(index, INDEX_URL);
        int count = 0;
        Exception lastFailure = null;
        for (String link : links) {
            if (count++ >= MAX_LINKED_ALERTS) break;
            try {
                String xml = transport.get(link, 1);
                result.addAll(CapAlertParser.parse(xml, latitude, longitude, nowMillis));
            } catch (Exception error) {
                lastFailure = error;
            }
        }
        if (links.isEmpty()) {
            throw new WeatherException(WeatherException.Kind.INVALID_DATA, "SMN CAP index did not expose CAP alerts or XML links");
        }
        if (result.isEmpty() && lastFailure != null && links.size() == 1) throw lastFailure;
        return result;
    }

    private static boolean containsCapAlert(String payload) {
        if (payload == null) return false;
        String text = payload.toLowerCase(java.util.Locale.ROOT);
        return text.contains("<alert") && (text.contains("urn:oasis:names:tc:emergency:cap") || text.contains("<identifier>"));
    }
}

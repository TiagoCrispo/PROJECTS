package com.mendozameteo.x10;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

final class CapAlertParser {
    private static final Pattern XML_LINK = Pattern.compile("(?i)(?:href\\s*=\\s*[\\\"']([^\\\"']+\\.xml[^\\\"']*)[\\\"']|(https://[^\\s<\\\"']+\\.xml[^\\s<\\\"']*))");

    private CapAlertParser() { }

    static List<OfficialAlert> parse(String xml, double latitude, double longitude, long nowMillis) throws Exception {
        ArrayList<OfficialAlert> result = new ArrayList<>();
        if (xml == null || xml.trim().isEmpty()) return result;
        Document document = secureFactory().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList alerts = document.getElementsByTagNameNS("*", "alert");
        if (alerts.getLength() == 0 && "alert".equalsIgnoreCase(local(document.getDocumentElement()))) {
            OfficialAlert alert = parseAlert(document.getDocumentElement(), latitude, longitude, nowMillis);
            if (alert != null) result.add(alert);
            return result;
        }
        for (int i = 0; i < alerts.getLength(); i++) {
            Node node = alerts.item(i);
            if (node instanceof Element) {
                OfficialAlert alert = parseAlert((Element) node, latitude, longitude, nowMillis);
                if (alert != null) result.add(alert);
            }
        }
        return result;
    }

    static List<String> extractXmlLinks(String payload, String baseUrl) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        if (payload == null) return new ArrayList<>();
        Matcher matcher = XML_LINK.matcher(payload);
        while (matcher.find() && links.size() < 60) {
            String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (raw == null || raw.trim().isEmpty()) continue;
            try {
                URL resolved = new URL(new URL(baseUrl), raw.replace("&amp;", "&"));
                if ("https".equalsIgnoreCase(resolved.getProtocol())) links.add(resolved.toString());
            } catch (MalformedURLException ignored) { }
        }
        return new ArrayList<>(links);
    }

    private static OfficialAlert parseAlert(Element root, double lat, double lon, long nowMillis) {
        String status = directText(root, "status");
        if (!status.isEmpty() && !"actual".equalsIgnoreCase(status)) return null;
        String msgType = directText(root, "msgType");
        boolean cancellation = "cancel".equalsIgnoreCase(msgType);
        String id = directText(root, "identifier");
        String sent = directText(root, "sent");
        String references = directText(root, "references");

        Element info = preferredInfo(root);
        if (info == null) return null;
        String event = directText(info, "event");
        String headline = directText(info, "headline");
        String description = directText(info, "description");
        String instruction = directText(info, "instruction");
        String effective = directText(info, "effective");
        String onset = directText(info, "onset");
        String expires = directText(info, "expires");
        String start = !onset.isEmpty() ? onset : (!effective.isEmpty() ? effective : sent);

        OfficialAlert.Level level = explicitLevel(info, headline + " " + event + " " + description);
        AreaMatch match = matchesArea(info, lat, lon);
        if (!match.matches) return null;

        OfficialAlert alert = new OfficialAlert(id, OfficialAlert.Source.SMN_CAP, level, event,
                headline, description, instruction, match.areaDescription, sent, start, expires,
                cancellation, references);
        if (!cancellation && !alert.activeAt(nowMillis)) return null;
        return alert;
    }

    private static Element preferredInfo(Element alert) {
        NodeList infos = alert.getElementsByTagNameNS("*", "info");
        Element fallback = null;
        for (int i = 0; i < infos.getLength(); i++) {
            Node node = infos.item(i);
            if (!(node instanceof Element)) continue;
            Element info = (Element) node;
            if (fallback == null) fallback = info;
            String language = directText(info, "language").toLowerCase(Locale.ROOT);
            if (language.isEmpty() || language.startsWith("es")) return info;
        }
        return fallback;
    }

    private static OfficialAlert.Level explicitLevel(Element info, String text) {
        StringBuilder evidence = new StringBuilder(text == null ? "" : text);
        NodeList params = info.getElementsByTagNameNS("*", "parameter");
        for (int i = 0; i < params.getLength(); i++) {
            Node node = params.item(i);
            if (!(node instanceof Element)) continue;
            Element p = (Element) node;
            evidence.append(' ').append(directText(p, "valueName")).append(' ').append(directText(p, "value"));
        }
        String normalized = normalize(evidence.toString());
        if (normalized.contains(" ROJO") || normalized.startsWith("ROJO") || normalized.contains("NIVEL ROJO")) return OfficialAlert.Level.RED;
        if (normalized.contains(" NARANJA") || normalized.startsWith("NARANJA") || normalized.contains("NIVEL NARANJA")) return OfficialAlert.Level.ORANGE;
        if (normalized.contains(" AMARILLO") || normalized.startsWith("AMARILLO") || normalized.contains("NIVEL AMARILLO")) return OfficialAlert.Level.YELLOW;
        return OfficialAlert.Level.UNKNOWN;
    }

    private static AreaMatch matchesArea(Element info, double lat, double lon) {
        NodeList areas = info.getElementsByTagNameNS("*", "area");
        boolean sawGeometry = false;
        for (int i = 0; i < areas.getLength(); i++) {
            Node node = areas.item(i);
            if (!(node instanceof Element)) continue;
            Element area = (Element) node;
            String desc = directText(area, "areaDesc");
            NodeList polygons = area.getElementsByTagNameNS("*", "polygon");
            for (int p = 0; p < polygons.getLength(); p++) {
                sawGeometry = true;
                if (pointInPolygon(lat, lon, polygons.item(p).getTextContent())) return new AreaMatch(true, desc);
            }
            NodeList circles = area.getElementsByTagNameNS("*", "circle");
            for (int c = 0; c < circles.getLength(); c++) {
                sawGeometry = true;
                if (pointInCircle(lat, lon, circles.item(c).getTextContent())) return new AreaMatch(true, desc);
            }
            if (!sawGeometry && fallbackAreaMatch(desc, lat, lon)) return new AreaMatch(true, desc);
        }
        return new AreaMatch(false, "");
    }

    static boolean fallbackAreaMatch(String areaDescription, double lat, double lon) {
        String text = normalize(areaDescription);
        if (!text.contains("MENDOZA")) return false;
        MendozaZone.Kind zone = MendozaZone.classify(lat, lon);
        Set<String> names = new LinkedHashSet<>();
        switch (zone) {
            case GRAN_MENDOZA:
            case PRECORDILLERA_PIEDEMONTE:
                add(names, "CAPITAL", "GODOY CRUZ", "GUAYMALLEN", "GUAYMALLÉN", "LAS HERAS", "LUJAN DE CUYO", "LUJÁN DE CUYO", "MAIPU", "MAIPÚ"); break;
            case VALLE_DE_UCO:
                add(names, "TUNUYAN", "TUNUYÁN", "TUPUNGATO", "SAN CARLOS"); break;
            case SOUTH:
                add(names, "SAN RAFAEL", "GENERAL ALVEAR", "MALARGUE", "MALARGÜE"); break;
            case EAST:
                add(names, "SAN MARTIN", "SAN MARTÍN", "JUNIN", "JUNÍN", "RIVADAVIA", "SANTA ROSA", "LA PAZ"); break;
            case HIGH_MOUNTAIN:
                add(names, "ALTA MONTAÑA", "CORDILLERA", "LAS HERAS", "LUJAN DE CUYO", "LUJÁN DE CUYO"); break;
            default: break;
        }
        boolean hasDepartmentList = text.contains(":") || text.contains(";") || text.contains(" - ");
        if (!hasDepartmentList) return true;
        for (String name : names) if (text.contains(normalize(name))) return true;
        return false;
    }

    private static boolean pointInPolygon(double lat, double lon, String polygon) {
        if (polygon == null) return false;
        String[] tokens = polygon.trim().split("\\s+");
        ArrayList<double[]> points = new ArrayList<>();
        for (String token : tokens) {
            String[] pair = token.split(",");
            if (pair.length < 2) continue;
            try { points.add(new double[]{Double.parseDouble(pair[0]), Double.parseDouble(pair[1])}); }
            catch (NumberFormatException ignored) { }
        }
        if (points.size() < 3) return false;
        boolean inside = false;
        for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
            double yi = points.get(i)[0], xi = points.get(i)[1];
            double yj = points.get(j)[0], xj = points.get(j)[1];
            boolean crosses = ((yi > lat) != (yj > lat)) &&
                    (lon < (xj - xi) * (lat - yi) / ((yj - yi) == 0 ? 1e-12 : (yj - yi)) + xi);
            if (crosses) inside = !inside;
        }
        return inside;
    }

    private static boolean pointInCircle(double lat, double lon, String circle) {
        if (circle == null) return false;
        String[] pieces = circle.trim().split("\\s+");
        if (pieces.length < 2) return false;
        String[] pair = pieces[0].split(",");
        if (pair.length < 2) return false;
        try {
            double cLat = Double.parseDouble(pair[0]);
            double cLon = Double.parseDouble(pair[1]);
            double radiusKm = Double.parseDouble(pieces[1]);
            return haversineKm(lat, lon, cLat, cLon) <= radiusKm;
        } catch (NumberFormatException ignored) { return false; }
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory;
    }

    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean value) throws Exception {
        factory.setFeature(feature, value);
    }

    private static String directText(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && name.equalsIgnoreCase(local(child))) return clean(child.getTextContent());
        }
        return "";
    }

    private static String local(Node node) {
        String name = node.getLocalName();
        if (name != null) return name;
        name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String clean(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
    private static String normalize(String value) { return clean(value).toUpperCase(Locale.ROOT); }
    private static void add(Set<String> set, String... values) { for (String value : values) set.add(value); }

    private static final class AreaMatch {
        final boolean matches;
        final String areaDescription;
        AreaMatch(boolean matches, String areaDescription) { this.matches = matches; this.areaDescription = areaDescription == null ? "" : areaDescription.trim(); }
    }
}

package com.mendozameteo.x10;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class WeatherClient {
    static final TimeZone MENDOZA_TZ = TimeZone.getTimeZone("America/Argentina/Mendoza");
    static final Locale ES_AR = new Locale("es", "AR");
    private WeatherClient() {}

    static final class Current {
        final int temp, feels, humidity, wind, gust, direction, code;
        final double precipitation;
        Current(int temp, int feels, int humidity, int wind, int gust, int direction, int code, double precipitation) {
            this.temp=temp; this.feels=feels; this.humidity=humidity; this.wind=wind; this.gust=gust;
            this.direction=direction; this.code=code; this.precipitation=precipitation;
        }
    }

    static final class Hour {
        final String iso;
        final int temp, rainProbability, gust, direction, humidity, code;
        final double precipitation;
        Hour(String iso, int temp, int rainProbability, int gust, int direction, int humidity, int code, double precipitation) {
            this.iso=iso; this.temp=temp; this.rainProbability=rainProbability; this.gust=gust;
            this.direction=direction; this.humidity=humidity; this.code=code; this.precipitation=precipitation;
        }
    }

    static final class Day {
        final String iso, label;
        final int max, min, rainProbability, gust, code;
        final double precipitation;
        Day(String iso, String label, int max, int min, int rainProbability, int gust, int code, double precipitation) {
            this.iso=iso; this.label=label; this.max=max; this.min=min; this.rainProbability=rainProbability;
            this.gust=gust; this.code=code; this.precipitation=precipitation;
        }
    }

    static final class Forecast {
        final Current current;
        final List<Hour> hours;
        final List<Day> days;
        Forecast(Current current, List<Hour> hours, List<Day> days) {
            this.current=current; this.hours=hours; this.days=days;
        }
    }

    static Forecast fetch(double lat, double lon) throws Exception {
        String endpoint = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lat + "&longitude=" + lon
                + "&timezone=America%2FArgentina%2FMendoza"
                + "&forecast_days=7"
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m"
                + "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,relative_humidity_2m,wind_gusts_10m,wind_direction_10m"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,weather_code,wind_gusts_10m_max";

        JSONObject root = getJson(endpoint);
        JSONObject c = root.getJSONObject("current");
        Current current = new Current(
                round(c, "temperature_2m"), round(c, "apparent_temperature"), round(c, "relative_humidity_2m"),
                round(c, "wind_speed_10m"), round(c, "wind_gusts_10m"), round(c, "wind_direction_10m"),
                c.optInt("weather_code", 0), c.optDouble("precipitation", 0.0));

        JSONObject h = root.getJSONObject("hourly");
        JSONArray ht = h.getJSONArray("time");
        JSONArray htemp = h.getJSONArray("temperature_2m");
        JSONArray hprob = h.getJSONArray("precipitation_probability");
        JSONArray hprec = h.getJSONArray("precipitation");
        JSONArray hcode = h.getJSONArray("weather_code");
        JSONArray hhum = h.getJSONArray("relative_humidity_2m");
        JSONArray hgust = h.getJSONArray("wind_gusts_10m");
        JSONArray hdir = h.getJSONArray("wind_direction_10m");
        int start = currentHourIndex(ht);
        List<Hour> hours = new ArrayList<>(24);
        for (int i=start; i<ht.length() && hours.size()<24; i++) {
            hours.add(new Hour(ht.getString(i), round(htemp,i), clamp(round(hprob,i),0,100), round(hgust,i),
                    normalizeDirection(round(hdir,i)), clamp(round(hhum,i),0,100), hcode.optInt(i,0), hprec.optDouble(i,0.0)));
        }
        if (hours.size() < 12) throw new IllegalStateException("Incomplete hourly forecast");

        JSONObject d = root.getJSONObject("daily");
        JSONArray dt = d.getJSONArray("time");
        JSONArray dmax = d.getJSONArray("temperature_2m_max");
        JSONArray dmin = d.getJSONArray("temperature_2m_min");
        JSONArray dprob = d.getJSONArray("precipitation_probability_max");
        JSONArray dprec = d.getJSONArray("precipitation_sum");
        JSONArray dcode = d.getJSONArray("weather_code");
        JSONArray dgust = d.getJSONArray("wind_gusts_10m_max");
        if (dt.length() < 7) throw new IllegalStateException("Incomplete daily forecast");
        List<Day> days = new ArrayList<>(7);
        for (int i=0; i<7; i++) {
            String iso = dt.getString(i);
            days.add(new Day(iso, dayLabel(iso,i), round(dmax,i), round(dmin,i), clamp(round(dprob,i),0,100),
                    round(dgust,i), dcode.optInt(i,0), dprec.optDouble(i,0.0)));
        }
        return new Forecast(current, hours, days);
    }

    private static JSONObject getJson(String endpoint) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(endpoint).openConnection();
        conn.setConnectTimeout(8000); conn.setReadTimeout(8000); conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "MendozaMeteoX10/6.0-native");
        conn.setUseCaches(false);
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) { conn.disconnect(); throw new IllegalStateException("HTTP "+status); }
        byte[] data;
        try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n=in.read(buffer))!=-1) { out.write(buffer,0,n); if (out.size()>768000) throw new IllegalStateException("Response too large"); }
            data=out.toByteArray();
        } finally { conn.disconnect(); }
        return new JSONObject(new String(data, StandardCharsets.UTF_8));
    }

    static boolean isRainCode(int code) { return (code>=51 && code<=67) || (code>=80 && code<=82) || code>=95; }
    static boolean isStormCode(int code) { return code>=95; }
    static boolean west(int degrees) { int d=normalizeDirection(degrees); return d>=240 && d<=320; }
    static int zondaScore(Hour h) {
        if (!west(h.direction) || h.gust<45 || h.humidity>38 || h.precipitation>=0.2 || isRainCode(h.code)) return 0;
        int score=35;
        score += Math.min(35, Math.max(0,h.gust-45));
        score += Math.min(20, Math.max(0,38-h.humidity));
        if (h.gust>=80) score+=10;
        return clamp(score,0,95);
    }
    static String hourLabel(String iso) { return iso.length()>=16 ? iso.substring(11,16) : "--:--"; }
    static String weatherText(int code) {
        if (code==0) return "Despejado";
        if (code<=3) return "Nubosidad variable";
        if (code==45 || code==48) return "Niebla";
        if (code>=95) return "Tormenta";
        if (isRainCode(code)) return "Lluvia";
        if (code>=71 && code<=77) return "Nieve";
        return "Variable";
    }

    private static int currentHourIndex(JSONArray times) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US); fmt.setTimeZone(MENDOZA_TZ);
        String now=fmt.format(new Date());
        for (int i=0;i<times.length();i++) if (now.equals(times.optString(i))) return i;
        return 0;
    }
    private static String dayLabel(String iso, int index) throws ParseException {
        if (index==0) return "Hoy";
        SimpleDateFormat in=new SimpleDateFormat("yyyy-MM-dd",Locale.US); in.setLenient(false); in.setTimeZone(MENDOZA_TZ);
        SimpleDateFormat out=new SimpleDateFormat("EEE",ES_AR); out.setTimeZone(MENDOZA_TZ);
        String v=out.format(in.parse(iso)).replace(".","");
        return v.substring(0,1).toUpperCase(ES_AR)+v.substring(1);
    }
    private static int round(JSONObject o,String k){ return (int)Math.round(o.optDouble(k,0)); }
    private static int round(JSONArray a,int i){ return (int)Math.round(a.optDouble(i,0)); }
    private static int clamp(int x,int a,int b){ return Math.max(a,Math.min(b,x)); }
    private static int normalizeDirection(int d){ int x=d%360; return x<0?x+360:x; }
}

package com.mendozameteo.x10;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

final class ForecastSerializer {
    private static final int MAGIC = 0x4D4D5832;
    private static final int VERSION = 2;
    private static final int MAX_HOURS = 48;
    private static final int MAX_DAYS = 16;
    private ForecastSerializer() { }

    static void write(OutputStream output, WeatherClient.Forecast f) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output));
        out.writeInt(MAGIC); out.writeInt(VERSION);
        out.writeUTF(safe(f.providerId)); out.writeUTF(safe(f.providerLabel)); out.writeUTF(safe(f.timezone)); out.writeUTF(safe(f.dataTime));
        out.writeLong(f.fetchedAtMillis); out.writeDouble(f.latitude); out.writeDouble(f.longitude);
        writeCurrent(out, f.current);
        if (f.hours.size() > MAX_HOURS) throw new IOException("Too many hourly rows");
        out.writeInt(f.hours.size()); for (WeatherClient.Hour h : f.hours) writeHour(out, h);
        if (f.days.size() > MAX_DAYS) throw new IOException("Too many daily rows");
        out.writeInt(f.days.size()); for (WeatherClient.Day d : f.days) writeDay(out, d);
        out.flush();
    }

    static WeatherClient.Forecast read(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(input));
        if (in.readInt() != MAGIC) throw new IOException("Bad weather cache magic");
        if (in.readInt() != VERSION) throw new IOException("Unsupported weather cache version");
        String providerId = in.readUTF(), providerLabel = in.readUTF(), timezone = in.readUTF(), dataTime = in.readUTF();
        long fetchedAt = in.readLong(); double latitude = in.readDouble(), longitude = in.readDouble();
        WeatherClient.Current current = readCurrent(in);
        int hourCount = in.readInt(); if (hourCount < 0 || hourCount > MAX_HOURS) throw new IOException("Invalid hourly cache count");
        List<WeatherClient.Hour> hours = new ArrayList<>(hourCount); for (int i=0;i<hourCount;i++) hours.add(readHour(in));
        int dayCount = in.readInt(); if (dayCount < 0 || dayCount > MAX_DAYS) throw new IOException("Invalid daily cache count");
        List<WeatherClient.Day> days = new ArrayList<>(dayCount); for (int i=0;i<dayCount;i++) days.add(readDay(in));
        if (!WeatherClient.MENDOZA_TZ_ID.equals(timezone)) throw new IOException("Cache timezone mismatch");
        if (hours.size()!=24 || days.size()!=7) throw new IOException("Incomplete normalized cache");
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) throw new IOException("Invalid cache coordinates");
        return new WeatherClient.Forecast(providerId, providerLabel, timezone, dataTime, fetchedAt, latitude, longitude, current, hours, days);
    }

    private static void writeCurrent(DataOutputStream o, WeatherClient.Current c) throws IOException {
        o.writeInt(c.temp); o.writeInt(c.feels); o.writeInt(c.humidity); o.writeInt(c.wind); o.writeInt(c.gust); o.writeInt(c.direction); o.writeInt(c.code); o.writeInt(c.rainProbability); o.writeInt(c.cloudCover);
        o.writeDouble(c.precipitation); o.writeDouble(c.snowfall); o.writeDouble(c.dewPoint); o.writeDouble(c.pressureMsl); o.writeDouble(c.visibility);
    }
    private static WeatherClient.Current readCurrent(DataInputStream i) throws IOException {
        return new WeatherClient.Current(i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readDouble(),i.readDouble(),i.readDouble(),i.readDouble(),i.readDouble());
    }
    private static void writeHour(DataOutputStream o, WeatherClient.Hour h) throws IOException {
        o.writeUTF(safe(h.iso)); o.writeInt(h.temp); o.writeInt(h.feels); o.writeInt(h.rainProbability); o.writeInt(h.wind); o.writeInt(h.gust); o.writeInt(h.direction); o.writeInt(h.humidity); o.writeInt(h.code); o.writeInt(h.cloudCover);
        o.writeDouble(h.precipitation); o.writeDouble(h.rain); o.writeDouble(h.showers); o.writeDouble(h.snowfall); o.writeDouble(h.dewPoint); o.writeDouble(h.pressureMsl); o.writeDouble(h.visibility);
    }
    private static WeatherClient.Hour readHour(DataInputStream i) throws IOException {
        return new WeatherClient.Hour(i.readUTF(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readDouble(),i.readDouble(),i.readDouble(),i.readDouble(),i.readDouble(),i.readDouble(),i.readDouble());
    }
    private static void writeDay(DataOutputStream o, WeatherClient.Day d) throws IOException {
        o.writeUTF(safe(d.iso)); o.writeUTF(safe(d.label)); o.writeInt(d.max); o.writeInt(d.min); o.writeInt(d.rainProbability); o.writeInt(d.wind); o.writeInt(d.gust); o.writeInt(d.direction); o.writeInt(d.code); o.writeDouble(d.precipitation); o.writeDouble(d.rain); o.writeDouble(d.snowfall);
    }
    private static WeatherClient.Day readDay(DataInputStream i) throws IOException {
        return new WeatherClient.Day(i.readUTF(),i.readUTF(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readInt(),i.readDouble(),i.readDouble(),i.readDouble());
    }
    private static String safe(String value) { return value == null ? "" : value; }
}

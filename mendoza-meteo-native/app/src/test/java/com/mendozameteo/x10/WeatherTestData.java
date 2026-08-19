package com.mendozameteo.x10;

import java.util.ArrayList;
import java.util.List;

final class WeatherTestData {
    private WeatherTestData() { }

    static WeatherClient.Forecast forecast(String providerId, long fetchedAt) {
        WeatherClient.Current current = new WeatherClient.Current(
                20, 19, 40, 15, 30, 270, 1, 10, 20,
                0.0, 0.0, 5.0, 1015.0, 20000.0);
        List<WeatherClient.Hour> hours = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String hour = i < 10 ? "0" + i : Integer.toString(i);
            hours.add(new WeatherClient.Hour(
                    "2026-08-18T" + hour + ":00",
                    20 + (i % 3), 19 + (i % 3), i == 5 ? 70 : 10,
                    15, 30, 270, 40, 1, 20,
                    i == 5 ? 1.0 : 0.0, i == 5 ? 1.0 : 0.0,
                    0.0, 0.0, 5.0, 1015.0, 20000.0));
        }
        List<WeatherClient.Day> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            days.add(new WeatherClient.Day(
                    "2026-08-" + (18 + i), i == 0 ? "Hoy" : "D" + i,
                    25, 10, 20, 20, 35, 270, 1,
                    0.0, 0.0, 0.0));
        }
        return new WeatherClient.Forecast(
                providerId, providerId, WeatherClient.MENDOZA_TZ_ID,
                "2026-08-18T13:00", fetchedAt, -32.89, -68.84,
                current, hours, days);
    }
}

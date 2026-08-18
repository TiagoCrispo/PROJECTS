package com.mendozameteo.x10;

import org.json.JSONObject;
import java.util.Locale;

final class OpenMeteoProvider implements WeatherProvider {
    enum Model {
        BEST_MATCH("openmeteo_best_match", "Open-Meteo Best Match", "https://api.open-meteo.com/v1/forecast", true, 2),
        GFS("openmeteo_gfs", "NOAA GFS · Open-Meteo", "https://api.open-meteo.com/v1/gfs", true, 1),
        ECMWF("openmeteo_ecmwf", "ECMWF IFS · Open-Meteo", "https://api.open-meteo.com/v1/ecmwf", false, 1);

        final String id;
        final String label;
        final String endpoint;
        final boolean probabilitySupported;
        final int attempts;

        Model(String id, String label, String endpoint, boolean probabilitySupported, int attempts) {
            this.id = id;
            this.label = label;
            this.endpoint = endpoint;
            this.probabilitySupported = probabilitySupported;
            this.attempts = attempts;
        }
    }

    private final Model model;
    private final HttpJsonTransport transport;

    OpenMeteoProvider(Model model, HttpJsonTransport transport) {
        this.model = model;
        this.transport = transport;
    }

    @Override public String id() { return model.id; }
    @Override public String label() { return model.label; }

    @Override
    public WeatherClient.Forecast fetch(double latitude, double longitude) throws WeatherException {
        JSONObject root = transport.get(buildEndpoint(latitude, longitude), model.attempts);
        return WeatherClient.parse(root, model.id, model.label, latitude, longitude,
                System.currentTimeMillis(), model.probabilitySupported);
    }

    String buildEndpoint(double latitude, double longitude) {
        String probability = model.probabilitySupported ? ",precipitation_probability" : "";
        String dailyProbability = model.probabilitySupported ? ",precipitation_probability_max" : "";
        String current = "temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m"
                + probability + ",precipitation,snowfall,weather_code,pressure_msl,cloud_cover,visibility"
                + ",wind_speed_10m,wind_gusts_10m,wind_direction_10m";
        String hourly = "temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m"
                + probability + ",precipitation,rain,showers,snowfall,weather_code,pressure_msl,cloud_cover,visibility"
                + ",wind_speed_10m,wind_gusts_10m,wind_direction_10m";
        String daily = "temperature_2m_max,temperature_2m_min" + dailyProbability
                + ",precipitation_sum,rain_sum,snowfall_sum,weather_code"
                + ",wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant";
        return model.endpoint
                + "?latitude=" + coordinate(latitude)
                + "&longitude=" + coordinate(longitude)
                + "&timezone=America%2FArgentina%2FMendoza"
                + "&forecast_days=7&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm&timeformat=iso8601"
                + "&current=" + current + "&hourly=" + hourly + "&daily=" + daily;
    }

    private static String coordinate(double value) { return String.format(Locale.US, "%.5f", value); }
}

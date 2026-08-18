package com.mendozameteo.x10;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OpenMeteoProviderTest {
    @Test public void bestMatchRequestsProbabilityAndExplicitMendozaUnits(){OpenMeteoProvider provider=new OpenMeteoProvider(OpenMeteoProvider.Model.BEST_MATCH,new HttpJsonTransport());String endpoint=provider.buildEndpoint(-32.89,-68.84);assertTrue(endpoint.startsWith("https://api.open-meteo.com/v1/forecast?"));assertTrue(endpoint.contains("timezone=America%2FArgentina%2FMendoza"));assertTrue(endpoint.contains("precipitation_probability"));assertTrue(endpoint.contains("wind_speed_unit=kmh"));assertTrue(endpoint.contains("precipitation_unit=mm"));}
    @Test public void ecmwfDoesNotPretendToHaveProbability(){OpenMeteoProvider provider=new OpenMeteoProvider(OpenMeteoProvider.Model.ECMWF,new HttpJsonTransport());String endpoint=provider.buildEndpoint(-32.89,-68.84);assertTrue(endpoint.startsWith("https://api.open-meteo.com/v1/ecmwf?"));assertFalse(endpoint.contains("precipitation_probability"));}
    @Test public void ecmwfSynthesizesCurrentFromItsOwnHourlySeries() throws Exception {
        OpenMeteoProvider provider=new OpenMeteoProvider(OpenMeteoProvider.Model.ECMWF,new HttpJsonTransport());JSONObject root=new JSONObject();JSONObject hourly=new JSONObject(),units=new JSONObject();JSONArray times=new JSONArray().put("2026-08-18T12:00").put("2026-08-18T13:00").put("2026-08-18T14:00");hourly.put("time",times);units.put("time","iso8601");
        String[] fields={"temperature_2m","apparent_temperature","relative_humidity_2m","dew_point_2m","precipitation","snowfall","weather_code","pressure_msl","cloud_cover","visibility","wind_speed_10m","wind_gusts_10m","wind_direction_10m"};
        for(String field:fields){hourly.put(field,new JSONArray().put(10).put(20).put(30));units.put(field,unitFor(field));}root.put("hourly",hourly);root.put("hourly_units",units);
        java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm",java.util.Locale.US);fmt.setTimeZone(WeatherClient.MENDOZA_TZ);long now=fmt.parse("2026-08-18T13:37").getTime();provider.preparePayload(root,now);
        assertTrue(root.has("current"));assertEquals("2026-08-18T13:00",root.getJSONObject("current").getString("time"));assertEquals(20.0,root.getJSONObject("current").getDouble("temperature_2m"),0.001);assertFalse(root.getJSONObject("current").has("precipitation_probability"));
    }
    private static String unitFor(String field){if(field.contains("temperature")||field.contains("dew_point"))return "°C";if(field.contains("humidity")||field.contains("cloud"))return "%";if(field.contains("precipitation")||field.contains("snowfall"))return "mm";if(field.contains("wind_speed")||field.contains("wind_gust"))return "km/h";if(field.contains("direction"))return "°";if(field.contains("pressure"))return "hPa";if(field.contains("visibility"))return "m";if(field.contains("weather_code"))return "wmo code";return "";}
}

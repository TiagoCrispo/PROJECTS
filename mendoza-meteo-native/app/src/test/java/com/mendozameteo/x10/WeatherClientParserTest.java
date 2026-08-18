package com.mendozameteo.x10;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WeatherClientParserTest {
    @Test public void startsAtCurrentHourAndDoesNotLeakPastRainProbability() throws Exception {
        WeatherClient.Forecast forecast=WeatherClient.parse(fixture(true),"test","Test",-32.89,-68.84,1000L,true);
        assertEquals("2026-08-18T23:00",forecast.hours.get(0).iso);assertEquals(0,forecast.hours.get(0).rainProbability);assertEquals(24,forecast.hours.size());assertEquals(7,forecast.days.size());
    }
    @Test public void missingProbabilityIsRejectedWhenProviderClaimsToSupportIt() throws Exception {
        boolean failed=false;try{WeatherClient.parse(fixture(false),"test","Test",-32.89,-68.84,1000L,true);}catch(WeatherException expected){failed=expected.kind==WeatherException.Kind.INVALID_DATA;}assertTrue(failed);
    }
    @Test public void probabilityCanBeUnknownForEcmwfFallback() throws Exception {
        WeatherClient.Forecast forecast=WeatherClient.parse(fixture(false),"ecmwf","ECMWF",-32.89,-68.84,1000L,false);
        assertEquals(-1,forecast.current.rainProbability);assertEquals(-1,forecast.hours.get(0).rainProbability);assertEquals(-1,forecast.days.get(0).rainProbability);assertEquals("—",WeatherClient.probabilityText(-1));
    }

    private static JSONObject fixture(boolean includeProbability) throws Exception {
        JSONObject root=new JSONObject();root.put("timezone",WeatherClient.MENDOZA_TZ_ID);
        JSONObject cu=new JSONObject();cu.put("temperature_2m","°C");cu.put("apparent_temperature","°C");cu.put("relative_humidity_2m","%");cu.put("precipitation","mm");cu.put("wind_speed_10m","km/h");cu.put("wind_gusts_10m","km/h");if(includeProbability)cu.put("precipitation_probability","%");root.put("current_units",cu);
        JSONObject hu=new JSONObject();hu.put("temperature_2m","°C");hu.put("relative_humidity_2m","%");hu.put("precipitation","mm");hu.put("wind_speed_10m","km/h");hu.put("wind_gusts_10m","km/h");if(includeProbability)hu.put("precipitation_probability","%");root.put("hourly_units",hu);
        JSONObject du=new JSONObject();du.put("temperature_2m_max","°C");du.put("temperature_2m_min","°C");du.put("precipitation_sum","mm");du.put("wind_gusts_10m_max","km/h");if(includeProbability)du.put("precipitation_probability_max","%");root.put("daily_units",du);
        JSONObject current=new JSONObject();current.put("time","2026-08-18T23:30");current.put("temperature_2m",12);current.put("apparent_temperature",11);current.put("relative_humidity_2m",45);current.put("dew_point_2m",2);if(includeProbability)current.put("precipitation_probability",0);current.put("precipitation",0);current.put("snowfall",0);current.put("weather_code",1);current.put("pressure_msl",1014);current.put("cloud_cover",20);current.put("visibility",20000);current.put("wind_speed_10m",10);current.put("wind_gusts_10m",20);current.put("wind_direction_10m",270);root.put("current",current);
        JSONObject hourly=new JSONObject();JSONArray time=new JSONArray(),temp=new JSONArray(),feels=new JSONArray(),prob=new JSONArray(),precip=new JSONArray(),rain=new JSONArray(),showers=new JSONArray(),snow=new JSONArray(),code=new JSONArray(),humidity=new JSONArray(),dew=new JSONArray(),pressure=new JSONArray(),cloud=new JSONArray(),visibility=new JSONArray(),wind=new JSONArray(),gust=new JSONArray(),direction=new JSONArray();
        for(int i=0;i<72;i++){int day=18+i/24,hour=i%24;time.put(String.format("2026-08-%02dT%02d:00",day,hour));temp.put(12);feels.put(11);prob.put(i==10?100:0);precip.put(i==10?3.0:0.0);rain.put(i==10?3.0:0.0);showers.put(0.0);snow.put(0.0);code.put(i==10?61:1);humidity.put(45);dew.put(2.0);pressure.put(1014.0);cloud.put(20);visibility.put(20000.0);wind.put(10);gust.put(20);direction.put(270);}
        hourly.put("time",time);hourly.put("temperature_2m",temp);hourly.put("apparent_temperature",feels);if(includeProbability)hourly.put("precipitation_probability",prob);hourly.put("precipitation",precip);hourly.put("rain",rain);hourly.put("showers",showers);hourly.put("snowfall",snow);hourly.put("weather_code",code);hourly.put("relative_humidity_2m",humidity);hourly.put("dew_point_2m",dew);hourly.put("pressure_msl",pressure);hourly.put("cloud_cover",cloud);hourly.put("visibility",visibility);hourly.put("wind_speed_10m",wind);hourly.put("wind_gusts_10m",gust);hourly.put("wind_direction_10m",direction);root.put("hourly",hourly);
        JSONObject daily=new JSONObject();JSONArray dayTime=new JSONArray(),max=new JSONArray(),min=new JSONArray(),dprob=new JSONArray(),dprecip=new JSONArray(),drain=new JSONArray(),dsnow=new JSONArray(),dcode=new JSONArray(),dwind=new JSONArray(),dgust=new JSONArray(),ddir=new JSONArray();
        for(int i=0;i<7;i++){dayTime.put(String.format("2026-08-%02d",18+i));max.put(20);min.put(5);dprob.put(i==0?100:0);dprecip.put(i==0?3.0:0.0);drain.put(i==0?3.0:0.0);dsnow.put(0.0);dcode.put(i==0?61:1);dwind.put(15);dgust.put(30);ddir.put(270);}
        daily.put("time",dayTime);daily.put("temperature_2m_max",max);daily.put("temperature_2m_min",min);if(includeProbability)daily.put("precipitation_probability_max",dprob);daily.put("precipitation_sum",dprecip);daily.put("rain_sum",drain);daily.put("snowfall_sum",dsnow);daily.put("weather_code",dcode);daily.put("wind_speed_10m_max",dwind);daily.put("wind_gusts_10m_max",dgust);daily.put("wind_direction_10m_dominant",ddir);root.put("daily",daily);return root;
    }
}

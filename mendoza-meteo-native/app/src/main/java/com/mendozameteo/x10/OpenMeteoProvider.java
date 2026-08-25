package com.mendozameteo.x10;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class OpenMeteoProvider implements WeatherProvider {
    enum Model {
        BEST_MATCH("openmeteo_best_match","Open-Meteo Best Match","https://api.open-meteo.com/v1/forecast",true,2),
        GFS("openmeteo_gfs","NOAA GFS · Open-Meteo","https://api.open-meteo.com/v1/gfs",true,1),
        ECMWF("openmeteo_ecmwf","ECMWF IFS · Open-Meteo","https://api.open-meteo.com/v1/ecmwf",false,1);
        final String id,label,endpoint; final boolean probabilitySupported; final int attempts;
        Model(String id,String label,String endpoint,boolean probabilitySupported,int attempts){this.id=id;this.label=label;this.endpoint=endpoint;this.probabilitySupported=probabilitySupported;this.attempts=attempts;}
    }

    private static final String[] CURRENT_FIELDS={"temperature_2m","apparent_temperature","relative_humidity_2m","dew_point_2m","precipitation","snowfall","weather_code","pressure_msl","cloud_cover","visibility","wind_speed_10m","wind_gusts_10m","wind_direction_10m"};
    private final Model model; private final HttpJsonTransport transport;
    OpenMeteoProvider(Model model,HttpJsonTransport transport){this.model=model;this.transport=transport;}
    @Override public String id(){return model.id;} @Override public String label(){return model.label;}

    @Override public WeatherClient.Forecast fetch(double latitude,double longitude)throws WeatherException{
        JSONObject root=transport.get(buildEndpoint(latitude,longitude),model.attempts);
        preparePayload(root,System.currentTimeMillis());
        return WeatherClient.parse(root,model.id,model.label,latitude,longitude,System.currentTimeMillis(),model.probabilitySupported);
    }

    void preparePayload(JSONObject root,long nowMillis)throws WeatherException{
        if(model!=Model.ECMWF||root.has("current"))return;
        try{
            JSONObject hourly=root.getJSONObject("hourly"),hourlyUnits=root.getJSONObject("hourly_units"); JSONArray times=hourly.getJSONArray("time");
            int index=currentIndex(times,nowMillis); JSONObject current=new JSONObject(),units=new JSONObject(); current.put("time",times.getString(index)); units.put("time",hourlyUnits.optString("time","iso8601"));
            for(String field:CURRENT_FIELDS){JSONArray values=hourly.optJSONArray(field);if(values==null||index>=values.length()||values.isNull(index))throw new WeatherException(WeatherException.Kind.INVALID_DATA,"ECMWF missing current fallback field: "+field);current.put(field,values.get(index));String unit=hourlyUnits.optString(field,"");if(unit.isEmpty())throw new WeatherException(WeatherException.Kind.INVALID_DATA,"ECMWF missing unit: "+field);units.put(field,unit);}
            root.put("current",current);root.put("current_units",units);
        }catch(WeatherException known){throw known;}catch(JSONException malformed){throw new WeatherException(WeatherException.Kind.INVALID_DATA,"Cannot normalize ECMWF current conditions",malformed);}
    }

    private static int currentIndex(JSONArray times,long nowMillis)throws WeatherException{
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm",Locale.US);format.setTimeZone(WeatherClient.MENDOZA_TZ);String now=format.format(new Date(nowMillis));int best=-1;
        for(int i=0;i<times.length();i++){String value=times.optString(i,"");if(value.length()<16)throw new WeatherException(WeatherException.Kind.INVALID_DATA,"Malformed ECMWF hourly time");if(value.substring(0,16).compareTo(now)<=0)best=i;else break;}
        if(best<0)throw new WeatherException(WeatherException.Kind.INVALID_DATA,"ECMWF forecast does not include current hour");return best;
    }

    String buildEndpoint(double latitude,double longitude){
        String probability=model.probabilitySupported?",precipitation_probability":"",dailyProbability=model.probabilitySupported?",precipitation_probability_max":"";
        String current="temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m"+probability+",precipitation,snowfall,weather_code,pressure_msl,cloud_cover,visibility,wind_speed_10m,wind_gusts_10m,wind_direction_10m";
        String hourly="temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m"+probability+",precipitation,rain,showers,snowfall,weather_code,pressure_msl,cloud_cover,visibility,wind_speed_10m,wind_gusts_10m,wind_direction_10m";
        String daily="temperature_2m_max,temperature_2m_min"+dailyProbability+",precipitation_sum,rain_sum,snowfall_sum,weather_code,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant";
        return model.endpoint+"?latitude="+coordinate(latitude)+"&longitude="+coordinate(longitude)+"&timezone=America%2FArgentina%2FMendoza&forecast_days=7&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm&timeformat=iso8601&current="+current+"&hourly="+hourly+"&daily="+daily;
    }
    private static String coordinate(double value){return String.format(Locale.US,"%.5f",value);}
}

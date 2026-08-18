package com.mendozameteo.x10;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class WeatherClient {
    static final String MENDOZA_TZ_ID = "America/Argentina/Mendoza";
    static final TimeZone MENDOZA_TZ = TimeZone.getTimeZone(MENDOZA_TZ_ID);
    static final Locale ES_AR = new Locale("es", "AR");

    private WeatherClient() { }

    static final class Current {
        final int temp, feels, humidity, wind, gust, direction, code, rainProbability, cloudCover;
        final double precipitation, snowfall, dewPoint, pressureMsl, visibility;

        Current(int temp, int feels, int humidity, int wind, int gust, int direction, int code,
                int rainProbability, int cloudCover, double precipitation, double snowfall,
                double dewPoint, double pressureMsl, double visibility) {
            this.temp=temp; this.feels=feels; this.humidity=humidity; this.wind=wind; this.gust=gust;
            this.direction=direction; this.code=code; this.rainProbability=rainProbability; this.cloudCover=cloudCover;
            this.precipitation=precipitation; this.snowfall=snowfall; this.dewPoint=dewPoint;
            this.pressureMsl=pressureMsl; this.visibility=visibility;
        }
    }

    static final class Hour {
        final String iso;
        final int temp, feels, rainProbability, wind, gust, direction, humidity, code, cloudCover;
        final double precipitation, rain, showers, snowfall, dewPoint, pressureMsl, visibility;

        Hour(String iso, int temp, int feels, int rainProbability, int wind, int gust, int direction,
             int humidity, int code, int cloudCover, double precipitation, double rain, double showers,
             double snowfall, double dewPoint, double pressureMsl, double visibility) {
            this.iso=iso; this.temp=temp; this.feels=feels; this.rainProbability=rainProbability;
            this.wind=wind; this.gust=gust; this.direction=direction; this.humidity=humidity; this.code=code;
            this.cloudCover=cloudCover; this.precipitation=precipitation; this.rain=rain; this.showers=showers;
            this.snowfall=snowfall; this.dewPoint=dewPoint; this.pressureMsl=pressureMsl; this.visibility=visibility;
        }
    }

    static final class Day {
        final String iso, label;
        final int max, min, rainProbability, wind, gust, direction, code;
        final double precipitation, rain, snowfall;

        Day(String iso, String label, int max, int min, int rainProbability, int wind, int gust,
            int direction, int code, double precipitation, double rain, double snowfall) {
            this.iso=iso; this.label=label; this.max=max; this.min=min; this.rainProbability=rainProbability;
            this.wind=wind; this.gust=gust; this.direction=direction; this.code=code;
            this.precipitation=precipitation; this.rain=rain; this.snowfall=snowfall;
        }
    }

    static final class Forecast {
        final String providerId, providerLabel, timezone, dataTime;
        final long fetchedAtMillis;
        final double latitude, longitude;
        final Current current;
        final List<Hour> hours;
        final List<Day> days;

        Forecast(String providerId, String providerLabel, String timezone, String dataTime,
                 long fetchedAtMillis, double latitude, double longitude,
                 Current current, List<Hour> hours, List<Day> days) {
            this.providerId=providerId; this.providerLabel=providerLabel; this.timezone=timezone; this.dataTime=dataTime;
            this.fetchedAtMillis=fetchedAtMillis; this.latitude=latitude; this.longitude=longitude;
            this.current=current; this.hours=hours; this.days=days;
        }
    }

    static Forecast parse(JSONObject root, String providerId, String providerLabel,
                          double requestedLat, double requestedLon, long fetchedAtMillis,
                          boolean probabilityExpected) throws WeatherException {
        try {
            String timezone = root.getString("timezone");
            if (!MENDOZA_TZ_ID.equals(timezone)) throw invalid("Unexpected timezone: " + timezone);
            validateUnits(root, probabilityExpected);

            JSONObject c = root.getJSONObject("current");
            String dataTime = requireIso(c.getString("time"));
            Current current = new Current(
                    requiredRounded(c,"temperature_2m",-100,70),
                    requiredRounded(c,"apparent_temperature",-120,80),
                    requiredRounded(c,"relative_humidity_2m",0,100),
                    requiredRounded(c,"wind_speed_10m",0,500),
                    requiredRounded(c,"wind_gusts_10m",0,500),
                    normalizeDirection(requiredRounded(c,"wind_direction_10m",0,360)),
                    requiredRounded(c,"weather_code",0,99),
                    optionalProbability(c,"precipitation_probability"),
                    optionalRounded(c,"cloud_cover",0,100,-1),
                    requiredFinite(c,"precipitation",0,2000),
                    optionalFinite(c,"snowfall",0,1000,0.0),
                    optionalFinite(c,"dew_point_2m",-120,80,Double.NaN),
                    optionalFinite(c,"pressure_msl",700,1150,Double.NaN),
                    optionalFinite(c,"visibility",0,200000,Double.NaN));

            JSONObject h = root.getJSONObject("hourly");
            JSONArray ht=requiredArray(h,"time"), htemp=requiredArray(h,"temperature_2m"), hfeels=optionalArray(h,"apparent_temperature"),
                    hprob=optionalArray(h,"precipitation_probability"), hprec=requiredArray(h,"precipitation"), hrain=optionalArray(h,"rain"),
                    hshow=optionalArray(h,"showers"), hsnow=optionalArray(h,"snowfall"), hcode=requiredArray(h,"weather_code"),
                    hhum=requiredArray(h,"relative_humidity_2m"), hdew=optionalArray(h,"dew_point_2m"), hpressure=optionalArray(h,"pressure_msl"),
                    hcloud=optionalArray(h,"cloud_cover"), hvis=optionalArray(h,"visibility"), hwind=requiredArray(h,"wind_speed_10m"),
                    hgust=requiredArray(h,"wind_gusts_10m"), hdir=requiredArray(h,"wind_direction_10m");
            int hourlyCount=ht.length();
            requireSameLength(hourlyCount,htemp,hprec,hcode,hhum,hwind,hgust,hdir);
            requireOptionalSameLength(hourlyCount,hfeels,hprob,hrain,hshow,hsnow,hdew,hpressure,hcloud,hvis);
            if (probabilityExpected && hprob==null) throw invalid("Provider omitted precipitation_probability");

            int start=currentHourIndex(ht,dataTime);
            List<Hour> hours=new ArrayList<>(24);
            for (int i=start;i<hourlyCount && hours.size()<24;i++) {
                hours.add(new Hour(
                        requireIso(ht.getString(i)),
                        requiredRounded(htemp,i,-100,70),
                        hfeels==null?requiredRounded(htemp,i,-100,70):requiredRounded(hfeels,i,-120,80),
                        hprob==null?-1:requiredProbability(hprob,i),
                        requiredRounded(hwind,i,0,500),
                        requiredRounded(hgust,i,0,500),
                        normalizeDirection(requiredRounded(hdir,i,0,360)),
                        requiredRounded(hhum,i,0,100),
                        requiredRounded(hcode,i,0,99),
                        hcloud==null?-1:requiredRounded(hcloud,i,0,100),
                        requiredFinite(hprec,i,0,2000),
                        hrain==null?0.0:requiredFinite(hrain,i,0,2000),
                        hshow==null?0.0:requiredFinite(hshow,i,0,2000),
                        hsnow==null?0.0:requiredFinite(hsnow,i,0,1000),
                        hdew==null?Double.NaN:requiredFinite(hdew,i,-120,80),
                        hpressure==null?Double.NaN:requiredFinite(hpressure,i,700,1150),
                        hvis==null?Double.NaN:requiredFinite(hvis,i,0,200000)));
            }
            if (hours.size()!=24) throw invalid("Incomplete 24-hour forecast: " + hours.size());

            JSONObject d=root.getJSONObject("daily");
            JSONArray dt=requiredArray(d,"time"), dmax=requiredArray(d,"temperature_2m_max"), dmin=requiredArray(d,"temperature_2m_min"),
                    dprob=optionalArray(d,"precipitation_probability_max"), dprec=requiredArray(d,"precipitation_sum"), drain=optionalArray(d,"rain_sum"),
                    dsnow=optionalArray(d,"snowfall_sum"), dcode=requiredArray(d,"weather_code"), dwind=optionalArray(d,"wind_speed_10m_max"),
                    dgust=requiredArray(d,"wind_gusts_10m_max"), ddir=optionalArray(d,"wind_direction_10m_dominant");
            if (dt.length()<7) throw invalid("Incomplete daily forecast");
            requireAtLeast(dt.length(),7,dmax,dmin,dprec,dcode,dgust);
            requireOptionalAtLeast(dt.length(),7,dprob,drain,dsnow,dwind,ddir);
            if (probabilityExpected && dprob==null) throw invalid("Provider omitted daily precipitation probability");

            List<Day> days=new ArrayList<>(7);
            for (int i=0;i<7;i++) {
                String iso=requireDate(dt.getString(i));
                days.add(new Day(
                        iso,dayLabel(iso,i),requiredRounded(dmax,i,-100,70),requiredRounded(dmin,i,-100,70),
                        dprob==null?-1:requiredProbability(dprob,i),
                        dwind==null?-1:requiredRounded(dwind,i,0,500),
                        requiredRounded(dgust,i,0,500),
                        ddir==null?-1:normalizeDirection(requiredRounded(ddir,i,0,360)),
                        requiredRounded(dcode,i,0,99),
                        requiredFinite(dprec,i,0,5000),
                        drain==null?0.0:requiredFinite(drain,i,0,5000),
                        dsnow==null?0.0:requiredFinite(dsnow,i,0,5000)));
            }
            return new Forecast(providerId,providerLabel,timezone,dataTime,fetchedAtMillis,requestedLat,requestedLon,current,hours,days);
        } catch (WeatherException known) {
            throw known;
        } catch (JSONException | ParseException malformed) {
            throw new WeatherException(WeatherException.Kind.INVALID_DATA,"Invalid forecast payload",malformed);
        }
    }

    private static void validateUnits(JSONObject root, boolean probabilityExpected) throws WeatherException, JSONException {
        JSONObject cu=root.getJSONObject("current_units");
        requireUnit(cu,"temperature_2m","°C"); requireUnit(cu,"apparent_temperature","°C");
        requireUnit(cu,"relative_humidity_2m","%"); requireUnit(cu,"precipitation","mm");
        requireUnit(cu,"wind_speed_10m","km/h"); requireUnit(cu,"wind_gusts_10m","km/h");
        if (probabilityExpected && cu.has("precipitation_probability")) requireUnit(cu,"precipitation_probability","%");
        JSONObject hu=root.getJSONObject("hourly_units");
        requireUnit(hu,"temperature_2m","°C"); requireUnit(hu,"relative_humidity_2m","%");
        requireUnit(hu,"precipitation","mm"); requireUnit(hu,"wind_speed_10m","km/h"); requireUnit(hu,"wind_gusts_10m","km/h");
        if (probabilityExpected) requireUnit(hu,"precipitation_probability","%");
        JSONObject du=root.getJSONObject("daily_units");
        requireUnit(du,"temperature_2m_max","°C"); requireUnit(du,"temperature_2m_min","°C");
        requireUnit(du,"precipitation_sum","mm"); requireUnit(du,"wind_gusts_10m_max","km/h");
        if (probabilityExpected) requireUnit(du,"precipitation_probability_max","%");
    }

    private static void requireUnit(JSONObject units,String key,String expected) throws WeatherException {
        String actual=units.optString(key,""); if (!expected.equals(actual)) throw invalid("Unexpected unit for " + key + ": " + actual);
    }
    private static JSONArray requiredArray(JSONObject o,String k) throws WeatherException { JSONArray v=o.optJSONArray(k); if(v==null)throw invalid("Missing array: "+k); return v; }
    private static JSONArray optionalArray(JSONObject o,String k){ return o.optJSONArray(k); }
    private static void requireSameLength(int expected,JSONArray... arrays)throws WeatherException{ for(JSONArray a:arrays)if(a.length()!=expected)throw invalid("Mismatched hourly array length"); }
    private static void requireOptionalSameLength(int expected,JSONArray... arrays)throws WeatherException{ for(JSONArray a:arrays)if(a!=null&&a.length()!=expected)throw invalid("Mismatched optional hourly array length"); }
    private static void requireAtLeast(int sourceCount,int minimum,JSONArray... arrays)throws WeatherException{ if(sourceCount<minimum)throw invalid("Insufficient daily timestamps"); for(JSONArray a:arrays)if(a.length()<minimum)throw invalid("Insufficient daily values"); }
    private static void requireOptionalAtLeast(int sourceCount,int minimum,JSONArray... arrays)throws WeatherException{ if(sourceCount<minimum)throw invalid("Insufficient daily timestamps"); for(JSONArray a:arrays)if(a!=null&&a.length()<minimum)throw invalid("Insufficient optional daily values"); }

    private static int currentHourIndex(JSONArray times,String currentIso)throws WeatherException{
        int best=-1; String current=currentIso.length()>=16?currentIso.substring(0,16):currentIso;
        for(int i=0;i<times.length();i++){ String candidate=times.optString(i,""); if(candidate.length()<16)throw invalid("Malformed hourly timestamp"); String normalized=candidate.substring(0,16); if(normalized.compareTo(current)<=0)best=i; else break; }
        if(best<0)throw invalid("Current time outside hourly forecast"); return best;
    }

    static boolean isRainCode(int code){ return (code>=51&&code<=67)||(code>=80&&code<=82)||(code>=95&&code<=99); }
    static boolean isStormCode(int code){ return code>=95&&code<=99; }
    static boolean isSnowCode(int code){ return code>=71&&code<=77; }
    static boolean west(int degrees){ int d=normalizeDirection(degrees); return d>=240&&d<=320; }
    static int zondaScore(Hour h){ if(!west(h.direction)||h.gust<45||h.humidity>38||h.precipitation>=0.2||isRainCode(h.code))return 0; int score=35; score+=Math.min(35,Math.max(0,h.gust-45)); score+=Math.min(20,Math.max(0,38-h.humidity)); if(h.gust>=80)score+=10; return clamp(score,0,95); }
    static String hourLabel(String iso){ return iso!=null&&iso.length()>=16?iso.substring(11,16):"--:--"; }
    static String probabilityText(int probability){ return probability<0?"—":probability+"%"; }
    static String weatherText(int code){ if(code==0)return "Despejado"; if(code<=3)return "Nubosidad variable"; if(code==45||code==48)return "Niebla"; if(isStormCode(code))return "Tormenta"; if(isSnowCode(code))return "Nieve"; if(isRainCode(code))return "Lluvia"; return "Variable"; }

    private static String dayLabel(String iso,int index)throws ParseException{ if(index==0)return "Hoy"; SimpleDateFormat in=new SimpleDateFormat("yyyy-MM-dd",Locale.US); in.setLenient(false); in.setTimeZone(MENDOZA_TZ); SimpleDateFormat out=new SimpleDateFormat("EEE",ES_AR); out.setTimeZone(MENDOZA_TZ); Date date=in.parse(iso); if(date==null)throw new ParseException("Invalid date",0); String v=out.format(date).replace(".",""); return v.substring(0,1).toUpperCase(ES_AR)+v.substring(1); }
    private static String requireIso(String v)throws WeatherException{ if(v==null||v.length()<16||v.charAt(10)!='T')throw invalid("Malformed ISO timestamp"); return v; }
    private static String requireDate(String v)throws WeatherException{ if(v==null||v.length()!=10||v.charAt(4)!='-'||v.charAt(7)!='-')throw invalid("Malformed date"); return v; }
    private static int requiredRounded(JSONObject o,String k,int min,int max)throws WeatherException{ return (int)Math.round(requiredFinite(o,k,min,max)); }
    private static int optionalRounded(JSONObject o,String k,int min,int max,int fallback)throws WeatherException{ if(!o.has(k)||o.isNull(k))return fallback; return requiredRounded(o,k,min,max); }
    private static int requiredRounded(JSONArray a,int i,int min,int max)throws WeatherException{ return (int)Math.round(requiredFinite(a,i,min,max)); }
    private static int optionalProbability(JSONObject o,String k)throws WeatherException{ if(!o.has(k)||o.isNull(k))return -1; return clamp(requiredRounded(o,k,0,100),0,100); }
    private static int requiredProbability(JSONArray a,int i)throws WeatherException{ return clamp(requiredRounded(a,i,0,100),0,100); }
    private static double requiredFinite(JSONObject o,String k,double min,double max)throws WeatherException{ if(!o.has(k)||o.isNull(k))throw invalid("Missing numeric field: "+k); double v=o.optDouble(k,Double.NaN); if(!Double.isFinite(v)||v<min||v>max)throw invalid("Invalid numeric field: "+k); return v; }
    private static double optionalFinite(JSONObject o,String k,double min,double max,double fallback)throws WeatherException{ if(!o.has(k)||o.isNull(k))return fallback; return requiredFinite(o,k,min,max); }
    private static double requiredFinite(JSONArray a,int i,double min,double max)throws WeatherException{ if(i<0||i>=a.length()||a.isNull(i))throw invalid("Missing numeric array value"); double v=a.optDouble(i,Double.NaN); if(!Double.isFinite(v)||v<min||v>max)throw invalid("Invalid numeric array value"); return v; }
    private static WeatherException invalid(String message){ return new WeatherException(WeatherException.Kind.INVALID_DATA,message); }
    private static int clamp(int value,int min,int max){ return Math.max(min,Math.min(max,value)); }
    private static int normalizeDirection(int degrees){ int value=degrees%360; return value<0?value+360:value; }
}

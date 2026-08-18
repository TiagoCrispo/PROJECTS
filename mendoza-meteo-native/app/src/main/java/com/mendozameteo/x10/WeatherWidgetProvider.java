package com.mendozameteo.x10;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.widget.RemoteViews;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public final class WeatherWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_REFRESH="com.mendozameteo.x10.REFRESH_WIDGET";
    private static final String UNIQUE_WORK="mendoza-meteo-widget-update";
    private static final double FALLBACK_LAT=-32.896748,FALLBACK_LON=-68.853418;
    private static final String PREFS="widget_cache_v6";
    enum UpdateResult{UPDATED,CACHED,CACHED_RETRYABLE,RETRYABLE_FAILURE,PERMANENT_FAILURE}

    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){Context app=context.getApplicationContext();renderCachedOrPlaceholder(app,manager,ids);enqueueUpdate(app);}
    @Override public void onReceive(Context context,Intent intent){super.onReceive(context,intent);if(ACTION_REFRESH.equals(intent.getAction()))enqueueUpdate(context.getApplicationContext());}
    public static void refreshAll(Context context){enqueueUpdate(context.getApplicationContext());}

    static void enqueueUpdate(Context context){
        Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(WidgetUpdateWorker.class).setConstraints(constraints).build();
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK,ExistingWorkPolicy.KEEP,request);
    }

    static UpdateResult performWorkerUpdate(Context context){
        AppWidgetManager manager=AppWidgetManager.getInstance(context); ComponentName component=new ComponentName(context,WeatherWidgetProvider.class); int[] ids=manager.getAppWidgetIds(component); if(ids.length==0)return UpdateResult.UPDATED;
        double[] point=resolvePoint(context); WeatherRepository.Result result=new WeatherRepository(context).load("local",point[0],point[1]);
        if(result.isSuccess()){boolean cached=result.origin==WeatherRepository.Origin.CACHE;publishForecast(context,result.forecast,cached,result.freshness);if(!cached)return UpdateResult.UPDATED;return result.failure!=null&&result.failure.retryable()?UpdateResult.CACHED_RETRYABLE:UpdateResult.CACHED;}
        renderCachedOrPlaceholder(context,manager,ids);return result.shouldRetryBackground()?UpdateResult.RETRYABLE_FAILURE:UpdateResult.PERMANENT_FAILURE;
    }

    static void publishForecast(Context context,WeatherClient.Forecast forecast){publishForecast(context,forecast,false,ForecastFreshness.State.FRESH);}
    static void publishForecast(Context context,WeatherClient.Forecast forecast,boolean cached,ForecastFreshness.State freshness){Context app=context.getApplicationContext();saveCache(app,forecast);AppWidgetManager manager=AppWidgetManager.getInstance(app);ComponentName component=new ComponentName(app,WeatherWidgetProvider.class);for(int id:manager.getAppWidgetIds(component)){RemoteViews views=createViews(app);bind(views,forecast,cached,freshness);manager.updateAppWidget(id,views);}}
    private static void renderCachedOrPlaceholder(Context context,AppWidgetManager manager,int[] ids){for(int id:ids){RemoteViews views=createViews(context);if(!loadCache(context,views))views.setTextViewText(R.id.current_temp,"--°");manager.updateAppWidget(id,views);}}
    private static RemoteViews createViews(Context context){RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.weather_widget);Intent open=new Intent(context,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);views.setOnClickPendingIntent(R.id.widget_root,pi);return views;}

    private static double[] resolvePoint(Context context){SharedPreferences last=context.getSharedPreferences("last_location_v6",Context.MODE_PRIVATE);double lat=Double.longBitsToDouble(last.getLong("lat",Double.doubleToLongBits(Double.NaN))),lon=Double.longBitsToDouble(last.getLong("lon",Double.doubleToLongBits(Double.NaN)));if(Double.isFinite(lat)&&Double.isFinite(lon))return new double[]{lat,lon};if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)try{LocationManager lm=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);Location best=null;for(String p:lm.getProviders(true)){Location c=lm.getLastKnownLocation(p);if(c!=null&&(best==null||c.getTime()>best.getTime()||(c.getTime()==best.getTime()&&c.getAccuracy()<best.getAccuracy())))best=c;}if(best!=null)return new double[]{best.getLatitude(),best.getLongitude()};}catch(SecurityException ignored){}return new double[]{FALLBACK_LAT,FALLBACK_LON};}

    private static void bind(RemoteViews views,WeatherClient.Forecast f,boolean cached,ForecastFreshness.State freshness){String suffix=cached?(freshness==ForecastFreshness.State.VERY_STALE?" ⚠":" ↻"):"";views.setTextViewText(R.id.current_temp,f.current.temp+"°"+suffix);views.setContentDescription(R.id.widget_root,cached?"Mendoza Meteo. Datos guardados: "+ForecastFreshness.ageLabel(ForecastFreshness.ageMillis(f.fetchedAtMillis,System.currentTimeMillis())):"Mendoza Meteo. Pronóstico actualizado");int[] dayIds={R.id.day1,R.id.day2,R.id.day3,R.id.day4,R.id.day5,R.id.day6,R.id.day7};int[] tempIds={R.id.temp1,R.id.temp2,R.id.temp3,R.id.temp4,R.id.temp5,R.id.temp6,R.id.temp7};int[] rainIds={R.id.rain1,R.id.rain2,R.id.rain3,R.id.rain4,R.id.rain5,R.id.rain6,R.id.rain7};for(int i=0;i<Math.min(7,f.days.size());i++){WeatherClient.Day d=f.days.get(i);views.setTextViewText(dayIds[i],d.label);views.setTextViewText(tempIds[i],d.max+"°/"+d.min+"°");views.setTextViewText(rainIds[i],WeatherClient.probabilityText(d.rainProbability));}}
    private static void saveCache(Context context,WeatherClient.Forecast f){if(f.days.size()<7)return;SharedPreferences.Editor e=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit();e.putInt("current",f.current.temp);for(int i=0;i<7;i++){WeatherClient.Day d=f.days.get(i);e.putString("day"+i,d.label);e.putInt("max"+i,d.max);e.putInt("min"+i,d.min);e.putInt("rain"+i,d.rainProbability);}e.putLong("updated",f.fetchedAtMillis);e.apply();}
    private static boolean loadCache(Context context,RemoteViews views){SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);if(!p.contains("updated"))return false;long updated=p.getLong("updated",0L);ForecastFreshness.State freshness=ForecastFreshness.classify(updated,System.currentTimeMillis());if(freshness==ForecastFreshness.State.EXPIRED)return false;String suffix=freshness==ForecastFreshness.State.FRESH?"":freshness==ForecastFreshness.State.VERY_STALE?" ⚠":" ↻";views.setTextViewText(R.id.current_temp,p.getInt("current",0)+"°"+suffix);views.setContentDescription(R.id.widget_root,freshness==ForecastFreshness.State.FRESH?"Mendoza Meteo. Pronóstico reciente":"Mendoza Meteo. Datos guardados: "+ForecastFreshness.ageLabel(ForecastFreshness.ageMillis(updated,System.currentTimeMillis())));int[] dayIds={R.id.day1,R.id.day2,R.id.day3,R.id.day4,R.id.day5,R.id.day6,R.id.day7};int[] tempIds={R.id.temp1,R.id.temp2,R.id.temp3,R.id.temp4,R.id.temp5,R.id.temp6,R.id.temp7};int[] rainIds={R.id.rain1,R.id.rain2,R.id.rain3,R.id.rain4,R.id.rain5,R.id.rain6,R.id.rain7};for(int i=0;i<7;i++){views.setTextViewText(dayIds[i],p.getString("day"+i,i==0?"Hoy":"---"));views.setTextViewText(tempIds[i],p.getInt("max"+i,0)+"°/"+p.getInt("min"+i,0)+"°");views.setTextViewText(rainIds[i],WeatherClient.probabilityText(p.getInt("rain"+i,-1)));}return true;}
}

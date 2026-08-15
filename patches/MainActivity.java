package com.mendozameteo.pro;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(5, 11, 20);
    private static final int PANEL = Color.rgb(12, 24, 38);
    private static final int PANEL2 = Color.rgb(18, 34, 50);
    private static final int TEXT = Color.rgb(235, 244, 250);
    private static final int MUTED = Color.rgb(142, 165, 180);
    private static final int CYAN = Color.rgb(32, 214, 255);
    private static final int GREEN = Color.rgb(50, 230, 165);
    private static final int VIOLET = Color.rgb(153, 119, 255);
    private static final int WARN = Color.rgb(255, 183, 77);

    private LinearLayout root, tabs, pageHost, nowPage, alertsPage, diagPage;
    private WebView radarView;
    private TextView status, titleLocation, currentTemp, currentDesc, feels, wind, gust, humidity, confidence;
    private LinearLayout importantBox, next2Box, compareBox, utnBox, hourlyBox, weeklyBox, historyBox;
    private WeatherRepository.BundleData currentData;
    private final Handler handler = new Handler();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LocationManager locationManager;
    private boolean fetching = false;
    private int activeTab = 0;

    private final Runnable foregroundRefresh = new Runnable() {
        @Override public void run() {
            if (activeTab == 0) refreshWeather(false);
            handler.postDelayed(this, 5 * 60 * 1000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setStatusBarColor(BG);
        buildUi();
        WeatherCache.initDefaults(this);
        WeatherWorker.schedule(this);
        requestNotificationPermission();
        renderCached();
        refreshWeather(true);
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(8), dp(12), dp(4));
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("Mendoza Meteo PRO", 20, TEXT, true);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button refresh = button("Actualizar", CYAN);
        refresh.setOnClickListener(v -> refreshWeather(true));
        top.addView(refresh);
        root.addView(top);

        status = text("Iniciando motor nativo…", 12, MUTED, false);
        root.addView(status);

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabs);
        root.addView(tabScroll, new LinearLayout.LayoutParams(-1, dp(52)));

        pageHost = new LinearLayout(this); pageHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1,0,1));

        addTab("Ahora",0); addTab("Radar",1); addTab("Alertas",2); addTab("Diagnóstico",3);
        showTab(0);
    }

    private void addTab(String name, int index) {
        Button b = button(name, PANEL2);
        b.setTag(index);
        b.setOnClickListener(v -> showTab((Integer)v.getTag()));
        tabs.addView(b, margin(dp(3),dp(4),dp(3),dp(4)));
    }

    private void showTab(int index) {
        activeTab = index;
        pageHost.removeAllViews();
        if (index == 0) { if (nowPage == null) nowPage = buildNowPage(); pageHost.addView(nowPage); if(currentData!=null) render(currentData); }
        else if (index == 1) { pageHost.addView(buildRadarPage()); }
        else if (index == 2) { if(alertsPage==null) alertsPage=buildAlertsPage(); pageHost.addView(alertsPage); }
        else { diagPage = buildDiagnosticsPage(); pageHost.addView(diagPage); }
    }

    private LinearLayout buildNowPage() {
        LinearLayout page = vertical();
        ScrollView sv = new ScrollView(this); LinearLayout content=vertical(); sv.addView(content); page.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout hero = card();
        titleLocation = text("Mi ubicación",14,CYAN,true); hero.addView(titleLocation);
        currentTemp = text("--°",52,TEXT,true); hero.addView(currentTemp);
        currentDesc = text("Esperando datos…",18,TEXT,true); hero.addView(currentDesc);
        LinearLayout stats=new LinearLayout(this); stats.setOrientation(LinearLayout.HORIZONTAL);
        feels=text("Sens. --",12,MUTED,false); wind=text("Viento --",12,MUTED,false); gust=text("Ráf. --",12,MUTED,false); humidity=text("Hum. --",12,MUTED,false);
        for(TextView t:new TextView[]{feels,wind,gust,humidity}) stats.addView(t,new LinearLayout.LayoutParams(0,-2,1)); hero.addView(stats);
        confidence=text("Confianza: --",12,GREEN,true); hero.addView(confidence); content.addView(hero, margin(0,dp(8),0,0));
        content.addView(sectionTitle("Lo importante hoy")); importantBox=card(); content.addView(importantBox);
        content.addView(sectionTitle("Próximas 2 horas")); next2Box=horizontalCard(); content.addView(next2Box);
        content.addView(sectionTitle("Tus lugares")); compareBox=card(); content.addView(compareBox);
        content.addView(sectionTitle("Voy a la UTN")); utnBox=card(); content.addView(utnBox);
        content.addView(sectionTitle("Próximas 24 horas")); HorizontalScrollView hsv=new HorizontalScrollView(this); hourlyBox=new LinearLayout(this); hourlyBox.setOrientation(LinearLayout.HORIZONTAL); hsv.addView(hourlyBox); content.addView(hsv);
        content.addView(sectionTitle("7 días")); weeklyBox=vertical(); content.addView(weeklyBox);
        content.addView(sectionTitle("Historial local")); historyBox=vertical(); content.addView(historyBox);
        return page;
    }

    private View buildRadarPage() {
        FrameLayout frame=new FrameLayout(this); radarView=new WebView(this); radarView.setBackgroundColor(BG); radarView.getSettings().setJavaScriptEnabled(true); radarView.getSettings().setDomStorageEnabled(true); radarView.loadUrl("file:///android_asset/radar.html"); frame.addView(radarView,new FrameLayout.LayoutParams(-1,-1)); return frame;
    }

    private LinearLayout buildAlertsPage() {
        LinearLayout page=vertical(); ScrollView sv=new ScrollView(this); LinearLayout c=vertical(); sv.addView(c); page.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        c.addView(text("Alertas inteligentes",24,TEXT,true)); c.addView(text("Solo avisamos cuando el cambio puede afectar tu día.",13,MUTED,false));
        addAlertToggle(c,"alert_rain","Lluvia próxima / probabilidad alta",true);
        addAlertToggle(c,"alert_gust","Ráfagas fuertes",true);
        addAlertToggle(c,"alert_cold","Frío intenso",false);
        addAlertToggle(c,"alert_heat","Calor intenso",false);
        addAlertToggle(c,"alert_uv","UV muy alto",true);
        Button test=button("Enviar notificación de prueba",VIOLET); test.setOnClickListener(v->WeatherNotifier.test(this)); c.addView(test,margin(0,dp(14),0,0));
        return page;
    }

    private LinearLayout buildDiagnosticsPage() {
        LinearLayout page=vertical(); ScrollView sv=new ScrollView(this); LinearLayout c=vertical(); sv.addView(c); page.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        c.addView(text("Diagnóstico",24,TEXT,true));
        WeatherCache cache=new WeatherCache(this);
        addDiag(c,"Motor","Android nativo · sin WebView para datos meteorológicos",GREEN);
        addDiag(c,"Red",networkOk()?"Conectada":"Sin conexión",networkOk()?GREEN:WARN);
        addDiag(c,"Último sync",cache.lastSyncText(),TEXT);
        addDiag(c,"Fuente",cache.lastSource(),TEXT);
        addDiag(c,"GPS",cache.gpsSummary(),TEXT);
        addDiag(c,"Worker",WeatherWorker.status(this),TEXT);
        addDiag(c,"Widget",widgetCount()+" instancia(s)",TEXT);
        addDiag(c,"Notificaciones",notificationsGranted()?"Permitidas":"Sin permiso",notificationsGranted()?GREEN:WARN);
        addDiag(c,"Último error",cache.lastError(),cache.lastError().equals("Ninguno")?GREEN:WARN);
        Button sync=button("Forzar sincronización",CYAN); sync.setOnClickListener(v->refreshWeather(true)); c.addView(sync,margin(0,dp(12),0,0));
        Button gps=button("Actualizar ubicación GPS",GREEN); gps.setOnClickListener(v->requestLocation()); c.addView(gps,margin(0,dp(8),0,0));
        Button settings=button("Abrir ajustes de la app",PANEL2); settings.setOnClickListener(v->{Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:"+getPackageName()));startActivity(i);}); c.addView(settings,margin(0,dp(8),0,0));
        TextView note=text("La app usa caché local: si una actualización falla, conserva el último pronóstico válido y muestra la antigüedad de los datos.",12,MUTED,false); c.addView(note,margin(0,dp(16),0,0));
        return page;
    }

    private void addAlertToggle(LinearLayout p,String key,String label,boolean def){CheckBox cb=new CheckBox(this);cb.setText(label);cb.setTextColor(TEXT);cb.setChecked(getPreferences(MODE_PRIVATE).getBoolean(key,def));cb.setOnCheckedChangeListener((b,v)->getPreferences(MODE_PRIVATE).edit().putBoolean(key,v).apply());p.addView(cb);}

    private void addDiag(LinearLayout c,String k,String v,int color){LinearLayout row=card();TextView a=text(k,13,MUTED,true);TextView b=text(v,13,color,false);row.addView(a);row.addView(b);c.addView(row,margin(0,dp(6),0,0));}

    private void renderCached() {
        WeatherCache cache=new WeatherCache(this); WeatherRepository.BundleData d=cache.readBundle();
        if(d!=null){currentData=d;if(nowPage!=null)render(d);status.setText("Datos guardados · "+cache.ageText());}
        else status.setText("Sin caché · buscando pronóstico…");
    }

    private void refreshWeather(boolean preferGps) {
        if(fetching)return; fetching=true; status.setText("Actualizando…");
        WeatherCache cache=new WeatherCache(this);
        double lat=cache.lastLat(), lon=cache.lastLon();
        if(preferGps && locationAllowed()){Location loc=bestLastLocation();if(loc!=null){lat=loc.getLatitude();lon=loc.getLongitude();cache.saveLocation(lat,lon,loc.getAccuracy(),"GPS");}}
        final double fLat=lat,fLon=lon;
        io.execute(()->{
            try{
                WeatherRepository repo=new WeatherRepository();
                WeatherRepository.BundleData data=repo.fetchBundle(fLat,fLon);
                new WeatherCache(this).saveBundle(data); WeatherWidgetProvider.updateAll(this,data); WeatherNotifier.evaluate(this,data);
                runOnUiThread(()->{fetching=false;currentData=data;render(data);status.setText("Actualizado ahora · "+data.source);if(activeTab==3)showTab(3);});
            }catch(Exception e){new WeatherCache(this).saveError(e);runOnUiThread(()->{fetching=false;status.setText("No se pudo actualizar · usando datos guardados");Toast.makeText(this,"Error de clima: "+shortError(e),Toast.LENGTH_LONG).show();renderCached();if(activeTab==3)showTab(3);});}
        });
    }

    private void render(WeatherRepository.BundleData d) {
        if(nowPage==null)return; WeatherRepository.Place me=d.me;
        titleLocation.setText(me.name); currentTemp.setText(Math.round(me.temp)+"°"); currentDesc.setText(WeatherRepository.weatherText(me.code));
        feels.setText("Sens. "+Math.round(me.apparent)+"°"); wind.setText("Viento "+Math.round(me.wind)+" km/h"); gust.setText("Ráf. "+Math.round(me.gust)+" km/h"); humidity.setText("Hum. "+Math.round(me.humidity)+"%");
        confidence.setText("Confianza: "+d.confidence.label+" · modelos Δ "+String.format(Locale.US,"%.1f°",d.confidence.delta));
        importantBox.removeAllViews(); importantBox.addView(text(d.summary,15,TEXT,false)); importantBox.addView(text("🌅 "+d.sunrise+"   🌇 "+d.sunset+"   ☀ UV max "+String.format(Locale.US,"%.1f",d.uvMax),12,MUTED,false));
        next2Box.removeAllViews(); for(int i=0;i<Math.min(5,d.nextHours.size());i++)next2Box.addView(hourMini(d.nextHours.get(i)),margin(dp(3),0,dp(3),0));
        compareBox.removeAllViews(); compareBox.addView(placeLine("📍",d.me));compareBox.addView(placeLine("🎓",d.utn));compareBox.addView(placeLine("🏠",d.home));compareBox.addView(text(d.comparison,12,GREEN,false));
        utnBox.removeAllViews();utnBox.addView(text(d.utnAdvice,14,TEXT,false));
        hourlyBox.removeAllViews();for(int i=0;i<Math.min(24,d.hourly.size());i++)hourlyBox.addView(hourMini(d.hourly.get(i)),margin(dp(3),0,dp(3),0));
        weeklyBox.removeAllViews();for(WeatherRepository.Day day:d.days)weeklyBox.addView(dayLine(day));
        historyBox.removeAllViews();for(String h:new WeatherCache(this).history())historyBox.addView(text(h,12,MUTED,false));
    }

    private View placeLine(String icon,WeatherRepository.Place p){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);TextView n=text(icon+" "+p.name,13,TEXT,true);TextView v=text(Math.round(p.temp)+"° · "+WeatherRepository.weatherText(p.code)+" · 💨 "+Math.round(p.gust),12,MUTED,false);r.addView(n,new LinearLayout.LayoutParams(0,-2,1));r.addView(v);r.setPadding(0,dp(5),0,dp(5));return r;}
    private View hourMini(WeatherRepository.Hour h){LinearLayout c=vertical();c.setGravity(Gravity.CENTER);c.setPadding(dp(10),dp(10),dp(10),dp(10));c.setBackground(round(PANEL,14));c.addView(text(h.time,11,MUTED,false));c.addView(text(WeatherRepository.icon(h.code),22,TEXT,false));c.addView(text(Math.round(h.temp)+"°",17,TEXT,true));c.addView(text("🌧 "+Math.round(h.rain)+"%",10,CYAN,false));c.setMinimumWidth(dp(86));return c;}
    private View dayLine(WeatherRepository.Day d){LinearLayout r=card();TextView date=text(d.label,13,TEXT,true);TextView icon=text(WeatherRepository.icon(d.code),20,TEXT,false);TextView temp=text(Math.round(d.min)+"° / "+Math.round(d.max)+"°",13,TEXT,true);TextView rain=text("🌧 "+Math.round(d.rain)+"%",12,CYAN,false);r.setGravity(Gravity.CENTER_VERTICAL);r.addView(date,new LinearLayout.LayoutParams(0,-2,1));r.addView(icon);r.addView(temp);r.addView(rain);return r;}

    private void requestLocation(){if(!locationAllowed()){ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},90);return;}try{locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);LocationListener listener=new LocationListener(){@Override public void onLocationChanged(Location l){new WeatherCache(MainActivity.this).saveLocation(l.getLatitude(),l.getLongitude(),l.getAccuracy(),"GPS");refreshWeather(false);try{locationManager.removeUpdates(this);}catch(Exception ignored){}}};locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER,listener,null);status.setText("Buscando GPS…");}catch(Exception e){status.setText("GPS no disponible · usando última ubicación");}}
    private boolean locationAllowed(){return ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private Location bestLastLocation(){try{LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);Location a=locationAllowed()?lm.getLastKnownLocation(LocationManager.GPS_PROVIDER):null;Location b=locationAllowed()?lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER):null;if(a==null)return b;if(b==null)return a;return a.getTime()>b.getTime()?a:b;}catch(Exception e){return null;}}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==90)refreshWeather(true);}

    private void requestNotificationPermission(){if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.POST_NOTIFICATIONS},91);}
    private boolean notificationsGranted(){return Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;}
    private boolean networkOk(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);if(cm==null)return false;NetworkCapabilities c=cm.getNetworkCapabilities(cm.getActiveNetwork());return c!=null&&(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)||c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));}catch(Exception e){return false;}}
    private int widgetCount(){AppWidgetManager a=AppWidgetManager.getInstance(this);return a.getAppWidgetIds(new ComponentName(this,WeatherWidgetProvider.class)).length;}
    private String shortError(Exception e){String s=e.getMessage();return TextUtils.isEmpty(s)?e.getClass().getSimpleName():(s.length()>100?s.substring(0,100):s);}

    @Override protected void onResume(){super.onResume();handler.removeCallbacks(foregroundRefresh);handler.postDelayed(foregroundRefresh,30_000);}
    @Override protected void onPause(){super.onPause();handler.removeCallbacks(foregroundRefresh);}
    @Override protected void onDestroy(){super.onDestroy();handler.removeCallbacks(foregroundRefresh);io.shutdownNow();}

    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout card(){LinearLayout l=vertical();l.setPadding(dp(14),dp(12),dp(14),dp(12));l.setBackground(round(PANEL,18));return l;}
    private LinearLayout horizontalCard(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setPadding(dp(8),dp(8),dp(8),dp(8));l.setBackground(round(PANEL,18));return l;}
    private TextView sectionTitle(String s){TextView t=text(s,13,CYAN,true);t.setPadding(dp(4),dp(18),dp(4),dp(6));return t;}
    private TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(dp(4),dp(3),dp(4),dp(3));return t;}
    private Button button(String s,int color){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setAllCaps(false);b.setBackground(round(color,14));b.setPadding(dp(12),0,dp(12),0);return b;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private LinearLayout.LayoutParams margin(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(l,t,r,b);return p;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
}

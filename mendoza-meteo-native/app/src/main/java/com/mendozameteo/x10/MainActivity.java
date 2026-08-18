package com.mendozameteo.x10;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final double UTN_LAT=-32.896748, UTN_LON=-68.853418;
    private static final int LOCATION_REQUEST=42;
    private final LoadGate loadGate=new LoadGate();
    private ExecutorService executor;
    private WeatherRepository repository;
    private LinearLayout content;
    private TextView status, refresh;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        executor=Executors.newFixedThreadPool(3);
        repository=new WeatherRepository(getApplicationContext());
        buildShell();
        if(hasLocation()) loadAll();
        else {
            status.setText("Permite ubicación para usar tu clima local…");
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_REQUEST);
        }
    }

    private void buildShell(){
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(11,15,22));
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); applyContentPadding(0,0,0,0);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2)); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30){ Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()); left=safe.left; top=safe.top; right=safe.right; bottom=safe.bottom; }
            else { left=insets.getSystemWindowInsetLeft(); top=insets.getSystemWindowInsetTop(); right=insets.getSystemWindowInsetRight(); bottom=insets.getSystemWindowInsetBottom(); if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null){ left=Math.max(left,insets.getDisplayCutout().getSafeInsetLeft()); top=Math.max(top,insets.getDisplayCutout().getSafeInsetTop()); right=Math.max(right,insets.getDisplayCutout().getSafeInsetRight()); bottom=Math.max(bottom,insets.getDisplayCutout().getSafeInsetBottom()); } }
            applyContentPadding(left,top,right,bottom); return insets;
        });
        scroll.requestApplyInsets();
        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title=text("Mendoza Meteo",24,Color.WHITE,true); head.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        refresh=text("Actualizar",13,Color.rgb(120,183,255),true); refresh.setGravity(Gravity.CENTER); refresh.setBackground(cardBg(Color.rgb(26,37,56),14)); refresh.setOnClickListener(v->loadAll()); head.addView(refresh,new LinearLayout.LayoutParams(dp(92),dp(38)));
        content.addView(head);
        status=text("Cargando pronóstico…",12,Color.rgb(174,185,203),false); content.addView(status,new LinearLayout.LayoutParams(-1,dp(34)));
    }

    private void applyContentPadding(int l,int t,int r,int b){ if(content!=null) content.setPadding(dp(14)+l,dp(10)+t,dp(14)+r,dp(28)+b); }

    private void loadAll(){
        if(destroyed||executor==null||executor.isShutdown())return;
        final long token=loadGate.begin();
        if(token==LoadGate.REJECTED){status.setText("Ya se está actualizando…");return;}
        setLoadingUi(true); while(content.getChildCount()>2)content.removeViewAt(2);
        Point point=bestPoint(); if(point.fromDevice)savePoint(point);
        executor.execute(()->{
            Future<WeatherRepository.Result> localTask=executor.submit(()->repository.load("local",point.lat,point.lon));
            Future<WeatherRepository.Result> utnTask=executor.submit(()->repository.load("utn",UTN_LAT,UTN_LON));
            try{
                WeatherRepository.Result local=localTask.get(), utn=utnTask.get();
                if(!loadGate.isActive(token)||Thread.currentThread().isInterrupted())return;
                if(local.isSuccess()) WeatherWidgetProvider.publishForecast(getApplicationContext(),local.forecast,local.origin==WeatherRepository.Origin.CACHE,local.freshness);
                postResult(token,()->render(local,utn,point.fromDevice));
            }catch(InterruptedException e){Thread.currentThread().interrupt();postResult(token,()->status.setText("Actualización cancelada."));}
            catch(ExecutionException e){postResult(token,()->status.setText("Error interno al actualizar el pronóstico."));}
            finally{localTask.cancel(true);utnTask.cancel(true);}
        });
    }

    private void postResult(long token,Runnable action){ runOnUiThread(()->{ if(destroyed||!loadGate.isActive(token))return; try{action.run();}finally{loadGate.finish(token);setLoadingUi(false);} }); }
    private void setLoadingUi(boolean loading){ if(status!=null&&loading)status.setText("Actualizando…"); if(refresh!=null){refresh.setEnabled(!loading);refresh.setAlpha(loading?0.55f:1f);} }

    private void render(WeatherRepository.Result local,WeatherRepository.Result utn,boolean deviceLocation){
        WeatherRepository.Result primary=local.isSuccess()?local:(utn.isSuccess()?utn:null);
        status.setText(combinedStatus(local,utn,deviceLocation));
        if(primary==null){renderUnavailable("No hay pronóstico disponible ni datos guardados utilizables.");return;}
        String suffix=local.isSuccess()?(deviceLocation?"":" · referencia UTN"):" · UTN (sin datos locales)";
        section("7 días"+suffix); renderDays(primary.forecast);
        section("Próximas 24 h"+suffix); renderHours(primary.forecast);
        section("Mi ubicación");
        if(local.isSuccess()){
            LinearLayout current=card(); current.setPadding(dp(14),dp(12),dp(14),dp(12)); current.addView(text(local.forecast.current.temp+"°",36,Color.WHITE,true));
            String prefix=deviceLocation?"":"Referencia UTN · ";
            current.addView(text(prefix+"Sensación "+local.forecast.current.feels+"° · Humedad "+local.forecast.current.humidity+"% · Viento "+local.forecast.current.wind+" km/h · Ráfagas "+local.forecast.current.gust+" km/h",12,Color.rgb(174,185,203),false));
            content.addView(current,new LinearLayout.LayoutParams(-1,-2)); renderPrecaution(local);
        } else renderUnavailable("Tu ubicación no tiene datos disponibles. UTN sigue funcionando como referencia.");
        section("UTN Mendoza");
        if(utn.isSuccess()){
            LinearLayout c=card(); c.setPadding(dp(14),dp(12),dp(14),dp(12)); WeatherClient.Forecast f=utn.forecast;
            c.addView(text(f.current.temp+"° · "+WeatherClient.weatherText(f.current.code),18,Color.WHITE,true)); WeatherClient.Day today=f.days.get(0);
            c.addView(text("Máx "+today.max+"° · Mín "+today.min+"° · Lluvia "+WeatherClient.probabilityText(today.rainProbability)+" · Ráfagas "+f.current.gust+" km/h",12,Color.rgb(174,185,203),false));
            if(utn.origin==WeatherRepository.Origin.CACHE)c.addView(text("Datos guardados · hace "+ForecastFreshness.ageLabel(utn.ageMillis),10,Color.rgb(241,184,91),true));
            content.addView(c,new LinearLayout.LayoutParams(-1,-2));
        } else renderUnavailable("UTN no pudo actualizarse y no hay caché utilizable.");
    }

    private void renderDays(WeatherClient.Forecast f){
        HorizontalScrollView scroll=new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0,0,dp(6),0);
        for(WeatherClient.Day d:f.days){ LinearLayout c=card(); c.setPadding(dp(10),dp(9),dp(10),dp(9)); c.addView(text(d.label,12,Color.rgb(174,185,203),true)); c.addView(text(d.max+"°",22,Color.WHITE,true)); c.addView(text(d.min+"° mín",11,Color.rgb(174,185,203),false)); c.addView(text("☂ "+WeatherClient.probabilityText(d.rainProbability),11,Color.rgb(120,183,255),true)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(82),dp(106)); lp.setMarginEnd(dp(8)); row.addView(c,lp); }
        scroll.addView(row); content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(112)));
    }

    private void renderHours(WeatherClient.Forecast f){
        HorizontalScrollView scroll=new HorizontalScrollView(this); scroll.setHorizontalScrollBarEnabled(false); LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        for(WeatherClient.Hour h:f.hours){ LinearLayout c=card(); c.setPadding(dp(8),dp(7),dp(8),dp(7)); c.addView(text(WeatherClient.hourLabel(h.iso),11,Color.rgb(174,185,203),true)); c.addView(text(h.temp+"°",18,Color.WHITE,true)); c.addView(text(WeatherClient.probabilityText(h.rainProbability),10,Color.rgb(120,183,255),true)); c.addView(text("↗"+h.gust,9,Color.rgb(174,185,203),false)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(62),dp(88)); lp.setMarginEnd(dp(6)); row.addView(c,lp); }
        scroll.addView(row); content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(94)));
    }

    private void renderPrecaution(WeatherRepository.Result result){
        LinearLayout alert=card(); alert.setPadding(dp(14),dp(11),dp(14),dp(11)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(8);
        if(!result.safeForAlerts()){ alert.setBackground(cardBg(Color.rgb(47,45,28),16)); alert.addView(text("Precauciones pausadas · datos guardados hace "+ForecastFreshness.ageLabel(result.ageMillis),12,Color.rgb(241,211,139),true)); content.addView(alert,lp); return; }
        int rainMax=0,zondaMax=0,persistent=0,maxPersistent=0; String rainStart=null,zondaStart=null; boolean storm=false;
        for(WeatherClient.Hour h:result.forecast.hours){ boolean rain=h.rainProbability>=50&&(h.precipitation>=0.2||WeatherClient.isRainCode(h.code)); if(rain){rainMax=Math.max(rainMax,h.rainProbability);if(rainStart==null)rainStart=WeatherClient.hourLabel(h.iso);storm|=WeatherClient.isStormCode(h.code);} int zs=WeatherClient.zondaScore(h); if(zs>0){persistent++;maxPersistent=Math.max(maxPersistent,persistent);if(zondaStart==null)zondaStart=WeatherClient.hourLabel(h.iso);zondaMax=Math.max(zondaMax,zs);}else persistent=0; }
        boolean zonda=zondaMax>=70||maxPersistent>=2;
        if(rainMax==0&&!zonda){alert.setBackground(cardBg(Color.rgb(20,47,39),16));alert.addView(text("✓ Sin precauciones relevantes en 24 h",13,Color.rgb(156,232,194),true));}
        else{alert.setBackground(cardBg(Color.rgb(72,48,18),16));StringBuilder s=new StringBuilder("Precaución · ");if(rainMax>0)s.append(storm?"tormenta ":"lluvia ").append(rainMax).append("% desde ").append(rainStart);if(zonda){if(rainMax>0)s.append(" · ");s.append("posible Zonda ").append(zondaMax).append("% desde ").append(zondaStart);}alert.addView(text(s.toString(),13,Color.rgb(255,211,123),true));}
        content.addView(alert,lp);
    }

    private String combinedStatus(WeatherRepository.Result local,WeatherRepository.Result utn,boolean deviceLocation){ if(local.isSuccess()){String v=local.statusText();if(!deviceLocation)v="Sin ubicación precisa · "+v;if(!utn.isSuccess())v+=" · UTN sin actualizar";return v;} if(utn.isSuccess())return "Sin datos locales · UTN: "+utn.statusText(); return local.statusText(); }
    private void renderUnavailable(String message){LinearLayout v=card();v.setPadding(dp(14),dp(12),dp(14),dp(12));v.setBackground(cardBg(Color.rgb(52,31,31),16));v.addView(text(message,12,Color.rgb(255,178,178),true));content.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    private void section(String name){TextView v=text(name,17,Color.WHITE,true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(38));lp.topMargin=dp(10);content.addView(v,lp);}
    private LinearLayout card(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setGravity(Gravity.CENTER_VERTICAL);v.setBackground(cardBg(Color.rgb(18,24,36),16));return v;}
    private GradientDrawable cardBg(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));g.setStroke(dp(1),Color.rgb(39,51,73));return g;}
    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private boolean hasLocation(){return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private Point bestPoint(){if(hasLocation())try{LocationManager lm=(LocationManager)getSystemService(Context.LOCATION_SERVICE);Location best=null;for(String p:lm.getProviders(true)){Location c=lm.getLastKnownLocation(p);if(c!=null&&(best==null||c.getTime()>best.getTime()||(c.getTime()==best.getTime()&&c.getAccuracy()<best.getAccuracy())))best=c;}if(best!=null)return new Point(best.getLatitude(),best.getLongitude(),true);}catch(SecurityException ignored){}return new Point(UTN_LAT,UTN_LON,false);}
    private void savePoint(Point p){getSharedPreferences("last_location_v6",MODE_PRIVATE).edit().putLong("lat",Double.doubleToRawLongBits(p.lat)).putLong("lon",Double.doubleToRawLongBits(p.lon)).apply();}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==LOCATION_REQUEST)loadAll();}
    @Override protected void onDestroy(){destroyed=true;loadGate.invalidate();if(executor!=null)executor.shutdownNow();super.onDestroy();}
    private static final class Point{final double lat,lon;final boolean fromDevice;Point(double lat,double lon,boolean fromDevice){this.lat=lat;this.lon=lon;this.fromDevice=fromDevice;}}
}

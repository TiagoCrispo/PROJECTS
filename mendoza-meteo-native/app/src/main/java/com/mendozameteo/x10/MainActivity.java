package com.mendozameteo.x10;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private static final long LOCATION_TIMEOUT_MILLIS=4_500L;
    private final LoadGate loadGate=new LoadGate();
    private ExecutorService executor;
    private WeatherRepository repository;
    private OfficialAlertRepository officialAlerts;
    private LocationResolver locationResolver;
    private LinearLayout content;
    private TextView status, refresh;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        executor=Executors.newFixedThreadPool(4);
        repository=new WeatherRepository(getApplicationContext());
        officialAlerts=new OfficialAlertRepository(getApplicationContext());
        locationResolver=new LocationResolver(getApplicationContext());
        buildShell();
        if(hasLocation()) loadAll();
        else {
            status.setText("Permite ubicación para usar tu clima local…");
            requestLocationPermissions();
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
        refresh=text("Actualizar",13,Color.rgb(120,183,255),true); refresh.setGravity(Gravity.CENTER); refresh.setBackground(cardBg(Color.rgb(26,37,56),14)); refresh.setOnClickListener(v->{if(hasLocation())loadAll();else requestLocationPermissions();}); head.addView(refresh,new LinearLayout.LayoutParams(dp(92),dp(38)));
        content.addView(head);
        status=text("Cargando pronóstico…",12,Color.rgb(174,185,203),false); content.addView(status,new LinearLayout.LayoutParams(-1,dp(34)));
    }

    private void requestLocationPermissions(){
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_REQUEST);
    }

    private void applyContentPadding(int l,int t,int r,int b){ if(content!=null) content.setPadding(dp(14)+l,dp(10)+t,dp(14)+r,dp(28)+b); }

    private void loadAll(){
        if(destroyed||executor==null||executor.isShutdown())return;
        final long token=loadGate.begin();
        if(token==LoadGate.REJECTED){status.setText("Ya se está actualizando…");return;}
        setLoadingUi(true); while(content.getChildCount()>2)content.removeViewAt(2);
        executor.execute(()->{
            LocationResolver.Point point=locationResolver.resolve(UTN_LAT,UTN_LON,LOCATION_TIMEOUT_MILLIS);
            if(!loadGate.isActive(token)||Thread.currentThread().isInterrupted())return;
            if(!point.fromUserLocation()){
                Future<WeatherRepository.Result> weatherTask=executor.submit(()->repository.load("utn",UTN_LAT,UTN_LON));
                Future<OfficialAlertRepository.Result> officialTask=executor.submit(()->officialAlerts.load(UTN_LAT,UTN_LON));
                try{
                    WeatherRepository.Result utn=weatherTask.get();
                    OfficialAlertRepository.Result official=officialTask.get();
                    if(!loadGate.isActive(token)||Thread.currentThread().isInterrupted())return;
                    if(utn.isSuccess()) WeatherWidgetProvider.publishForecast(getApplicationContext(),utn.forecast,utn.origin==WeatherRepository.Origin.CACHE,utn.freshness);
                    postResult(token,()->render(utn,utn,point,official));
                }catch(InterruptedException e){Thread.currentThread().interrupt();postResult(token,()->status.setText("Actualización cancelada."));}
                catch(ExecutionException e){postResult(token,()->status.setText("Error interno al actualizar."));}
                finally{weatherTask.cancel(true);officialTask.cancel(true);}
                return;
            }
            Future<WeatherRepository.Result> localTask=executor.submit(()->repository.load("local",point.lat,point.lon));
            Future<WeatherRepository.Result> utnTask=executor.submit(()->repository.load("utn",UTN_LAT,UTN_LON));
            Future<OfficialAlertRepository.Result> officialTask=executor.submit(()->officialAlerts.load(point.lat,point.lon));
            try{
                WeatherRepository.Result local=localTask.get(), utn=utnTask.get();
                OfficialAlertRepository.Result official=officialTask.get();
                if(!loadGate.isActive(token)||Thread.currentThread().isInterrupted())return;
                if(local.isSuccess()) WeatherWidgetProvider.publishForecast(getApplicationContext(),local.forecast,local.origin==WeatherRepository.Origin.CACHE,local.freshness);
                postResult(token,()->render(local,utn,point,official));
            }catch(InterruptedException e){Thread.currentThread().interrupt();postResult(token,()->status.setText("Actualización cancelada."));}
            catch(ExecutionException e){postResult(token,()->status.setText("Error interno al actualizar el pronóstico."));}
            finally{localTask.cancel(true);utnTask.cancel(true);officialTask.cancel(true);}
        });
    }

    private void postResult(long token,Runnable action){ runOnUiThread(()->{ if(destroyed||!loadGate.isActive(token))return; try{action.run();}finally{loadGate.finish(token);setLoadingUi(false);} }); }
    private void setLoadingUi(boolean loading){ if(status!=null&&loading)status.setText("Buscando ubicación y actualizando…"); if(refresh!=null){refresh.setEnabled(!loading);refresh.setAlpha(loading?0.55f:1f);} }

    private void render(WeatherRepository.Result local,WeatherRepository.Result utn,LocationResolver.Point point,OfficialAlertRepository.Result official){
        WeatherRepository.Result primary=local.isSuccess()?local:(utn.isSuccess()?utn:null);
        status.setText(combinedStatus(local,utn,point,official));
        if(primary==null){renderUnavailable("No hay pronóstico disponible ni datos guardados utilizables.");if(official!=null&&official.hasAlerts())renderOfficialAlerts(official);return;}
        String suffix=local.isSuccess()?(point.fromUserLocation()?"":" · referencia UTN"):" · UTN (sin datos locales)";
        section("7 días"+suffix); renderDays(primary.forecast);
        section("Próximas 24 h"+suffix); renderHours(primary.forecast);
        section("Mi ubicación");
        if(local.isSuccess()){
            LinearLayout current=card(); current.setPadding(dp(14),dp(12),dp(14),dp(12)); current.addView(text(local.forecast.current.temp+"°",36,Color.WHITE,true));
            current.addView(text(point.cardPrefix()+"Sensación "+local.forecast.current.feels+"° · Humedad "+local.forecast.current.humidity+"% · Viento "+local.forecast.current.wind+" km/h · Ráfagas "+local.forecast.current.gust+" km/h",12,Color.rgb(174,185,203),false));
            content.addView(current,new LinearLayout.LayoutParams(-1,-2));
            renderOfficialAlerts(official);
            renderPrecaution(local,official);
        } else {
            renderUnavailable("Tu ubicación no tiene datos disponibles. UTN sigue funcionando como referencia.");
            renderOfficialAlerts(official);
        }
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

    private void renderOfficialAlerts(OfficialAlertRepository.Result result){
        if(result==null||!result.hasAlerts())return;
        int shown=0;
        long now=System.currentTimeMillis();
        for(OfficialAlert official:result.alerts){
            if(shown++>=3)break;
            LinearLayout alert=card(); alert.setPadding(dp(14),dp(11),dp(14),dp(11));
            int bg=Color.rgb(28,39,58), titleColor=Color.rgb(184,211,255);
            if(official.level==OfficialAlert.Level.YELLOW){bg=Color.rgb(66,58,17);titleColor=Color.rgb(255,226,104);}
            else if(official.level==OfficialAlert.Level.ORANGE){bg=Color.rgb(82,43,16);titleColor=Color.rgb(255,181,96);}
            else if(official.level==OfficialAlert.Level.RED){bg=Color.rgb(78,25,29);titleColor=Color.rgb(255,143,151);}
            alert.setBackground(cardBg(bg,16));
            String level=official.level==OfficialAlert.Level.UNKNOWN?"":(" · "+official.level.label);
            alert.addView(text("OFICIAL · "+official.sourceLabel()+level,11,titleColor,true));
            alert.addView(text(clip(official.title(),180),13,Color.WHITE,true));
            if(!official.area.isEmpty())alert.addView(text(clip(official.area,220),10,Color.rgb(218,220,224),false));
            String timing=official.timingText(now);
            if(!timing.isEmpty())alert.addView(text(timing,10,titleColor,true));
            if(!official.description.isEmpty())alert.addView(text(clip(official.description,360),10,Color.rgb(232,224,208),false));
            if(!official.instruction.isEmpty())alert.addView(text(clip(official.instruction,260),10,titleColor,true));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);content.addView(alert,lp);
        }
        if(result.alerts.size()>3){TextView more=text("+"+(result.alerts.size()-3)+" alerta(s) oficial(es) vigente(s)",10,Color.rgb(174,185,203),false);content.addView(more,new LinearLayout.LayoutParams(-1,dp(24)));}
    }

    private void renderPrecaution(WeatherRepository.Result result,OfficialAlertRepository.Result official){
        LinearLayout alert=card(); alert.setPadding(dp(14),dp(11),dp(14),dp(11)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(8);
        if(!result.safeForAlerts()){
            alert.setBackground(cardBg(Color.rgb(47,45,28),16));
            alert.addView(text("Precauciones X10 pausadas · datos guardados hace "+ForecastFreshness.ageLabel(result.ageMillis),12,Color.rgb(241,211,139),true));
            alert.addView(text("Las alertas oficiales de arriba son independientes del forecast X10.",10,Color.rgb(188,180,145),false));
            content.addView(alert,lp); return;
        }

        AlertEngine.Report report=AlertEngine.analyze(result.forecast);
        if(!report.hasHazards()){
            alert.setBackground(cardBg(Color.rgb(20,47,39),16));
            String label=official!=null&&official.hasAlerts()?"✓ Sin señales X10 adicionales en las próximas 24 h":"✓ Sin precauciones relevantes en las próximas 24 h";
            alert.addView(text(label,13,Color.rgb(156,232,194),true));
            alert.addView(text("Heurística X10 · solo horas futuras · no sustituye al SMN",10,Color.rgb(125,184,157),false));
            content.addView(alert,lp); return;
        }

        int bg=Color.rgb(72,48,18), titleColor=Color.rgb(255,211,123);
        if(report.highestSeverity==AlertEngine.Severity.IMPORTANT){ bg=Color.rgb(78,43,18); titleColor=Color.rgb(255,190,112); }
        else if(report.highestSeverity==AlertEngine.Severity.DANGER){ bg=Color.rgb(74,27,31); titleColor=Color.rgb(255,151,157); }
        alert.setBackground(cardBg(bg,16));
        alert.addView(text((official!=null&&official.hasAlerts()?"Complemento X10":"Precaución inteligente X10")+" · "+report.highestSeverity.label,13,titleColor,true));
        int shown=0;
        for(AlertEngine.Event event:report.events){
            if(shown>=3)break;
            alert.addView(text(event.detailText(),11,Color.rgb(235,224,205),shown==0));
            shown++;
        }
        if(report.events.size()>shown)alert.addView(text("+"+(report.events.size()-shown)+" evento(s) adicional(es) en 24 h",10,Color.rgb(202,190,169),false));
        alert.addView(text("Heurística X10 · nunca cambia el nivel de una alerta oficial",10,Color.rgb(202,190,169),false));
        content.addView(alert,lp);
    }

    private String combinedStatus(WeatherRepository.Result local,WeatherRepository.Result utn,LocationResolver.Point point,OfficialAlertRepository.Result official){
        String officialStatus=official==null?"alertas oficiales pendientes":official.statusText();
        if(local.isSuccess()){String v=point.statusLabel()+" · "+local.statusText();if(!utn.isSuccess()&&point.fromUserLocation())v+=" · UTN sin actualizar";return v+" · "+officialStatus;}
        if(utn.isSuccess())return point.statusLabel()+" · sin datos locales · UTN: "+utn.statusText()+" · "+officialStatus;
        return point.statusLabel()+" · "+local.statusText()+" · "+officialStatus;
    }
    private String clip(String value,int max){if(value==null)return "";String clean=value.trim().replaceAll("\\s+"," ");return clean.length()<=max?clean:clean.substring(0,max-1)+"…";}
    private void renderUnavailable(String message){LinearLayout v=card();v.setPadding(dp(14),dp(12),dp(14),dp(12));v.setBackground(cardBg(Color.rgb(52,31,31),16));v.addView(text(message,12,Color.rgb(255,178,178),true));content.addView(v,new LinearLayout.LayoutParams(-1,-2));}
    private void section(String name){TextView v=text(name,17,Color.WHITE,true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(38));lp.topMargin=dp(10);content.addView(v,lp);}
    private LinearLayout card(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setGravity(Gravity.CENTER_VERTICAL);v.setBackground(cardBg(Color.rgb(18,24,36),16));return v;}
    private GradientDrawable cardBg(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));g.setStroke(dp(1),Color.rgb(39,51,73));return g;}
    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private boolean hasLocation(){return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==LOCATION_REQUEST)loadAll();}
    @Override protected void onDestroy(){destroyed=true;loadGate.invalidate();if(locationResolver!=null)locationResolver.cancel();if(executor!=null)executor.shutdownNow();super.onDestroy();}
}

package com.mendozameteo.x10;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final double UTN_LAT=-32.896748, UTN_LON=-68.853418;
    private static final int LOCATION_REQUEST=42;
    private final ExecutorService executor=Executors.newFixedThreadPool(2);
    private LinearLayout content;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(11,15,22));
        getWindow().setNavigationBarColor(Color.rgb(11,15,22));
        buildShell();
        if (!hasLocation()) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_REQUEST);
        loadAll();
    }

    private void buildShell(){
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(11,15,22));
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(14),dp(10),dp(14),dp(28));
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2)); setContentView(scroll);
        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title=text("Mendoza Meteo",24,Color.WHITE,true); head.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView refresh=text("Actualizar",13,Color.rgb(120,183,255),true); refresh.setGravity(Gravity.CENTER); refresh.setBackground(cardBg(Color.rgb(26,37,56),14));
        refresh.setOnClickListener(v->loadAll()); head.addView(refresh,new LinearLayout.LayoutParams(dp(92),dp(38)));
        content.addView(head);
        status=text("Cargando pronóstico…",12,Color.rgb(174,185,203),false); content.addView(status,new LinearLayout.LayoutParams(-1,dp(30)));
    }

    private void loadAll(){
        status.setText("Actualizando…");
        while(content.getChildCount()>2) content.removeViewAt(2);
        double[] point=bestPoint(); savePoint(point);
        executor.execute(()->{
            try{
                WeatherClient.Forecast local=WeatherClient.fetch(point[0],point[1]);
                WeatherClient.Forecast utn=WeatherClient.fetch(UTN_LAT,UTN_LON);
                runOnUiThread(()->render(local,utn));
            }catch(Exception e){ runOnUiThread(()->status.setText("No se pudo actualizar. Revisa internet y vuelve a intentar.")); }
        });
    }

    private void render(WeatherClient.Forecast local, WeatherClient.Forecast utn){
        status.setText("Actualizado · America/Argentina/Mendoza");
        section("7 días");
        HorizontalScrollView daysScroll=new HorizontalScrollView(this); daysScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout days=new LinearLayout(this); days.setOrientation(LinearLayout.HORIZONTAL); days.setPadding(0,0,dp(6),0);
        for(WeatherClient.Day d:local.days){
            LinearLayout c=card(); c.setPadding(dp(10),dp(9),dp(10),dp(9));
            c.addView(text(d.label,12,Color.rgb(174,185,203),true));
            c.addView(text(d.max+"°",22,Color.WHITE,true));
            c.addView(text(d.min+"° mín",11,Color.rgb(174,185,203),false));
            c.addView(text("☂ "+d.rainProbability+"%",11,Color.rgb(120,183,255),true));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(82),dp(106)); lp.setMarginEnd(dp(8)); days.addView(c,lp);
        }
        daysScroll.addView(days); content.addView(daysScroll,new LinearLayout.LayoutParams(-1,dp(112)));

        section("Próximas 24 h");
        HorizontalScrollView hoursScroll=new HorizontalScrollView(this); hoursScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout hours=new LinearLayout(this); hours.setOrientation(LinearLayout.HORIZONTAL);
        for(WeatherClient.Hour h:local.hours){
            LinearLayout c=card(); c.setPadding(dp(8),dp(7),dp(8),dp(7));
            c.addView(text(WeatherClient.hourLabel(h.iso),11,Color.rgb(174,185,203),true));
            c.addView(text(h.temp+"°",18,Color.WHITE,true));
            c.addView(text(h.rainProbability+"%",10,Color.rgb(120,183,255),true));
            c.addView(text("↗"+h.gust,9,Color.rgb(174,185,203),false));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(62),dp(88)); lp.setMarginEnd(dp(6)); hours.addView(c,lp);
        }
        hoursScroll.addView(hours); content.addView(hoursScroll,new LinearLayout.LayoutParams(-1,dp(94)));

        section("Mi ubicación");
        LinearLayout current=card(); current.setPadding(dp(14),dp(12),dp(14),dp(12));
        current.addView(text(local.current.temp+"°",36,Color.WHITE,true));
        current.addView(text("Sensación "+local.current.feels+"° · Humedad "+local.current.humidity+"% · Ráfagas "+local.current.gust+" km/h",12,Color.rgb(174,185,203),false));
        content.addView(current,new LinearLayout.LayoutParams(-1,-2));
        renderPrecaution(local);

        section("UTN Mendoza");
        LinearLayout uc=card(); uc.setPadding(dp(14),dp(12),dp(14),dp(12));
        uc.addView(text(utn.current.temp+"° · "+WeatherClient.weatherText(utn.current.code),18,Color.WHITE,true));
        WeatherClient.Day ud=utn.days.get(0);
        uc.addView(text("Máx "+ud.max+"° · Mín "+ud.min+"° · Lluvia "+ud.rainProbability+"% · Ráfagas "+utn.current.gust+" km/h",12,Color.rgb(174,185,203),false));
        content.addView(uc,new LinearLayout.LayoutParams(-1,-2));
        WeatherWidgetProvider.refreshAll(this);
    }

    private void renderPrecaution(WeatherClient.Forecast f){
        int rainMax=0, zondaMax=0, persistent=0, maxPersistent=0; String rainStart=null,zondaStart=null; boolean storm=false;
        for(WeatherClient.Hour h:f.hours){
            boolean rain=h.rainProbability>=50 && (h.precipitation>=0.2 || WeatherClient.isRainCode(h.code));
            if(rain){ rainMax=Math.max(rainMax,h.rainProbability); if(rainStart==null)rainStart=WeatherClient.hourLabel(h.iso); storm|=WeatherClient.isStormCode(h.code); }
            int zs=WeatherClient.zondaScore(h); if(zs>0){persistent++; maxPersistent=Math.max(maxPersistent,persistent); if(zondaStart==null)zondaStart=WeatherClient.hourLabel(h.iso); zondaMax=Math.max(zondaMax,zs);} else persistent=0;
        }
        boolean zonda=zondaMax>=70 || maxPersistent>=2;
        LinearLayout alert=card(); alert.setPadding(dp(14),dp(11),dp(14),dp(11));
        if(rainMax==0 && !zonda){ alert.setBackground(cardBg(Color.rgb(20,47,39),16)); alert.addView(text("✓ Sin precauciones relevantes en 24 h",13,Color.rgb(156,232,194),true)); }
        else{
            alert.setBackground(cardBg(Color.rgb(72,48,18),16));
            StringBuilder s=new StringBuilder("Precaución · ");
            if(rainMax>0) s.append(storm?"tormenta ":"lluvia ").append(rainMax).append("% desde ").append(rainStart);
            if(zonda){ if(rainMax>0)s.append(" · "); s.append("posible Zonda ").append(zondaMax).append("% desde ").append(zondaStart); }
            alert.addView(text(s.toString(),13,Color.rgb(255,211,123),true));
        }
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=dp(8); content.addView(alert,lp);
    }

    private void section(String name){ TextView v=text(name,17,Color.WHITE,true); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(38)); lp.topMargin=dp(10); content.addView(v,lp); }
    private LinearLayout card(){ LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setGravity(Gravity.CENTER_VERTICAL); v.setBackground(cardBg(Color.rgb(18,24,36),16)); return v; }
    private GradientDrawable cardBg(int color,int radius){ GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); g.setStroke(dp(1),Color.rgb(39,51,73)); return g; }
    private TextView text(String value,int sp,int color,boolean bold){ TextView t=new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); t.setIncludeFontPadding(false); if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
    private boolean hasLocation(){ return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED; }
    private double[] bestPoint(){
        if(hasLocation()) try{
            LocationManager lm=(LocationManager)getSystemService(Context.LOCATION_SERVICE); Location best=null;
            for(String p:lm.getProviders(true)){ Location c=lm.getLastKnownLocation(p); if(c!=null && (best==null || c.getTime()>best.getTime()))best=c; }
            if(best!=null)return new double[]{best.getLatitude(),best.getLongitude()};
        }catch(SecurityException ignored){}
        return new double[]{UTN_LAT,UTN_LON};
    }
    private void savePoint(double[] p){ getSharedPreferences("last_location_v6",MODE_PRIVATE).edit().putLong("lat",Double.doubleToRawLongBits(p[0])).putLong("lon",Double.doubleToRawLongBits(p[1])).apply(); }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){ super.onRequestPermissionsResult(r,p,g); if(r==LOCATION_REQUEST)loadAll(); }
    @Override protected void onDestroy(){ executor.shutdownNow(); super.onDestroy(); }
}

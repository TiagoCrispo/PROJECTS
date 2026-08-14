package com.fer.bandlab;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

public class WorkoutService extends Service implements LocationListener {
    public static final String ACTION_START = "com.fer.bandlab.START";
    public static final String ACTION_PAUSE = "com.fer.bandlab.PAUSE";
    public static final String ACTION_RESUME = "com.fer.bandlab.RESUME";
    public static final String ACTION_STOP = "com.fer.bandlab.STOP";
    public static final String ACTION_UPDATE = "com.fer.bandlab.UPDATE";
    public static final String EXTRA_TYPE = "type";
    public static final String PREFS = "bandlab_prefs";
    public static final String KEY_HISTORY = "history";

    public static final class State {
        public String type = "";
        public boolean active = false;
        public boolean manualPaused = false;
        public boolean autoPaused = false;
        public long elapsedMs = 0;
        public long activeMs = 0;
        public long pausedMs = 0;
        public int pauseCount = 0;
        public double distanceM = 0;
        public double speedMps = 0;
        public float accuracyM = -1;
        public double calories = 0;
        public long startedEpoch = 0;
        public String status = "Listo";
    }

    public static volatile State STATE = new State();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private Location lastLocation;
    private long startedElapsed;
    private long lastTick;
    private long lowSpeedSince = 0;
    private long movingSince = 0;
    private long lastNotify = 0;
    private boolean stopped = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!STATE.active) return;
            long now = SystemClock.elapsedRealtime();
            long delta = Math.max(0, now - lastTick);
            lastTick = now;
            STATE.elapsedMs = now - startedElapsed;
            if (STATE.manualPaused || STATE.autoPaused) STATE.pausedMs += delta;
            else STATE.activeMs += delta;
            STATE.calories = estimateCalories();
            broadcast();
            if (now - lastNotify > 5000) {
                lastNotify = now;
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(91, notification());
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel("bandlab_workout", "Entrenamientos BandLab", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mantiene el registro activo con la pantalla apagada");
            nm.createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) startWorkout(intent.getStringExtra(EXTRA_TYPE));
        else if (ACTION_PAUSE.equals(action)) manualPause();
        else if (ACTION_RESUME.equals(action)) manualResume();
        else if (ACTION_STOP.equals(action)) finishWorkout();
        return START_NOT_STICKY;
    }

    private void startWorkout(String type) {
        if (STATE.active) return;
        stopped = false;
        STATE = new State();
        STATE.type = type == null ? "WALK" : type;
        STATE.active = true;
        STATE.status = "Registrando";
        STATE.startedEpoch = System.currentTimeMillis();
        startedElapsed = SystemClock.elapsedRealtime();
        lastTick = startedElapsed;

        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) {
            int fgType;
            if ("ROPE".equals(STATE.type) && Build.VERSION.SDK_INT >= 34) fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
            else if (!"ROPE".equals(STATE.type)) fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            else fgType = 0;
            startForeground(91, n, fgType);
        } else startForeground(91, n);

        if (!"ROPE".equals(STATE.type)) startLocation();
        handler.post(ticker);
        broadcast();
    }

    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            STATE.status = "Falta permiso de ubicación";
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
        } catch (Exception e) {
            STATE.status = "GPS no disponible";
        }
    }

    private void manualPause() {
        if (!STATE.active || STATE.manualPaused) return;
        STATE.manualPaused = true;
        STATE.pauseCount++;
        STATE.status = "Pausa manual";
        broadcast();
    }

    private void manualResume() {
        if (!STATE.active) return;
        STATE.manualPaused = false;
        STATE.autoPaused = false;
        lowSpeedSince = 0;
        movingSince = 0;
        STATE.status = "Registrando";
        broadcast();
    }

    private void finishWorkout() {
        if (!STATE.active || stopped) return;
        stopped = true;
        long now = SystemClock.elapsedRealtime();
        long delta = Math.max(0, now - lastTick);
        if (STATE.manualPaused || STATE.autoPaused) STATE.pausedMs += delta; else STATE.activeMs += delta;
        STATE.elapsedMs = now - startedElapsed;
        STATE.calories = estimateCalories();
        saveHistory();
        STATE.active = false;
        STATE.status = "Guardado";
        handler.removeCallbacks(ticker);
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        broadcast();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onLocationChanged(Location loc) {
        if (!STATE.active || loc == null) return;
        STATE.accuracyM = loc.hasAccuracy() ? loc.getAccuracy() : -1;
        if (loc.hasAccuracy() && loc.getAccuracy() > 35f) return;

        double speed = loc.hasSpeed() ? loc.getSpeed() : 0;
        if (!loc.hasSpeed() && lastLocation != null) {
            long dt = loc.getTime() - lastLocation.getTime();
            if (dt > 300) speed = lastLocation.distanceTo(loc) / (dt / 1000.0);
        }
        STATE.speedMps = Math.max(0, speed);
        long now = SystemClock.elapsedRealtime();
        updateAutoPause(now, STATE.speedMps);

        if (lastLocation != null && !STATE.manualPaused && !STATE.autoPaused) {
            float d = lastLocation.distanceTo(loc);
            if (d >= 0.7f && d < 100f) STATE.distanceM += d;
        }
        lastLocation = loc;
        broadcast();
    }

    private void updateAutoPause(long now, double speed) {
        double pauseThreshold = "CYCLE".equals(STATE.type) ? 0.8 : 0.35;
        double resumeThreshold = "CYCLE".equals(STATE.type) ? 1.5 : 0.65;
        long pauseDelay = 6000L;
        long resumeDelay = 3000L;

        if (!STATE.autoPaused && !STATE.manualPaused) {
            if (speed < pauseThreshold) {
                if (lowSpeedSince == 0) lowSpeedSince = now;
                if (now - lowSpeedSince >= pauseDelay) {
                    STATE.autoPaused = true;
                    STATE.pauseCount++;
                    STATE.status = "Auto-pausa";
                    movingSince = 0;
                }
            } else lowSpeedSince = 0;
        } else if (STATE.autoPaused && !STATE.manualPaused) {
            if (speed > resumeThreshold) {
                if (movingSince == 0) movingSince = now;
                if (now - movingSince >= resumeDelay) {
                    STATE.autoPaused = false;
                    STATE.status = "Reanudado automáticamente";
                    lowSpeedSince = 0;
                }
            } else movingSince = 0;
        }
    }

    private double estimateCalories() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        double kg = 75.0;
        try { kg = Double.parseDouble(p.getString("weight", "75")); } catch (Exception ignored) {}
        double met;
        switch (STATE.type) {
            case "RUN": met = 8.0; break;
            case "CYCLE": met = 6.8; break;
            case "ROPE": met = 10.0; break;
            default: met = 3.5;
        }
        return met * kg * (STATE.activeMs / 3600000.0);
    }

    private void saveHistory() {
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONArray old = new JSONArray(p.getString(KEY_HISTORY, "[]"));
            JSONArray next = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("id", UUID.randomUUID().toString());
            o.put("type", STATE.type);
            o.put("started", STATE.startedEpoch);
            o.put("elapsed", STATE.elapsedMs);
            o.put("active", STATE.activeMs);
            o.put("paused", STATE.pausedMs);
            o.put("pauses", STATE.pauseCount);
            o.put("distance", STATE.distanceM);
            o.put("calories", STATE.calories);
            next.put(o);
            for (int i = 0; i < Math.min(old.length(), 199); i++) next.put(old.getJSONObject(i));
            p.edit().putString(KEY_HISTORY, next.toString()).apply();
        } catch (Exception ignored) {}
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = typeLabel(STATE.type) + " · " + formatTime(STATE.activeMs) + " activo";
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "bandlab_workout") : new Notification.Builder(this);
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("BandLab · entrenamiento")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build();
    }

    private void broadcast() {
        Intent i = new Intent(ACTION_UPDATE);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private static String typeLabel(String t) {
        if ("RUN".equals(t)) return "Correr";
        if ("CYCLE".equals(t)) return "Ciclismo";
        if ("ROPE".equals(t)) return "Saltar cuerda";
        return "Caminar";
    }

    public static String formatTime(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%02d:%02d", m, sec);
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) { STATE.status = "GPS desactivado"; broadcast(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        super.onDestroy();
    }
}

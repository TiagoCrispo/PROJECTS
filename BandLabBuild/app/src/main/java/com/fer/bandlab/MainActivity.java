package com.fer.bandlab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int REQ_PERMS = 40;
    private static final int REQ_PHOTO = 41;
    private static final int BG = Color.rgb(7, 9, 12);
    private static final int SURFACE = Color.rgb(16, 19, 25);
    private static final int SURFACE2 = Color.rgb(23, 27, 34);
    private static final int TEXT = Color.rgb(244, 246, 248);
    private static final int MUTED = Color.rgb(147, 155, 168);
    private static final int ACCENT = Color.rgb(185, 247, 210);
    private static final int BLUE = Color.rgb(184, 216, 255);
    private static final int WARM = Color.rgb(255, 215, 168);

    private FrameLayout content;
    private LinearLayout nav;
    private int currentTab = 0;
    private SharedPreferences prefs;
    private SensorManager sensorManager;
    private Sensor stepSensor;
    private float latestStepRaw = -1;
    private long todaySteps = 0;
    private TextView stepValue;
    private final Map<String, ScanResult> scanResults = new LinkedHashMap<>();
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt gatt;
    private TextView deviceStatus;
    private LinearLayout deviceList;
    private ImageView photoPreview;
    private TextView photoStatus;
    private File preparedPhoto;

    private final BroadcastReceiver workoutReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (currentTab == 1) showTraining();
            else if (currentTab == 2) showHistory();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) w.getDecorView().setSystemUiVisibility(0);

        prefs = getSharedPreferences(WorkoutService.PREFS, MODE_PRIVATE);
        requestRuntimePermissions();
        setupStepCounter();
        setupBluetooth();
        buildShell();
        registerWorkoutReceiver();
        showHealth();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(8), dp(14), dp(8));

        content = new FrameLayout(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(content, cp);

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(0, dp(7), 0, dp(2));
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        addNav("Salud", 0);
        addNav("Entrenar", 1);
        addNav("Historial", 2);
        addNav("Dispositivo", 3);
        setContentView(root);
        updateNav();
    }

    private void addNav(String label, int tab) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setOnClickListener(v -> {
            currentTab = tab;
            updateNav();
            if (tab == 0) showHealth(); else if (tab == 1) showTraining(); else if (tab == 2) showHistory(); else showDevice();
        });
        nav.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void updateNav() {
        if (nav == null) return;
        for (int i = 0; i < nav.getChildCount(); i++) {
            TextView t = (TextView) nav.getChildAt(i);
            t.setTextColor(i == currentTab ? ACCENT : MUTED);
            t.setBackground(roundRect(i == currentTab ? SURFACE2 : Color.TRANSPARENT, 18));
        }
    }

    private ScrollView page() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(2), dp(18), dp(2), dp(28));
        scroll.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setTag(column);
        return scroll;
    }

    private LinearLayout column(ScrollView s) { return (LinearLayout) s.getTag(); }

    private void setPage(ScrollView scroll) {
        content.removeAllViews();
        content.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void header(LinearLayout c, String eyebrow, String title, String subtitle) {
        TextView e = text(eyebrow.toUpperCase(Locale.ROOT), 12, ACCENT, true);
        c.addView(e);
        TextView h = text(title, 30, TEXT, true);
        h.setPadding(0, dp(7), 0, 0);
        c.addView(h);
        TextView s = text(subtitle, 14, MUTED, false);
        s.setPadding(0, dp(7), 0, dp(16));
        c.addView(s);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        card.setBackground(roundRect(SURFACE, 22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        parent.addView(card, lp);
        return card;
    }

    private void showHealth() {
        currentTab = 0; updateNav();
        ScrollView s = page(); LinearLayout c = column(s);
        header(c, "Salud", "Tu día, sin ruido", "Datos locales y métricas que podemos verificar.");

        LinearLayout activity = card(c);
        activity.addView(text("ACTIVIDAD DE HOY", 12, MUTED, true));
        stepValue = text(String.format(Locale.US, "%,d", todaySteps), 38, TEXT, true);
        stepValue.setPadding(0, dp(5), 0, 0); activity.addView(stepValue);
        activity.addView(text("pasos registrados por el Galaxy A53", 13, MUTED, false));
        LinearLayout metrics = row(); activity.addView(metrics);
        long movement = todayWorkoutActiveMs() / 60000L;
        double kc = todayWorkoutCalories();
        metrics.addView(metric("Movimiento", movement + " min", ACCENT), weight());
        metrics.addView(metric("Entreno", String.format(Locale.US, "%.0f kcal", kc), WARM), weight());

        LinearLayout vital = card(c);
        vital.addView(text("RITMO CARDÍACO", 12, MUTED, true));
        vital.addView(text("—", 32, TEXT, true));
        vital.addView(text("La v0.2 no inventa pulsaciones: falta terminar la lectura autenticada del M2435B1.", 13, MUTED, false));

        LinearLayout stress = card(c);
        stress.addView(text("ESTRÉS", 12, MUTED, true));
        stress.addView(text("—", 32, BLUE, true));
        stress.addView(text("Pendiente de sincronización Xiaomi. Cuando el protocolo esté validado aparecerá aquí.", 13, MUTED, false));

        LinearLayout weightCard = card(c);
        weightCard.addView(text("PESO", 12, MUTED, true));
        String saved = prefs.getString("weight", "");
        TextView weightValue = text(saved.isEmpty() ? "Sin registrar" : saved + " kg", 28, TEXT, true);
        weightCard.addView(weightValue);
        Button edit = button("Actualizar peso", false);
        edit.setOnClickListener(v -> weightDialog(weightValue));
        weightCard.addView(edit);

        LinearLayout note = card(c);
        note.setBackground(roundRect(SURFACE2, 22));
        note.addView(text("PRECISIÓN", 12, ACCENT, true));
        note.addView(text("Pasos del teléfono y entrenamientos propios funcionan ya. Corazón y estrés se activarán sólo cuando la Band entregue datos reales.", 14, TEXT, false));
        setPage(s);
    }

    private void weightDialog(TextView weightValue) {
        EditText input = new EditText(this);
        input.setText(prefs.getString("weight", ""));
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("kg");
        input.setTextColor(TEXT); input.setHintTextColor(MUTED);
        FrameLayout wrap = new FrameLayout(this); wrap.setPadding(dp(20), 0, dp(20), 0); wrap.addView(input);
        new AlertDialog.Builder(this)
            .setTitle("Peso actual")
            .setView(wrap)
            .setPositiveButton("Guardar", (d, which) -> {
                String v = input.getText().toString().replace(',', '.').trim();
                try {
                    double kg = Double.parseDouble(v);
                    if (kg < 25 || kg > 350) throw new Exception();
                    prefs.edit().putString("weight", String.format(Locale.US, "%.1f", kg)).apply();
                    weightValue.setText(String.format(Locale.US, "%.1f kg", kg));
                } catch (Exception e) { toast("Ingresá un peso válido"); }
            })
            .setNegativeButton("Cancelar", null).show();
    }

    private void showTraining() {
        currentTab = 1; updateNav();
        ScrollView s = page(); LinearLayout c = column(s);
        WorkoutService.State st = WorkoutService.STATE;
        if (!st.active) {
            header(c, "Entrenar", "Elegí una actividad", "Auto-pausa para que detenerte no destruya tu ritmo.");
            activityButton(c, "Caminar", "GPS · pausas · distancia", "WALK", ACCENT);
            activityButton(c, "Correr", "ritmo activo · GPS · pausas", "RUN", BLUE);
            activityButton(c, "Ciclismo", "velocidad · distancia · auto-pausa", "CYCLE", WARM);
            activityButton(c, "Saltar cuerda", "tiempo activo · calorías estimadas", "ROPE", ACCENT);
            LinearLayout info = card(c);
            info.addView(text("AUTO-PAUSA", 12, ACCENT, true));
            info.addView(text("Parada sostenida, no un salto de GPS", 19, TEXT, true));
            info.addView(text("Caminar y correr pausan bajo ~0,35 m/s durante 6 s. Ciclismo usa un umbral mayor. Para reanudar exige movimiento sostenido durante 3 s.", 13, MUTED, false));
        } else {
            header(c, st.autoPaused ? "Auto-pausa" : st.manualPaused ? "Pausado" : "En curso", labelType(st.type), st.status);
            LinearLayout time = card(c);
            time.addView(text("TIEMPO ACTIVO", 12, MUTED, true));
            time.addView(text(WorkoutService.formatTime(st.activeMs), 40, TEXT, true));
            LinearLayout rr = row(); time.addView(rr);
            rr.addView(metric("Total", WorkoutService.formatTime(st.elapsedMs), TEXT), weight());
            rr.addView(metric("Pausado", WorkoutService.formatTime(st.pausedMs), WARM), weight());
            rr.addView(metric("Paradas", String.valueOf(st.pauseCount), TEXT), weight());

            LinearLayout gps = card(c);
            gps.addView(text("RENDIMIENTO", 12, MUTED, true));
            LinearLayout g1 = row(); gps.addView(g1);
            g1.addView(metric("Distancia", String.format(Locale.US, "%.2f km", st.distanceM / 1000.0), BLUE), weight());
            g1.addView(metric("Velocidad", String.format(Locale.US, "%.1f km/h", st.speedMps * 3.6), TEXT), weight());
            LinearLayout g2 = row(); gps.addView(g2);
            g2.addView(metric("Ritmo", pace(st.activeMs, st.distanceM), ACCENT), weight());
            g2.addView(metric("Calorías", String.format(Locale.US, "%.0f kcal", st.calories), WARM), weight());
            gps.addView(text(st.accuracyM > 0 ? "Precisión GPS ±" + Math.round(st.accuracyM) + " m" : "Esperando GPS", 13, MUTED, false));

            Button pause = button(st.manualPaused ? "Reanudar" : "Pausar", true);
            pause.setOnClickListener(v -> sendWorkoutAction(st.manualPaused ? WorkoutService.ACTION_RESUME : WorkoutService.ACTION_PAUSE));
            c.addView(pause, buttonLp());
            Button stop = button("Finalizar y guardar", false);
            stop.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Finalizar entrenamiento").setMessage("Se guardarán tiempo activo, total, pausas, distancia y calorías.")
                .setPositiveButton("Guardar", (d,w) -> sendWorkoutAction(WorkoutService.ACTION_STOP)).setNegativeButton("Seguir", null).show());
            c.addView(stop, buttonLp());
        }
        setPage(s);
    }

    private void activityButton(LinearLayout c, String title, String sub, String type, int accent) {
        LinearLayout x = card(c);
        x.setClickable(true); x.setFocusable(true);
        x.addView(text(title, 20, TEXT, true));
        x.addView(text(sub, 13, MUTED, false));
        TextView go = text("Iniciar  →", 13, accent, true); go.setPadding(0, dp(9), 0, 0); x.addView(go);
        x.setOnClickListener(v -> startWorkout(type));
    }

    private void startWorkout(String type) {
        if (!"ROPE".equals(type) && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions(); toast("Necesito ubicación para registrar este entrenamiento"); return;
        }
        if ("ROPE".equals(type) && Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions(); toast("Necesito permiso de actividad"); return;
        }
        Intent i = new Intent(this, WorkoutService.class).setAction(WorkoutService.ACTION_START).putExtra(WorkoutService.EXTRA_TYPE, type);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        showTraining();
    }

    private void sendWorkoutAction(String action) { startService(new Intent(this, WorkoutService.class).setAction(action)); }

    private void showHistory() {
        currentTab = 2; updateNav();
        ScrollView s = page(); LinearLayout c = column(s);
        header(c, "Historial", "Tus entrenamientos", "Tiempo activo, paradas y evolución guardados localmente.");
        JSONArray arr = history();
        if (arr.length() == 0) {
            LinearLayout empty = card(c);
            empty.addView(text("Todavía no hay sesiones", 20, TEXT, true));
            empty.addView(text("Terminá un entrenamiento y aparecerá aquí automáticamente.", 14, MUTED, false));
        }
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                LinearLayout h = card(c);
                h.addView(text(labelType(o.optString("type")) + " · " + date(o.optLong("started")), 18, TEXT, true));
                LinearLayout r1 = row(); h.addView(r1);
                r1.addView(metric("Activo", WorkoutService.formatTime(o.optLong("active")), ACCENT), weight());
                r1.addView(metric("Distancia", String.format(Locale.US, "%.2f km", o.optDouble("distance") / 1000.0), BLUE), weight());
                LinearLayout r2 = row(); h.addView(r2);
                r2.addView(metric("Pausas", String.valueOf(o.optInt("pauses")), TEXT), weight());
                r2.addView(metric("Calorías", String.format(Locale.US, "%.0f kcal", o.optDouble("calories")), WARM), weight());
                TextView coach = text(coach(o), 13, MUTED, false); coach.setPadding(0, dp(10), 0, 0); h.addView(coach);
            } catch (Exception ignored) {}
        }
        if (arr.length() > 0) {
            Button clear = button("Borrar historial", false);
            clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Borrar historial").setMessage("Esta acción elimina las sesiones guardadas en BandLab.")
                .setPositiveButton("Borrar", (d,w) -> { prefs.edit().remove(WorkoutService.KEY_HISTORY).apply(); showHistory(); }).setNegativeButton("Cancelar", null).show());
            c.addView(clear, buttonLp());
        }
        setPage(s);
    }

    private String coach(JSONObject o) {
        long active = o.optLong("active"), elapsed = o.optLong("elapsed"), paused = o.optLong("paused");
        double distance = o.optDouble("distance");
        if (active < 5 * 60000L) return "Coach: sesión corta; sumá más minutos activos para comparar tendencias con confianza.";
        if (elapsed > 0 && paused / (double) elapsed > 0.15) return "Coach: más del 15% del tiempo fue pausa. Compará progreso usando tiempo activo y buscá reducir paradas gradualmente.";
        if (distance > 500) return "Coach: ritmo activo " + pace(active, distance) + ". Repetí una distancia parecida para ver si mejorás sin aumentar demasiado las pausas.";
        return "Coach: buena base. Con varias sesiones similares podremos comparar continuidad, distancia y tiempo activo.";
    }

    private void showDevice() {
        currentTab = 3; updateNav();
        ScrollView s = page(); LinearLayout c = column(s);
        header(c, "Dispositivo", "Xiaomi Smart Band 9 Active", "M2435B1 · conexión BLE y modo foto.");

        LinearLayout status = card(c);
        status.addView(text("CONEXIÓN", 12, MUTED, true));
        deviceStatus = text(gatt == null ? "Desconectada" : "Conectada por GATT", 20, TEXT, true); status.addView(deviceStatus);
        LinearLayout actions = row(); status.addView(actions);
        Button scan = button("Buscar Band", true); scan.setOnClickListener(v -> startBleScan()); actions.addView(scan, weight());
        Button disc = button("Desconectar", false); disc.setOnClickListener(v -> disconnectGatt()); actions.addView(disc, weight());
        deviceList = new LinearLayout(this); deviceList.setOrientation(LinearLayout.VERTICAL); status.addView(deviceList);

        LinearLayout photo = card(c);
        photo.addView(text("FOTO COMPLETA", 12, ACCENT, true));
        photo.addView(text("172 × 320 · sin texto añadido", 20, TEXT, true));
        photo.addView(text("Elegís una imagen y BandLab genera una versión vertical exacta para la pantalla. El envío como watchface limpio todavía depende de terminar la autenticación propietaria de Xiaomi.", 13, MUTED, false));
        photoPreview = new ImageView(this); photoPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(172), dp(320)); ip.gravity = Gravity.CENTER_HORIZONTAL; ip.setMargins(0, dp(14), 0, dp(14)); photo.addView(photoPreview, ip);
        Button pick = button("Elegir foto", true); pick.setOnClickListener(v -> pickPhoto()); photo.addView(pick, buttonLp());
        Button send = button("Mostrar foto limpia en la Band", false); send.setOnClickListener(v -> {
            if (preparedPhoto == null) toast("Primero elegí una foto");
            else toast("Foto preparada. Transferencia Xiaomi todavía experimental; no voy a simular un envío.");
        }); photo.addView(send, buttonLp());
        Button normal = button("Volver a modo normal", false); normal.setOnClickListener(v -> toast("Restauración de esfera: pendiente de autenticación Xiaomi.")); photo.addView(normal, buttonLp());
        photoStatus = text(preparedPhoto == null ? "Ninguna foto preparada" : "Foto lista", 13, MUTED, false); photo.addView(photoStatus);

        LinearLayout cfg = card(c);
        cfg.addView(text("AJUSTES DE LA BAND", 12, MUTED, true));
        cfg.addView(settingLine("Brillo", "Pendiente de protocolo Xiaomi"));
        cfg.addView(settingLine("Notificaciones", "Pendiente de protocolo Xiaomi"));
        cfg.addView(settingLine("Frecuencia cardíaca", "Lectura histórica pendiente"));
        cfg.addView(settingLine("Estrés", "Lectura histórica pendiente"));
        cfg.addView(settingLine("Batería", gatt == null ? "Conectá la Band para explorar GATT" : "Servicios GATT detectados"));

        setPage(s);
    }

    private View settingLine(String a, String b) {
        LinearLayout r = row(); r.setPadding(0, dp(8), 0, dp(8));
        r.addView(text(a, 15, TEXT, true), weight());
        TextView v = text(b, 12, MUTED, false); v.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); r.addView(v, weight());
        return r;
    }

    private void setupBluetooth() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ba = bm == null ? null : bm.getAdapter();
        bleScanner = ba == null ? null : ba.getBluetoothLeScanner();
    }

    private void startBleScan() {
        if (bleScanner == null) { toast("Bluetooth no disponible o desactivado"); return; }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions(); toast("Dale permiso Bluetooth y volvé a tocar Buscar Band"); return;
        }
        scanResults.clear(); if (deviceList != null) deviceList.removeAllViews();
        if (deviceStatus != null) deviceStatus.setText("Buscando BLE…");
        try {
            bleScanner.startScan(scanCallback);
            deviceStatus.postDelayed(() -> { try { bleScanner.stopScan(scanCallback); } catch (Exception ignored) {} if (deviceStatus != null && gatt == null) deviceStatus.setText("Búsqueda terminada"); }, 10000);
        } catch (SecurityException e) { toast("Falta permiso Bluetooth"); }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = null;
            try { if (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) name = d.getName(); } catch (Exception ignored) {}
            if (name == null || name.trim().isEmpty()) name = "BLE " + d.getAddress();
            String key = d.getAddress();
            if (!scanResults.containsKey(key)) {
                scanResults.put(key, result);
                String n = name;
                runOnUiThread(() -> addDeviceResult(n, result));
            }
        }
    };

    private void addDeviceResult(String name, ScanResult r) {
        if (deviceList == null) return;
        TextView t = text(name + "   " + r.getRssi() + " dBm", 13, name.toLowerCase(Locale.ROOT).contains("band") ? ACCENT : MUTED, true);
        t.setPadding(0, dp(10), 0, dp(10));
        t.setOnClickListener(v -> connectGatt(r.getDevice(), name));
        deviceList.addView(t);
    }

    private void connectGatt(BluetoothDevice d, String name) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { requestRuntimePermissions(); return; }
        try {
            if (bleScanner != null) bleScanner.stopScan(scanCallback);
            if (gatt != null) { gatt.close(); gatt = null; }
            if (deviceStatus != null) deviceStatus.setText("Conectando a " + name + "…");
            gatt = d.connectGatt(this, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) { runOnUiThread(() -> { if (deviceStatus != null) deviceStatus.setText("BLE conectado · leyendo servicios"); }); try { g.requestMtu(247); g.discoverServices(); } catch (Exception ignored) {} }
                    else if (newState == BluetoothProfile.STATE_DISCONNECTED) runOnUiThread(() -> { if (deviceStatus != null) deviceStatus.setText("Desconectada"); });
                }
                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    List<BluetoothGattService> services = g.getServices();
                    runOnUiThread(() -> { if (deviceStatus != null) deviceStatus.setText("Conectada · " + services.size() + " servicios GATT · autenticación Xiaomi pendiente"); });
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) { toast("No pude abrir GATT: " + e.getMessage()); }
    }

    private void disconnectGatt() {
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Exception ignored) {}
        gatt = null; if (deviceStatus != null) deviceStatus.setText("Desconectada");
    }

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, REQ_PHOTO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PHOTO && resultCode == RESULT_OK && data != null && data.getData() != null) processPhoto(data.getData());
    }

    private void processPhoto(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap src = BitmapFactory.decodeStream(in);
            if (src == null) throw new Exception("imagen inválida");
            int tw = 172, th = 320;
            float scale = Math.max(tw / (float) src.getWidth(), th / (float) src.getHeight());
            int sw = Math.round(src.getWidth() * scale), sh = Math.round(src.getHeight() * scale);
            Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
            int x = Math.max(0, (sw - tw) / 2), y = Math.max(0, (sh - th) / 2);
            Bitmap crop = Bitmap.createBitmap(scaled, x, y, tw, th);
            preparedPhoto = new File(getFilesDir(), "band_photo_172x320.png");
            try (FileOutputStream out = new FileOutputStream(preparedPhoto)) { crop.compress(Bitmap.CompressFormat.PNG, 100, out); }
            if (photoPreview != null) photoPreview.setImageBitmap(crop);
            if (photoStatus != null) photoStatus.setText("Lista · 172×320 PNG · sin overlays añadidos");
            toast("Foto preparada");
        } catch (Exception e) { toast("No pude preparar la foto"); }
    }

    private void setupStepCounter() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        stepSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (stepSensor != null && (Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)) sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_STEP_COUNTER) return;
        latestStepRaw = event.values[0];
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        String baseDay = prefs.getString("step_base_day", "");
        float base;
        if (!day.equals(baseDay)) {
            base = latestStepRaw;
            prefs.edit().putString("step_base_day", day).putFloat("step_base", base).apply();
        } else base = prefs.getFloat("step_base", latestStepRaw);
        todaySteps = Math.max(0, Math.round(latestStepRaw - base));
        if (stepValue != null) stepValue.setText(String.format(Locale.US, "%,d", todaySteps));
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void requestRuntimePermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) { p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT); }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 29) p.add(Manifest.permission.ACTIVITY_RECOGNITION);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), REQ_PERMS);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) setupStepCounter();
    }

    private void registerWorkoutReceiver() {
        IntentFilter f = new IntentFilter(WorkoutService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(workoutReceiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(workoutReceiver, f);
    }

    private JSONArray history() {
        try { return new JSONArray(prefs.getString(WorkoutService.KEY_HISTORY, "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    private long todayWorkoutActiveMs() {
        long total = 0; String d = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()); JSONArray a = history();
        for (int i=0;i<a.length();i++) { JSONObject o=a.optJSONObject(i); if (o!=null && d.equals(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(o.optLong("started"))))) total += o.optLong("active"); }
        return total;
    }
    private double todayWorkoutCalories() {
        double total = 0; String d = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()); JSONArray a = history();
        for (int i=0;i<a.length();i++) { JSONObject o=a.optJSONObject(i); if (o!=null && d.equals(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(o.optLong("started"))))) total += o.optDouble("calories"); }
        return total;
    }

    private String date(long ms) { return new SimpleDateFormat("dd MMM · HH:mm", new Locale("es", "AR")).format(new Date(ms)); }
    private String pace(long activeMs, double distanceM) {
        if (distanceM < 50 || activeMs <= 0) return "—";
        double minKm = (activeMs / 60000.0) / (distanceM / 1000.0);
        int sec = (int)Math.round(minKm * 60); return String.format(Locale.US, "%d:%02d/km", sec/60, sec%60);
    }
    private String labelType(String t) { if ("RUN".equals(t)) return "Correr"; if ("CYCLE".equals(t)) return "Ciclismo"; if ("ROPE".equals(t)) return "Saltar cuerda"; return "Caminar"; }

    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(10), 0, 0); return r; }
    private LinearLayout metric(String label, String value, int color) { LinearLayout m = new LinearLayout(this); m.setOrientation(LinearLayout.VERTICAL); m.addView(text(label, 11, MUTED, true)); m.addView(text(value, 18, color, true)); return m; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); }
    private LinearLayout.LayoutParams buttonLp() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)); p.setMargins(0, dp(7), 0, dp(3)); return p; }

    private Button button(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? BG : TEXT); b.setBackground(roundRect(primary ? ACCENT : SURFACE2, 18)); b.setPadding(dp(12), 0, dp(12), 0); return b;
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(0, 1.08f); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private GradientDrawable roundRect(int color, int radiusDp) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radiusDp)); return g; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() { super.onResume(); if (content != null) { if (currentTab==0) showHealth(); else if(currentTab==1) showTraining(); } }
    @Override protected void onDestroy() {
        try { unregisterReceiver(workoutReceiver); } catch (Exception ignored) {}
        if (sensorManager != null) sensorManager.unregisterListener(this);
        disconnectGatt();
        super.onDestroy();
    }
}

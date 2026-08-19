package com.fer.a53performance;

import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.StatFs;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** v1.21 Ready Mode overlay. Read-only preflight + quick navigation. */
public class MainActivity extends MainActivivy {
    private static final long GIB = 1024L * 1024L * 1024L;
    private final ExecutorService readyIo = Executors.newSingleThreadExecutor();
    private final List<GameApp> games = new ArrayList<GameApp>();

    private Dialog readyDialog;
    private Spinner gameSpinner;
    private TextView headline;
    private TextView deviceCard;
    private TextView profileCard;
    private TextView actionCard;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addReadyEntry();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (readyDialog != null && readyDialog.isShowing()) refreshReady();
    }

    @Override
    protected void onDestroy() {
        readyIo.shutdownNow();
        super.onDestroy();
    }

    private void addReadyEntry() {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        Button b = new Button(this);
        b.setText("Ready Mode");
        b.setTextColor(Color.WHITE);
        b.setTextSize(14f);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setElevation(dp(8));
        b.setContentDescription("Abrir Ready Mode");
        b.setBackground(roundRect(0xFF2F855A, dp(24)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showReady(); }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52), Gravity.END | Gravity.BOTTOM);
        lp.setMargins(dp(16), dp(16), dp(16), dp(142));
        if (content instanceof FrameLayout) {
            ((FrameLayout) content).addView(b, lp);
        } else {
            FrameLayout overlay = new FrameLayout(this);
            overlay.setClickable(false);
            ((ViewGroup) content).addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.addView(b, lp);
        }
    }

    private void showReady() {
        readyDialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        readyDialog.setContentView(buildReadyView());
        readyDialog.show();
        Window w = readyDialog.getWindow();
        if (w != null) {
            w.setStatusBarColor(0xFF0D1117);
            w.setNavigationBarColor(0xFF0D1117);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        loadGames();
        refreshReady();
    }

    private View buildReadyView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(14), dp(10), dp(14), dp(18));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Ready Mode", 22f, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(54), 1f));
        Button close = smallButton("Cerrar");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (readyDialog != null) readyDialog.dismiss(); }
        });
        header.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(header);

        TextView info = text("Pre-vuelo de solo lectura. No cambia Hz, energía, Data Saver ni apps de fondo por sí solo.", 12f, 0xFF9AA7B6, false);
        info.setPadding(0, 0, 0, dp(8));
        root.addView(info);

        headline = text("Comprobando…", 17f, Color.WHITE, true);
        headline.setPadding(dp(12), dp(11), dp(12), dp(11));
        headline.setBackground(roundRect(0xFF17324A, dp(14)));
        root.addView(headline);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        TextView gameLabel = text("Juego", 13f, 0xFF98A5B6, true);
        gameLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(gameLabel);
        gameSpinner = new Spinner(this);
        root.addView(gameSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        gameSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { refreshReady(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });

        deviceCard = card(root, "Estado ahora");
        profileCard = card(root, "Perfil Galaxy guardado");
        actionCard = card(root, "Qué conviene hacer");

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(10), 0, 0);
        Button refresh = smallButton("Revisar");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshReady(); }
        });
        row1.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button openGame = smallButton("Abrir juego · sin cambios");
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        glp.setMargins(dp(7), 0, 0, 0);
        row1.addView(openGame, glp);
        openGame.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchSelectedGame(); }
        });
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(7), 0, 0);
        Button gaming = smallButton("Gaming 2.0");
        gaming.setBackground(roundRect(0xFFE17B2D, dp(12)));
        gaming.setTextColor(Color.WHITE);
        gaming.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLayer("com.fer.a53performance.MainActivjty", "showGaming"); }
        });
        row2.addView(gaming, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button doctor = smallButton("Doctor 2.0");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        dlp.setMargins(dp(7), 0, 0, 0);
        row2.addView(doctor, dlp);
        doctor.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLayer("com.fer.a53performance.MainActivivy", "showDoctor"); }
        });
        root.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setPadding(0, dp(7), 0, 0);
        Button bg = smallButton("Segundo plano");
        bg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLayer("com.fer.a53performance.MainActivitw", "showBackgroundCenter"); }
        });
        row3.addView(bg, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button gallery = smallButton("Fotos · limpiar");
        LinearLayout.LayoutParams galp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        galp.setMargins(dp(7), 0, 0, 0);
        row3.addView(gallery, galp);
        gallery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLayer("com.fer.a53performance.MainActivitz", "showGallery2"); }
        });
        root.addView(row3);

        TextView foot = text("Ready Mode solo organiza información y accesos rápidos. Los cambios de rendimiento siguen ocurriendo únicamente dentro de Gaming 2.0 o Segundo Plano, con sus snapshots y rollback ya aceptados.", 12f, 0xFF8793A3, false);
        foot.setPadding(0, dp(11), 0, 0);
        root.addView(foot);

        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private TextView card(LinearLayout parent, String title) {
        TextView label = text(title, 13f, 0xFF98A5B6, true);
        label.setPadding(0, dp(12), 0, dp(5));
        parent.addView(label);
        TextView body = text("—", 13f, 0xFFE5EAF0, false);
        body.setPadding(dp(11), dp(10), dp(11), dp(10));
        body.setBackground(roundRect(0xFF171D26, dp(12)));
        parent.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return body;
    }

    private void loadGames() {
        readyIo.execute(new Runnable() {
            @Override public void run() {
                final List<GameApp> loaded = queryLaunchableApps();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        games.clear();
                        games.addAll(loaded);
                        List<String> labels = new ArrayList<String>();
                        for (GameApp g : games) labels.add((g.gameCategory ? "🎮 " : "") + g.label + " · " + g.pkg);
                        ArrayAdapter<String> adapter = new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_spinner_item, labels);
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        gameSpinner.setAdapter(adapter);
                        refreshReady();
                    }
                });
            }
        });
    }

    private List<GameApp> queryLaunchableApps() {
        List<GameApp> out = new ArrayList<GameApp>();
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps;
        try { apps = pm.getInstalledApplications(PackageManager.GET_META_DATA); }
        catch (Throwable t) { return out; }
        for (ApplicationInfo ai : apps) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (getPackageName().equals(ai.packageName)) continue;
            Intent launch;
            try { launch = pm.getLaunchIntentForPackage(ai.packageName); }
            catch (Throwable t) { launch = null; }
            if (launch == null) continue;
            String label;
            try { label = String.valueOf(pm.getApplicationLabel(ai)); }
            catch (Throwable t) { label = ai.packageName; }
            boolean game = Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME;
            out.add(new GameApp(label, ai.packageName, game));
        }
        Collections.sort(out, new Comparator<GameApp>() {
            @Override public int compare(GameApp a, GameApp b) {
                if (a.gameCategory != b.gameCategory) return a.gameCategory ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private void refreshReady() {
        if (progress != null) progress.setVisibility(View.VISIBLE);
        if (headline != null) headline.setText("Comprobando…");
        final GameApp game = selectedGame();
        readyIo.execute(new Runnable() {
            @Override public void run() {
                final ReadySnapshot s = collectSnapshot(game);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        renderSnapshot(s);
                        if (progress != null) progress.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    private ReadySnapshot collectSnapshot(GameApp game) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean powerSaver = pm != null && pm.isPowerSaveMode();
        int thermal = -1;
        float thermalHeadroom = Float.NaN;
        if (pm != null && Build.VERSION.SDK_INT >= 29) {
            try { thermal = pm.getCurrentThermalStatus(); } catch (Throwable ignored) { }
        }
        if (pm != null && Build.VERSION.SDK_INT >= 30) {
            try { thermalHeadroom = pm.getThermalHeadroom(10); } catch (Throwable ignored) { }
        }

        long availMem = -1L, totalMem = -1L;
        boolean lowMemory = false;
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            try {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                availMem = mi.availMem;
                totalMem = mi.totalMem;
                lowMemory = mi.lowMemory;
            } catch (Throwable ignored) { }
        }

        long storageAvail = -1L, storageTotal = -1L;
        try {
            StatFs fs = new StatFs(getFilesDir().getAbsolutePath());
            storageAvail = fs.getAvailableBytes();
            storageTotal = fs.getTotalBytes();
        } catch (Throwable ignored) { }

        int batteryPct = -1;
        float batteryTemp = Float.NaN;
        boolean charging = false;
        try {
            Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) {
                int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) batteryPct = Math.round(level * 100f / scale);
                int temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                if (temp != Integer.MIN_VALUE) batteryTemp = temp / 10f;
                int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Throwable ignored) { }

        float displayHz = -1f;
        try {
            Display d = getWindowManager().getDefaultDisplay();
            if (d != null) displayHz = d.getRefreshRate();
        } catch (Throwable ignored) { }

        boolean shizuku = false;
        try { shizuku = ShizukuShell.ready(); } catch (Throwable ignored) { }
        String minHz = shizuku ? readSetting("system", "min_refresh_rate") : null;
        String peakHz = shizuku ? readSetting("system", "peak_refresh_rate") : null;
        Boolean dataSaver = shizuku ? readDataSaver() : null;

        SharedPreferences session = getSharedPreferences("gaming_session_v119", MODE_PRIVATE);
        boolean pending = session.getBoolean("pending", false);
        boolean active = session.getBoolean("active", false);
        String pendingGame = session.getString("game", "");

        int bgRules = 0;
        try {
            SharedPreferences bg = getSharedPreferences("bg_center_v118", MODE_PRIVATE);
            for (Map.Entry<String, ?> e : bg.getAll().entrySet()) {
                if (!e.getKey().startsWith("rule.")) continue;
                Object v = e.getValue();
                if (v instanceof String && !"NONE".equals(v)) bgRules++;
            }
        } catch (Throwable ignored) { }

        String profile = game == null ? "Elegí un juego" : savedProfile(game.pkg);
        return new ReadySnapshot(game, shizuku, thermal, thermalHeadroom, powerSaver,
                availMem, totalMem, lowMemory, storageAvail, storageTotal,
                batteryPct, batteryTemp, charging, displayHz, minHz, peakHz,
                dataSaver, pending, active, pendingGame, bgRules, profile);
    }

    private String savedProfile(String pkg) {
        SharedPreferences p = getSharedPreferences("gaming_profiles_v119", MODE_PRIVATE);
        int hz = p.getInt("hz." + pkg, 0);
        int power = p.getInt("power." + pkg, 0);
        int data = p.getInt("data." + pkg, 0);
        String bg = p.getString("bg." + pkg, "");
        int count = 0;
        if (bg != null && bg.length() > 0) {
            for (String s : bg.split(";")) if (s.length() > 0) count++;
        }
        String hzText = hz == 1 ? "60 Hz" : hz == 2 ? "60–120 dinámico" : hz == 3 ? "120 fijo" : "Conservar Hz";
        String powerText = power == 1 ? "Ahorro OFF" : "Conservar ahorro";
        String dataText = data == 1 ? "Data Saver ON" : data == 2 ? "Data Saver OFF" : "Conservar Data Saver";
        return hzText + " · " + powerText + "\n" + dataText + " · Apps fondo: " + count;
    }

    private void renderSnapshot(ReadySnapshot s) {
        String state;
        int color;
        List<String> actions = new ArrayList<String>();
        if (s.pending) {
            state = "RESTAURAR PRIMERO · sesión Gaming pendiente";
            color = 0xFF9C2F2F;
            actions.add("Abrí Gaming 2.0 y tocá RESTAURAR antes de iniciar otra sesión.");
        } else if (s.thermal >= 3) {
            state = "LIMITADO · thermal " + thermalName(s.thermal);
            color = 0xFF9C5A1E;
            actions.add("El sistema ya reporta throttling severo; evitá 120 Hz fijo por ahora.");
        } else if (s.lowMemory) {
            state = "ATENCIÓN · Android reporta memoria baja";
            color = 0xFF9C5A1E;
            actions.add("No fuerces limpieza agresiva; dejá que Android gestione memoria y cerrá solo apps innecesarias.");
        } else {
            boolean attention = false;
            if (!s.shizuku) { attention = true; actions.add("Shizuku no está listo; Gaming no podrá confirmar varios cambios."); }
            if (s.powerSaver) { attention = true; actions.add("Ahorro de energía está ON; puede limitar rendimiento o refresco."); }
            if (s.storageAvail >= 0 && s.storageAvail < 5L * GIB) { attention = true; actions.add("Quedan menos de 5 GB internos; conviene liberar espacio antes de sesiones largas."); }
            if (s.batteryPct >= 0 && s.batteryPct < 15 && !s.charging) { attention = true; actions.add("Batería baja; Android puede aplicar políticas más conservadoras."); }
            state = attention ? "ATENCIÓN · revisá los puntos abajo" : "LISTO · condiciones principales OK";
            color = attention ? 0xFF87651E : 0xFF1F6D4A;
        }
        headline.setText(state);
        headline.setBackground(roundRect(color, dp(14)));

        String headroom = Float.isNaN(s.thermalHeadroom) ? "NC" : String.format(Locale.ROOT, "%.2f", s.thermalHeadroom);
        deviceCard.setText(
                "Thermal: " + thermalName(s.thermal) + " · Headroom 10 s: " + headroom
                + "\nBatería: " + (s.batteryPct < 0 ? "NC" : s.batteryPct + "%")
                + (s.charging ? " · cargando" : "")
                + " · Temp batería: " + (Float.isNaN(s.batteryTemp) ? "NC" : String.format(Locale.ROOT, "%.1f °C", s.batteryTemp))
                + "\nRAM disponible: " + memoryLine(s.availMem, s.totalMem) + (s.lowMemory ? " · LOW MEMORY" : "")
                + "\nAlmacenamiento: " + storageLine(s.storageAvail, s.storageTotal)
                + "\nDisplay ahora: " + (s.displayHz > 0 ? String.format(Locale.ROOT, "%.0f Hz", s.displayHz) : "NC")
                + " · rango: " + val(s.minHz) + " / " + val(s.peakHz)
                + "\nAhorro: " + (s.powerSaver ? "ON" : "OFF")
                + " · Data Saver: " + boolVal(s.dataSaver)
                + " · Shizuku: " + (s.shizuku ? "OK" : "NC"));

        String gameName = s.game == null ? "Sin juego" : s.game.label + " · " + s.game.pkg;
        profileCard.setText(gameName + "\n" + s.profile
                + "\nReglas activas Segundo Plano: " + s.bgRules
                + "\nSesión Gaming: " + (s.pending ? "PENDIENTE" : s.active ? "ACTIVA" : "ninguna")
                + (s.pendingGame == null || s.pendingGame.length() == 0 ? "" : " · " + s.pendingGame));

        if (actions.isEmpty()) actions.add("Podés abrir Gaming 2.0 si querés aplicar el perfil guardado; Ready Mode no modifica nada por sí mismo.");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) b.append('\n');
            b.append("• ").append(actions.get(i));
        }
        actionCard.setText(b.toString());
    }

    private void launchSelectedGame() {
        GameApp g = selectedGame();
        if (g == null) { toast("Elegí un juego primero"); return; }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(g.pkg);
            if (i == null) { toast("No encontré cómo abrir " + g.label); return; }
            startActivity(i);
        } catch (Throwable t) { toast("No pude abrir " + g.label); }
    }

    private void openLayer(String className, String methodName) {
        try {
            if (readyDialog != null) readyDialog.dismiss();
            Class<?> c = Class.forName(className);
            Method m = c.getDeclaredMethod(methodName);
            m.setAccessible(true);
            m.invoke(this);
        } catch (Throwable t) {
            toast("No pude abrir esa sección");
        }
    }

    private GameApp selectedGame() {
        if (gameSpinner == null || games.isEmpty()) return null;
        int p = gameSpinner.getSelectedItemPosition();
        return p >= 0 && p < games.size() ? games.get(p) : null;
    }

    private String readSetting(String ns, String key) {
        ShizukuShell.Result r = shell("settings get " + ns + " " + key);
        if (r == null || !r.ok() || r.out == null) return null;
        String v = r.out.trim();
        if (v.length() == 0 || "null".equalsIgnoreCase(v)) return null;
        return v;
    }

    private Boolean readDataSaver() {
        ShizukuShell.Result r = shell("cmd netpolicy get restrict-background");
        if (r == null || !r.ok() || r.out == null) return null;
        String s = r.out.toLowerCase(Locale.ROOT);
        if (s.contains("enabled") || s.contains("true")) return Boolean.TRUE;
        if (s.contains("disabled") || s.contains("false")) return Boolean.FALSE;
        return null;
    }

    private ShizukuShell.Result shell(String cmd) {
        try { return ShizukuShell.run(cmd); }
        catch (Throwable t) { return null; }
    }

    private String thermalName(int t) {
        switch (t) {
            case 0: return "NONE";
            case 1: return "LIGHT";
            case 2: return "MODERATE";
            case 3: return "SEVERE";
            case 4: return "CRITICAL";
            case 5: return "EMERGENCY";
            case 6: return "SHUTDOWN";
            default: return "NC";
        }
    }

    private String memoryLine(long avail, long total) {
        if (avail < 0) return "NC";
        return total > 0 ? humanBytes(avail) + " / " + humanBytes(total) : humanBytes(avail);
    }

    private String storageLine(long avail, long total) {
        if (avail < 0) return "NC";
        return total > 0 ? humanBytes(avail) + " disponibles / " + humanBytes(total) : humanBytes(avail);
    }

    private String humanBytes(long bytes) {
        if (bytes < 0) return "NC";
        double g = bytes / (1024d * 1024d * 1024d);
        if (g >= 1d) return String.format(Locale.ROOT, "%.1f GB", g);
        return String.format(Locale.ROOT, "%.0f MB", bytes / (1024d * 1024d));
    }

    private String val(String s) { return s == null ? "NC" : s; }
    private String boolVal(Boolean b) { return b == null ? "NC" : (b.booleanValue() ? "ON" : "OFF"); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button smallButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setTextColor(0xFFE8EDF3);
        b.setBackground(roundRect(0xFF263241, dp(10)));
        b.setPadding(dp(9), 0, dp(9), 0);
        return b;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static final class GameApp {
        final String label;
        final String pkg;
        final boolean gameCategory;
        GameApp(String label, String pkg, boolean gameCategory) {
            this.label = label; this.pkg = pkg; this.gameCategory = gameCategory;
        }
    }

    private static final class ReadySnapshot {
        final GameApp game;
        final boolean shizuku;
        final int thermal;
        final float thermalHeadroom;
        final boolean powerSaver;
        final long availMem, totalMem;
        final boolean lowMemory;
        final long storageAvail, storageTotal;
        final int batteryPct;
        final float batteryTemp;
        final boolean charging;
        final float displayHz;
        final String minHz, peakHz;
        final Boolean dataSaver;
        final boolean pending, active;
        final String pendingGame;
        final int bgRules;
        final String profile;
        ReadySnapshot(GameApp game, boolean shizuku, int thermal, float thermalHeadroom,
                      boolean powerSaver, long availMem, long totalMem, boolean lowMemory,
                      long storageAvail, long storageTotal, int batteryPct, float batteryTemp,
                      boolean charging, float displayHz, String minHz, String peakHz,
                      Boolean dataSaver, boolean pending, boolean active, String pendingGame,
                      int bgRules, String profile) {
            this.game = game; this.shizuku = shizuku; this.thermal = thermal;
            this.thermalHeadroom = thermalHeadroom; this.powerSaver = powerSaver;
            this.availMem = availMem; this.totalMem = totalMem; this.lowMemory = lowMemory;
            this.storageAvail = storageAvail; this.storageTotal = storageTotal;
            this.batteryPct = batteryPct; this.batteryTemp = batteryTemp; this.charging = charging;
            this.displayHz = displayHz; this.minHz = minHz; this.peakHz = peakHz;
            this.dataSaver = dataSaver; this.pending = pending; this.active = active;
            this.pendingGame = pendingGame; this.bgRules = bgRules; this.profile = profile;
        }
    }
}

package com.fer.a53performance;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.Dialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** v1.20 Galaxy Doctor 2.0 overlay. Read-only diagnostics. */
public class MainActivity extends MainActivjty {
    private static final long GIB = 1024L * 1024L * 1024L;
    private final ExecutorService doctorIo = Executors.newSingleThreadExecutor();

    private Dialog doctorDialog;
    private TextView headline;
    private TextView systemCard;
    private TextView resourceCard;
    private TextView capabilityCard;
    private TextView stateCard;
    private TextView recommendationCard;
    private TextView updatedText;
    private ProgressBar doctorProgress;
    private DoctorSnapshot lastSnapshot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addDoctorEntry();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (doctorDialog != null && doctorDialog.isShowing()) refreshDoctor();
    }

    @Override
    protected void onDestroy() {
        doctorIo.shutdownNow();
        super.onDestroy();
    }

    private void addDoctorEntry() {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        Button button = new Button(this);
        button.setText("Doctor 2.0");
        button.setTextColor(Color.WHITE);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setElevation(dp(8));
        button.setContentDescription("Abrir Galaxy Doctor 2.0");
        button.setBackground(roundRect(0xFF2A9D8F, dp(24)));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDoctor(); }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        lp.setMargins(dp(16), dp(16), dp(16), dp(80));
        if (content instanceof FrameLayout) {
            ((FrameLayout) content).addView(button, lp);
        } else {
            FrameLayout overlay = new FrameLayout(this);
            overlay.setClickable(false);
            ((ViewGroup) content).addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.addView(button, lp);
        }
    }

    private void showDoctor() {
        doctorDialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        doctorDialog.setContentView(buildDoctorView());
        doctorDialog.show();
        Window w = doctorDialog.getWindow();
        if (w != null) {
            w.setStatusBarColor(0xFF0D1117);
            w.setNavigationBarColor(0xFF0D1117);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        refreshDoctor();
    }

    private View buildDoctorView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(14), dp(10), dp(14), dp(18));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Galaxy Doctor 2.0", 22f, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(54), 1f));
        Button close = smallButton("Cerrar");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (doctorDialog != null) doctorDialog.dismiss(); }
        });
        header.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(header);

        headline = text("Comprobando el A53…", 17f, Color.WHITE, true);
        headline.setPadding(dp(12), dp(11), dp(12), dp(11));
        headline.setBackground(roundRect(0xFF17324A, dp(14)));
        root.addView(headline);

        updatedText = text("", 11f, 0xFF8995A5, false);
        updatedText.setPadding(0, dp(5), 0, dp(4));
        root.addView(updatedText);

        doctorProgress = new ProgressBar(this);
        doctorProgress.setIndeterminate(true);
        root.addView(doctorProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        systemCard = card(root, "Sistema / Gaming");
        resourceCard = card(root, "Recursos reales");
        capabilityCard = card(root, "Capacidades y permisos");
        stateCard = card(root, "Estado Galaxy");
        recommendationCard = card(root, "Qué atender");

        LinearLayout action1 = new LinearLayout(this);
        action1.setOrientation(LinearLayout.HORIZONTAL);
        action1.setPadding(0, dp(10), 0, 0);
        Button refresh = smallButton("Actualizar");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshDoctor(); }
        });
        action1.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button copy = smallButton("Copiar diagnóstico");
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        copyLp.setMargins(dp(7), 0, 0, 0);
        action1.addView(copy, copyLp);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyDoctor(); }
        });
        root.addView(action1);

        TextView permTitle = text("Accesos", 14f, Color.WHITE, true);
        permTitle.setPadding(0, dp(12), 0, dp(5));
        root.addView(permTitle);

        LinearLayout action2 = new LinearLayout(this);
        action2.setOrientation(LinearLayout.HORIZONTAL);
        Button usage = smallButton("Uso");
        usage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openUsageAccess(); }
        });
        action2.addView(usage, new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button files = smallButton("Archivos");
        LinearLayout.LayoutParams filesLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        filesLp.setMargins(dp(6), 0, 0, 0);
        action2.addView(files, filesLp);
        files.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAllFiles(); }
        });
        Button write = smallButton("Ajustes");
        LinearLayout.LayoutParams writeLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        writeLp.setMargins(dp(6), 0, 0, 0);
        action2.addView(write, writeLp);
        write.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWriteSettings(); }
        });
        root.addView(action2);

        LinearLayout action3 = new LinearLayout(this);
        action3.setOrientation(LinearLayout.HORIZONTAL);
        action3.setPadding(0, dp(6), 0, 0);
        Button notifications = smallButton("Notificaciones");
        notifications.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openNotifications(); }
        });
        action3.addView(notifications, new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button shizuku = smallButton("Shizuku");
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        shLp.setMargins(dp(6), 0, 0, 0);
        action3.addView(shizuku, shLp);
        shizuku.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { ShizukuShell.request(); toast("Solicitud de Shizuku enviada"); }
                catch (Throwable t) { toast("Shizuku no disponible"); }
            }
        });
        root.addView(action3);

        TextView foot = text("Doctor 2.0 es diagnóstico de solo lectura: no cambia Hz, AppOps, Data Saver, ahorro ni apps de fondo.", 12f, 0xFF8793A3, false);
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

    private void refreshDoctor() {
        if (doctorProgress != null) doctorProgress.setVisibility(View.VISIBLE);
        if (headline != null) headline.setText("Comprobando el A53…");
        doctorIo.execute(new Runnable() {
            @Override public void run() {
                final DoctorSnapshot s = collectSnapshot();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        lastSnapshot = s;
                        renderSnapshot(s);
                        if (doctorProgress != null) doctorProgress.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    private DoctorSnapshot collectSnapshot() {
        DoctorSnapshot s = new DoctorSnapshot();
        s.timeMs = System.currentTimeMillis();

        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            try {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                s.availRam = mi.availMem;
                s.totalRam = mi.totalMem;
                s.lowMemory = mi.lowMemory;
                s.lowThreshold = mi.threshold;
            } catch (Throwable ignored) { }
        }

        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            s.storageAvail = sf.getAvailableBytes();
            s.storageTotal = sf.getTotalBytes();
        } catch (Throwable ignored) { }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            try { s.powerSave = pm.isPowerSaveMode(); } catch (Throwable ignored) { }
            if (Build.VERSION.SDK_INT >= 29) {
                try { s.thermal = pm.getCurrentThermalStatus(); } catch (Throwable ignored) { }
            }
        }

        try {
            Display d = getWindowManager().getDefaultDisplay();
            if (d != null) s.displayHz = d.getRefreshRate();
        } catch (Throwable ignored) { }

        Intent bat = null;
        try { bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); }
        catch (Throwable ignored) { }
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) s.batteryPct = Math.round(level * 100f / scale);
            int temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temp != Integer.MIN_VALUE) s.batteryTempC = temp / 10f;
            int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            s.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        }

        s.usageAccess = hasUsageAccess();
        if (s.usageAccess) s.usedApps24h = countUsedApps24h();
        if (Build.VERSION.SDK_INT >= 30) {
            try { s.allFiles = Environment.isExternalStorageManager(); } catch (Throwable ignored) { }
        } else s.allFiles = true;
        try { s.writeSettings = Settings.System.canWrite(this); } catch (Throwable ignored) { }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try { s.notifications = nm.areNotificationsEnabled(); } catch (Throwable ignored) { }
        }

        try { s.shizuku = ShizukuShell.ready(); } catch (Throwable ignored) { }
        if (s.shizuku) {
            s.minHz = readSetting("system", "min_refresh_rate");
            s.peakHz = readSetting("system", "peak_refresh_rate");
            s.dataSaver = readDataSaver();
        }

        SharedPreferences bg = getSharedPreferences("bg_center_v118", MODE_PRIVATE);
        int rules = 0;
        try {
            for (Map.Entry<String, ?> e : bg.getAll().entrySet()) {
                if (e.getKey().startsWith("rule.") && e.getValue() instanceof String && !"NONE".equals(e.getValue())) rules++;
            }
        } catch (Throwable ignored) { }
        s.backgroundRules = rules;

        SharedPreferences gaming = getSharedPreferences("gaming_session_v119", MODE_PRIVATE);
        try {
            s.gamingPending = gaming.getBoolean("pending", false);
            s.gamingActive = gaming.getBoolean("active", false);
            s.gamingPkg = gaming.getString("game", null);
        } catch (Throwable ignored) { }

        return s;
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) { return false; }
    }

    private int countUsedApps24h() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return -1;
            long now = System.currentTimeMillis();
            Map<String, UsageStats> map = usm.queryAndAggregateUsageStats(now - 24L * 60L * 60L * 1000L, now);
            if (map == null) return -1;
            int count = 0;
            for (UsageStats u : map.values()) if (u != null && u.getLastTimeUsed() > now - 24L * 60L * 60L * 1000L) count++;
            return count;
        } catch (Throwable t) { return -1; }
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
        String x = r.out.toLowerCase(Locale.ROOT);
        if (x.contains("enabled") || x.contains("true")) return Boolean.TRUE;
        if (x.contains("disabled") || x.contains("false")) return Boolean.FALSE;
        return null;
    }

    private ShizukuShell.Result shell(String cmd) {
        try { return ShizukuShell.run(cmd); }
        catch (Throwable t) { return null; }
    }

    private void renderSnapshot(DoctorSnapshot s) {
        String overall = overallState(s);
        headline.setText(overall);
        int bg;
        if (overall.startsWith("LISTO")) bg = 0xFF1F6F5F;
        else if (overall.startsWith("LIMITADO")) bg = 0xFF7A343A;
        else bg = 0xFF745B24;
        headline.setBackground(roundRect(bg, dp(14)));
        updatedText.setText("Actualizado · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(s.timeMs)));

        systemCard.setText(
                "Thermal Status: " + thermalName(s.thermal)
                + "\nBatería: " + pct(s.batteryPct) + (s.charging ? " · cargando" : "")
                + " · temp batería: " + temp(s.batteryTempC)
                + "\nAhorro de energía: " + (s.powerSave ? "ON" : "OFF")
                + "\nDisplay actual: " + hz(s.displayHz)
                + " · rango sistema: " + val(s.minHz) + " / " + val(s.peakHz)
                + "\nData Saver global: " + boolVal(s.dataSaver));

        resourceCard.setText(
                "RAM disponible: " + bytes(s.availRam) + " / " + bytes(s.totalRam)
                + "\nAndroid lowMemory: " + (s.lowMemory ? "SÍ" : "NO")
                + (s.lowThreshold > 0 ? " · umbral " + bytes(s.lowThreshold) : "")
                + "\nAlmacenamiento disponible: " + bytes(s.storageAvail) + " / " + bytes(s.storageTotal)
                + "\nLibre: " + percent(s.storageAvail, s.storageTotal));

        capabilityCard.setText(
                "Shizuku: " + ok(s.shizuku)
                + "\nAcceso de uso: " + ok(s.usageAccess) + (s.usedApps24h >= 0 ? " · apps usadas 24 h: " + s.usedApps24h : "")
                + "\nAdministrar todos los archivos: " + ok(s.allFiles)
                + "\nModificar ajustes del sistema: " + ok(s.writeSettings)
                + "\nNotificaciones Galaxy: " + ok(s.notifications));

        stateCard.setText(
                "Reglas de Segundo Plano activas: " + s.backgroundRules
                + "\nGaming snapshot pendiente: " + (s.gamingPending ? "SÍ ⚠" : "NO")
                + " · sesión marcada activa: " + (s.gamingActive ? "SÍ" : "NO")
                + (s.gamingPkg == null ? "" : "\nJuego del snapshot: " + s.gamingPkg)
                + "\nDoctor 2.0: solo lectura");

        recommendationCard.setText(recommendations(s));
    }

    private String overallState(DoctorSnapshot s) {
        if (s.thermal >= 4) return "LIMITADO · temperatura crítica";
        if (s.thermal == 3) return "LIMITADO · throttling severo";
        if (s.lowMemory) return "ATENCIÓN · Android reporta memoria baja";
        if (s.storageAvail >= 0 && s.storageAvail < 2L * GIB) return "ATENCIÓN · poco almacenamiento libre";
        if (s.gamingPending) return "ATENCIÓN · hay rollback Gaming pendiente";
        if (!s.shizuku || !s.usageAccess || !s.allFiles || !s.writeSettings) return "ATENCIÓN · faltan capacidades";
        if (s.powerSave) return "ATENCIÓN · ahorro de energía activo";
        if (s.batteryPct >= 0 && s.batteryPct < 15 && !s.charging) return "ATENCIÓN · batería baja";
        return "LISTO · sin alertas importantes";
    }

    private String recommendations(DoctorSnapshot s) {
        StringBuilder b = new StringBuilder();
        if (s.thermal >= 3) addRec(b, "Dejá enfriar el teléfono antes de usar 120 Hz; Android ya está aplicando throttling.");
        if (s.lowMemory) addRec(b, "Android marcó lowMemory. Cerrá manualmente lo que no uses; no hace falta un task-killer continuo.");
        if (s.storageAvail >= 0 && (s.storageAvail < 5L * GIB || ratio(s.storageAvail, s.storageTotal) < 0.08)) addRec(b, "Liberá almacenamiento: queda poco margen para cachés, actualizaciones y multimedia.");
        if (s.gamingPending) addRec(b, "Abrí Gaming 2.0 y tocá RESTAURAR antes de iniciar otra sesión.");
        if (!s.shizuku) addRec(b, "Autorizá Shizuku para confirmar Hz y Data Saver; sin él esas lecturas quedan NC.");
        if (!s.usageAccess) addRec(b, "Concedé Acceso de uso para que Segundo Plano pueda mostrar uso real por app.");
        if (!s.allFiles) addRec(b, "Concedé Administrar todos los archivos si querés usar el motor completo de limpieza.");
        if (!s.writeSettings) addRec(b, "Concedé Modificar ajustes del sistema para las funciones que dependen de ese permiso.");
        if (!s.notifications) addRec(b, "Las notificaciones de Galaxy están desactivadas; algunas operaciones largas pueden perder visibilidad.");
        if (s.powerSave) addRec(b, "Ahorro de energía está ON; Gaming 2.0 puede apagarlo temporalmente con rollback.");
        if (s.batteryPct >= 0 && s.batteryPct < 15 && !s.charging) addRec(b, "Batería baja: evitá una sesión larga de alto refresco.");
        if (b.length() == 0) b.append("Sin alertas importantes. No hace falta ejecutar optimizaciones adicionales.");
        return b.toString();
    }

    private void addRec(StringBuilder b, String s) {
        if (b.length() > 0) b.append("\n");
        b.append("• ").append(s);
    }

    private void copyDoctor() {
        DoctorSnapshot s = lastSnapshot;
        if (s == null) { toast("Actualizá el diagnóstico primero"); return; }
        String report = "Galaxy Doctor 2.0\n"
                + overallState(s) + "\n\n"
                + "Sistema\n" + systemCard.getText() + "\n\n"
                + "Recursos\n" + resourceCard.getText() + "\n\n"
                + "Capacidades\n" + capabilityCard.getText() + "\n\n"
                + "Estado Galaxy\n" + stateCard.getText() + "\n\n"
                + "Recomendaciones\n" + recommendationCard.getText();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Galaxy Doctor 2.0", report));
                toast("Diagnóstico copiado");
            }
        } catch (Throwable t) { toast("No pude copiar el diagnóstico"); }
    }

    private void openUsageAccess() {
        try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
        catch (Throwable t) { toast("No pude abrir Acceso de uso"); }
    }

    private void openAllFiles() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else toast("No aplica a esta versión de Android");
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
            catch (Throwable ignored) { toast("No pude abrir Administrar todos los archivos"); }
        }
    }

    private void openWriteSettings() {
        try { startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        catch (Throwable t) { toast("No pude abrir Modificar ajustes"); }
    }

    private void openNotifications() {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Throwable t) { toast("No pude abrir Notificaciones"); }
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

    private String ok(boolean b) { return b ? "OK ✓" : "FALTA/NC"; }
    private String val(String s) { return s == null ? "NC" : s; }
    private String boolVal(Boolean b) { return b == null ? "NC" : (b.booleanValue() ? "ON" : "OFF"); }
    private String pct(int p) { return p < 0 ? "NC" : p + "%"; }
    private String temp(float t) { return Float.isNaN(t) ? "NC" : String.format(Locale.ROOT, "%.1f °C", t); }
    private String hz(float h) { return h <= 0 ? "NC" : String.format(Locale.ROOT, "%.0f Hz", h); }
    private String percent(long a, long t) { return t <= 0 || a < 0 ? "NC" : String.format(Locale.ROOT, "%.1f%%", 100.0 * a / t); }
    private double ratio(long a, long t) { return t <= 0 || a < 0 ? 1.0 : (double) a / (double) t; }

    private String bytes(long value) {
        if (value < 0) return "NC";
        double v = value;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024.0 && i < units.length - 1) { v /= 1024.0; i++; }
        return String.format(Locale.ROOT, i == 0 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTextColor(0xFFE8EDF3);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(roundRect(0xFF26313E, dp(11)));
        return b;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static final class DoctorSnapshot {
        long timeMs;
        long availRam = -1L;
        long totalRam = -1L;
        long lowThreshold = -1L;
        boolean lowMemory;
        long storageAvail = -1L;
        long storageTotal = -1L;
        boolean powerSave;
        int thermal = -1;
        float displayHz = -1f;
        int batteryPct = -1;
        float batteryTempC = Float.NaN;
        boolean charging;
        boolean usageAccess;
        int usedApps24h = -1;
        boolean allFiles;
        boolean writeSettings;
        boolean notifications;
        boolean shizuku;
        String minHz;
        String peakHz;
        Boolean dataSaver;
        int backgroundRules;
        boolean gamingPending;
        boolean gamingActive;
        String gamingPkg;
    }
}

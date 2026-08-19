package com.fer.a53performance;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** v1.19 Gaming 2.0 overlay. */
public class MainActivity extends MainActivitw {
    private static final String PROFILE_PREFS = "gaming_profiles_v119";
    private static final String SESSION_PREFS = "gaming_session_v119";
    private static final Pattern SAFE_PKG = Pattern.compile("[A-Za-z0-9._]+");

    private static final int HZ_KEEP = 0;
    private static final int HZ_60 = 1;
    private static final int HZ_120_DYNAMIC = 2;
    private static final int HZ_120_FIXED = 3;
    private static final int POWER_KEEP = 0;
    private static final int POWER_OFF = 1;
    private static final int DATA_KEEP = 0;
    private static final int DATA_ON = 1;
    private static final int DATA_OFF = 2;

    private final ExecutorService gameIo = Executors.newSingleThreadExecutor();
    private final List<GameApp> games = new ArrayList<GameApp>();
    private final List<GameApp> backgroundCandidates = new ArrayList<GameApp>();
    private final Set<String> selectedBackground = new HashSet<String>();
    private final Set<String> sensitive = new HashSet<String>();

    private SharedPreferences profilePrefs;
    private SharedPreferences sessionPrefs;
    private Dialog gameDialog;
    private Spinner gameSpinner;
    private Spinner hzSpinner;
    private Spinner powerSpinner;
    private Spinner dataSpinner;
    private TextView preflightText;
    private TextView backgroundText;
    private TextView sessionText;
    private ProgressBar gameProgress;
    private Button activateButton;
    private Button restoreButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profilePrefs = getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE);
        sessionPrefs = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE);
        seedSensitive();
        addGamingEntry();
    }

    @Override
    protected void onDestroy() {
        gameIo.shutdownNow();
        super.onDestroy();
    }

    private void seedSensitive() {
        Collections.addAll(sensitive,
                "com.fer.a53performance",
                "com.whatsapp", "com.whatsapp.w4b",
                "com.google.android.gm",
                "com.samsung.android.messaging", "com.google.android.apps.messaging",
                "com.samsung.android.dialer", "com.google.android.dialer",
                "com.sec.android.app.clockpackage", "com.google.android.deskclock",
                "com.spotify.music",
                "com.google.android.apps.docs", "com.microsoft.office.word",
                "com.openai.chatgpt",
                "moe.shizuku.privileged.api");
    }

    private void addGamingEntry() {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        Button button = new Button(this);
        button.setText("Gaming 2.0");
        button.setTextColor(Color.WHITE);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setElevation(dp(8));
        button.setContentDescription("Abrir Gaming 2.0 reversible");
        button.setBackground(roundRect(0xFFE17B2D, dp(24)));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showGaming(); }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        lp.setMargins(dp(16), dp(16), dp(16), dp(142));
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

    private void showGaming() {
        gameDialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        gameDialog.setContentView(buildGamingView());
        gameDialog.show();
        Window w = gameDialog.getWindow();
        if (w != null) {
            w.setStatusBarColor(0xFF0D1117);
            w.setNavigationBarColor(0xFF0D1117);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        loadGames();
        refreshPreflight();
        updateSessionUi();
    }

    private View buildGamingView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Gaming 2.0", 22f, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(54), 1f));
        Button close = smallButton("Cerrar");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (gameDialog != null) gameDialog.dismiss(); }
        });
        header.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(header);

        TextView explain = text("Sesión manual y reversible. Galaxy guarda el estado real antes de cambiar Hz, ahorro, Data Saver o apps de fondo.", 13f, 0xFFABB5C2, false);
        explain.setPadding(0, 0, 0, dp(8));
        root.addView(explain);

        preflightText = text("Comprobando dispositivo…", 13f, 0xFFE5EAF0, false);
        preflightText.setPadding(dp(10), dp(9), dp(10), dp(9));
        preflightText.setBackground(roundRect(0xFF171D26, dp(12)));
        root.addView(preflightText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        gameProgress = new ProgressBar(this);
        gameProgress.setIndeterminate(true);
        root.addView(gameProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        root.addView(label("Juego"));
        gameSpinner = new Spinner(this);
        root.addView(gameSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        gameSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                loadProfileForSelectedGame();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        root.addView(label("Pantalla"));
        hzSpinner = spinner(new String[]{"Conservar", "60 Hz", "60–120 Hz dinámico", "120 Hz fijo · más calor"});
        root.addView(hzSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        root.addView(label("Ahorro de energía"));
        powerSpinner = spinner(new String[]{"Conservar", "Ahorro OFF durante sesión"});
        root.addView(powerSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        root.addView(label("Data Saver"));
        dataSpinner = spinner(new String[]{"Conservar", "ON durante sesión", "OFF durante sesión"});
        root.addView(dataSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        LinearLayout bgRow = new LinearLayout(this);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        bgRow.setGravity(Gravity.CENTER_VERTICAL);
        backgroundText = text("Apps de fondo: ninguna", 13f, 0xFFCFD7E2, false);
        bgRow.addView(backgroundText, new LinearLayout.LayoutParams(0, dp(50), 1f));
        Button chooseBg = smallButton("Elegir apps");
        chooseBg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseBackgroundApps(); }
        });
        bgRow.addView(chooseBg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(bgRow);

        TextView bgHint = text("Solo las apps que elijas reciben una restricción temporal de fondo. Apps sensibles y el juego seleccionado quedan excluidos.", 12f, 0xFF8F9AAA, false);
        bgHint.setPadding(0, 0, 0, dp(8));
        root.addView(bgHint);

        sessionText = text("Sin sesión activa", 13f, Color.WHITE, true);
        sessionText.setPadding(dp(10), dp(9), dp(10), dp(9));
        sessionText.setBackground(roundRect(0xFF171D26, dp(12)));
        root.addView(sessionText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, 0);
        activateButton = smallButton("ACTIVAR Y ABRIR");
        activateButton.setTextColor(Color.WHITE);
        activateButton.setBackground(roundRect(0xFFE17B2D, dp(12)));
        activateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { activateSession(true); }
        });
        buttons.addView(activateButton, new LinearLayout.LayoutParams(0, dp(52), 1f));

        restoreButton = smallButton("RESTAURAR");
        restoreButton.setTextColor(Color.WHITE);
        restoreButton.setBackground(roundRect(0xFF3D556E, dp(12)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        rlp.setMargins(dp(8), 0, 0, 0);
        buttons.addView(restoreButton, rlp);
        restoreButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { restoreSessionAsync(false); }
        });
        root.addView(buttons);

        Button applyOnly = smallButton("Solo activar · no abrir juego");
        applyOnly.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { activateSession(false); }
        });
        LinearLayout.LayoutParams aolp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        aolp.setMargins(0, dp(7), 0, 0);
        root.addView(applyOnly, aolp);

        TextView foot = text("Si Galaxy o Android se cierran durante una activación, el snapshot queda guardado y el botón RESTAURAR seguirá disponible al volver a abrir la app.", 12f, 0xFF8F9AAA, false);
        foot.setPadding(0, dp(8), 0, 0);
        root.addView(foot);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void loadGames() {
        gameProgress.setVisibility(View.VISIBLE);
        gameIo.execute(new Runnable() {
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
                        gameProgress.setVisibility(View.GONE);
                        if (games.isEmpty()) sessionText.setText("No encontré apps iniciables de usuario");
                        else loadProfileForSelectedGame();
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
            boolean cat = Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME;
            out.add(new GameApp(label, ai.packageName, ai.uid, cat));
        }
        Collections.sort(out, new Comparator<GameApp>() {
            @Override public int compare(GameApp a, GameApp b) {
                if (a.gameCategory != b.gameCategory) return a.gameCategory ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private void refreshPreflight() {
        gameIo.execute(new Runnable() {
            @Override public void run() {
                final DeviceState s = readDeviceState();
                runOnUiThread(new Runnable() {
                    @Override public void run() { preflightText.setText(formatPreflight(s)); }
                });
            }
        });
    }

    private DeviceState readDeviceState() {
        boolean sh = isShizukuReady();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean saver = pm != null && pm.isPowerSaveMode();
        int thermal = -1;
        if (pm != null && Build.VERSION.SDK_INT >= 29) {
            try { thermal = pm.getCurrentThermalStatus(); } catch (Throwable ignored) { }
        }
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        long avail = -1L;
        long total = -1L;
        if (am != null) {
            try {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                avail = mi.availMem;
                if (Build.VERSION.SDK_INT >= 16) total = mi.totalMem;
            } catch (Throwable ignored) { }
        }
        float displayHz = -1f;
        try {
            Display d = getWindowManager().getDefaultDisplay();
            if (d != null) displayHz = d.getRefreshRate();
        } catch (Throwable ignored) { }
        String minHz = sh ? readSetting("system", "min_refresh_rate") : null;
        String peakHz = sh ? readSetting("system", "peak_refresh_rate") : null;
        Boolean dataSaver = sh ? readDataSaver() : null;
        return new DeviceState(sh, saver, thermal, avail, total, displayHz, minHz, peakHz, dataSaver);
    }

    private String formatPreflight(DeviceState s) {
        return "Shizuku: " + (s.shizuku ? "OK ✓" : "NC")
                + " · Thermal: " + thermalName(s.thermal)
                + "\nAhorro: " + (s.powerSave ? "ON" : "OFF")
                + " · Display ahora: " + (s.displayHz > 0 ? String.format(Locale.ROOT, "%.0f Hz", s.displayHz) : "NC")
                + "\nRango sistema: " + val(s.minHz) + " / " + val(s.peakHz)
                + " · Data Saver: " + boolVal(s.dataSaver)
                + "\nRAM libre: " + memoryLine(s.availMem, s.totalMem);
    }

    private String memoryLine(long avail, long total) {
        if (avail < 0) return "NC";
        if (total > 0) return humanBytes(avail) + " / " + humanBytes(total);
        return humanBytes(avail);
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

    private void loadProfileForSelectedGame() {
        GameApp g = selectedGame();
        if (g == null || profilePrefs == null || hzSpinner == null) return;
        hzSpinner.setSelection(clamp(profilePrefs.getInt("hz." + g.pkg, HZ_KEEP), 0, 3));
        powerSpinner.setSelection(clamp(profilePrefs.getInt("power." + g.pkg, POWER_KEEP), 0, 1));
        dataSpinner.setSelection(clamp(profilePrefs.getInt("data." + g.pkg, DATA_KEEP), 0, 2));
        selectedBackground.clear();
        String saved = profilePrefs.getString("bg." + g.pkg, "");
        if (saved != null && saved.length() > 0) {
            String[] parts = saved.split(";");
            for (String p : parts) if (SAFE_PKG.matcher(p).matches() && !p.equals(g.pkg) && !sensitive.contains(p)) selectedBackground.add(p);
        }
        updateBackgroundText();
    }

    private void saveProfile(GameApp g) {
        if (g == null) return;
        StringBuilder b = new StringBuilder();
        List<String> sorted = new ArrayList<String>(selectedBackground);
        Collections.sort(sorted);
        for (String p : sorted) {
            if (b.length() > 0) b.append(';');
            b.append(p);
        }
        profilePrefs.edit()
                .putInt("hz." + g.pkg, hzSpinner.getSelectedItemPosition())
                .putInt("power." + g.pkg, powerSpinner.getSelectedItemPosition())
                .putInt("data." + g.pkg, dataSpinner.getSelectedItemPosition())
                .putString("bg." + g.pkg, b.toString())
                .apply();
    }

    private void chooseBackgroundApps() {
        final GameApp game = selectedGame();
        if (game == null) return;
        backgroundCandidates.clear();
        for (GameApp a : games) {
            if (a.pkg.equals(game.pkg)) continue;
            if (sensitive.contains(a.pkg)) continue;
            backgroundCandidates.add(a);
        }
        final CharSequence[] names = new CharSequence[backgroundCandidates.size()];
        final boolean[] checked = new boolean[backgroundCandidates.size()];
        for (int i = 0; i < backgroundCandidates.size(); i++) {
            GameApp a = backgroundCandidates.get(i);
            names[i] = a.label + " · " + a.pkg;
            checked[i] = selectedBackground.contains(a.pkg);
        }
        new AlertDialog.Builder(this)
                .setTitle("Apps a restringir durante Gaming")
                .setMessage("Ninguna app se selecciona automáticamente. WhatsApp, llamadas, reloj, Spotify, Shizuku y otras apps sensibles están excluidas.")
                .setMultiChoiceItems(names, checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which, boolean isChecked) {
                        if (which < 0 || which >= backgroundCandidates.size()) return;
                        String p = backgroundCandidates.get(which).pkg;
                        if (isChecked) selectedBackground.add(p); else selectedBackground.remove(p);
                    }
                })
                .setPositiveButton("Guardar selección", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        updateBackgroundText();
                        saveProfile(game);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void updateBackgroundText() {
        if (backgroundText != null) backgroundText.setText("Apps de fondo: " + (selectedBackground.isEmpty() ? "ninguna" : String.valueOf(selectedBackground.size())));
    }

    private void activateSession(final boolean launchGame) {
        final GameApp game = selectedGame();
        if (game == null) { toast("Elegí un juego primero"); return; }
        if (sessionPrefs.getBoolean("pending", false)) {
            toast("Hay una sesión pendiente. Restaurala antes de iniciar otra.");
            updateSessionUi();
            return;
        }
        if (!isShizukuReady()) {
            try { ShizukuShell.request(); } catch (Throwable ignored) { }
            toast("Shizuku no está listo. Autorizalo y volvé a intentar.");
            return;
        }
        saveProfile(game);
        setBusy(true, "Preparando snapshot real…");
        gameIo.execute(new Runnable() {
            @Override public void run() {
                SessionSnapshot snap = captureSessionSnapshot(game);
                if (snap == null) {
                    finishBusy("No pude confirmar el estado previo · NO se cambió nada");
                    return;
                }
                if (!persistSnapshot(snap)) {
                    finishBusy("No pude persistir el snapshot · NO se cambió nada");
                    return;
                }
                ApplyReport report = applySession(game, snap);
                if (!report.ok) {
                    RestoreReport rollback = restoreSessionInternal();
                    if (rollback.ok) clearSession();
                    finishBusy("Activación falló: " + report.message + (rollback.ok ? " · rollback ✓" : " · RESTAURAR pendiente"));
                    return;
                }
                sessionPrefs.edit().putBoolean("active", true).commit();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setBusy(false, "Sesión Gaming ACTIVA ✓ · " + game.label);
                        refreshPreflight();
                        if (launchGame) launchGame(game);
                    }
                });
            }
        });
    }

    private SessionSnapshot captureSessionSnapshot(GameApp game) {
        int hz = hzSpinner.getSelectedItemPosition();
        int power = powerSpinner.getSelectedItemPosition();
        int data = dataSpinner.getSelectedItemPosition();
        String min = null, peak = null, low = null;
        Boolean saver = null;
        if (hz != HZ_KEEP) {
            min = readSetting("system", "min_refresh_rate");
            peak = readSetting("system", "peak_refresh_rate");
            if (min == null || peak == null) return null;
        }
        if (power == POWER_OFF) {
            low = readSetting("global", "low_power");
            if (low == null || !("0".equals(low) || "1".equals(low))) return null;
        }
        if (data != DATA_KEEP) {
            saver = readDataSaver();
            if (saver == null) return null;
        }
        List<AppOpSnapshot> bg = new ArrayList<AppOpSnapshot>();
        List<String> sorted = new ArrayList<String>(selectedBackground);
        Collections.sort(sorted);
        for (String pkg : sorted) {
            if (!SAFE_PKG.matcher(pkg).matches() || pkg.equals(game.pkg) || sensitive.contains(pkg)) continue;
            String mode = readAppOp(pkg);
            if (mode == null) return null;
            bg.add(new AppOpSnapshot(pkg, mode));
        }
        return new SessionSnapshot(game.pkg, hz, power, data, min, peak, low, saver, bg);
    }

    private boolean persistSnapshot(SessionSnapshot s) {
        SharedPreferences.Editor e = sessionPrefs.edit().clear();
        e.putBoolean("pending", true);
        e.putBoolean("active", false);
        e.putString("game", s.gamePkg);
        e.putInt("hz.action", s.hzAction);
        e.putInt("power.action", s.powerAction);
        e.putInt("data.action", s.dataAction);
        if (s.minRefresh != null) e.putString("snap.min", s.minRefresh);
        if (s.peakRefresh != null) e.putString("snap.peak", s.peakRefresh);
        if (s.lowPower != null) e.putString("snap.low", s.lowPower);
        if (s.dataSaver != null) e.putBoolean("snap.data", s.dataSaver.booleanValue());
        StringBuilder list = new StringBuilder();
        for (AppOpSnapshot a : s.background) {
            if (list.length() > 0) list.append(';');
            list.append(a.pkg);
            e.putString("snap.op." + a.pkg, a.mode);
        }
        e.putString("snap.bg", list.toString());
        return e.commit();
    }

    private ApplyReport applySession(GameApp game, SessionSnapshot snap) {
        if (snap.hzAction != HZ_KEEP) {
            String minTarget;
            String peakTarget;
            if (snap.hzAction == HZ_60) { minTarget = "60.0"; peakTarget = "60.0"; }
            else if (snap.hzAction == HZ_120_DYNAMIC) { minTarget = "60.0"; peakTarget = "120.0"; }
            else { minTarget = "120.0"; peakTarget = "120.0"; }
            if (!shellOk("settings put system min_refresh_rate " + minTarget)
                    || !shellOk("settings put system peak_refresh_rate " + peakTarget))
                return new ApplyReport(false, "Hz rechazado");
            String min = readSetting("system", "min_refresh_rate");
            String peak = readSetting("system", "peak_refresh_rate");
            if (!sameNumber(min, minTarget) || !sameNumber(peak, peakTarget))
                return new ApplyReport(false, "Hz sin confirmar");
        }
        if (snap.powerAction == POWER_OFF) {
            if (!shellOk("cmd power set-mode 0")) return new ApplyReport(false, "Ahorro OFF rechazado");
            String low = readSetting("global", "low_power");
            if (!"0".equals(low)) return new ApplyReport(false, "Ahorro OFF sin confirmar");
        }
        if (snap.dataAction != DATA_KEEP) {
            boolean target = snap.dataAction == DATA_ON;
            if (!shellOk("cmd netpolicy set restrict-background " + (target ? "true" : "false")))
                return new ApplyReport(false, "Data Saver rechazado");
            Boolean after = readDataSaver();
            if (after == null || after.booleanValue() != target) return new ApplyReport(false, "Data Saver sin confirmar");
        }
        for (AppOpSnapshot a : snap.background) {
            if (!shellOk("cmd appops set " + a.pkg + " RUN_ANY_IN_BACKGROUND ignore"))
                return new ApplyReport(false, "AppOps rechazado: " + a.pkg);
            String mode = readAppOp(a.pkg);
            if (!("ignore".equals(mode) || "deny".equals(mode)))
                return new ApplyReport(false, "AppOps sin confirmar: " + a.pkg);
        }
        return new ApplyReport(true, "OK");
    }

    private void restoreSessionAsync(boolean silent) {
        if (!sessionPrefs.getBoolean("pending", false)) {
            if (!silent) toast("No hay snapshot Gaming pendiente");
            updateSessionUi();
            return;
        }
        setBusy(true, "Restaurando estado anterior…");
        gameIo.execute(new Runnable() {
            @Override public void run() {
                RestoreReport r = restoreSessionInternal();
                if (r.ok) clearSession();
                finishBusy(r.ok ? "Estado anterior restaurado ✓" : "Restauración PARCIAL/NC · snapshot conservado · " + r.message);
            }
        });
    }

    private RestoreReport restoreSessionInternal() {
        if (!sessionPrefs.getBoolean("pending", false)) return new RestoreReport(true, "sin snapshot");
        int hz = sessionPrefs.getInt("hz.action", HZ_KEEP);
        int power = sessionPrefs.getInt("power.action", POWER_KEEP);
        int data = sessionPrefs.getInt("data.action", DATA_KEEP);
        if (hz != HZ_KEEP) {
            String min = sessionPrefs.getString("snap.min", null);
            String peak = sessionPrefs.getString("snap.peak", null);
            if (min == null || peak == null) return new RestoreReport(false, "snapshot Hz incompleto");
            if (!restoreSetting("system", "min_refresh_rate", min) || !restoreSetting("system", "peak_refresh_rate", peak))
                return new RestoreReport(false, "restore Hz rechazado");
            if (!sameSetting("system", "min_refresh_rate", min) || !sameSetting("system", "peak_refresh_rate", peak))
                return new RestoreReport(false, "restore Hz sin confirmar");
        }
        if (power == POWER_OFF) {
            String low = sessionPrefs.getString("snap.low", null);
            if (!"0".equals(low) && !"1".equals(low)) return new RestoreReport(false, "snapshot ahorro incompleto");
            if (!shellOk("cmd power set-mode " + low)) return new RestoreReport(false, "restore ahorro rechazado");
            if (!low.equals(readSetting("global", "low_power"))) return new RestoreReport(false, "restore ahorro sin confirmar");
        }
        if (data != DATA_KEEP) {
            boolean old = sessionPrefs.getBoolean("snap.data", false);
            if (!shellOk("cmd netpolicy set restrict-background " + (old ? "true" : "false")))
                return new RestoreReport(false, "restore Data Saver rechazado");
            Boolean after = readDataSaver();
            if (after == null || after.booleanValue() != old) return new RestoreReport(false, "restore Data Saver sin confirmar");
        }
        String bg = sessionPrefs.getString("snap.bg", "");
        if (bg != null && bg.length() > 0) {
            String[] pkgs = bg.split(";");
            for (String pkg : pkgs) {
                if (!SAFE_PKG.matcher(pkg).matches()) return new RestoreReport(false, "pkg snapshot inválido");
                String mode = sessionPrefs.getString("snap.op." + pkg, null);
                if (mode == null || !validAppOp(mode)) return new RestoreReport(false, "snapshot AppOps incompleto");
                if (!shellOk("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND " + mode))
                    return new RestoreReport(false, "restore AppOps rechazado: " + pkg);
                String after = readAppOp(pkg);
                if (!mode.equals(after)) return new RestoreReport(false, "restore AppOps sin confirmar: " + pkg);
            }
        }
        return new RestoreReport(true, "OK");
    }

    private void clearSession() {
        sessionPrefs.edit().clear().commit();
        runOnUiThread(new Runnable() {
            @Override public void run() { updateSessionUi(); refreshPreflight(); }
        });
    }

    private void updateSessionUi() {
        if (sessionText == null) return;
        boolean pending = sessionPrefs.getBoolean("pending", false);
        boolean active = sessionPrefs.getBoolean("active", false);
        String game = sessionPrefs.getString("game", "");
        if (pending) {
            sessionText.setText((active ? "SESIÓN ACTIVA" : "SNAPSHOT PENDIENTE") + " · " + game + " · RESTAURAR disponible");
            if (restoreButton != null) restoreButton.setEnabled(true);
            if (activateButton != null) activateButton.setEnabled(false);
        } else {
            sessionText.setText("Sin sesión activa · listo para snapshot");
            if (restoreButton != null) restoreButton.setEnabled(false);
            if (activateButton != null) activateButton.setEnabled(true);
        }
    }

    private void setBusy(final boolean busy, final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (gameProgress != null) gameProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
                if (sessionText != null) sessionText.setText(message);
                if (activateButton != null) activateButton.setEnabled(!busy && !sessionPrefs.getBoolean("pending", false));
                if (restoreButton != null) restoreButton.setEnabled(!busy && sessionPrefs.getBoolean("pending", false));
            }
        });
    }

    private void finishBusy(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (gameProgress != null) gameProgress.setVisibility(View.GONE);
                if (sessionText != null) sessionText.setText(message);
                updateSessionUiDelayed(message);
                refreshPreflight();
            }
        });
    }

    private void updateSessionUiDelayed(String message) {
        boolean pending = sessionPrefs.getBoolean("pending", false);
        if (activateButton != null) activateButton.setEnabled(!pending);
        if (restoreButton != null) restoreButton.setEnabled(pending);
    }

    private void launchGame(GameApp game) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(game.pkg);
            if (i == null) { toast("No pude abrir " + game.label); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) { toast("No pude abrir el juego"); }
    }

    private GameApp selectedGame() {
        if (gameSpinner == null) return null;
        int i = gameSpinner.getSelectedItemPosition();
        if (i < 0 || i >= games.size()) return null;
        return games.get(i);
    }

    private boolean isShizukuReady() {
        try { return ShizukuShell.ready(); } catch (Throwable t) { return false; }
    }

    private ShizukuShell.Result shell(String command) {
        try { return ShizukuShell.run(command); } catch (Throwable t) { return null; }
    }

    private boolean shellOk(String command) {
        ShizukuShell.Result r = shell(command);
        return r != null && r.ok();
    }

    private String readSetting(String namespace, String key) {
        ShizukuShell.Result r = shell("settings get " + namespace + " " + key);
        if (r == null || !r.ok() || r.out == null) return null;
        String s = r.out.trim();
        if (s.length() == 0) return null;
        return s;
    }

    private boolean restoreSetting(String namespace, String key, String value) {
        if ("null".equalsIgnoreCase(value)) return shellOk("settings delete " + namespace + " " + key);
        return shellOk("settings put " + namespace + " " + key + " " + value);
    }

    private boolean sameSetting(String namespace, String key, String expected) {
        String actual = readSetting(namespace, key);
        if (actual == null) return false;
        if ("null".equalsIgnoreCase(expected)) return "null".equalsIgnoreCase(actual);
        return sameNumber(actual, expected) || actual.equals(expected);
    }

    private boolean sameNumber(String a, String b) {
        if (a == null || b == null) return false;
        try { return Math.abs(Float.parseFloat(a.trim()) - Float.parseFloat(b.trim())) < 0.05f; }
        catch (Throwable t) { return a.trim().equals(b.trim()); }
    }

    private Boolean readDataSaver() {
        ShizukuShell.Result r = shell("cmd netpolicy get restrict-background");
        if (r == null || !r.ok() || r.out == null) return null;
        String s = r.out.toLowerCase(Locale.ROOT);
        if (s.contains("enabled") || s.contains("true")) return Boolean.TRUE;
        if (s.contains("disabled") || s.contains("false")) return Boolean.FALSE;
        return null;
    }

    private String readAppOp(String pkg) {
        ShizukuShell.Result r = shell("cmd appops get " + pkg + " RUN_ANY_IN_BACKGROUND");
        if (r == null || !r.ok() || r.out == null) return null;
        String s = r.out.toLowerCase(Locale.ROOT);
        if (s.contains("ignore")) return "ignore";
        if (s.contains("deny")) return "deny";
        if (s.contains("allow")) return "allow";
        if (s.contains("default") || s.contains("no operations")) return "default";
        return null;
    }

    private boolean validAppOp(String s) {
        return "allow".equals(s) || "ignore".equals(s) || "deny".equals(s) || "default".equals(s);
    }

    private String humanBytes(long n) {
        if (n < 0) return "NC";
        double v = n;
        String[] u = {"B", "KB", "MB", "GB"};
        int i = 0;
        while (v >= 1024.0 && i < u.length - 1) { v /= 1024.0; i++; }
        return String.format(Locale.ROOT, i == 0 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        return s;
    }

    private TextView label(String s) {
        TextView t = text(s, 12f, 0xFF8F9AAA, true);
        t.setPadding(0, dp(7), 0, 0);
        return t;
    }

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
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(roundRect(0xFF232B36, dp(10)));
        b.setTextColor(0xFFE4EAF2);
        return b;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private String val(String s) { return s == null ? "NC" : s; }
    private String boolVal(Boolean b) { return b == null ? "NC" : (b.booleanValue() ? "ON" : "OFF"); }
    private void toast(final String s) { runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show(); } }); }

    static final class GameApp {
        final String label; final String pkg; final int uid; final boolean gameCategory;
        GameApp(String label, String pkg, int uid, boolean gameCategory) {
            this.label = label; this.pkg = pkg; this.uid = uid; this.gameCategory = gameCategory;
        }
    }

    static final class AppOpSnapshot {
        final String pkg; final String mode;
        AppOpSnapshot(String pkg, String mode) { this.pkg = pkg; this.mode = mode; }
    }

    static final class SessionSnapshot {
        final String gamePkg; final int hzAction; final int powerAction; final int dataAction;
        final String minRefresh; final String peakRefresh; final String lowPower; final Boolean dataSaver;
        final List<AppOpSnapshot> background;
        SessionSnapshot(String gamePkg, int hzAction, int powerAction, int dataAction,
                        String minRefresh, String peakRefresh, String lowPower, Boolean dataSaver,
                        List<AppOpSnapshot> background) {
            this.gamePkg = gamePkg; this.hzAction = hzAction; this.powerAction = powerAction; this.dataAction = dataAction;
            this.minRefresh = minRefresh; this.peakRefresh = peakRefresh; this.lowPower = lowPower; this.dataSaver = dataSaver;
            this.background = background;
        }
    }

    static final class DeviceState {
        final boolean shizuku; final boolean powerSave; final int thermal;
        final long availMem; final long totalMem; final float displayHz;
        final String minHz; final String peakHz; final Boolean dataSaver;
        DeviceState(boolean shizuku, boolean powerSave, int thermal, long availMem, long totalMem,
                    float displayHz, String minHz, String peakHz, Boolean dataSaver) {
            this.shizuku = shizuku; this.powerSave = powerSave; this.thermal = thermal;
            this.availMem = availMem; this.totalMem = totalMem; this.displayHz = displayHz;
            this.minHz = minHz; this.peakHz = peakHz; this.dataSaver = dataSaver;
        }
    }

    static final class ApplyReport {
        final boolean ok; final String message;
        ApplyReport(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    static final class RestoreReport {
        final boolean ok; final String message;
        RestoreReport(boolean ok, String message) { this.ok = ok; this.message = message; }
    }
}

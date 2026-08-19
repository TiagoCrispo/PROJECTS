package com.fer.a53performance;

import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.Dialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** v1.18 Background Center overlay. */
public class MainActivity extends MainActivitz {
    private static final String PREFS = "bg_center_v118";
    private static final String RULE_NONE = "NONE";
    private static final String RULE_OPTIMIZE = "OPTIMIZE";
    private static final String RULE_UNRESTRICTED = "UNRESTRICTED";
    private static final Pattern SAFE_PKG = Pattern.compile("[A-Za-z0-9._]+");
    private static final long DAY = 24L * 60L * 60L * 1000L;

    private final ExecutorService bgIo = Executors.newSingleThreadExecutor();
    private final List<BgApp> allApps = new ArrayList<BgApp>();
    private final List<BgApp> visibleApps = new ArrayList<BgApp>();
    private final Set<String> sensitivePackages = new HashSet<String>();

    private Dialog bgDialog;
    private ListView appList;
    private BgAdapter bgAdapter;
    private TextView bgSummary;
    private ProgressBar bgProgress;
    private Spinner bgFilter;
    private Spinner bgSort;
    private SharedPreferences bgPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bgPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        seedSensitivePackages();
        addBackgroundEntry();
    }

    @Override
    protected void onDestroy() {
        bgIo.shutdownNow();
        super.onDestroy();
    }

    private void seedSensitivePackages() {
        Collections.addAll(sensitivePackages,
                "com.whatsapp",
                "com.google.android.gm",
                "com.samsung.android.messaging",
                "com.google.android.apps.messaging",
                "com.samsung.android.dialer",
                "com.google.android.dialer",
                "com.sec.android.app.clockpackage",
                "com.google.android.deskclock",
                "com.spotify.music",
                "com.google.android.apps.nbu.files",
                "com.google.android.apps.docs",
                "com.microsoft.office.word",
                "com.openai.chatgpt");
    }

    private void addBackgroundEntry() {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;

        Button button = new Button(this);
        button.setText("Segundo plano");
        button.setTextColor(Color.WHITE);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setElevation(dp(8));
        button.setContentDescription("Abrir Centro de Segundo Plano por aplicación");
        button.setBackground(roundRect(0xFF6C4DD9, dp(24)));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showBackgroundCenter(); }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52), Gravity.START | Gravity.BOTTOM);
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

    private void showBackgroundCenter() {
        bgDialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        bgDialog.setContentView(buildBackgroundView());
        bgDialog.show();
        Window w = bgDialog.getWindow();
        if (w != null) {
            w.setStatusBarColor(0xFF0D1117);
            w.setNavigationBarColor(0xFF0D1117);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        loadApps();
    }

    private View buildBackgroundView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Centro de Segundo Plano");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1f));
        Button close = smallButton("Cerrar");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (bgDialog != null) bgDialog.dismiss(); }
        });
        header.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(header);

        bgSummary = new TextView(this);
        bgSummary.setTextColor(0xFFAEB8C4);
        bgSummary.setTextSize(13f);
        bgSummary.setText("Cargando apps…");
        bgSummary.setPadding(0, 0, 0, dp(8));
        root.addView(bgSummary);

        LinearLayout accessRow = new LinearLayout(this);
        accessRow.setOrientation(LinearLayout.HORIZONTAL);
        accessRow.setGravity(Gravity.CENTER_VERTICAL);
        Button usage = smallButton("Acceso de uso");
        usage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
                catch (Throwable t) { Toast.makeText(MainActivity.this, "No pude abrir Acceso de uso", Toast.LENGTH_SHORT).show(); }
            }
        });
        accessRow.addView(usage, new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button shizuku = smallButton("Autorizar Shizuku");
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        shLp.setMargins(dp(6), 0, 0, 0);
        accessRow.addView(shizuku, shLp);
        shizuku.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    ShizukuShell.request();
                    Toast.makeText(MainActivity.this, "Solicitud de Shizuku enviada", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "Shizuku no disponible", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(accessRow);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        bgFilter = new Spinner(this);
        ArrayAdapter<String> fa = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                new String[]{"Todas", "Usadas 24 h", "Sin usar 7+ días", "Sensibles", "Con regla Galaxy"});
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bgFilter.setAdapter(fa);
        controls.addView(bgFilter, new LinearLayout.LayoutParams(0, dp(48), 1f));
        bgSort = new Spinner(this);
        ArrayAdapter<String> sa = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                new String[]{"Uso reciente", "Nombre", "Más olvidadas"});
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bgSort.setAdapter(sa);
        controls.addView(bgSort, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(controls);

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { applyBgFilter(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        bgFilter.setOnItemSelectedListener(listener);
        bgSort.setOnItemSelectedListener(listener);

        bgProgress = new ProgressBar(this);
        bgProgress.setIndeterminate(true);
        root.addView(bgProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        appList = new ListView(this);
        appList.setDividerHeight(dp(1));
        appList.setBackgroundColor(0xFF0D1117);
        bgAdapter = new BgAdapter(this, visibleApps);
        appList.setAdapter(bgAdapter);
        appList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < visibleApps.size()) showAppDetail(visibleApps.get(position));
            }
        });
        root.addView(appList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText("Galaxy no mata procesos continuamente. 'Optimizar fondo' usa una restricción Android/AppOps reversible y conserva un snapshot antes de tocar cada app.");
        foot.setTextColor(0xFF8F9AAA);
        foot.setTextSize(12f);
        foot.setPadding(dp(4), dp(8), dp(4), dp(6));
        root.addView(foot);
        return root;
    }

    private void loadApps() {
        if (bgProgress != null) bgProgress.setVisibility(View.VISIBLE);
        bgIo.execute(new Runnable() {
            @Override public void run() {
                final List<BgApp> loaded = queryUserApps();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        allApps.clear();
                        allApps.addAll(loaded);
                        if (bgProgress != null) bgProgress.setVisibility(View.GONE);
                        applyBgFilter();
                    }
                });
            }
        });
    }

    private List<BgApp> queryUserApps() {
        PackageManager pm = getPackageManager();
        long now = System.currentTimeMillis();
        Map<String, UsageStats> usage = new HashMap<String, UsageStats>();
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                Map<String, UsageStats> queried = usm.queryAndAggregateUsageStats(now - 45L * DAY, now);
                if (queried != null) usage.putAll(queried);
            }
        } catch (Throwable ignored) { }

        List<ApplicationInfo> installed;
        if (Build.VERSION.SDK_INT >= 33) installed = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0));
        else installed = pm.getInstalledApplications(0);

        List<BgApp> out = new ArrayList<BgApp>();
        for (ApplicationInfo ai : installed) {
            if (ai == null || ai.packageName == null) continue;
            if (getPackageName().equals(ai.packageName)) continue;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (system) continue;
            String label;
            try { label = String.valueOf(pm.getApplicationLabel(ai)); }
            catch (Throwable t) { label = ai.packageName; }
            UsageStats s = usage.get(ai.packageName);
            long last = s == null ? 0L : s.getLastTimeUsed();
            boolean dozeExempt = false;
            try {
                PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
                dozeExempt = power != null && power.isIgnoringBatteryOptimizations(ai.packageName);
            } catch (Throwable ignored) { }
            out.add(new BgApp(ai.packageName, label, ai.uid, last, dozeExempt, isSensitive(ai.packageName)));
        }
        return out;
    }

    private void applyBgFilter() {
        if (bgAdapter == null || bgFilter == null || bgSort == null) return;
        visibleApps.clear();
        long now = System.currentTimeMillis();
        int filter = bgFilter.getSelectedItemPosition();
        for (BgApp app : allApps) {
            String rule = getRule(app.pkg);
            boolean keep;
            switch (filter) {
                case 1: keep = app.lastUsed > 0L && now - app.lastUsed <= DAY; break;
                case 2: keep = app.lastUsed == 0L || now - app.lastUsed >= 7L * DAY; break;
                case 3: keep = app.sensitive; break;
                case 4: keep = !RULE_NONE.equals(rule); break;
                default: keep = true;
            }
            if (keep) visibleApps.add(app);
        }
        int sort = bgSort.getSelectedItemPosition();
        if (sort == 1) {
            Collections.sort(visibleApps, new Comparator<BgApp>() {
                @Override public int compare(BgApp a, BgApp b) { return a.label.compareToIgnoreCase(b.label); }
            });
        } else if (sort == 2) {
            Collections.sort(visibleApps, new Comparator<BgApp>() {
                @Override public int compare(BgApp a, BgApp b) {
                    long aa = a.lastUsed == 0L ? Long.MIN_VALUE : a.lastUsed;
                    long bb = b.lastUsed == 0L ? Long.MIN_VALUE : b.lastUsed;
                    return Long.compare(aa, bb);
                }
            });
        } else {
            Collections.sort(visibleApps, new Comparator<BgApp>() {
                @Override public int compare(BgApp a, BgApp b) { return Long.compare(b.lastUsed, a.lastUsed); }
            });
        }
        bgAdapter.notifyDataSetChanged();
        boolean access = hasUsageAccess();
        boolean shizuku = isShizukuReady();
        bgSummary.setText(visibleApps.size() + " apps · Uso " + (access ? "✓" : "sin permiso") + " · Shizuku " + (shizuku ? "✓" : "NC"));
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) { return false; }
    }

    private boolean isShizukuReady() {
        try { return ShizukuShell.ready(); }
        catch (Throwable t) { return false; }
    }

    private void showAppDetail(final BgApp app) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_Alert);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));
        root.setBackgroundColor(0xFF161B22);

        TextView title = text(app.label, 20f, Color.WHITE, true);
        root.addView(title);
        TextView pkg = text(app.pkg + " · UID " + app.uid, 12f, 0xFF98A2AF, false);
        root.addView(pkg);
        TextView last = text("Último uso: " + relativeLastUse(app.lastUsed) + " · Doze: " + (app.dozeExempt ? "exenta" : "normal"), 13f, 0xFFB7C0CC, false);
        last.setPadding(0, dp(8), 0, dp(8));
        root.addView(last);

        final TextView state = text("Estado real: toca Actualizar", 13f, 0xFFD5D9E0, false);
        state.setPadding(dp(10), dp(10), dp(10), dp(10));
        state.setBackground(roundRect(0xFF202833, dp(12)));
        root.addView(state);

        TextView rule = text("Regla Galaxy: " + ruleLabel(getRule(app.pkg)) + (app.sensitive ? " · APP SENSIBLE" : ""), 13f, app.sensitive ? 0xFFFFC857 : 0xFF9FE3B2, true);
        rule.setPadding(0, dp(10), 0, dp(6));
        root.addView(rule);

        Button refresh = smallButton("Actualizar estado real");
        root.addView(refresh, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshShellState(app, state); }
        });

        Button none = policyButton("Sin tocar · restaurar", 0xFF39424E);
        root.addView(none, lpButton());
        none.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyPolicyMaybe(app, RULE_NONE, state, d); }
        });

        Button optimize = policyButton("Optimizar fondo", 0xFF2F6FD5);
        root.addView(optimize, lpButton());
        optimize.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (app.sensitive) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("App sensible")
                            .setMessage("Restringir " + app.label + " puede retrasar notificaciones, sincronización o reproducción. Galaxy guardará el estado previo para poder restaurarlo. ¿Continuar?")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Optimizar", (dialog, which) -> applyPolicyMaybe(app, RULE_OPTIMIZE, state, d))
                            .show();
                } else applyPolicyMaybe(app, RULE_OPTIMIZE, state, d);
            }
        });

        Button unrestricted = policyButton("No restringir", 0xFF27835A);
        root.addView(unrestricted, lpButton());
        unrestricted.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { applyPolicyMaybe(app, RULE_UNRESTRICTED, state, d); }
        });

        Button settingsButton = smallButton("Ajustes de la app");
        root.addView(settingsButton, lpButton());
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg));
                    startActivity(i);
                } catch (Throwable t) { Toast.makeText(MainActivity.this, "No pude abrir ajustes", Toast.LENGTH_SHORT).show(); }
            }
        });

        Button close = smallButton("Cerrar");
        root.addView(close, lpButton());
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });

        d.setContentView(root);
        d.show();
        Window w = d.getWindow();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshShellState(app, state);
    }

    private void refreshShellState(final BgApp app, final TextView target) {
        target.setText("Leyendo AppOps / Data Saver / standby…");
        bgIo.execute(new Runnable() {
            @Override public void run() {
                final ShellState s = readShellState(app);
                runOnUiThread(new Runnable() {
                    @Override public void run() { target.setText(formatShellState(s)); }
                });
            }
        });
    }

    private void applyPolicyMaybe(final BgApp app, final String policy, final TextView target, final Dialog detail) {
        if (!isShizukuReady()) {
            target.setText("Shizuku NC · no se cambió nada");
            try { ShizukuShell.request(); } catch (Throwable ignored) { }
            return;
        }
        target.setText("Aplicando " + ruleLabel(policy) + "…");
        bgIo.execute(new Runnable() {
            @Override public void run() {
                final ApplyResult r = applyPolicy(app, policy);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        target.setText(r.message);
                        if (r.ok) {
                            applyBgFilter();
                            Toast.makeText(MainActivity.this, r.message, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private ApplyResult applyPolicy(BgApp app, String policy) {
        if (!SAFE_PKG.matcher(app.pkg).matches()) return new ApplyResult(false, "Paquete inválido · no se cambió nada");
        if (!isShizukuReady()) return new ApplyResult(false, "Shizuku NC · no se cambió nada");

        if (RULE_NONE.equals(policy)) {
            Snapshot snap = loadSnapshot(app.pkg);
            if (snap == null) {
                bgPrefs.edit().remove("rule." + app.pkg).apply();
                return new ApplyResult(true, "Sin snapshot previo · Galaxy deja la app sin tocar");
            }
            if (!restoreSnapshot(app, snap)) return new ApplyResult(false, "RESTAURACIÓN PARCIAL/NC · snapshot conservado");
            bgPrefs.edit()
                    .remove("rule." + app.pkg)
                    .remove("snap.mode." + app.pkg)
                    .remove("snap.wl." + app.pkg)
                    .remove("snap.bl." + app.pkg)
                    .apply();
            ShellState s = readShellState(app);
            return new ApplyResult(true, "Restaurado ✓ · " + formatShellStateOneLine(s));
        }

        Snapshot snap = loadSnapshot(app.pkg);
        if (snap == null) {
            snap = captureSnapshot(app);
            if (snap == null) return new ApplyResult(false, "No pude confirmar el estado previo · NO se cambió nada");
            if (!saveSnapshot(app.pkg, snap)) return new ApplyResult(false, "No pude persistir el snapshot · NO se cambió nada");
        }

        boolean ok;
        if (RULE_OPTIMIZE.equals(policy)) {
            ok = shellOk("cmd appops set " + app.pkg + " RUN_ANY_IN_BACKGROUND ignore")
                    && shellOk("cmd netpolicy remove restrict-background-whitelist " + app.uid);
        } else if (RULE_UNRESTRICTED.equals(policy)) {
            ok = shellOk("cmd appops set " + app.pkg + " RUN_ANY_IN_BACKGROUND allow")
                    && shellOk("cmd netpolicy remove restrict-background-blacklist " + app.uid)
                    && shellOk("cmd netpolicy add restrict-background-whitelist " + app.uid);
        } else return new ApplyResult(false, "Regla desconocida · no se cambió nada");

        ShellState after = readShellState(app);
        boolean verified = false;
        if (ok && RULE_OPTIMIZE.equals(policy)) {
            verified = ("ignore".equals(after.appOp) || "deny".equals(after.appOp)) && Boolean.FALSE.equals(after.whitelist);
        } else if (ok && RULE_UNRESTRICTED.equals(policy)) {
            verified = "allow".equals(after.appOp) && Boolean.TRUE.equals(after.whitelist) && Boolean.FALSE.equals(after.blacklist);
        }

        if (verified) {
            bgPrefs.edit().putString("rule." + app.pkg, policy).apply();
            return new ApplyResult(true, ruleLabel(policy) + " ✓ · " + formatShellStateOneLine(after));
        }
        return new ApplyResult(false, "PARCIAL/NC · snapshot conservado · " + formatShellStateOneLine(after));
    }

    private Snapshot captureSnapshot(BgApp app) {
        ShellState s = readShellState(app);
        if (s.appOp == null || s.whitelist == null || s.blacklist == null) return null;
        if (!("allow".equals(s.appOp) || "ignore".equals(s.appOp) || "deny".equals(s.appOp) || "default".equals(s.appOp))) return null;
        return new Snapshot(s.appOp, s.whitelist.booleanValue(), s.blacklist.booleanValue());
    }

    private boolean restoreSnapshot(BgApp app, Snapshot snap) {
        boolean a = shellOk("cmd appops set " + app.pkg + " RUN_ANY_IN_BACKGROUND " + snap.appOpMode);
        boolean w = shellOk("cmd netpolicy " + (snap.whitelist ? "add" : "remove") + " restrict-background-whitelist " + app.uid);
        boolean b = shellOk("cmd netpolicy " + (snap.blacklist ? "add" : "remove") + " restrict-background-blacklist " + app.uid);
        if (!(a && w && b)) return false;
        ShellState s = readShellState(app);
        return snap.appOpMode.equals(s.appOp)
                && Boolean.valueOf(snap.whitelist).equals(s.whitelist)
                && Boolean.valueOf(snap.blacklist).equals(s.blacklist);
    }

    private ShellState readShellState(BgApp app) {
        if (!isShizukuReady()) return new ShellState(null, null, null, null, "Shizuku NC");
        ShizukuShell.Result op = shell("cmd appops get " + app.pkg + " RUN_ANY_IN_BACKGROUND");
        ShizukuShell.Result wl = shell("cmd netpolicy list restrict-background-whitelist");
        ShizukuShell.Result bl = shell("cmd netpolicy list restrict-background-blacklist");
        ShizukuShell.Result bucket = shell("am get-standby-bucket " + app.pkg);
        String mode = op != null && op.ok() ? parseAppOp(op.out) : null;
        Boolean inWl = wl != null && wl.ok() ? Boolean.valueOf(containsUid(wl.out, app.uid)) : null;
        Boolean inBl = bl != null && bl.ok() ? Boolean.valueOf(containsUid(bl.out, app.uid)) : null;
        String standby = bucket != null && bucket.ok() ? parseBucket(bucket.out) : null;
        String err = firstError(op, wl, bl, bucket);
        return new ShellState(mode, inWl, inBl, standby, err);
    }

    private ShizukuShell.Result shell(String command) {
        try { return ShizukuShell.run(command); }
        catch (Throwable t) { return null; }
    }

    private boolean shellOk(String command) {
        ShizukuShell.Result r = shell(command);
        return r != null && r.ok();
    }

    private String parseAppOp(String out) {
        String s = out == null ? "" : out.toLowerCase(Locale.ROOT);
        if (s.contains("ignore")) return "ignore";
        if (s.contains("deny")) return "deny";
        if (s.contains("allow")) return "allow";
        if (s.contains("default") || s.contains("no operations")) return "default";
        return null;
    }

    private boolean containsUid(String out, int uid) {
        if (out == null) return false;
        String token = String.valueOf(uid);
        int from = 0;
        while (true) {
            int i = out.indexOf(token, from);
            if (i < 0) return false;
            boolean left = i == 0 || !Character.isDigit(out.charAt(i - 1));
            int end = i + token.length();
            boolean right = end >= out.length() || !Character.isDigit(out.charAt(end));
            if (left && right) return true;
            from = end;
        }
    }

    private String parseBucket(String out) {
        if (out == null) return null;
        String s = out.trim().toLowerCase(Locale.ROOT);
        if (s.contains("active")) return "ACTIVE";
        if (s.contains("working")) return "WORKING_SET";
        if (s.contains("frequent")) return "FREQUENT";
        if (s.contains("rare")) return "RARE";
        if (s.contains("restricted")) return "RESTRICTED";
        if (s.contains("never")) return "NEVER";
        try {
            int v = Integer.parseInt(s.replaceAll("[^0-9]", ""));
            switch (v) {
                case 10: return "ACTIVE";
                case 20: return "WORKING_SET";
                case 30: return "FREQUENT";
                case 40: return "RARE";
                case 45: return "RESTRICTED";
                case 50: return "NEVER";
                default: return String.valueOf(v);
            }
        } catch (Throwable t) { return null; }
    }

    private String firstError(ShizukuShell.Result... results) {
        for (ShizukuShell.Result r : results) {
            if (r == null) return "NC";
            if (!r.ok()) {
                String e = r.err == null ? "" : r.err.trim();
                return e.length() == 0 ? "exit " + r.code : e;
            }
        }
        return null;
    }

    private String formatShellState(ShellState s) {
        return "AppOps fondo: " + val(s.appOp)
                + "\nData Saver allowlist: " + boolVal(s.whitelist)
                + " · blacklist: " + boolVal(s.blacklist)
                + "\nStandby bucket: " + val(s.standby)
                + (s.error == null ? "" : "\nLectura: " + s.error);
    }

    private String formatShellStateOneLine(ShellState s) {
        return "AppOps " + val(s.appOp) + " · WL " + boolVal(s.whitelist) + " · BL " + boolVal(s.blacklist);
    }

    private String val(String s) { return s == null ? "NC" : s; }
    private String boolVal(Boolean b) { return b == null ? "NC" : (b.booleanValue() ? "ON" : "OFF"); }

    private boolean saveSnapshot(String pkg, Snapshot snap) {
        return bgPrefs.edit()
                .putString("snap.mode." + pkg, snap.appOpMode)
                .putBoolean("snap.wl." + pkg, snap.whitelist)
                .putBoolean("snap.bl." + pkg, snap.blacklist)
                .commit();
    }

    private Snapshot loadSnapshot(String pkg) {
        String mode = bgPrefs.getString("snap.mode." + pkg, null);
        if (mode == null) return null;
        return new Snapshot(mode,
                bgPrefs.getBoolean("snap.wl." + pkg, false),
                bgPrefs.getBoolean("snap.bl." + pkg, false));
    }

    private String getRule(String pkg) {
        return bgPrefs == null ? RULE_NONE : bgPrefs.getString("rule." + pkg, RULE_NONE);
    }

    private String ruleLabel(String rule) {
        if (RULE_OPTIMIZE.equals(rule)) return "Optimizar fondo";
        if (RULE_UNRESTRICTED.equals(rule)) return "No restringir";
        return "Sin tocar";
    }

    private boolean isSensitive(String pkg) {
        if (sensitivePackages.contains(pkg)) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("messaging") || p.contains("dialer") || p.contains("clock") || p.contains("alarm");
    }

    private String relativeLastUse(long when) {
        if (when <= 0L) return hasUsageAccess() ? "sin uso registrado" : "sin permiso de uso";
        long age = Math.max(0L, System.currentTimeMillis() - when);
        if (age < 60L * 60L * 1000L) return "hace " + Math.max(1L, age / (60L * 1000L)) + " min";
        if (age < DAY) return "hace " + Math.max(1L, age / (60L * 60L * 1000L)) + " h";
        if (age < 14L * DAY) return "hace " + Math.max(1L, age / DAY) + " días";
        return DateFormat.getDateInstance(DateFormat.SHORT).format(new java.util.Date(when));
    }

    private LinearLayout.LayoutParams lpButton() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, dp(7), 0, 0);
        return lp;
    }

    private Button policyButton(String label, int color) {
        Button b = smallButton(label);
        b.setTextColor(Color.WHITE);
        b.setBackground(roundRect(color, dp(12)));
        return b;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(roundRect(0xFF28313C, dp(12)));
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class BgAdapter extends BaseAdapter {
        private final Context context;
        private final List<BgApp> data;
        BgAdapter(Context context, List<BgApp> data) { this.context = context; this.data = data; }
        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int position) { return data.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(8), dp(8), dp(8));
                row.setBackgroundColor(0xFF111720);
                ImageView icon = new ImageView(context);
                row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
                LinearLayout texts = new LinearLayout(context);
                texts.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tlp.setMargins(dp(10), 0, dp(8), 0);
                row.addView(texts, tlp);
                TextView name = text("", 15f, Color.WHITE, true);
                TextView sub = text("", 12f, 0xFF9EA8B5, false);
                TextView rule = text("", 12f, 0xFF9FE3B2, true);
                texts.addView(name);
                texts.addView(sub);
                texts.addView(rule);
                TextView arrow = text("›", 28f, 0xFF7F8A98, false);
                row.addView(arrow, new LinearLayout.LayoutParams(dp(26), dp(50)));
                h = new RowHolder(icon, name, sub, rule);
                row.setTag(h);
                convertView = row;
            } else h = (RowHolder) convertView.getTag();

            BgApp app = data.get(position);
            h.name.setText(app.label + (app.sensitive ? "  ⚠" : ""));
            h.sub.setText(relativeLastUse(app.lastUsed) + " · Doze " + (app.dozeExempt ? "exenta" : "normal"));
            h.rule.setText("Galaxy: " + ruleLabel(getRule(app.pkg)));
            try {
                Drawable icon = getPackageManager().getApplicationIcon(app.pkg);
                h.icon.setImageDrawable(icon);
            } catch (Throwable t) { h.icon.setImageDrawable(null); }
            return convertView;
        }
    }

    private static final class RowHolder {
        final ImageView icon; final TextView name; final TextView sub; final TextView rule;
        RowHolder(ImageView icon, TextView name, TextView sub, TextView rule) {
            this.icon = icon; this.name = name; this.sub = sub; this.rule = rule;
        }
    }

    private static final class BgApp {
        final String pkg; final String label; final int uid; final long lastUsed; final boolean dozeExempt; final boolean sensitive;
        BgApp(String pkg, String label, int uid, long lastUsed, boolean dozeExempt, boolean sensitive) {
            this.pkg = pkg; this.label = label; this.uid = uid; this.lastUsed = lastUsed; this.dozeExempt = dozeExempt; this.sensitive = sensitive;
        }
    }

    private static final class Snapshot {
        final String appOpMode; final boolean whitelist; final boolean blacklist;
        Snapshot(String mode, boolean wl, boolean bl) { appOpMode = mode; whitelist = wl; blacklist = bl; }
    }

    private static final class ShellState {
        final String appOp; final Boolean whitelist; final Boolean blacklist; final String standby; final String error;
        ShellState(String appOp, Boolean whitelist, Boolean blacklist, String standby, String error) {
            this.appOp = appOp; this.whitelist = whitelist; this.blacklist = blacklist; this.standby = standby; this.error = error;
        }
    }

    private static final class ApplyResult {
        final boolean ok; final String message;
        ApplyResult(boolean ok, String message) { this.ok = ok; this.message = message; }
    }
}

package com.fer.wavault;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.ClipData;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.media.PlaybackParams;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.os.PowerManager;
import android.os.CancellationSignal;
import android.net.Uri;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final ExecutorService CLEANUP_EXECUTOR=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"wa-vault-ui-cleanup");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private static final ExecutorService STARTUP_EXECUTOR=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"wa-vault-ui-startup");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private static final AtomicBoolean CLEANUP_QUEUED=new AtomicBoolean(false);
    private static final AtomicBoolean STARTUP_MAINTENANCE_QUEUED=new AtomicBoolean(false);
    private static final AtomicBoolean VOICE_BANK_PREP_QUEUED=new AtomicBoolean(false);
    private static final int MEDIA_REQ = 42;
    private static final int VOICE_BANK_REQ = 43;
    private static final int CREDENTIAL_REQ = 44;
    private static final int EXPORT_REQ = 45;
    private static final int DIAGNOSTIC_EXPORT_REQ = 46;
    private static final int MEDIA_PAGE_SIZE = 80;
    private static final int MAX_IMAGE_PREVIEWS = 24;
    private LinearLayout content;
    private TextView status;
    private ScrollView currentScroll;
    private final Handler uiRefreshHandler = new Handler(Looper.getMainLooper());
    private boolean uiReceiverRegistered = false;
    private Runnable pendingVisibleRefresh;
    private final BroadcastReceiver dataChangedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !VaultUiNotifier.ACTION_DATA_CHANGED.equals(intent.getAction())) return;
            scheduleVisibleDataRefresh(intent.getStringExtra(VaultUiNotifier.EXTRA_KIND));
        }
    };
    private VaultDb db;
    private boolean permissionFlowStarted = false;
    private boolean openedNotificationSettings = false;
    private boolean openedAllFilesSettings = false;
    private boolean openedBatterySettings = false;
    private boolean openedSamsungSettings = false;
    private String currentScreen = "messages";
    private boolean uiUnlocked = true;
    private boolean unlockPromptOpen = false;
    private long backgroundAt = 0L;
    private CancellationSignal biometricCancel;
    private MediaPlayer audioPlayer;
    private final Handler audioHandler = new Handler(Looper.getMainLooper());
    private Runnable audioProgressTask;
    private String activeAudioPath;
    private File activeAudioTempFile;
    private File pendingExportFile;
    private String pendingExportName;
    private String pendingExportMime;
    private Button activePlayButton;
    private Button activeSpeedButton;
    private SeekBar activeSeekBar;
    private TextView activeTimeText;
    private boolean audioPrepared = false;
    private float audioSpeed = 1.0f;
    private String messageFilter = "all";
    private String messageQuery = "";
    private int messagePageLimit = 60;
    private LinearLayout messagesDynamicHost;
    private volatile boolean messageRefreshRunning = false;
    private String conversationFilterKey = "";
    private boolean conversationMode = false;
    private String mediaFilter = "all";
    private String mediaSort = "recent";
    private String mediaQuery = "";
    private int mediaDays = 0;
    private int mediaLoadGeneration = 0;
    private int mediaQueryLimit = MEDIA_PAGE_SIZE;
    private int mediaNextOffset = 0;
    private volatile boolean mediaHasMore = false;
    private volatile boolean mediaLoadingMore = false;
    private GridView recoveredGrid;
    private RecyclerView recoveryRecycler;
    private RecoveryCenterAdapter recoveryCenterAdapter;
    private final Set<Long> recoveredSelection = new LinkedHashSet<>();
    private TextView recoveredSelectionText;
    private LinearLayout recoveredSelectBulk, recoveredBulkActions;
    private boolean mediaPendingOnly = false;
    private boolean mediaUnlinkedOnly = false;
    private boolean mediaFavoritesOnly = false;
    private final ArrayList<VaultDb.Media> recoveredItems = new ArrayList<>();
    private long recoveredRefreshAnchorId = 0L;
    private long recoveredRefreshFallbackId = 0L;
    private int recoveredRefreshAnchorIndex = 0;
    private int recoveredRefreshAnchorOffset = 0;
    private int recoveredRefreshTargetCount = 0;
    private MediaAdapter recoveredAdapter;
    private TextView recoveredCountText;
    private String pendingDiagnosticExport;
    private int homeLoadGeneration=0;
    private int diagnosticsLoadGeneration=0;
    private final Map<Long,String> recoveredContext = new LinkedHashMap<>();
    private int bg = Color.rgb(7,10,13), card = Color.rgb(15,20,26), cardSoft = Color.rgb(20,27,35),
            fg = Color.rgb(247,249,251), muted = Color.rgb(139,152,167), green = Color.rgb(92,224,164),
            line = Color.rgb(37,48,59), warning = Color.rgb(255,188,112);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new VaultDb(this);
        final Context appContext=getApplicationContext();
        try{MigrationCoordinator.ensureAsync(appContext);}catch(Throwable t){recordUiError("METADATA_PRIVACY_MIGRATION",t);}
        if(STARTUP_MAINTENANCE_QUEUED.compareAndSet(false,true))STARTUP_EXECUTOR.execute(()->{try{
            VaultDb local=new VaultDb(appContext);
            try{local.purgeStickerMedia();}catch(Throwable ignored){}
            try{local.repairLegacyAppCancelConfirmations();}catch(Throwable t){try{local.logInternalError("APP_CANCEL_MIGRATION",t);}catch(Throwable ignored){}}
            try{local.normalizeConfirmedMediaVisibility();}catch(Throwable t){try{local.logInternalError("VISIBILITY_NORMALIZE",t);}catch(Throwable ignored){}}
            try{MediaArchiver.resumePendingMonitors(appContext);}catch(Throwable ignored){}
        }finally{STARTUP_MAINTENANCE_QUEUED.set(false);}});
        registerUiReceiver();
        if(CLEANUP_QUEUED.compareAndSet(false,true))CLEANUP_EXECUTOR.execute(()->{try{
            try{MediaCrypto.cleanupCache(appContext);}catch(Throwable ignored){}
            try{StorageAnalyzer.cleanupTemporary(appContext);}catch(Throwable ignored){}
        }finally{CLEANUP_QUEUED.set(false);}});
        try { CaptureCoordinator.initialize(appContext); } catch (Throwable ignored) {}
        applySecureWindowSetting();
        if (MediaArchiver.hasVoiceBank(appContext)&&VOICE_BANK_PREP_QUEUED.compareAndSet(false,true))STARTUP_EXECUTOR.execute(()->{try{MediaArchiver.prepareVoiceBank(appContext);}catch(Throwable ignored){}finally{VOICE_BANK_PREP_QUEUED.set(false);}});
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        // v0.5.30: user preference: every Activity recreation starts directly in deleted messages.
        // Do not restore HOME/diagnostics as the launch destination; filters inside Messages remain independent.
        currentScreen = "messages";
        getSharedPreferences("wa_vault_ui", MODE_PRIVATE).edit().putString("last_screen", currentScreen).apply();
        boolean lockEnabled=getSharedPreferences("wa_vault_settings",MODE_PRIVATE).getBoolean("app_lock",false);
        uiUnlocked=!lockEnabled;
        if(lockEnabled){showLockedScreen();getWindow().getDecorView().postDelayed(this::authenticateVault,180L);}
        else{renderCurrentScreen();getWindow().getDecorView().postDelayed(MainActivity.this::startPermissionFlow,350L);}
    }

    private void registerUiReceiver(){
        if(uiReceiverRegistered)return;
        try{
            IntentFilter f=new IntentFilter(VaultUiNotifier.ACTION_DATA_CHANGED);
            if(Build.VERSION.SDK_INT>=33) registerReceiver(dataChangedReceiver,f,VaultUiNotifier.INTERNAL_PERMISSION,null,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(dataChangedReceiver,f,VaultUiNotifier.INTERNAL_PERMISSION,null);
            uiReceiverRegistered=true;
        }catch(Throwable ignored){}
    }

    private void scheduleVisibleDataRefresh(String kind){
        if(!uiUnlocked)return;
        if(!"messages".equals(currentScreen)&&!"recovered".equals(currentScreen))return;
        if(activeAudioPath!=null)return;
        if(pendingVisibleRefresh!=null)uiRefreshHandler.removeCallbacks(pendingVisibleRefresh);
        final String screen=currentScreen;
        final int y=currentScroll==null?0:currentScroll.getScrollY();
        pendingVisibleRefresh=()->{
            if(!screen.equals(currentScreen))return;
            if("messages".equals(screen)){
                refreshMessagesInPlace(y);
            }else refreshRecoveredInPlace();
        };
        uiRefreshHandler.postDelayed(pendingVisibleRefresh,45L);
    }

    @Override protected void onDestroy(){
        try{if(uiReceiverRegistered)unregisterReceiver(dataChangedReceiver);}catch(Throwable ignored){}
        uiReceiverRegistered=false;
        uiRefreshHandler.removeCallbacksAndMessages(null);
        try { if (biometricCancel != null) biometricCancel.cancel(); } catch (Throwable ignored) {}
        releaseAudioPlayer(false);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        final Context appContext=getApplicationContext();
        if(CLEANUP_QUEUED.compareAndSet(false,true))CLEANUP_EXECUTOR.execute(()->{
            try{StorageAnalyzer.cleanupDecryptedTemporary(appContext);}catch(Throwable ignored){}
            try{StorageAnalyzer.cleanupShareTemporary(appContext,15L*60L*1000L);}catch(Throwable ignored){}
            finally{CLEANUP_QUEUED.set(false);}
        });
        boolean cameBack = openedNotificationSettings || openedAllFilesSettings || openedBatterySettings || openedSamsungSettings;
        openedNotificationSettings = false;
        openedAllFilesSettings = false;
        openedBatterySettings = false;
        openedSamsungSettings = false;
        if (cameBack) {
            getWindow().getDecorView().postDelayed(() -> {
                try { CaptureCoordinator.initialize(getApplicationContext()); } catch (Throwable ignored) {}
                if (uiUnlocked) { renderCurrentScreen(); continueSetupAfterPermissions(); }
            }, 220L);
        }
    }

    @Override protected void onStop() {
        try { new VaultDb(getApplicationContext()).logEvent("APP_BACKGROUND", "MainActivity.onStop", 0L, 0L); } catch (Throwable ignored) {}
        super.onStop();
        if (getSharedPreferences("wa_vault_settings", MODE_PRIVATE).getBoolean("app_lock", false)) {
            backgroundAt = System.currentTimeMillis();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        try { new VaultDb(getApplicationContext()).logEvent("APP_FOREGROUND", "MainActivity.onStart", 0L, 0L); } catch (Throwable ignored) {}
        if (!getSharedPreferences("wa_vault_settings", MODE_PRIVATE).getBoolean("app_lock", false)) return;
        long delay = getSharedPreferences("wa_vault_settings", MODE_PRIVATE).getLong("app_lock_delay_ms", 30_000L);
        if (backgroundAt > 0 && System.currentTimeMillis() - backgroundAt >= Math.max(0L, delay)) {
            uiUnlocked = false;
            showLockedScreen();
            getWindow().getDecorView().postDelayed(this::authenticateVault, 120L);
        }
    }

    private void showLockedScreen() {
        releaseAudioPlayer(true);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(bg);
        applySafeInsets(root,28,20,28,20);
        TextView icon=text("◈",52,green,true); icon.setGravity(Gravity.CENTER); root.addView(icon);
        TextView title=text("WA Vault",28,fg,true); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub=text("Archivo privado bloqueado",14,muted,false); sub.setGravity(Gravity.CENTER); root.addView(sub);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.setMargins(0,dp(24),0,0);
        Button unlock=button("Desbloquear",v->authenticateVault());root.addView(unlock,bp);
        setContentView(root);
    }

    private void authenticateVault() {
        if (uiUnlocked || unlockPromptOpen) return;
        unlockPromptOpen=true;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Executor executor = getMainExecutor();
                BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                        .setTitle("Desbloquear WA Vault")
                        .setSubtitle("Huella, rostro o bloqueo del dispositivo");
                if (Build.VERSION.SDK_INT >= 29) {
                    b.setDeviceCredentialAllowed(true);
                } else {
                    b.setNegativeButton("Usar PIN", executor, (dialog, which) -> { unlockPromptOpen=false; launchDeviceCredential(); });
                }
                BiometricPrompt prompt=b.build();
                biometricCancel=new CancellationSignal();
                prompt.authenticate(biometricCancel,executor,new BiometricPrompt.AuthenticationCallback(){
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){
                        unlockPromptOpen=false;uiUnlocked=true;backgroundAt=0L;renderCurrentScreen();getWindow().getDecorView().postDelayed(MainActivity.this::startPermissionFlow,350L);
                    }
                    @Override public void onAuthenticationError(int errorCode,CharSequence errString){
                        unlockPromptOpen=false;
                    }
                });
                return;
            } catch (Throwable ignored) { unlockPromptOpen=false; }
        }
        launchDeviceCredential();
    }

    private void launchDeviceCredential() {
        unlockPromptOpen=false;
        try {
            KeyguardManager km=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
            Intent i=km==null?null:km.createConfirmDeviceCredentialIntent("Desbloquear WA Vault","Usa el bloqueo de tu teléfono");
            if(i!=null){startActivityForResult(i,CREDENTIAL_REQ);return;}
        } catch(Throwable ignored){}
        // Device without a configured credential: do not trap the user behind an unusable lock.
        uiUnlocked=true;renderCurrentScreen();
    }

    private int dp(int v){ return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable outlined(int color, int strokeColor, int radiusDp) {
        GradientDrawable d = rounded(color, radiusDp);
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private void applySafeInsets(View root, int leftDp, int topDp, int rightDp, int bottomDp) {
        final int l=dp(leftDp), t=dp(topDp), r=dp(rightDp), b=dp(bottomDp);
        // Android 15+: keep chat/media UI hidden from remote screen-share sessions even
        // when the user deliberately allows local screenshots by disabling FLAG_SECURE.
        if(Build.VERSION.SDK_INT>=35){
            try{root.setContentSensitivity(View.CONTENT_SENSITIVITY_SENSITIVE);}catch(Throwable ignored){}
        }
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int il,it,ir,ib;
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                il=bars.left;it=bars.top;ir=bars.right;ib=bars.bottom;
            }else{
                il=insets.getSystemWindowInsetLeft();it=insets.getSystemWindowInsetTop();
                ir=insets.getSystemWindowInsetRight();ib=insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l+il,t+it,r+ir,b+ib);
            return insets;
        });
        root.requestApplyInsets();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s == null ? "" : s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setPadding(0,dp(3),0,dp(3));
        v.setLineSpacing(0,1.05f);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b=new Button(this);
        b.setText(s == null ? "" : s);b.setTextColor(fg);b.setTextSize(12.5f);b.setAllCaps(false);b.setGravity(Gravity.CENTER);b.setMaxLines(2);b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMinimumWidth(0);b.setMinWidth(0);b.setMinimumHeight(dp(46));b.setMinHeight(dp(46));b.setPadding(dp(14),dp(9),dp(14),dp(9));b.setClickable(true);b.setFocusable(true);
        GradientDrawable base=outlined(cardSoft,line,16);if(Build.VERSION.SDK_INT>=21)b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(42,92,224,164)),base,null));else b.setBackground(base);
        b.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);if(l!=null)l.onClick(v);});
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(3),dp(3),dp(3),dp(3));b.setLayoutParams(p);return b;
    }
    private Button navButton(String label,String screen,Runnable action){
        Button b=button(label,v->{if(action!=null)action.run();});
        b.setTag("wa_nav");
        b.setMaxLines(1);
        b.setSingleLine(true);
        if(screen.equals(currentScreen)){
            b.setTextColor(green);
            GradientDrawable base=outlined(Color.rgb(13,35,28),Color.rgb(38,92,68),16);
            if(Build.VERSION.SDK_INT>=21)b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60,63,220,135)),base,null));else b.setBackground(base);
        }
        return b;
    }

    private LinearLayout row(){ LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setClipChildren(false); return r; }
    private void addWeighted(LinearLayout r, View v){
        if(v instanceof Button){
            Button b=(Button)v;
            if("wa_nav".equals(b.getTag())){b.setTextSize(10.5f);b.setSingleLine(true);b.setMaxLines(1);b.setMinHeight(dp(44));b.setPadding(dp(4),dp(7),dp(4),dp(7));}
            else {b.setTextSize(12);b.setMaxLines(2);b.setMinHeight(dp(52));}
        }
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1f);p.setMargins(dp(3),dp(2),dp(3),dp(2));r.addView(v,p);
    }
    private LinearLayout card(){
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18),dp(16),dp(18),dp(16));
        c.setBackground(outlined(card,line,22));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,0,0,dp(12));
        c.setLayoutParams(p);
        return c;
    }

    private boolean notificationAccess(){
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private void rememberScreen(String screen) {
        currentScreen = screen;
        getSharedPreferences("wa_vault_ui", MODE_PRIVATE).edit().putString("last_screen", screen).apply();
    }

    private void renderCurrentScreen() {
        if ("messages".equals(currentScreen)) showMessages();
        else if ("recovered".equals(currentScreen) || "media".equals(currentScreen)) showMedia();
        else if ("diagnostics".equals(currentScreen)) showDiagnostics();
        else if ("settings".equals(currentScreen)) showSettings();
        else showHome();
    }

    private LinearLayout bottomNav(){
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setPadding(0,dp(6),0,0);wrap.setBackgroundColor(bg);
        LinearLayout nav=row();
        addWeighted(nav,navButton("Inicio","home",this::showHome));
        addWeighted(nav,navButton("Borrados","messages",this::showMessages));
        addWeighted(nav,navButton("Recuperados","recovered",this::showMedia));
        addWeighted(nav,navButton("Ajustes","settings",this::showSettings));
        wrap.addView(nav,new LinearLayout.LayoutParams(-1,-2));return wrap;
    }

    private void shell(String title){
        releaseAudioPlayer(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);applySafeInsets(root,16,8,16,6);
        LinearLayout heading=row();TextView h=text(title,26,fg,true);heading.addView(h,new LinearLayout.LayoutParams(0,-2,1f));
        int level=protectionLevel();String chip=level==2?"COMPLETA":(level==1?"LIMITADA":"ATENCIÓN");int chipColor=level==2?green:warning;status=text(chip,10,chipColor,true);status.setGravity(Gravity.CENTER);status.setBackground(outlined(level==2?Color.rgb(12,34,27):Color.rgb(45,29,21),level==2?Color.rgb(37,93,68):Color.rgb(104,72,39),30));status.setPadding(dp(11),dp(6),dp(11),dp(6));heading.addView(status);root.addView(heading);
        ScrollView sc=new ScrollView(this);currentScroll=sc;sc.setFillViewport(true);sc.setClipToPadding(false);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,dp(14),0,dp(18));sc.addView(content);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));
        root.addView(bottomNav());setContentView(root);
    }

    private int protectionLevel(){
        if(!notificationAccess())return 0;
        SharedPreferences d=getSharedPreferences("wa_vault_diag",MODE_PRIVATE);long now=System.currentTimeMillis();
        boolean mediaFallback=hasAllMediaPermissions()||DirectMediaWatcher.isAvailable(this)||DirectVoiceWatcher.isAvailable(this);if(!mediaFallback)return 0;
        long init=d.getLong("capture_coordinator_last_at",0L),wd=d.getLong("watchdog_last_at",0L);int startFailures=d.getInt("capture_start_failures",0);
        boolean settling=init>0&&now-init<1800L&&startFailures==0;
        boolean directMediaOk=!DirectMediaWatcher.isAvailable(this)||DirectMediaWatcher.isHealthy()||settling;
        boolean directVoiceOk=!DirectVoiceWatcher.isAvailable(this)||DirectVoiceWatcher.isHealthy()||settling;
        boolean mediaStoreOk=MediaStoreWatcher.isHealthy()||settling;
        if(init>0&&now-init<5*60_000L&&startFailures>0&&(!directMediaOk||!directVoiceOk||!mediaStoreOk))return 0;
        boolean watchdogFresh=wd<=0||now-wd<7*60_000L;
        if(!directMediaOk||!directVoiceOk||!mediaStoreOk||!watchdogFresh||!ignoresBatteryOptimizations()||!hasAllMediaPermissions())return 1;
        return 2;
    }
    private boolean protectionHealthy(){return protectionLevel()==2;}
    private String protectionHeadline(){int l=protectionLevel();return l==2?"Protección completa":(l==1?"Protección limitada":"Requiere atención");}
    private String protectionDescription(){int l=protectionLevel();if(l==2)return "Todos los motores principales están activos y solo se muestran borrados confirmados.";if(l==1)return "La captura sigue disponible, pero Android está usando una ruta alternativa o una restricción puede reducir la velocidad.";return "Falta un permiso o un motor crítico. Usa Reparar protección para volver a iniciar las rutas seguras.";}
    private void repairProtection(){CaptureCoordinator.restart(getApplicationContext());Toast.makeText(this,"Revisando y reparando protección…",Toast.LENGTH_SHORT).show();getWindow().getDecorView().postDelayed(this::renderCurrentScreen,700L);}

    private void applySecureWindowSetting() {
        boolean secure = getSharedPreferences("wa_vault_settings", MODE_PRIVATE).getBoolean("secure_screenshots", true);
        if (secure) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void showHome(){
        rememberScreen("home");shell("WA Vault");final int generation=++homeLoadGeneration;
        LinearLayout loading=card();loading.addView(text("Cargando estado…",14,muted,true));content.addView(loading);
        final Context app=getApplicationContext();
        new Thread(()->{
            VaultDb.Stats st;try{st=new VaultDb(app).getStats();}catch(Throwable t){st=new VaultDb.Stats();}
            long physical;try{physical=StorageAnalyzer.vaultPhysicalBytes(app);}catch(Throwable t){physical=0L;}
            final VaultDb.Stats stats=st;final long bytes=physical;
            postUiIfAlive(()->{if(generation!=homeLoadGeneration||!"home".equals(currentScreen))return;renderHomeSnapshot(stats,bytes);});
        },"wa-vault-home-stats").start();
    }

    private void renderHomeSnapshot(VaultDb.Stats st,long physical){
        shell("WA Vault");
        LinearLayout hero=card();hero.setBackground(outlined(Color.rgb(11,30,24),Color.rgb(35,83,63),24));
        int protection=protectionLevel();String activeTitle=protection==0?"WA Vault requiere atención":"WA Vault activo";hero.addView(text(activeTitle,20,protection==2?fg:warning,true));
        hero.addView(text("Estado · "+protectionHeadline(),12,protection==2?green:warning,true));
        hero.addView(text(protectionDescription(),11,protection==2?muted:warning,false));
        LinearLayout metrics=row();addWeighted(metrics,metricTile(String.valueOf(st.deletedMessages),"Borrados"));addWeighted(metrics,metricTile(String.valueOf(st.savedFiles),"Recuperados"));addWeighted(metrics,metricTile(human(st.totalBytes),"Guardado"));hero.addView(metrics);content.addView(hero);

        LinearLayout quick=row();addWeighted(quick,button("Ver borrados",v->showMessages()));addWeighted(quick,button("Ver recuperados",v->showMedia()));content.addView(quick);

        LinearLayout storage=card();storage.addView(text("Almacenamiento",16,fg,true));storage.addView(text("Recuperados  "+human(st.totalBytes),13,fg,true));storage.addView(text("Papelera  "+human(st.trashBytes)+"   ·   Temporal oculto  "+human(st.pendingBytes),11,muted,false));storage.addView(text("Uso físico de WA Vault  "+human(physical),11,muted,false));if(st.trashedMedia>0)storage.addView(button("Abrir papelera · "+st.trashedMedia,v->showTrashDialog()));content.addView(storage);

        SharedPreferences diag=getSharedPreferences("wa_vault_diag",MODE_PRIVATE);long wd=diag.getLong("watchdog_last_at",0L);long init=diag.getLong("capture_coordinator_last_at",0L);boolean settling=init>0&&System.currentTimeMillis()-init<1800L;
        LinearLayout health=card();health.addView(text("Estado",16,fg,true));
        health.addView(statusLine(notificationAccess(),"Notificaciones",notificationAccess()?"Acceso activo":"Debes activar acceso a notificaciones"));
        health.addView(statusLine(hasAllMediaPermissions(),"Archivos",hasAllMediaPermissions()?"Acceso multimedia activo":"Revisa permiso de archivos/multimedia"));
        health.addView(statusLine(ignoresBatteryOptimizations(),"Batería",ignoresBatteryOptimizations()?"Sin restricción crítica":"Android puede pausar la captura"));
        boolean dm=DirectMediaWatcher.isAvailable(this)&&(DirectMediaWatcher.isHealthy()||settling);boolean dv=DirectVoiceWatcher.isAvailable(this)&&(DirectVoiceWatcher.isHealthy()||settling);
        health.addView(statusLine(dm,"Fotos y videos",dm?(DirectMediaWatcher.isHealthy()?"Watcher directo activo":"Iniciando watcher…"):"Ruta alternativa / reparación disponible"));
        health.addView(statusLine(dv,"Audios",dv?(DirectVoiceWatcher.isHealthy()?"Watcher directo activo":"Iniciando watcher…"):"Ruta alternativa / reparación disponible"));
        boolean ms=MediaStoreWatcher.isHealthy()||settling;health.addView(statusLine(ms,"MediaStore",MediaStoreWatcher.isHealthy()?"Observador activo":(settling?"Iniciando observador…":"Necesita reinicio")));
        health.addView(text(wd>0?"Autorreparación · "+DateFormat.format("HH:mm",new Date(wd)):"Autorreparación iniciándose…",10,muted,false));LinearLayout ha=row();addWeighted(ha,button("Ver estado",v->showDiagnostics()));addWeighted(ha,button("Reparar protección",v->repairProtection()));health.addView(ha);content.addView(health);
    }

    private View metricTile(String value,String label){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);x.setGravity(Gravity.CENTER);x.setPadding(dp(6),dp(14),dp(6),dp(8));x.addView(text(value,17,fg,true));x.addView(text(label,10,muted,false));return x;}
    private TextView statusLine(boolean ok,String label,String detail){return text((ok?"✓  ":"!  ")+label+"  ·  "+detail,11,ok?green:warning,ok);}

    private boolean uiAlive(){return !isFinishing()&&(Build.VERSION.SDK_INT<17||!isDestroyed());}
    private void postUiIfAlive(Runnable action){if(action==null)return;MainActivity.this.runOnUiThread(()->{if(uiAlive())action.run();});}

    private void recordUiError(String scope,Throwable t){try{if(db!=null)db.logInternalError(scope,t);}catch(Throwable ignored){}}

    private List<VaultDb.Msg> safeMessages(int limit) {
        try { return db.listMessages(limit); }
        catch (Throwable t) { return new ArrayList<>(); }
    }

    private List<VaultDb.Media> safeMedia(int limit) {
        try { return db.listMedia(limit); }
        catch (Throwable t) { return new ArrayList<>(); }
    }

    private int countDeleted(List<VaultDb.Msg> l){int n=0; for(VaultDb.Msg m:l) if(m.deleted)n++; return n;}

    private void showMessages(){
        rememberScreen("messages");
        shell("Mensajes borrados");

        LinearLayout intro=card();intro.setBackground(outlined(Color.rgb(12,27,23),Color.rgb(32,70,55),22));intro.addView(text("Solo borrados confirmados",16,fg,true));intro.addView(text("Los mensajes normales y las detecciones dudosas permanecen fuera de esta pantalla.",11,muted,false));content.addView(intro);
        LinearLayout toolbar=row();
        addWeighted(toolbar,button(messageQuery.isEmpty()?"Buscar":"Buscar · "+messageQuery,v->showMessageSearchDialog()));
        addWeighted(toolbar,button(conversationMode?"Lista":"Conversaciones",v->{conversationMode=!conversationMode;showMessages();}));
        content.addView(toolbar);
        if(!conversationFilterKey.isEmpty()) content.addView(button("← Ver todas las conversaciones",v->{conversationFilterKey="";conversationMode=true;showMessages();}));
        LinearLayout filtersTop=row();
        addWeighted(filtersTop,button(filterLabel("all","Todos"),v->{messageFilter="all";messagePageLimit=60;showMessages();}));
        addWeighted(filtersTop,button(filterLabel("text","Texto"),v->{messageFilter="text";messagePageLimit=60;showMessages();}));
        addWeighted(filtersTop,button(filterLabel("audio","Audio"),v->{messageFilter="audio";messagePageLimit=60;showMessages();}));
        content.addView(filtersTop);
        LinearLayout filtersBottom=row();
        addWeighted(filtersBottom,button(filterLabel("image","Fotos"),v->{messageFilter="image";messagePageLimit=60;showMessages();}));
        addWeighted(filtersBottom,button(filterLabel("video","Videos"),v->{messageFilter="video";messagePageLimit=60;showMessages();}));
        content.addView(filtersBottom);
        content.addView(text("Mantén pulsado un mensaje o archivo para eliminarlo",10,muted,false));
        messagesDynamicHost=new LinearLayout(this);messagesDynamicHost.setOrientation(LinearLayout.VERTICAL);
        content.addView(messagesDynamicHost,new LinearLayout.LayoutParams(-1,-2));
        renderMessagesDynamic(messagesDynamicHost);
    }

    private List<VaultDb.Msg> filteredDeletedMessages(List<VaultDb.Msg> raw){
        List<VaultDb.Msg> l=new ArrayList<>();
        if(raw==null)return l;
        String q=messageQuery==null?"":messageQuery.trim().toLowerCase(Locale.ROOT);
        for(VaultDb.Msg m:raw){
            if(m==null)continue;String body=m.body==null?"":m.body;
            if(!conversationFilterKey.isEmpty()&&!conversationFilterKey.equals(conversationKey(m)))continue;
            String expected=expectedMediaType(body);
            if(!"all".equals(messageFilter)){if("text".equals(messageFilter)&&!expected.isEmpty())continue;if(!"text".equals(messageFilter)&&!messageFilter.equals(expected))continue;}
            if(!q.isEmpty()){String hay=((m.sender==null?"":m.sender)+" "+(m.conversation==null?"":m.conversation)+" "+body).toLowerCase(Locale.ROOT);if(!hay.contains(q))continue;}
            l.add(m);
        }
        return l;
    }

    private void refreshMessagesInPlace(int scrollY){
        if(!"messages".equals(currentScreen)||messagesDynamicHost==null||messageRefreshRunning)return;
        messageRefreshRunning=true;
        final LinearLayout host=messagesDynamicHost;
        host.post(()->{
            try{
                if(host!=messagesDynamicHost||!"messages".equals(currentScreen))return;
                host.removeAllViews();renderMessagesDynamic(host);
                if(currentScroll!=null)currentScroll.post(()->{try{currentScroll.scrollTo(0,Math.max(0,scrollY));}catch(Throwable t){recordUiError("MESSAGE_SCROLL",t);}});
            }finally{messageRefreshRunning=false;}
        });
    }

    private void renderMessagesDynamic(LinearLayout host){
        if(host==null)return;
        List<VaultDb.Msg> raw;try{raw=db.listConfirmedDeletedMessages(messagePageLimit);}catch(Throwable t){recordUiError("MESSAGE_QUERY",t);raw=new ArrayList<>();}
        List<VaultDb.Msg> l=filteredDeletedMessages(raw);
        LinearLayout previous=content;content=host;
        try{

        if(conversationMode && conversationFilterKey.isEmpty()) {
            showConversationGroups(l);
            if(raw.size()>=messagePageLimit)content.addView(button("Cargar 60 más",v->{messagePageLimit+=60;showMessages();}));
            return;
        }

        if(l.isEmpty()){
            LinearLayout c=card();
            c.addView(text(raw.isEmpty()?"Todavía no hay mensajes borrados confirmados":"No hay resultados con este filtro",18,fg,true));
            c.addView(text(raw.isEmpty()?"WA Vault captura mensajes en segundo plano, pero aquí solo muestra los que tienen una señal de borrado confirmada. Los candidatos dudosos permanecen ocultos.":"Cambia el filtro o limpia la búsqueda.",13,muted,false));
            content.addView(c);
            if(raw.size()>=messagePageLimit)content.addView(button("Cargar 60 más",v->{messagePageLimit+=60;showMessages();}));
            return;
        }

        java.util.HashSet<Long> globallyShownMediaIds=new java.util.HashSet<>();
        for(VaultDb.Msg m:l){
            LinearLayout c=card();
            c.setOnLongClickListener(v->{confirmDeleteMessage(m);return true;});
            String sender=m.sender==null||m.sender.isEmpty()?"WhatsApp":m.sender;
            String conv=m.conversation==null||m.conversation.isEmpty()?sender:m.conversation;
            TextView stateChip=text("MENSAJE BORRADO",11,Color.rgb(255,105,115),true);
            stateChip.setPadding(dp(10),dp(5),dp(10),dp(5));
            stateChip.setBackground(rounded(Color.rgb(62,24,29),30));
            LinearLayout stateRow=row();stateRow.addView(stateChip,new LinearLayout.LayoutParams(-2,-2));c.addView(stateRow);
            c.addView(text(sender,19,fg,true));
            String contextLabel=m.isGroup?"Grupo: "+conv:"Privado";
            c.addView(text(contextLabel+" · "+DateFormat.format("dd/MM/yyyy HH:mm",new Date(m.timestamp)),12,green,false));
            String body=m.body==null?"":m.body;
            boolean deletionPlaceholder=VaultDb.DELETION_PLACEHOLDER_BODY.equals(body);
            String expectedBodyType=expectedMediaType(body);
            boolean genericMediaBody=isGenericMediaPlaceholder(body,expectedBodyType);
            if(deletionPlaceholder){
                c.addView(text("Contenido original no disponible",14,fg,true));
                c.addView(text("WhatsApp confirmó el borrado, pero Android no entregó una copia correlacionable del texto original. El evento sí quedó registrado.",12,muted,false));
            }else if(!body.isEmpty()&&!genericMediaBody)c.addView(text(body,15,fg,false));

            List<VaultDb.Media> related;
            try{related=db.listMediaForMessage(m.id,m.timestamp,4);}catch(Throwable t){related=new ArrayList<>();}
            int shown=0;
            for(VaultDb.Media media:related){
                if(media==null||globallyShownMediaIds.contains(media.id))continue;
                boolean explicit=media.linkedMessageId==m.id;
                if(!explicit&&!mediaMatchesMessage(body,media.type))continue;
                addDeletedMessageMedia(c,media);
                globallyShownMediaIds.add(media.id);shown++;if(shown>=2)break;
            }
            // v0.5.14: rendering is deliberately read-only. The UI must never "guess" an
            // attachment by timestamp because doing so can glue a later photo/audio to a text
            // message (for example, `cc`). Correlation is resolved by the capture engine before
            // anything becomes visible.
            if(shown==0&&!expectedBodyType.isEmpty()){
                if(genericMediaBody)c.addView(text(mediaPlaceholderLabel(expectedBodyType),13,fg,true));
                String waiting = "image".equals(expectedBodyType)
                        ? "La foto ya está registrada. WA Vault intenta conservar la vista previa de la notificación y reemplazarla automáticamente por el archivo completo cuando Android/WhatsApp lo exponga."
                        : ("video".equals(expectedBodyType)
                        ? "El video ya está registrado. WA Vault intenta conservar su vista previa y capturar automáticamente el archivo completo cuando Android/WhatsApp lo exponga."
                        : "El contenido está registrado y la captura sigue activa en segundo plano.");
                c.addView(text(waiting,11,muted,false));
            }
            content.addView(c);
        }
        if(raw.size()>=messagePageLimit)content.addView(button("Cargar 60 más",v->{messagePageLimit+=60;showMessages();}));
        content.addView(button("BORRAR HISTORIAL DE MENSAJES",v->confirmClear(false)));
        }finally{content=previous;}
    }

    private String conversationKey(VaultDb.Msg m){
        if(m==null)return "";
        String sender=m.sender==null?"":m.sender.trim();
        String conv=m.conversation==null?"":m.conversation.trim();
        return (m.isGroup?"g:":"p:")+(m.isGroup?conv:sender).toLowerCase(Locale.ROOT);
    }

    private void showConversationGroups(List<VaultDb.Msg> messages){
        if(messages==null||messages.isEmpty()){
            LinearLayout empty=card();empty.addView(text("No hay conversaciones con este filtro",17,fg,true));content.addView(empty);return;
        }
        LinkedHashMap<String,List<VaultDb.Msg>> groups=new LinkedHashMap<>();
        for(VaultDb.Msg m:messages){String k=conversationKey(m);if(k.isEmpty())continue;groups.computeIfAbsent(k,x->new ArrayList<>()).add(m);}
        for(Map.Entry<String,List<VaultDb.Msg>> e:groups.entrySet()){
            List<VaultDb.Msg> items=e.getValue();if(items.isEmpty())continue;VaultDb.Msg latest=items.get(0);
            String title=latest.isGroup?(latest.conversation==null?"Grupo":latest.conversation):(latest.sender==null?"WhatsApp":latest.sender);
            int texts=0,audios=0,photos=0,videos=0;
            for(VaultDb.Msg m:items){String t=expectedMediaType(m.body);if("audio".equals(t))audios++;else if("image".equals(t))photos++;else if("video".equals(t))videos++;else texts++;}
            LinearLayout c=card();
            c.addView(text(title,19,fg,true));
            c.addView(text((latest.isGroup?"Grupo":"Privado")+" · "+items.size()+" borrado(s) · "+DateFormat.format("dd/MM HH:mm",new Date(latest.timestamp)),12,green,false));
            String parts="Texto "+texts+"   Audio "+audios+"   Fotos "+photos+"   Video "+videos;
            c.addView(text(parts,12,muted,false));
            c.setOnClickListener(v->{conversationFilterKey=e.getKey();conversationMode=false;showMessages();});
            content.addView(c);
        }
    }

    private String filterLabel(String id,String label){return id.equals(messageFilter)?"● "+label:label;}

    private void showMessageSearchDialog(){
        final EditText input=new EditText(this);input.setText(messageQuery);input.setHint("Contacto, grupo o texto");input.setSingleLine(true);input.setPadding(dp(16),dp(10),dp(16),dp(10));
        new AlertDialog.Builder(this).setTitle("Buscar en borrados").setView(input).setNegativeButton("Cancelar",null).setNeutralButton("Limpiar",(d,w)->{messageQuery="";messagePageLimit=60;showMessages();}).setPositiveButton("Buscar",(d,w)->{messageQuery=input.getText()==null?"":input.getText().toString().trim();messagePageLimit=60;showMessages();}).show();
    }

    private boolean mediaMatchesMessage(String body, String type) {
        return type!=null && type.equals(WhatsAppNotificationListener.strictMediaKindForText(body));
    }

    private String expectedMediaType(String body) {
        return WhatsAppNotificationListener.strictMediaKindForText(body);
    }

    private boolean isGenericMediaPlaceholder(String body,String type) {
        if(type==null||type.isEmpty())return false;
        String b=body==null?"":body.trim().toLowerCase(Locale.ROOT);
        b=b.replace("🎤","").replace("🎙","").trim();
        if("audio".equals(type))return b.equals("audio")||b.equals("voz")||b.equals("voice message")||b.equals("mensaje de voz")||b.equals("nota de voz");
        if("image".equals(type))return b.equals("foto")||b.equals("photo")||b.equals("imagen")||b.equals("image")||b.equals("gif");
        if("video".equals(type))return b.equals("video")||b.equals("vídeo");
        if("document".equals(type))return b.equals("documento")||b.equals("document")||b.equals("archivo")||b.equals("archivo adjunto");
        return false;
    }

    private String mediaPlaceholderLabel(String type) {
        if("audio".equals(type))return "🎙 Mensaje de voz";
        if("image".equals(type))return "▣ Foto";
        if("video".equals(type))return "▶ Video";
        if("document".equals(type))return "▤ Documento";
        return "Archivo multimedia";
    }

    private void addDeletedMessageMedia(LinearLayout parent, VaultDb.Media media) {
        File file = media.path == null ? null : new File(media.path);
        if (file == null || !file.exists()) return;
        boolean notificationPreview=isNotificationPreview(media);
        TextView label = text(notificationPreview ? ("video".equals(media.type)?"Vista previa de video":"Vista previa de foto") : mediaChipLabel(media.type),12,green,true);
        parent.addView(label);
        if ("image".equals(media.type)) {
            ImageView iv = makePreviewImage();
            parent.addView(iv,new LinearLayout.LayoutParams(-1,dp(520)));
            loadThumbnailAsync(iv,file,"image");
            iv.setOnClickListener(v->showImageViewer(file, media.name));
            iv.setOnLongClickListener(v->{ confirmDeleteMedia(media, this::showMessages); return true; });
        } else if ("video".equals(media.type)) {
            ImageView iv = makePreviewImage();
            parent.addView(iv,new LinearLayout.LayoutParams(-1,dp(420)));
            loadThumbnailAsync(iv,file,notificationPreview?"image":"video");
            Button playVideo = button(notificationPreview?"▣ ABRIR VISTA PREVIA":"▶ VER VIDEO",v->{if(notificationPreview)showImageViewer(file,media.name);else showVideoViewer(file,media.name);});
            parent.addView(playVideo);
            playVideo.setOnLongClickListener(v->{ confirmDeleteMedia(media, this::showMessages); return true; });
        } else if ("audio".equals(media.type)) {
            LinearLayout audioBox = new LinearLayout(this);
            audioBox.setOrientation(LinearLayout.VERTICAL);
            audioBox.setPadding(dp(10),dp(8),dp(10),dp(8));
            audioBox.setBackground(rounded(cardSoft,14));
            parent.addView(audioBox,new LinearLayout.LayoutParams(-1,-2));
            Button play = addAudioControls(audioBox,file);
            View.OnLongClickListener deleteAudio = v -> { confirmDeleteMedia(media, this::showMessages); return true; };
            audioBox.setOnLongClickListener(deleteAudio);
            if (play != null) play.setOnLongClickListener(deleteAudio);
        }
    }

    private boolean isNotificationPreview(VaultDb.Media media){
        return media!=null && media.origin!=null && media.origin.contains("PREVIEW");
    }

    private String safeMediaType(String type) {
        if ("image".equals(type)) return "FOTO";
        if ("video".equals(type)) return "VIDEO";
        if ("audio".equals(type)) return "AUDIO";
        if ("document".equals(type)) return "DOCUMENTO";
        return "ARCHIVO";
    }

    private String friendlyOrigin(String origin){
        if(origin==null)return "";
        if(origin.contains("NOTIFICATION"))return "notificación";
        if(origin.contains("FILE_OBSERVER"))return "descarga manual";
        if(origin.contains("audio"))return "captura de audio";
        return origin.toLowerCase(Locale.ROOT);
    }

    private String mediaChipLabel(String type) {
        if ("audio".equals(type)) return "Audio recuperado";
        if ("image".equals(type)) return "Foto recuperada";
        if ("video".equals(type)) return "Video recuperado";
        if ("document".equals(type)) return "Documento recuperado";
        return "Archivo recuperado";
    }

    private void showMedia(){
        rememberScreen("recovered");
        releaseAudioPlayer(true);
        ++mediaLoadGeneration;
        mediaNextOffset=0;mediaHasMore=true;mediaLoadingMore=false;recoveredRefreshAnchorId=0L;recoveredRefreshFallbackId=0L;recoveredRefreshAnchorIndex=0;recoveredRefreshAnchorOffset=0;recoveredRefreshTargetCount=0;recoveredSelection.clear();recoveredItems.clear();recoveredContext.clear();

        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);applySafeInsets(root,16,8,16,6);
        LinearLayout head=row();LinearLayout titleBox=new LinearLayout(this);titleBox.setOrientation(LinearLayout.VERTICAL);titleBox.addView(text("Recuperados",26,fg,true));titleBox.addView(text("Solo archivos conservados por borrados confirmados",11,muted,false));head.addView(titleBox,new LinearLayout.LayoutParams(0,-2,1f));
        TextView count=text("…",12,green,true);recoveredCountText=count;count.setGravity(Gravity.CENTER);count.setBackground(rounded(Color.rgb(16,50,35),30));count.setPadding(dp(10),dp(6),dp(10),dp(6));head.addView(count);Button menu=button("⋮",v->showMediaMenu());LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(54),dp(48));mp.setMargins(dp(8),0,0,0);head.addView(menu,mp);root.addView(head);


        VaultDb.Stats st;try{st=db.getStats();}catch(Throwable t){st=new VaultDb.Stats();}
        LinearLayout summary=card();summary.addView(text(st.savedFiles+" archivos · "+human(st.totalBytes),18,fg,true));summary.addView(text("Fotos "+human(st.imageBytes)+"   ·   Videos "+human(st.videoBytes)+"   ·   Audio "+human(st.audioBytes),11,muted,false));summary.addView(text("Papelera "+human(st.trashBytes)+"   ·   Temporal "+human(st.pendingBytes),10,muted,false));
        LinearLayout sumActions=row();addWeighted(sumActions,button("Filtrar",v->showMediaMenu()));addWeighted(sumActions,button(st.trashedMedia>0?"Papelera · "+st.trashedMedia:"Papelera",v->showTrashDialog()));summary.addView(sumActions);root.addView(summary);

        recoveredSelectionText=text("Mantén pulsado para seleccionar",11,muted,false);recoveredSelectionText.setPadding(0,dp(3),0,dp(3));root.addView(recoveredSelectionText);
        recoveredSelectBulk=row();addWeighted(recoveredSelectBulk,button("Seleccionar todo",v->selectAllRecovered()));addWeighted(recoveredSelectBulk,button("Cancelar",v->{recoveredSelection.clear();updateRecoveredSelectionUi();}));recoveredSelectBulk.setVisibility(View.GONE);root.addView(recoveredSelectBulk);
        recoveredBulkActions=row();addWeighted(recoveredBulkActions,button("Favoritos",v->bulkFavoriteSelected()));addWeighted(recoveredBulkActions,button("Papelera",v->bulkTrashSelected()));recoveredBulkActions.setVisibility(View.GONE);root.addView(recoveredBulkActions);

        RecyclerView rv=new RecyclerView(this);recoveryRecycler=rv;rv.setBackgroundColor(bg);rv.setClipToPadding(false);rv.setPadding(0,dp(8),0,dp(16));GridLayoutManager glm=new GridLayoutManager(this,2);rv.setLayoutManager(glm);
        recoveryCenterAdapter=new RecoveryCenterAdapter(this,new RecoveryCenterAdapter.Callback(){
            @Override public void onOpen(VaultDb.Media m){openRecoveryMedia(m);}
            @Override public void onFavorite(VaultDb.Media m){boolean next=!m.favorite;if(db.setFavorite(m.id,next)){m.favorite=next;if(recoveryCenterAdapter!=null)recoveryCenterAdapter.notifyMediaChanged(m.id);}}
            @Override public void onShare(VaultDb.Media m){File f=mediaFile(m);if(f!=null)shareRecoveredFile(f,displayMediaName(m),guessShareMime(displayMediaName(m),m.mime));}
            @Override public void onSave(VaultDb.Media m){File f=mediaFile(m);if(f!=null)downloadRecoveredFile(f,displayMediaName(m),guessShareMime(displayMediaName(m),m.mime));}
            @Override public void onTrash(VaultDb.Media m){confirmDeleteMedia(m,MainActivity.this::refreshRecoveredInPlace);}
            @Override public void onToggleSelection(VaultDb.Media m){if(!recoveredSelection.add(m.id))recoveredSelection.remove(m.id);updateRecoveredSelectionUi();}
            @Override public void onDetails(VaultDb.Media m){showRecoveryTimeline(m);}
            @Override public boolean isSelected(long id){return recoveredSelection.contains(id);}
            @Override public boolean selectionActive(){return !recoveredSelection.isEmpty();}
            @Override public String contextFor(long id){String x=recoveredContext.get(id);return x==null?"":x;}
            @Override public String confidenceFor(VaultDb.Media m){return associationConfidence(m);}
        });
        rv.setAdapter(recoveryCenterAdapter);rv.addOnScrollListener(new RecyclerView.OnScrollListener(){@Override public void onScrolled(RecyclerView r,int dx,int dy){super.onScrolled(r,dx,dy);int last=glm.findLastVisibleItemPosition();if(last>=0&&last>=recoveryCenterAdapter.getItemCount()-8)loadMoreRecoveredInPlace();}});
        root.addView(rv,new LinearLayout.LayoutParams(-1,0,1f));root.addView(bottomNav());setContentView(root);loadRecoveredPage(true);
    }

    private File mediaFile(VaultDb.Media m){if(m==null||m.path==null||m.path.isEmpty())return null;File f=new File(m.path);if(!f.exists()){Toast.makeText(this,"La copia ya no está disponible",Toast.LENGTH_SHORT).show();return null;}return f;}
    private String displayMediaName(VaultDb.Media m){if(m==null)return "Archivo";return m.name==null||m.name.isEmpty()?friendlyMediaName(m.type):m.name;}
    private void openRecoveryMedia(VaultDb.Media m){File f=mediaFile(m);if(f==null)return;String type=m.type==null?"":m.type,name=displayMediaName(m);if("audio".equals(type))showAudioViewer(f,name);else if("image".equals(type)||isNotificationPreview(m))showImageViewer(f,name);else if("video".equals(type))showVideoViewer(f,name);else openRecoveredDocument(f,name,guessShareMime(name,m.mime));}
    private String associationConfidence(VaultDb.Media m){
        if(m==null)return "";String o=m.origin==null?"":m.origin;
        if(o.contains("VIDEO_LIMIT_PLACEHOLDER"))return "No archivado · supera el límite actual de "+MediaLimits.limitLabel(this);
        if(o.contains("VIDEO_PENDING_PLACEHOLDER"))return "Video detectado · esperando bytes (límite actual "+MediaLimits.limitLabel(this)+")";
        String t="image".equals(m.type)?"Foto":("video".equals(m.type)?"Video":("audio".equals(m.type)?"Audio":("document".equals(m.type)?"Documento":"Archivo")));
        return m.linkedMessageId>0?("Borrado confirmado · "+t):"Pendiente interno · no debería ser visible";
    }

    private void loadRecoveredPage(boolean reset){
        if(!"recovered".equals(currentScreen))return;if(mediaLoadingMore&&!reset)return;if(reset){mediaNextOffset=0;mediaHasMore=true;recoveredItems.clear();recoveredContext.clear();}
        final int generation=mediaLoadGeneration,offset=mediaNextOffset;final long cutoff=mediaDays<=0?0L:System.currentTimeMillis()-mediaDays*86400000L;final int requestedLimit=reset&&recoveredRefreshTargetCount>MEDIA_PAGE_SIZE?recoveredRefreshTargetCount:MEDIA_PAGE_SIZE;mediaLoadingMore=true;if(recoveredCountText!=null&&reset)recoveredCountText.setText("Cargando…");
        new Thread(()->{
            List<VaultDb.Media> raw;try{raw=db.listRecoveryCenterPage(mediaFilter,mediaPendingOnly,mediaSort,mediaFavoritesOnly,mediaUnlinkedOnly,cutoff,requestedLimit,offset);}catch(Throwable t){recordUiError("RECOVERY_PAGE",t);raw=new ArrayList<>();}
            Map<Long,String> contexts;try{contexts=db.listMessageContextsForMedia(raw);}catch(Throwable t){recordUiError("RECOVERY_CONTEXT",t);contexts=new LinkedHashMap<>();}
            final List<VaultDb.Media> page=applyRecoveredFilters(raw,contexts);final Map<Long,String> pageContexts=contexts;final int rawCount=raw.size();
            postUiIfAlive(()->{if(!"recovered".equals(currentScreen)||generation!=mediaLoadGeneration)return;if(reset){recoveredItems.clear();recoveredContext.clear();}for(VaultDb.Media m:page){boolean exists=false;for(VaultDb.Media x:recoveredItems)if(x.id==m.id){exists=true;break;}if(!exists)recoveredItems.add(m);}recoveredContext.putAll(pageContexts);mediaNextOffset=offset+rawCount;mediaHasMore=rawCount>=requestedLimit;mediaLoadingMore=false;submitRecoveredList();restoreRecoveredAnchorIfNeeded();if(recoveredCountText!=null)recoveredCountText.setText(recoveredItems.size()+(mediaHasMore?"+":"")+" archivos");if(recoveredItems.isEmpty()&&mediaHasMore)loadMoreRecoveredInPlace();});
        },"wa-vault-recovery-page").start();
    }

    private void submitRecoveredList(){if(recoveryCenterAdapter!=null)recoveryCenterAdapter.submitList(new ArrayList<>(recoveredItems));updateRecoveredSelectionUi();}
    private void refreshRecoveredInPlace(){
        if(!"recovered".equals(currentScreen))return;
        recoveredRefreshAnchorId=0L;recoveredRefreshFallbackId=0L;recoveredRefreshAnchorIndex=0;recoveredRefreshAnchorOffset=0;recoveredRefreshTargetCount=Math.max(MEDIA_PAGE_SIZE,recoveredItems.size());
        try{
            if(recoveryRecycler!=null&&recoveryRecycler.getLayoutManager() instanceof GridLayoutManager){
                GridLayoutManager lm=(GridLayoutManager)recoveryRecycler.getLayoutManager();int pos=lm.findFirstVisibleItemPosition();
                if(pos>=0&&pos<recoveredItems.size()){
                    recoveredRefreshAnchorIndex=pos;recoveredRefreshAnchorId=recoveredItems.get(pos).id;
                    if(pos+1<recoveredItems.size())recoveredRefreshFallbackId=recoveredItems.get(pos+1).id;
                    else if(pos>0)recoveredRefreshFallbackId=recoveredItems.get(pos-1).id;
                    View v=lm.findViewByPosition(pos);if(v!=null)recoveredRefreshAnchorOffset=v.getTop()-recoveryRecycler.getPaddingTop();
                }
            }
        }catch(Throwable t){recordUiError("RECOVERY_ANCHOR",t);}
        ++mediaLoadGeneration;mediaLoadingMore=false;loadRecoveredPage(true);
    }
    private void restoreRecoveredAnchorIfNeeded(){
        if(recoveredRefreshTargetCount<=0)return;long anchor=recoveredRefreshAnchorId,fallback=recoveredRefreshFallbackId;int oldIndex=recoveredRefreshAnchorIndex,offset=recoveredRefreshAnchorOffset;
        recoveredRefreshTargetCount=0;recoveredRefreshAnchorId=0L;recoveredRefreshFallbackId=0L;recoveredRefreshAnchorIndex=0;recoveredRefreshAnchorOffset=0;
        if(recoveryRecycler==null||!(recoveryRecycler.getLayoutManager() instanceof GridLayoutManager)||recoveredItems.isEmpty())return;
        int pos=-1;for(int i=0;i<recoveredItems.size();i++)if(recoveredItems.get(i).id==anchor){pos=i;break;}
        if(pos<0&&fallback>0)for(int i=0;i<recoveredItems.size();i++)if(recoveredItems.get(i).id==fallback){pos=i;break;}
        if(pos<0)pos=Math.max(0,Math.min(oldIndex,recoveredItems.size()-1));
        final int target=pos,off=offset;recoveryRecycler.post(()->{try{((GridLayoutManager)recoveryRecycler.getLayoutManager()).scrollToPositionWithOffset(target,off);}catch(Throwable t){recordUiError("RECOVERY_SCROLL_RESTORE",t);}});
    }
    private void loadMoreRecoveredInPlace(){if(!mediaHasMore||mediaLoadingMore||!"recovered".equals(currentScreen))return;loadRecoveredPage(false);}
    private void updateRecoveredSelectionUi(){boolean active=!recoveredSelection.isEmpty();if(recoveredSelectionText!=null)recoveredSelectionText.setText(active?recoveredSelection.size()+" seleccionados":"Mantén pulsado para seleccionar");if(recoveredSelectBulk!=null)recoveredSelectBulk.setVisibility(active?View.VISIBLE:View.GONE);if(recoveredBulkActions!=null)recoveredBulkActions.setVisibility(active?View.VISIBLE:View.GONE);if(recoveryCenterAdapter!=null)recoveryCenterAdapter.notifyDataSetChanged();}
    private void selectAllRecovered(){
        final int generation=mediaLoadGeneration;final long cutoff=mediaDays<=0?0L:System.currentTimeMillis()-mediaDays*86400000L;
        Toast.makeText(this,"Seleccionando todos los elementos del filtro…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            ArrayList<VaultDb.Media> all=new ArrayList<>();int offset=0;final int pageSize=500;
            while(true){List<VaultDb.Media> page;try{page=db.listRecoveryCenterPage(mediaFilter,mediaPendingOnly,mediaSort,mediaFavoritesOnly,mediaUnlinkedOnly,cutoff,pageSize,offset);}catch(Throwable t){recordUiError("SELECT_ALL_PAGE",t);break;}if(page.isEmpty())break;all.addAll(page);offset+=page.size();if(page.size()<pageSize)break;}
            LinkedHashMap<Long,String> ctx=new LinkedHashMap<>();for(int i=0;i<all.size();i+=300)try{ctx.putAll(db.listMessageContextsForMedia(all.subList(i,Math.min(all.size(),i+300))));}catch(Throwable ignored){}
            final List<VaultDb.Media> filtered=applyRecoveredFilters(all,ctx);final ArrayList<Long> ids=new ArrayList<>();for(VaultDb.Media m:filtered)if(m!=null)ids.add(m.id);
            postUiIfAlive(()->{if(!"recovered".equals(currentScreen)||generation!=mediaLoadGeneration)return;recoveredSelection.clear();recoveredSelection.addAll(ids);updateRecoveredSelectionUi();Toast.makeText(this,ids.size()+" seleccionados",Toast.LENGTH_SHORT).show();});
        },"wa-vault-select-all").start();
    }
    private void bulkFavoriteSelected(){if(recoveredSelection.isEmpty()){Toast.makeText(this,"No hay archivos seleccionados",Toast.LENGTH_SHORT).show();return;}new Thread(()->{int n=0;for(Long id:new ArrayList<>(recoveredSelection))if(id!=null&&db.setFavorite(id,true))n++;final int done=n;postUiIfAlive(()->{Toast.makeText(this,done+" marcados como favoritos",Toast.LENGTH_SHORT).show();recoveredSelection.clear();++mediaLoadGeneration;loadRecoveredPage(true);});},"wa-vault-bulk-fav").start();}
    private void bulkTrashSelected(){if(recoveredSelection.isEmpty()){Toast.makeText(this,"No hay archivos seleccionados",Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle("Mover a papelera").setMessage("Los "+recoveredSelection.size()+" archivos seleccionados se conservarán 7 días antes de su eliminación automática.").setNegativeButton("Cancelar",null).setPositiveButton("Mover",(d,w)->new Thread(()->{int n=db.moveMediaToTrash(new ArrayList<>(recoveredSelection));postUiIfAlive(()->{Toast.makeText(this,n+" movidos a papelera",Toast.LENGTH_SHORT).show();recoveredSelection.clear();++mediaLoadGeneration;loadRecoveredPage(true);});},"wa-vault-bulk-trash").start()).show();}

    private void showTrashDialog(){showTrashDialog(0);}
    private void showTrashDialog(int offset){
        final int pageSize=60,total=db.countTrash();if(total<=0){new AlertDialog.Builder(this).setTitle("Papelera").setMessage("La papelera está vacía. Los archivos movidos aquí se eliminan automáticamente después de 7 días.").setPositiveButton("OK",null).show();return;}
        int safeOffset=Math.max(0,Math.min(offset,Math.max(0,((total-1)/pageSize)*pageSize)));List<VaultDb.Media> items;try{items=db.listTrashPage(pageSize,safeOffset);}catch(Throwable t){recordUiError("TRASH_QUERY",t);items=new ArrayList<>();}
        final List<VaultDb.Media> list=items;ArrayList<String> labels=new ArrayList<>();boolean prev=safeOffset>0,next=safeOffset+list.size()<total;if(prev)labels.add("← Anteriores");for(VaultDb.Media m:list)labels.add(displayMediaName(m)+" · "+human(m.size)+" · "+DateFormat.format("dd/MM HH:mm",new Date(m.trashedAt)));if(next)labels.add("Siguientes →");final boolean hasPrev=prev,hasNext=next;final int off=safeOffset;
        new AlertDialog.Builder(this).setTitle("Papelera · "+total+" · "+(safeOffset+1)+"–"+Math.min(total,safeOffset+list.size())).setItems(labels.toArray(new String[0]),(d,w)->{if(hasPrev&&w==0){showTrashDialog(Math.max(0,off-pageSize));return;}int idx=w-(hasPrev?1:0);if(idx>=0&&idx<list.size()){showTrashItemActions(list.get(idx));return;}if(hasNext)showTrashDialog(off+pageSize);}).setNeutralButton("Vaciar",(d,w)->confirmEmptyTrash()).setPositiveButton("Restaurar todo",(d,w)->new Thread(()->{int n=db.restoreAllTrash();final int done=n;postUiIfAlive(()->{Toast.makeText(this,done+" restaurados",Toast.LENGTH_SHORT).show();if("recovered".equals(currentScreen))showMedia();else if("home".equals(currentScreen))showHome();});},"wa-vault-trash-restore").start()).setNegativeButton("Cerrar",null).show();
    }
    private void showTrashItemActions(VaultDb.Media m){new AlertDialog.Builder(this).setTitle(displayMediaName(m)).setItems(new String[]{"Restaurar","Eliminar solo de WA Vault","Eliminar de WA Vault y Galería"},(d,w)->{if(w==0){db.restoreMedia(m.id);Toast.makeText(this,"Archivo restaurado",Toast.LENGTH_SHORT).show();return;}boolean gallery=w==2;new AlertDialog.Builder(this).setTitle("Eliminar definitivamente").setMessage(gallery?"Se borrará la copia privada y, si WA Vault la exportó, también su copia de Galería. Esta acción no se puede deshacer.":"Se borrará la copia privada de WA Vault. La copia de Galería, si existe, se conservará.").setNegativeButton("Cancelar",null).setPositiveButton("Eliminar",(a,b)->new Thread(()->{boolean ok=db.deleteMediaPermanently(m.id,gallery);postUiIfAlive(()->{Toast.makeText(this,ok?"Eliminado definitivamente":"No se pudo eliminar",Toast.LENGTH_SHORT).show();if("recovered".equals(currentScreen))showMedia();else if("home".equals(currentScreen))showHome();});},"wa-vault-delete-one").start()).show();}).show();}
    private void confirmEmptyTrash(){new AlertDialog.Builder(this).setTitle("Vaciar papelera").setItems(new String[]{"Vaciar solo WA Vault","Vaciar WA Vault y sus copias de Galería"},(d,w)->{boolean gallery=w==1;new AlertDialog.Builder(this).setTitle("Confirmar vaciado").setMessage(gallery?"Se eliminarán definitivamente las copias privadas y también las exportadas por WA Vault a Galería.":"Se eliminarán definitivamente las copias privadas. La Galería no se tocará.").setNegativeButton("Cancelar",null).setPositiveButton("Vaciar",(a,b)->new Thread(()->{int n=db.emptyTrash(gallery);postUiIfAlive(()->{Toast.makeText(this,n+" archivos eliminados",Toast.LENGTH_SHORT).show();if("recovered".equals(currentScreen))showMedia();else if("home".equals(currentScreen))showHome();});},"wa-vault-empty-trash").start()).show();}).setNegativeButton("Cancelar",null).show();}
    private void showRecoveryTimeline(VaultDb.Media m){
        new Thread(()->{List<VaultDb.RecoveryJob> jobs;List<VaultDb.Event> events;try{jobs=db.listRecoveryJobsForMedia(m.id,24);}catch(Throwable t){jobs=new ArrayList<>();}try{events=db.listEventsForMedia(m.id,24);}catch(Throwable t){events=new ArrayList<>();}StringBuilder out=new StringBuilder();out.append(displayMediaName(m)).append("\n").append(associationConfidence(m)).append("\n\n");if(jobs.isEmpty()&&events.isEmpty())out.append("No hay pasos técnicos antiguos registrados para esta copia.\n");for(VaultDb.RecoveryJob j:jobs){out.append(DateFormat.format("dd/MM HH:mm:ss",new Date(j.updatedAt))).append(" · ").append(j.state==null?"":j.state);if(j.reason!=null&&!j.reason.isEmpty())out.append(" · ").append(j.reason);if(j.origin!=null&&!j.origin.isEmpty())out.append(" · ").append(j.origin);out.append("\n");}for(VaultDb.Event e:events){out.append(DateFormat.format("dd/MM HH:mm:ss",new Date(e.timestamp))).append(" · ").append(e.code==null?"EVENT":e.code);if(e.detail!=null&&!e.detail.isEmpty())out.append(" · ").append(e.detail);out.append("\n");}final String text=out.toString();postUiIfAlive(()->new AlertDialog.Builder(this).setTitle("Historial de recuperación").setMessage(text).setPositiveButton("Cerrar",null).show());},"wa-vault-timeline").start();
    }

    private void showMediaMenu(){
        String[] options={"Tipo: "+recoveredFilterName(),"Buscar"+(mediaQuery.isEmpty()?"":" · "+mediaQuery),"Fecha: "+mediaDateLabel(),mediaFavoritesOnly?"Mostrar todos":"Solo favoritos","Ordenar: "+mediaSortShortLabel(),"Papelera","Almacenamiento"};
        new AlertDialog.Builder(this).setTitle("Recuperados").setItems(options,(d,which)->{
            if(which==0)showRecoveredFilterDialog();
            else if(which==1)showRecoveredSearchDialog();
            else if(which==2)showRecoveredDateDialog();
            else if(which==3){mediaFavoritesOnly=!mediaFavoritesOnly;showMedia();}
            else if(which==4)showMediaSortDialog();
            else if(which==5)showTrashDialog();
            else if(which==6)showStorageManager();
        }).show();
    }

    private String recoveredFilterName(){
        if("image".equals(mediaFilter))return "Fotos";
        if("video".equals(mediaFilter))return "Videos";
        if("audio".equals(mediaFilter))return "Audio";
        if("document".equals(mediaFilter))return "Documentos";
        return "Todos";
    }

    private void showRecoveredFilterDialog(){
        final String[] labels={"Todos","Fotos","Videos","Audio","Documentos"};final String[] ids={"all","image","video","audio","document"};
        new AlertDialog.Builder(this).setTitle("Mostrar").setItems(labels,(d,w)->{mediaFilter=ids[w];mediaPendingOnly=false;mediaUnlinkedOnly=false;showMedia();}).show();
    }

    private String mediaDateLabel(){if(mediaDays<=0)return "Cualquier fecha";if(mediaDays==1)return "Últimas 24 h";return "Últimos "+mediaDays+" días";}
    private void showRecoveredSearchDialog(){EditText input=new EditText(this);input.setText(mediaQuery);input.setHint("Nombre, contacto, grupo…");new AlertDialog.Builder(this).setTitle("Buscar en recuperados").setView(input).setNegativeButton("Cancelar",null).setNeutralButton("Limpiar",(d,w)->{mediaQuery="";showMedia();}).setPositiveButton("Buscar",(d,w)->{mediaQuery=input.getText()==null?"":input.getText().toString().trim();showMedia();}).show();}
    private void showRecoveredDateDialog(){String[] labels={"Cualquier fecha","Últimas 24 horas","Últimos 7 días","Últimos 30 días","Últimos 90 días"};int[] values={0,1,7,30,90};new AlertDialog.Builder(this).setTitle("Fecha").setItems(labels,(d,w)->{mediaDays=values[w];showMedia();}).show();}
    private List<VaultDb.Media> applyRecoveredFilters(List<VaultDb.Media> source,Map<Long,String> contexts){ArrayList<VaultDb.Media> out=new ArrayList<>();long cutoff=mediaDays<=0?0L:System.currentTimeMillis()-mediaDays*86400000L;String q=mediaQuery==null?"":mediaQuery.trim().toLowerCase(Locale.ROOT);for(VaultDb.Media m:source){if(m==null)continue;if(mediaFavoritesOnly&&!m.favorite)continue;if(cutoff>0&&m.capturedAt<cutoff)continue;if(!q.isEmpty()){String ctx=contexts==null?"":contexts.get(m.id);String hay=((m.name==null?"":m.name)+" "+(m.type==null?"":m.type)+" "+(m.origin==null?"":m.origin)+" "+(ctx==null?"":ctx)).toLowerCase(Locale.ROOT);if(!hay.contains(q))continue;}out.add(m);}return out;}

    private String mediaSortLabel(){
        if("recent".equals(mediaSort))return "más recientes primero";
        if("old".equals(mediaSort))return "más antiguos primero";
        if("name".equals(mediaSort))return "orden alfabético";
        if("size_asc".equals(mediaSort))return "menor a mayor peso";
        return "mayor a menor peso";
    }
    private String mediaSortShortLabel(){
        if("recent".equals(mediaSort))return "Reciente";if("old".equals(mediaSort))return "Antiguo";if("name".equals(mediaSort))return "Nombre";if("size_asc".equals(mediaSort))return "Liviano";return "Pesado";
    }
    private void showMediaSortDialog(){
        final String[] labels={"Más recientes","Más antiguos","Mayor peso","Menor peso","Nombre"};
        final String[] ids={"recent","old","size_desc","size_asc","name"};
        new AlertDialog.Builder(this).setTitle("Ordenar").setItems(labels,(d,w)->{mediaSort=ids[w];showMedia();}).show();
    }

    private String mediaFilterLabel(String id,String label){return id.equals(mediaFilter)?"● "+label:label;}

    private final class MediaRowHolder {
        LinearLayout cardView;
        ImageView preview;
        TextView previewGlyph,title,meta;
        Button favorite,share,save,delete;
    }

    private class MediaAdapter extends BaseAdapter {
        private final List<VaultDb.Media> items;
        MediaAdapter(List<VaultDb.Media> items){ this.items=items; }
        @Override public int getCount(){ return items.size(); }
        @Override public Object getItem(int position){ return items.get(position); }
        @Override public long getItemId(int position){ return items.get(position).id; }

        @Override public View getView(int position, View convertView, ViewGroup parent){
            final MediaRowHolder h;
            if(convertView==null){
                LinearLayout c=card();
                c.setPadding(dp(8),dp(8),dp(8),dp(8));
                c.setLayoutParams(new android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(258)));
                FrameLayout frame=new FrameLayout(MainActivity.this);frame.setBackground(rounded(cardSoft,15));
                TextView glyph=text("",30,green,true);glyph.setGravity(Gravity.CENTER);frame.addView(glyph,new FrameLayout.LayoutParams(-1,-1));
                ImageView iv=new ImageView(MainActivity.this);iv.setScaleType(ImageView.ScaleType.FIT_CENTER);iv.setAdjustViewBounds(false);iv.setClipToOutline(true);frame.addView(iv,new FrameLayout.LayoutParams(-1,-1));
                c.addView(frame,new LinearLayout.LayoutParams(-1,dp(145)));
                TextView title=text("",13,fg,true);title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.END);title.setPadding(0,dp(6),0,0);c.addView(title);
                TextView meta=text("",10,muted,false);meta.setMaxLines(1);meta.setEllipsize(TextUtils.TruncateAt.END);c.addView(meta);
                LinearLayout actions=row();actions.setPadding(0,dp(4),0,0);
                Button fav=button("☆",null);fav.setContentDescription("Favorito");
                Button share=button("↗",null);share.setContentDescription("Compartir");
                Button save=button("↓",null);save.setContentDescription("Descargar");
                Button del=button("🗑",null);del.setContentDescription("Eliminar");
                addWeighted(actions,fav);addWeighted(actions,share);addWeighted(actions,save);addWeighted(actions,del);c.addView(actions);
                h=new MediaRowHolder();h.cardView=c;h.preview=iv;h.previewGlyph=glyph;h.title=title;h.meta=meta;h.favorite=fav;h.share=share;h.save=save;h.delete=del;
                c.setTag(h);convertView=c;
            }else h=(MediaRowHolder)convertView.getTag();

            VaultDb.Media m=items.get(position);
            String type=m.type==null?"archivo":m.type;
            String name=m.name==null||m.name.isEmpty()?friendlyMediaName(type):m.name;
            File file=m.path==null?null:new File(m.path);
            boolean preview=isNotificationPreview(m);
            h.title.setText((m.favorite?"★ ":"")+name);
            h.favorite.setText(m.favorite?"★":"☆");
            String ctx=recoveredContext.get(m.id);String meta=(preview?"PREVIEW · ":"")+safeMediaType(type)+" · "+human(m.size)+" · "+DateFormat.format("dd/MM HH:mm",new Date(m.capturedAt));if(ctx!=null&&!ctx.isEmpty())meta+=" · "+ctx;h.meta.setText(meta);
            h.preview.setImageDrawable(null);h.preview.setTag(null);h.previewGlyph.setText("audio".equals(type)?"♪":("video".equals(type)?"▶":("document".equals(type)?"DOC":"▣")));
            if(file!=null&&file.exists()&&("image".equals(type)||"video".equals(type)))MediaThumbnailLoader.load(MainActivity.this,h.preview,file,preview?"image":type,dp(320));
            boolean available=file!=null&&file.exists();h.cardView.setAlpha(available?1f:0.5f);
            View.OnClickListener open=v->{if(!available){Toast.makeText(MainActivity.this,"La copia ya no está disponible",Toast.LENGTH_SHORT).show();return;}if("audio".equals(type))showAudioViewer(file,name);else if("image".equals(type)||preview)showImageViewer(file,name);else if("video".equals(type))showVideoViewer(file,name);else openRecoveredDocument(file,name,guessShareMime(name,m.mime));};
            h.cardView.setOnClickListener(open);
            h.favorite.setOnClickListener(v->{boolean next=!m.favorite;if(db.setFavorite(m.id,next)){m.favorite=next;notifyDataSetChanged();}});
            h.share.setOnClickListener(v->{if(available)shareRecoveredFile(file,name,guessShareMime(name,m.mime));});
            h.save.setOnClickListener(v->{if(!available)return;downloadRecoveredFile(file,name,guessShareMime(name,preview?"image/jpeg":m.mime));});
            h.delete.setOnClickListener(v->confirmDeleteMedia(m,()->{int idx=items.indexOf(m);if(idx>=0)items.remove(idx);notifyDataSetChanged();if(recoveredCountText!=null)recoveredCountText.setText(items.size()+" archivos");}));
            return convertView;
        }
    }

    private String friendlyMediaName(String type){
        if("image".equals(type))return "Foto de WhatsApp";
        if("video".equals(type))return "Video de WhatsApp";
        if("audio".equals(type))return "Audio de WhatsApp";
        if("document".equals(type))return "Documento de WhatsApp";
        return "Archivo de WhatsApp";
    }

    private ImageView makePreviewImage(){
        ImageView iv=new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setMinimumHeight(dp(240));
        iv.setMaxHeight(dp(640));
        iv.setBackground(rounded(cardSoft,16));
        iv.setClipToOutline(true);
        iv.setImageDrawable(null);
        return iv;
    }

    private void loadThumbnailAsync(ImageView iv, File file, String type){
        final String tag=file.getAbsolutePath()+"|"+System.nanoTime();
        iv.setTag(tag);
        new Thread(()->{
            Bitmap b=null;
            File readable=null;
            try {
                readable=MediaCrypto.materialize(getApplicationContext(),file,file.getName());
                if(readable!=null){
                    if("video".equals(type)) b=decodeVideoThumb(readable,640,360);
                    else b=decodeThumb(readable,640,420);
                }
            } catch(Throwable ignored){}
            finally { if(readable!=null && !readable.equals(file)) try{readable.delete();}catch(Throwable ignored){} }
            final Bitmap result=b;
            postUiIfAlive(()->{
                if(!tag.equals(iv.getTag())) { if(result!=null) result.recycle(); return; }
                if(result!=null) {
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iv.setAdjustViewBounds(true);
                    iv.setImageBitmap(result);
                    try {
                        ViewGroup.LayoutParams lp=iv.getLayoutParams();
                        if(lp!=null && lp.height>dp(200)){
                            int usable=Math.max(dp(280),getResources().getDisplayMetrics().widthPixels-dp(56));
                            int wanted=(int)Math.round(usable*(result.getHeight()/(double)Math.max(1,result.getWidth())));
                            lp.height=Math.max(dp(260),Math.min(dp(640),wanted));
                            iv.setLayoutParams(lp);
                        }
                    } catch(Throwable ignored){}
                }
                else {
                    iv.setScaleType(ImageView.ScaleType.CENTER);
                    iv.setImageDrawable(null);
                    TextView fallback = null;
                }
            });
        }).start();
    }

    private Bitmap decodeVideoThumb(File file,int reqW,int reqH){
        MediaMetadataRetriever r=new MediaMetadataRetriever();
        try{
            r.setDataSource(file.getAbsolutePath());
            if(Build.VERSION.SDK_INT>=27){
                Bitmap b=r.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,reqW,reqH);
                if(b!=null)return b;
            }
            Bitmap b=r.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if(b==null)return null;
            return scaleBitmap(b,reqW,reqH);
        }catch(Throwable t){return null;}
        finally{try{r.release();}catch(Throwable ignored){}}
    }

    private Bitmap scaleBitmap(Bitmap b,int reqW,int reqH){
        if(b==null)return null;
        int w=b.getWidth(),h=b.getHeight();
        if(w<=reqW && h<=reqH)return b;
        float scale=Math.min(reqW/(float)Math.max(1,w),reqH/(float)Math.max(1,h));
        Bitmap out=Bitmap.createScaledBitmap(b,Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)),true);
        if(out!=b)b.recycle();
        return out;
    }

    private void showImageViewer(File file,String name){
        if(file==null||!file.exists())return;
        final File readable=MediaCrypto.materialize(this,file,name);
        if(readable==null){Toast.makeText(this,"No se pudo descifrar esta foto",Toast.LENGTH_LONG).show();return;}
        Dialog d=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        applySafeInsets(root,12,8,12,8);
        LinearLayout actions=row();
        Button close=button("✕ Cerrar",v->d.dismiss());
        Button save=button("↓ Galería",v->beginExport(file,name,guessShareMime(name,"image/jpeg")));
        Button share=button("↗ Compartir",v->shareRecoveredFile(file,name,guessShareMime(name,"image/jpeg")));
        addWeighted(actions,close);addWeighted(actions,save);addWeighted(actions,share);
        root.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        ImageView iv=new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(iv,new LinearLayout.LayoutParams(-1,0,1f));
        TextView loading=text("Cargando imagen…",14,Color.WHITE,true);
        root.addView(loading);
        d.setContentView(root);
        d.setOnDismissListener(x->{if(!readable.equals(file))try{readable.delete();}catch(Throwable ignored){}});
        d.show();
        new Thread(()->{
            int maxW=Math.max(720,getResources().getDisplayMetrics().widthPixels*2);
            int maxH=Math.max(1280,getResources().getDisplayMetrics().heightPixels*2);
            Bitmap b=decodeThumb(readable,maxW,maxH);
            postUiIfAlive(()->{
                if(!d.isShowing()){if(b!=null)b.recycle();return;}
                if(b!=null){iv.setImageBitmap(b);loading.setText(name==null?"":name);}
                else loading.setText("No pude decodificar esta imagen. Puede estar dañada o incompleta.");
            });
        },"wa-vault-image-open").start();
    }

    private void showVideoViewer(File file,String name){
        if(file==null||!file.exists())return;
        final File readable=MediaCrypto.materialize(this,file,name);
        if(readable==null){Toast.makeText(this,"No se pudo descifrar este video",Toast.LENGTH_LONG).show();return;}
        Dialog d=new Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        applySafeInsets(root,12,8,12,8);
        LinearLayout actions=row();
        Button close=button("✕ Cerrar",v->d.dismiss());
        Button save=button("↓ Galería",v->beginExport(file,name,guessShareMime(name,"video/mp4")));
        Button share=button("↗ Compartir",v->shareRecoveredFile(file,name,guessShareMime(name,"video/mp4")));
        addWeighted(actions,close);addWeighted(actions,save);addWeighted(actions,share);
        root.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        VideoView video=new VideoView(this);
        MediaController controller=new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        root.addView(video,new LinearLayout.LayoutParams(-1,0,1f));
        root.addView(text(name==null?"Video":name,13,Color.WHITE,false));
        d.setContentView(root);
        d.setOnDismissListener(x->{try{video.stopPlayback();}catch(Throwable ignored){}if(!readable.equals(file))try{readable.delete();}catch(Throwable ignored){}});
        d.show();
        try{
            video.setVideoURI(Uri.fromFile(readable));
            video.setOnPreparedListener(mp->{try{video.start();controller.show(2500);}catch(Throwable ignored){}});
            video.setOnErrorListener((mp,what,extra)->{Toast.makeText(this,"No se pudo reproducir este video",Toast.LENGTH_LONG).show();return true;});
            video.requestFocus();
        }catch(Throwable t){Toast.makeText(this,"No se pudo abrir este video",Toast.LENGTH_LONG).show();}
    }

    private String guessShareMime(String name,String fallback){
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        if(n.endsWith(".jpg")||n.endsWith(".jpeg"))return "image/jpeg";
        if(n.endsWith(".png"))return "image/png";
        if(n.endsWith(".webp"))return "image/webp";
        if(n.endsWith(".gif"))return "image/gif";
        if(n.endsWith(".mp4"))return "video/mp4";
        if(n.endsWith(".webm"))return "video/webm";
        if(n.endsWith(".3gp"))return "video/3gpp";
        if(n.endsWith(".pdf"))return "application/pdf";
        if(n.endsWith(".doc"))return "application/msword";
        if(n.endsWith(".docx"))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if(n.endsWith(".xls"))return "application/vnd.ms-excel";
        if(n.endsWith(".xlsx"))return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if(n.endsWith(".ppt"))return "application/vnd.ms-powerpoint";
        if(n.endsWith(".pptx"))return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if(n.endsWith(".zip"))return "application/zip";
        return fallback==null?"application/octet-stream":fallback;
    }

    private String safeExportName(String name,File file,String mime){
        String n=(name==null||name.trim().isEmpty())?(file==null?"WA_Vault":file.getName()):name.trim();
        n=n.replaceAll("[\\\\/:*?\"<>|]","_");
        if(!n.contains(".")){
            if(mime!=null&&mime.startsWith("image/"))n+=".jpg";
            else if(mime!=null&&mime.startsWith("video/"))n+=".mp4";
        }
        return n;
    }

    private void downloadRecoveredFile(File stored,String name,String mime){
        if(stored==null||!stored.exists()){Toast.makeText(this,"El archivo ya no está disponible",Toast.LENGTH_SHORT).show();return;}
        Toast.makeText(this,"Descargando…",Toast.LENGTH_SHORT).show();
        DownloadsExporter.saveAsync(getApplicationContext(),stored,safeExportName(name,stored,mime),mime,
                ()->postUiIfAlive(()->Toast.makeText(this,"Descargado · Downloads/WA Vault",Toast.LENGTH_LONG).show()),
                ()->postUiIfAlive(()->Toast.makeText(this,"No se pudo descargar el archivo",Toast.LENGTH_LONG).show()));
    }

    private void beginExport(File stored,String name,String mime){
        if(stored==null||!stored.exists()){Toast.makeText(this,"El archivo ya no está disponible",Toast.LENGTH_SHORT).show();return;}
        String type=(mime!=null&&mime.toLowerCase(Locale.ROOT).startsWith("video/"))?"video":"image";
        try{VaultDb.Media tracked=db.findMediaByPath(stored.getAbsolutePath());if(tracked!=null&&tracked.galleryUri!=null&&!tracked.galleryUri.isEmpty()){if(GalleryExporter.exportedCopyExists(getApplicationContext(),tracked.galleryUri)){Toast.makeText(this,"Ya está guardado en Galería",Toast.LENGTH_SHORT).show();return;}db.clearGalleryUriForPath(stored.getAbsolutePath());}}catch(Throwable t){recordUiError("GALLERY_DUP_CHECK",t);}
        Toast.makeText(this,"Guardando en Galería…",Toast.LENGTH_SHORT).show();
        boolean queued=GalleryExporter.saveFileAsync(getApplicationContext(),stored,safeExportName(name,stored,mime),mime,type,
                uri->{
                    boolean tracked=false;try{tracked=db.updateGalleryUriForPath(stored.getAbsolutePath(),uri.toString());}catch(Throwable t){recordUiError("GALLERY_URI_TRACK",t);}
                    if(!tracked){try{GalleryExporter.deleteUri(getApplicationContext(),uri);}catch(Throwable ignored){}postUiIfAlive(()->Toast.makeText(this,"No se pudo registrar la copia de Galería",Toast.LENGTH_LONG).show());return;}
                    postUiIfAlive(()->Toast.makeText(this,"Guardado en Galería · WA Vault",Toast.LENGTH_LONG).show());
                },
                ()->postUiIfAlive(()->Toast.makeText(this,"No se pudo guardar en Galería",Toast.LENGTH_LONG).show()));
        if(!queued)Toast.makeText(this,"Ese archivo ya se está guardando",Toast.LENGTH_SHORT).show();
    }

    private void shareRecoveredFile(File stored,String name,String mime){
        if(stored==null||!stored.exists()){Toast.makeText(this,"El archivo ya no está disponible",Toast.LENGTH_SHORT).show();return;}
        Toast.makeText(this,"Preparando para compartir…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            File out=null;
            try{
                File dir=new File(getCacheDir(),"vault_share");if(!dir.exists())dir.mkdirs();
                String safe=safeExportName(name,stored,mime);
                out=new File(dir,VaultFileNames.shareName(safe,mime,null));
                if(!MediaCrypto.decryptTo(stored,out))throw new IllegalStateException("decrypt");
                Uri uri=VaultShareProvider.uriFor(out);
                Intent send=new Intent(Intent.ACTION_SEND);
                send.setType(mime==null?"application/octet-stream":mime);
                send.putExtra(Intent.EXTRA_STREAM,uri);
                send.setClipData(ClipData.newRawUri("WA Vault",uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                postUiIfAlive(()->startActivity(Intent.createChooser(send,"Compartir archivo")));
            }catch(Throwable t){if(out!=null)try{out.delete();}catch(Throwable ignored){}postUiIfAlive(()->Toast.makeText(this,"No se pudo preparar el archivo para compartir",Toast.LENGTH_LONG).show());}
        },"wa-vault-share").start();
    }

    private void openRecoveredDocument(File stored,String name,String mime){
        if(stored==null||!stored.exists())return;
        new Thread(()->{File out=null;try{File dir=new File(getCacheDir(),"vault_share");if(!dir.exists())dir.mkdirs();out=new File(dir,VaultFileNames.shareName(name,mime,null));if(!MediaCrypto.decryptTo(stored,out))throw new IllegalStateException();Uri uri=VaultShareProvider.uriFor(out);Intent view=new Intent(Intent.ACTION_VIEW);view.setDataAndType(uri,mime==null?"application/octet-stream":mime);view.setClipData(ClipData.newRawUri("WA Vault",uri));view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);postUiIfAlive(()->{try{startActivity(view);}catch(Throwable t){Toast.makeText(this,"No hay una app instalada para abrir este documento",Toast.LENGTH_LONG).show();}});}catch(Throwable t){if(out!=null)try{out.delete();}catch(Throwable ignored){}postUiIfAlive(()->Toast.makeText(this,"No se pudo abrir el documento",Toast.LENGTH_LONG).show());}},"wa-vault-open-doc").start();
    }

    private void finishExport(Uri destination){
        final File stored=pendingExportFile;final String label=pendingExportName;
        pendingExportFile=null;pendingExportName=null;pendingExportMime=null;
        if(stored==null||destination==null)return;
        new Thread(()->{
            File readable=null;boolean ok=false;
            try{
                readable=MediaCrypto.materialize(getApplicationContext(),stored,label);
                if(readable==null)throw new IllegalStateException();
                try(InputStream in=new FileInputStream(readable);OutputStream out=getContentResolver().openOutputStream(destination,"w")){
                    if(out==null)throw new IllegalStateException();byte[] buf=new byte[131072];int n;while((n=in.read(buf))>0)out.write(buf,0,n);out.flush();ok=true;
                }
            }catch(Throwable ignored){}finally{if(readable!=null&&!readable.equals(stored))try{readable.delete();}catch(Throwable ignored){}}
            final boolean saved=ok;postUiIfAlive(()->Toast.makeText(this,saved?"Archivo guardado":"No se pudo guardar el archivo",Toast.LENGTH_LONG).show());
        },"wa-vault-export").start();
    }

    private void confirmDeleteMessage(VaultDb.Msg message) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar mensaje guardado")
                .setMessage("¿Eliminar esta copia de WA Vault? Los archivos asociados se conservan en Archivos salvo que los elimines por separado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (d,w) -> {
                    try { db.deleteMessage(message.id); } catch (Throwable ignored) {}
                    showMessages();
                }).show();
    }

    private void confirmDeleteMedia(VaultDb.Media media,Runnable after){
        new AlertDialog.Builder(this)
                .setTitle("Mover a papelera")
                .setMessage("La copia se conservará 7 días en la papelera antes de eliminarse definitivamente. Esto no modifica WhatsApp.")
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Mover",(d,w)->{
                    try{db.moveMediaToTrash(media.id);}catch(Throwable t){recordUiError("TRASH_MEDIA",t);}
                    if(after!=null)after.run();
                }).show();
    }

    private void showAudioViewer(File file,String name){
        if(file==null||!file.exists())return;
        Dialog d=new Dialog(this,android.R.style.Theme_Material_NoActionBar);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(bg);root.setPadding(dp(20),dp(20),dp(20),dp(20));
        LinearLayout top=row();TextView title=text(name==null?"Audio":name,18,fg,true);title.setMaxLines(2);top.addView(title,new LinearLayout.LayoutParams(0,-2,1f));Button close=button("✕",v->d.dismiss());top.addView(close,new LinearLayout.LayoutParams(dp(52),dp(48)));root.addView(top);
        LinearLayout player=card();player.setPadding(dp(14),dp(14),dp(14),dp(14));addAudioControls(player,file);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.setMargins(0,dp(14),0,0);root.addView(player,pp);
        TextView hint=text("Mantén pulsado el archivo en Archivos para eliminarlo",11,muted,false);hint.setPadding(0,dp(10),0,0);root.addView(hint);
        d.setContentView(root);d.setOnDismissListener(x->releaseAudioPlayer(true));d.show();
        if(d.getWindow()!=null)d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Button addAudioControls(LinearLayout cardView, File file) {
        TextView time=text("00:00  /  --:--",12,muted,false);
        time.setGravity(Gravity.CENTER_HORIZONTAL);
        cardView.addView(time);
        SeekBar seek=new SeekBar(this); seek.setMax(1000); seek.setProgress(0); seek.setPadding(0,0,0,0);
        cardView.addView(seek,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout controls=row(); controls.setPadding(0,dp(6),0,0);
        Button back=button("−5 s",v->seekAudioRelative(file,-5000));
        Button speed=button(speedLabel(audioSpeed),v->cycleAudioSpeed(file,(Button)v));
        Button play=button("▶",v->toggleAudio(file,(Button)v,seek,time,speed));
        Button forward=button("+5 s",v->seekAudioRelative(file,5000));
        addWeighted(controls,back); addWeighted(controls,play); addWeighted(controls,forward); addWeighted(controls,speed);
        cardView.addView(controls,new LinearLayout.LayoutParams(-1,-2));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){if(!fromUser)return;if(audioPlayer!=null&&audioPrepared&&file.getAbsolutePath().equals(activeAudioPath)){try{audioPlayer.seekTo(progress);updateAudioTime();}catch(Throwable ignored){}}}
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        return play;
    }

    private void toggleAudio(File file, Button playButton, SeekBar seekBar, TextView timeText, Button speedButton) {
        String path = file.getAbsolutePath();
        if (audioPlayer != null && path.equals(activeAudioPath)) {
            if (!audioPrepared) return;
            try {
                if (audioPlayer.isPlaying()) {
                    audioPlayer.pause();
                    playButton.setText("▶");
                    stopProgressUpdates();
                    updateAudioTime();
                } else {
                    applyAudioSpeed();
                    audioPlayer.start();
                    playButton.setText("⏸");
                    startProgressUpdates();
                }
            } catch (Throwable t) {
                Toast.makeText(this, "No se pudo reproducir este audio", Toast.LENGTH_SHORT).show();
                releaseAudioPlayer(false);
            }
            return;
        }

        releaseAudioPlayer(true);
        activeAudioPath = path;
        activePlayButton = playButton;
        activeSeekBar = seekBar;
        activeTimeText = timeText;
        activeSpeedButton = speedButton;
        speedButton.setText(speedLabel(audioSpeed));
        audioPrepared = false;
        playButton.setText("…");
        seekBar.setProgress(0);
        timeText.setText("00:00 / --:--");

        try {
            File readable=MediaCrypto.materialize(this,file,file.getName());
            if(readable==null){Toast.makeText(this,"No se pudo descifrar este audio",Toast.LENGTH_SHORT).show();releaseAudioPlayer(false);return;}
            if(!readable.equals(file))activeAudioTempFile=readable;
            MediaPlayer mp = new MediaPlayer();
            audioPlayer = mp;
            mp.setDataSource(readable.getAbsolutePath());
            mp.setOnPreparedListener(player -> {
                if (audioPlayer != player || !path.equals(activeAudioPath)) return;
                audioPrepared = true;
                try {
                    int duration = Math.max(1, player.getDuration());
                    activeSeekBar.setMax(duration);
                    activeSeekBar.setProgress(0);
                    applyAudioSpeed();
                    player.start();
                    activePlayButton.setText("⏸");
                    updateAudioTime();
                    startProgressUpdates();
                } catch (Throwable t) {
                    Toast.makeText(this, "El formato del audio no es compatible con el reproductor interno", Toast.LENGTH_LONG).show();
                    releaseAudioPlayer(false);
                }
            });
            mp.setOnCompletionListener(player -> {
                if (audioPlayer != player) return;
                stopProgressUpdates();
                try {
                    if (activeSeekBar != null) activeSeekBar.setProgress(activeSeekBar.getMax());
                    if (activePlayButton != null) activePlayButton.setText("↻");
                    updateAudioTime();
                } catch (Throwable ignored) {}
            });
            mp.setOnErrorListener((player, what, extra) -> {
                Toast.makeText(this, "No se pudo abrir este audio", Toast.LENGTH_SHORT).show();
                releaseAudioPlayer(false);
                return true;
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            Toast.makeText(this, "No se pudo abrir este audio", Toast.LENGTH_SHORT).show();
            releaseAudioPlayer(false);
        }
    }

    private void seekAudioRelative(File file, int deltaMs) {
        if (audioPlayer == null || !audioPrepared || file == null || !file.getAbsolutePath().equals(activeAudioPath)) {
            Toast.makeText(this, "Pulsa Reproducir primero", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int duration = Math.max(0, audioPlayer.getDuration());
            int target = Math.max(0, Math.min(duration, audioPlayer.getCurrentPosition() + deltaMs));
            audioPlayer.seekTo(target);
            updateAudioTime();
        } catch (Throwable ignored) {}
    }

    private void cycleAudioSpeed(File file, Button speedButton) {
        float next;
        if (audioSpeed < 1.25f) next = 1.5f;
        else if (audioSpeed < 1.75f) next = 2.0f;
        else next = 1.0f;
        audioSpeed = next;
        speedButton.setText(speedLabel(audioSpeed));
        if (file.getAbsolutePath().equals(activeAudioPath)) {
            activeSpeedButton = speedButton;
            applyAudioSpeed();
        }
    }

    private String speedLabel(float speed) {
        if (speed >= 1.9f) return "2×";
        if (speed >= 1.4f) return "1.5×";
        return "1×";
    }

    private void applyAudioSpeed() {
        if (audioPlayer == null || !audioPrepared || Build.VERSION.SDK_INT < 23) return;
        try {
            PlaybackParams params = audioPlayer.getPlaybackParams();
            params.setSpeed(audioSpeed);
            params.setPitch(1.0f);
            audioPlayer.setPlaybackParams(params);
            if (activeSpeedButton != null) activeSpeedButton.setText(speedLabel(audioSpeed));
        } catch (Throwable ignored) {}
    }

    private void startProgressUpdates() {
        stopProgressUpdates();
        audioProgressTask = new Runnable() {
            @Override public void run() {
                if (audioPlayer == null || !audioPrepared) return;
                updateAudioTime();
                try {
                    if (audioPlayer.isPlaying()) audioHandler.postDelayed(this, 250);
                } catch (Throwable ignored) {}
            }
        };
        audioHandler.post(audioProgressTask);
    }

    private void stopProgressUpdates() {
        if (audioProgressTask != null) {
            audioHandler.removeCallbacks(audioProgressTask);
            audioProgressTask = null;
        }
    }

    private void updateAudioTime() {
        if (audioPlayer == null || !audioPrepared) return;
        try {
            int pos = Math.max(0, audioPlayer.getCurrentPosition());
            int dur = Math.max(0, audioPlayer.getDuration());
            if (activeSeekBar != null) {
                if (activeSeekBar.getMax() != dur && dur > 0) activeSeekBar.setMax(dur);
                activeSeekBar.setProgress(Math.min(pos, activeSeekBar.getMax()));
            }
            if (activeTimeText != null) activeTimeText.setText(formatAudioTime(pos) + " / " + formatAudioTime(dur));
        } catch (Throwable ignored) {}
    }

    private String formatAudioTime(int millis) {
        int total = Math.max(0, millis / 1000);
        int min = total / 60;
        int sec = total % 60;
        return String.format(Locale.ROOT, "%02d:%02d", min, sec);
    }

    private void releaseAudioPlayer(boolean resetUi) {
        stopProgressUpdates();
        if (resetUi && activePlayButton != null) {
            try { activePlayButton.setText("▶"); } catch (Throwable ignored) {}
        }
        if (audioPlayer != null) {
            try { audioPlayer.stop(); } catch (Throwable ignored) {}
            try { audioPlayer.reset(); } catch (Throwable ignored) {}
            try { audioPlayer.release(); } catch (Throwable ignored) {}
        }
        audioPlayer = null;
        if(activeAudioTempFile!=null){try{activeAudioTempFile.delete();}catch(Throwable ignored){}}
        activeAudioTempFile=null;
        audioPrepared = false;
        activeAudioPath = null;
        activePlayButton = null;
        activeSpeedButton = null;
        activeSeekBar = null;
        activeTimeText = null;
    }

    private Bitmap decodeThumb(File file, int reqW, int reqH) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, src) -> {
                    int w=Math.max(1,info.getSize().getWidth());
                    int h=Math.max(1,info.getSize().getHeight());
                    float scale=Math.min(1f,Math.min(reqW/(float)w,reqH/(float)h));
                    decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                });
            }
        } catch (Throwable ignored) {}
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while ((bounds.outWidth / sample) > reqW * 2 || (bounds.outHeight / sample) > reqH * 2) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private String formatDiagTime(long epoch){
        if(epoch<=0)return "nunca";
        return DateFormat.format("dd/MM HH:mm:ss",new Date(epoch)).toString();
    }

    private String human(long n){
        if(n<1024)return n+" B";
        if(n<1024L*1024L)return String.format(Locale.ROOT,"%.1f KB",n/1024.0);
        if(n<1024L*1024L*1024L)return String.format(Locale.ROOT,"%.1f MB",n/1048576.0);
        return String.format(Locale.ROOT,"%.2f GB",n/1073741824.0);
    }

    private String lockDelayLabel(long ms){if(ms<=0)return "inmediatamente";if(ms<60_000L)return (ms/1000L)+" s";return (ms/60_000L)+" min";}
    private void showLockDelayDialog(){
        final String[] labels={"Inmediatamente","30 segundos","1 minuto","5 minutos"};
        final long[] values={0L,30_000L,60_000L,300_000L};
        new AlertDialog.Builder(this).setTitle("Bloqueo automático").setItems(labels,(d,w)->{getSharedPreferences("wa_vault_settings",MODE_PRIVATE).edit().putLong("app_lock_delay_ms",values[w]).apply();showSettings();}).show();
    }

    private void showSettings(){
        rememberScreen("settings");
        shell("Ajustes");
        SharedPreferences settings=getSharedPreferences("wa_vault_settings",MODE_PRIVATE);

        boolean notif=notificationAccess();
        boolean media=hasAllMediaPermissions();
        boolean partial=hasPartialVisualMediaAccess();
        boolean fast=(Build.VERSION.SDK_INT<30||Environment.isExternalStorageManager());
        boolean battery=ignoresBatteryOptimizations();

        LinearLayout protection=card();
        protection.addView(text("Protección",18,fg,true));
        protection.addView(text("Lo esencial para que WA Vault pueda reaccionar antes de que WhatsApp retire el contenido.",11,muted,false));
        protection.addView(statusLine(notif,"Notificaciones",notif?"Listo":"Necesita acceso"));
        protection.addView(statusLine(media,"Fotos, video y audio",media?"Listo":(partial?"Acceso parcial":"Necesita permiso")));
        protection.addView(statusLine(fast,"Acceso rápido",fast?"Android/media disponible":"Modo limitado"));
        protection.addView(statusLine(battery,"Segundo plano",battery?"Sin restricciones":"Android puede detener la captura"));
        if(!notif)protection.addView(button("Activar notificaciones",v->openNotificationSettings()));
        if(!media)protection.addView(button("Dar acceso a multimedia",v->requestMediaPermissions()));
        if(Build.VERSION.SDK_INT>=30&&!fast)protection.addView(button("Activar acceso rápido",v->requestFastStorageAccess()));
        if(!battery)protection.addView(button("Quitar restricción de batería",v->requestBatteryExemption()));
        LinearLayout protectActions=row();
        addWeighted(protectActions,button("Ver estado",v->showDiagnostics()));
        addWeighted(protectActions,button("Reparar protección",v->repairProtection()));
        protection.addView(protectActions);content.addView(protection);

        boolean secure=settings.getBoolean("secure_screenshots",true);
        boolean appLock=settings.getBoolean("app_lock",false);
        LinearLayout privacy=card();
        privacy.addView(text("Privacidad",18,fg,true));
        privacy.addView(statusLine(secure,"Pantalla",secure?"Capturas bloqueadas":"Capturas permitidas"));
        privacy.addView(statusLine(appLock,"Bloqueo",appLock?"Biometría / dispositivo":"Desactivado"));
        privacy.addView(text("Mensajes cifrados · archivos "+MediaCrypto.modeLabel(this)+" · clave protegida por Android Keystore",11,muted,false));
        if(MediaCrypto.isMigrationRunning())privacy.addView(text("Migración de cifrado obligatorio en curso",11,green,true));
        privacy.addView(statusLine(true,"Cifrado de archivos","Obligatorio · AES-256-GCM"));
        LinearLayout privacyActions=row();
        addWeighted(privacyActions,button(appLock?"Quitar bloqueo":"Activar bloqueo",v->{boolean next=!settings.getBoolean("app_lock",false);settings.edit().putBoolean("app_lock",next).apply();showSettings();}));
        addWeighted(privacyActions,button(secure?"Permitir capturas":"Bloquear capturas",v->{boolean next=!settings.getBoolean("secure_screenshots",true);settings.edit().putBoolean("secure_screenshots",next).apply();applySecureWindowSetting();showSettings();}));
        privacy.addView(privacyActions);
        if(appLock)privacy.addView(button("Tiempo para volver a bloquear",v->showLockDelayDialog()));
        content.addView(privacy);

        LinearLayout advanced=card();
        advanced.addView(text("Mantenimiento",18,fg,true));
        advanced.addView(text("Normalmente no necesitas tocar nada aquí.",11,muted,false));
        advanced.addView(button("Diagnóstico e integridad",v->showDiagnostics()));
        advanced.addView(button("Gestionar recuperados y almacenamiento",v->showMedia()));
        if(Build.MANUFACTURER!=null&&Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("samsung"))advanced.addView(button("Samsung · evitar autosuspensión",v->openSamsungNeverSleeping()));
        content.addView(advanced);
    }

    private static final class DiagnosticsSnapshot {
        VaultDb.Stats stats=new VaultDb.Stats();
        int internalErrors,partialCount;
        long free,physical;
        String history="Capturas 0   ·   Fallos 0   ·   Duplicados evitados 0   ·   Reparaciones 0";
        List<VaultDb.RecoveryJob> jobs=new ArrayList<>();
        List<VaultDb.Event> events=new ArrayList<>();
    }

    private void showDiagnostics(){
        rememberScreen("diagnostics");shell("Diagnóstico");final int generation=++diagnosticsLoadGeneration;
        LinearLayout loading=card();loading.addView(text("Cargando diagnóstico…",14,muted,true));content.addView(loading);
        final Context app=getApplicationContext();
        new Thread(()->{
            DiagnosticsSnapshot snap=new DiagnosticsSnapshot();VaultDb x=new VaultDb(app);
            try{snap.stats=x.getStats();}catch(Throwable ignored){}
            try{for(VaultDb.Event e:x.listEvents(160))if(e!=null&&e.code!=null&&(e.code.startsWith("ERROR_")||e.code.startsWith("CAPTURE_FAIL_")))snap.internalErrors++;}catch(Throwable ignored){}
            try{snap.jobs=x.listRecoveryJobs(8);}catch(Throwable ignored){}
            try{snap.events=x.listEvents(20);}catch(Throwable ignored){}
            try{snap.history=healthHistory24h(x.listEventsSince(System.currentTimeMillis()-24L*60L*60L*1000L,600));}catch(Throwable ignored){}
            try{snap.partialCount=x.countCaptureAttempts("PARTIAL");}catch(Throwable ignored){}
            try{snap.free=StorageAnalyzer.freeBytes(app);snap.physical=StorageAnalyzer.vaultPhysicalBytes(app);}catch(Throwable ignored){}
            postUiIfAlive(()->{if(generation!=diagnosticsLoadGeneration||!"diagnostics".equals(currentScreen))return;renderDiagnosticsSnapshot(snap);});
        },"wa-vault-diagnostics-load").start();
    }

    private void renderDiagnosticsSnapshot(DiagnosticsSnapshot snap){
        shell("Diagnóstico");SharedPreferences d=getSharedPreferences("wa_vault_diag",MODE_PRIVATE);VaultDb.Stats st=snap.stats;
        int pLevel=protectionLevel();boolean allGood=pLevel==2;
        LinearLayout simple=card();simple.addView(text(protectionHeadline()+(allGood?" ✓":""),20,allGood?green:Color.rgb(255,190,110),true));simple.addView(text("Última captura: "+humanLastCapture(d),12,muted,false));content.addView(simple);

        long init=d.getLong("capture_coordinator_last_at",0L);boolean settling=init>0&&System.currentTimeMillis()-init<1800L;
        LinearLayout health=card();health.addView(text("Estado general",18,fg,true));
        addDiagnosticLine(health,"Notificaciones",notificationAccess(),notificationAccess()?"Listener habilitado":"Falta activar acceso");
        boolean directAudio=DirectVoiceWatcher.isAvailable(this)&&(DirectVoiceWatcher.isHealthy()||settling);addDiagnosticLine(health,"Audio directo",directAudio,directAudio?(DirectVoiceWatcher.isHealthy()?"FileObserver activo":"Iniciando…"):"Usando ruta alternativa / reparar");
        boolean directMedia=DirectMediaWatcher.isAvailable(this)&&(DirectMediaWatcher.isHealthy()||settling);addDiagnosticLine(health,"Fotos / videos directos",directMedia,directMedia?(DirectMediaWatcher.isHealthy()?"FileObserver activo":"Iniciando…"):"Usando ruta alternativa / reparar");
        boolean ms=MediaStoreWatcher.isHealthy()||settling;addDiagnosticLine(health,"MediaStore",ms,MediaStoreWatcher.isHealthy()?"ContentObserver activo":(settling?"Iniciando…":"Necesita reinicio"));
        addDiagnosticLine(health,"Fast Capture Engine",DirectMediaWatcher.isAvailable(this),DirectMediaWatcher.isAvailable(this)?"FD-first disponible":"Necesita acceso a WhatsApp/Media");
        addDiagnosticLine(health,"Batería",ignoresBatteryOptimizations(),ignoresBatteryOptimizations()?"Sin optimización de Android":"Android puede limitar la app");
        addDiagnosticLine(health,"Almacenamiento",snap.free>256L*1024L*1024L,"Libre "+human(snap.free)+" · Vault "+human(snap.physical));
        addDiagnosticLine(health,"Centro de recuperación",true,"Detectados "+Math.max(st.media,st.detectedFiles)+" · guardados "+st.savedFiles+" · incidencias "+st.recoveryIssues);
        addDiagnosticLine(health,"Errores internos recientes",snap.internalErrors==0,snap.internalErrors==0?"Ninguno registrado":(snap.internalErrors+" registrados · revisar últimas señales"));content.addView(health);

        LinearLayout latest=card();latest.addView(text("Últimas señales",18,fg,true));latest.addView(diagRow("Mensaje",d.getString("last_event","Sin actividad")));latest.addView(diagRow("Borrado",d.getString("last_delete_path","Sin señal")+" · prev "+d.getInt("last_delete_previous",0)+" → actual "+d.getInt("last_delete_current",0)+" · marcados "+d.getInt("last_delete_marked",0)));latest.addView(diagRow("Borrados no verificables",d.getInt("delete_unverifiable_count",0)+" candidatos ocultos · "+d.getString("delete_unverifiable_last","sin incidencias")));latest.addView(diagRow("Audio",d.getString("notif_audio_status",d.getString("direct_watcher_last_event","Sin evento"))));latest.addView(diagRow("Foto / video",d.getString("last_direct_media","Sin evento directo")));latest.addView(diagRow("MediaStore",d.getString("last_mediastore_event","Sin evento")));latest.addView(diagRow("Último resultado",d.getString("last_capture_reason","Sin fallos del motor rápido registrados")));latest.addView(diagRow("Último cierre inesperado",CrashRegistry.summary(this)));latest.addView(diagRow("Guardados",st.media+" archivos · "+human(st.totalBytes)));latest.addView(diagRow("Escaneo manual",d.getBoolean("recovery_scan_running",false)?"En curso":(d.getLong("recovery_scan_finished",0)>0?"Último: "+DateFormat.format("dd/MM HH:mm",new Date(d.getLong("recovery_scan_finished",0)))+" · "+d.getInt("recovery_scan_found",0)+" incorporados":"No ejecutado")));content.addView(latest);

        LinearLayout lifecycle=card();lifecycle.addView(text("Ciclo de recuperación",18,fg,true));lifecycle.addView(text("Detectado → Copiando → Verificando → Guardado. Si Android retira o daña el archivo, queda registrado como perdido/corrupto y no cuenta como guardado.",11,muted,false));if(snap.jobs.isEmpty())lifecycle.addView(text("Aún no hay operaciones del motor v0.4.0.",11,muted,false));for(VaultDb.RecoveryJob r:snap.jobs){String icon=RecoveryLedger.SAVED.equals(r.state)?"✓":((RecoveryLedger.LOST.equals(r.state)||RecoveryLedger.CORRUPT.equals(r.state))?"!":"·");lifecycle.addView(text(icon+" "+r.state+" · "+(r.type==null?"archivo":r.type)+" · "+human(r.bytes),11,RecoveryLedger.SAVED.equals(r.state)?green:muted,true));if(r.reason!=null&&!r.reason.isEmpty())lifecycle.addView(text(r.reason,10,muted,false));}content.addView(lifecycle);

        LinearLayout timeline=card();timeline.addView(text("Actividad reciente",18,fg,true));timeline.addView(text("Los eventos más nuevos aparecen arriba. Sirve para saber exactamente en qué punto falló una captura.",11,muted,false));if(snap.events.isEmpty())timeline.addView(text("Todavía no hay eventos técnicos.",12,muted,false));for(VaultDb.Event e:snap.events){String when=DateFormat.format("HH:mm:ss.SSS",new Date(e.timestamp)).toString();timeline.addView(text(when+"   "+friendlyEventCode(e.code),11,fg,true));if(e.detail!=null&&!e.detail.isEmpty())timeline.addView(text(e.detail,10,muted,false));}content.addView(timeline);
        LinearLayout actions=row();addWeighted(actions,button("Actualizar",v->showDiagnostics()));addWeighted(actions,button("Reparar protección",v->{CaptureCoordinator.restart(getApplicationContext());Toast.makeText(this,"Protección revisada",Toast.LENGTH_SHORT).show();uiRefreshHandler.postDelayed(this::showDiagnostics,500L);}));content.addView(actions);
        content.addView(button("Comprobar WA Vault",v->runFullSelfCheck()));content.addView(button("Exportar diagnóstico técnico",v->exportTechnicalDiagnostics()));
        LinearLayout bench=card();bench.addView(text("Prueba de borrado extremo · 60 s",16,fg,true));bench.addView(text("Mide con reloj monotónico: evento → descriptor → primer byte → staging → Vault. Durante la prueba manda fotos/videos y bórralos rápido.",11,muted,false));bench.addView(text(CaptureMetrics.summary(this),11,muted,false));bench.addView(button(CaptureMetrics.active(this)?"Prueba en curso…":"Iniciar prueba extrema",v->{CaptureMetrics.startExtreme60s(getApplicationContext());Toast.makeText(this,"Prueba extrema iniciada · manda y borra multimedia durante 60 s",Toast.LENGTH_LONG).show();showDiagnostics();}));content.addView(bench);
        LinearLayout history=card();history.addView(text("Últimas 24 horas",16,fg,true));history.addView(text(snap.history,12,muted,false));content.addView(history);
        if(snap.partialCount>0){final int pc=snap.partialCount;LinearLayout partial=card();partial.addView(text("Recuperaciones parciales · "+pc,16,Color.rgb(255,190,110),true));partial.addView(text("WA Vault alcanzó a copiar bytes, pero la foto/video no quedó estructuralmente completo o reproducible. No se cuenta como recuperación completa.",11,muted,false));partial.addView(button("Ver causas",v->showPartialAttemptsDialog()));content.addView(partial);}
        LinearLayout integrity=card();integrity.addView(text("Prueba de integridad",16,fg,true));integrity.addView(text("Comprueba detección estricta, exclusión de stickers, identidad de lote, orden de audios y Keystore sin tocar tus datos.",11,muted,false));integrity.addView(text(CaptureIntegritySelfTest.summary(this),12,fg,true));integrity.addView(button("Ejecutar prueba",v->new Thread(()->{CaptureIntegritySelfTest.Result r=CaptureIntegritySelfTest.run(getApplicationContext());postUiIfAlive(()->{new AlertDialog.Builder(this).setTitle(r.ok()?"Todo correcto":"Hay algo que revisar").setMessage(r.detail).setPositiveButton("OK",null).show();showDiagnostics();});},"wa-vault-integrity-test").start()));content.addView(integrity);
        LinearLayout self=card();self.addView(text("Prueba real con WhatsApp",16,fg,true));self.addView(text("1. Envíate un texto  ·  2. una foto  ·  3. un video  ·  4. un audio  ·  5. un documento",11,muted,false));self.addView(text(CaptureSelfTest.summary(this),12,fg,true));self.addView(button(CaptureSelfTest.startedAt(this)>0?"Reiniciar prueba":"Iniciar prueba guiada",v->{CaptureSelfTest.start(getApplicationContext());Toast.makeText(this,"Prueba iniciada · realiza los 5 pasos en WhatsApp",Toast.LENGTH_LONG).show();showDiagnostics();}));content.addView(self);
        content.addView(button("Reparar y limpiar base de datos",v->{VaultMaintenance.runAsync(getApplicationContext());CaptureRecovery.runAsync(getApplicationContext());Toast.makeText(this,"Mantenimiento iniciado",Toast.LENGTH_SHORT).show();}));content.addView(button("Limpiar historial técnico",v->new AlertDialog.Builder(this).setTitle("Limpiar diagnóstico").setMessage("Esto solo borra el historial técnico, no mensajes ni archivos recuperados.").setNegativeButton("Cancelar",null).setPositiveButton("Limpiar",(a,b)->{new Thread(()->{db.clearEvents();postUiIfAlive(this::showDiagnostics);},"wa-vault-clear-events").start();}).show()));
    }

    private boolean waitVoiceWatcherHealthy(Context app,long maxMs){
        if(!DirectVoiceWatcher.isAvailable(app))return false;try{DirectVoiceWatcher.ensureHealthy(app);}catch(Throwable ignored){}
        long until=android.os.SystemClock.elapsedRealtime()+Math.max(0L,maxMs);do{if(DirectVoiceWatcher.isHealthy())return true;android.os.SystemClock.sleep(75L);}while(android.os.SystemClock.elapsedRealtime()<until);return DirectVoiceWatcher.isHealthy();
    }
    private boolean waitMediaWatcherHealthy(Context app,long maxMs){
        if(!DirectMediaWatcher.isAvailable(app))return false;try{DirectMediaWatcher.ensureHealthy(app);}catch(Throwable ignored){}
        long until=android.os.SystemClock.elapsedRealtime()+Math.max(0L,maxMs);do{if(DirectMediaWatcher.isHealthy())return true;android.os.SystemClock.sleep(75L);}while(android.os.SystemClock.elapsedRealtime()<until);return DirectMediaWatcher.isHealthy();
    }
    private boolean waitMediaStoreHealthy(Context app,long maxMs){
        try{MediaStoreWatcher.ensureHealthy(app);}catch(Throwable ignored){}long until=android.os.SystemClock.elapsedRealtime()+Math.max(0L,maxMs);do{if(MediaStoreWatcher.isHealthy())return true;android.os.SystemClock.sleep(75L);}while(android.os.SystemClock.elapsedRealtime()<until);return MediaStoreWatcher.isHealthy();
    }

    private void runFullSelfCheck(){
        final Context app=getApplicationContext();
        Toast.makeText(this,"Comprobando protección…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            ArrayList<String> issues=new ArrayList<>();
            try{if(!notificationAccess())issues.add("Activa el acceso a notificaciones");}catch(Throwable t){issues.add("No se pudo comprobar el listener");}
            try{if(!hasAllMediaPermissions())issues.add("Faltan permisos de fotos, videos o audio");}catch(Throwable t){issues.add("No se pudieron comprobar los permisos multimedia");}
            try{if(Build.VERSION.SDK_INT>=30&&!Environment.isExternalStorageManager())issues.add("Falta acceso completo a archivos");}catch(Throwable t){issues.add("No se pudo comprobar el acceso a archivos");}
            try{if(!waitVoiceWatcherHealthy(app,1200L))issues.add("Captura directa de audios necesita reparación");}catch(Throwable t){issues.add("No se pudo comprobar la captura de audios");}
            try{if(!waitMediaWatcherHealthy(app,1200L))issues.add("Captura directa de fotos/videos necesita reparación");}catch(Throwable t){issues.add("No se pudo comprobar la captura multimedia");}
            try{if(!waitMediaStoreHealthy(app,900L))issues.add("MediaStore necesita reinicio");}catch(Throwable t){issues.add("No se pudo comprobar MediaStore");}
            try{if(!ignoresBatteryOptimizations())issues.add("Desactiva la optimización de batería para WA Vault");}catch(Throwable t){issues.add("No se pudo comprobar la batería");}
            try{long free=StorageAnalyzer.freeBytes(app);long reserve=MediaLimits.reserveBytes(app);if(free<=reserve)issues.add("Almacenamiento libre insuficiente para captura segura");}catch(Throwable t){issues.add("No se pudo comprobar el almacenamiento");}
            try{CaptureIntegritySelfTest.Result r=CaptureIntegritySelfTest.run(app);if(!r.ok())issues.add("La prueba del motor de integridad no pasó");}catch(Throwable t){issues.add("No se pudo probar el motor de integridad");}
            try{if(!MediaCrypto.selfTest(app))issues.add("El cifrado de archivos necesita revisión");}catch(Throwable t){issues.add("No se pudo comprobar el cifrado de archivos");}
            try{if(!new CryptoManager(app).selfTest())issues.add("El cifrado de textos/metadatos necesita revisión");}catch(Throwable t){issues.add("No se pudo comprobar el cifrado de metadatos");}
            try{if(!MetadataPrivacy.selfTest(app))issues.add("La clave HMAC privada necesita revisión");}catch(Throwable t){issues.add("No se pudo comprobar el HMAC privado");}
            try{int n=LegacyPlainMigration.remainingCount(app);if(n!=0)issues.add("Quedan "+n+" campos heredados sin migrar");}catch(Throwable t){issues.add("No se pudo verificar la migración de textos");}
            try{int n=db.countLegacyContentHashes();if(n!=0)issues.add("Quedan "+n+" huellas multimedia antiguas sin proteger");}catch(Throwable t){issues.add("No se pudo verificar la privacidad de huellas multimedia");}
            try{int n=MediaCrypto.migrationRemaining(app);if(n!=0)issues.add("Quedan "+n+" archivos pendientes de migración privada");}catch(Throwable t){issues.add("No se pudo verificar la migración de archivos");}
            try{int n=new VaultDb(app).countSensitiveSourceMetadata();if(n!=0)issues.add("Quedan "+n+" identificadores de origen por anonimizar");}catch(Throwable t){issues.add("No se pudo verificar la privacidad de metadatos");}
            try{if(CrashRegistry.hasUnacknowledged(app))issues.add("Cierre inesperado registrado: "+CrashRegistry.summary(app));}catch(Throwable ignored){}
            postUiIfAlive(()->{String title=issues.isEmpty()?"Todo correcto ✓":"Hay cosas que corregir";String msg=issues.isEmpty()?"Listener, permisos, captura, MediaStore, batería, almacenamiento, integridad, cifrado y privacidad de metadatos funcionan correctamente.":android.text.TextUtils.join("\n• ",issues);if(!issues.isEmpty())msg="• "+msg;new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK",(a,b)->CrashRegistry.acknowledge(app)).show();showDiagnostics();});
        },"wa-vault-full-self-check").start();
    }

    private void exportTechnicalDiagnostics(){
        pendingDiagnosticExport=buildTechnicalDiagnostics();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE,"WA-Vault-diagnostico-"+DateFormat.format("yyyyMMdd-HHmm",new Date())+".txt");
        try{startActivityForResult(i,DIAGNOSTIC_EXPORT_REQ);}catch(Throwable t){pendingDiagnosticExport=null;Toast.makeText(this,"No se pudo abrir el selector de archivos",Toast.LENGTH_LONG).show();}
    }

    private void finishTechnicalDiagnosticsExport(Uri destination){
        final String report=pendingDiagnosticExport;pendingDiagnosticExport=null;if(destination==null||report==null)return;
        new Thread(()->{boolean ok=false;try(OutputStream out=getContentResolver().openOutputStream(destination,"w")){if(out==null)throw new IllegalStateException();byte[] bytes=report.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.write(bytes);out.flush();ok=true;}catch(Throwable t){recordUiError("DIAG_EXPORT",t);}final boolean saved=ok;postUiIfAlive(()->Toast.makeText(this,saved?"Diagnóstico guardado":"No se pudo guardar el diagnóstico",Toast.LENGTH_LONG).show());},"wa-vault-diag-export").start();
    }

    private String appVersionName(){try{String v=getPackageManager().getPackageInfo(getPackageName(),0).versionName;return v==null||v.isEmpty()?"unknown":v;}catch(Throwable t){return "unknown";}}

    private String buildTechnicalDiagnostics(){
        SharedPreferences d=getSharedPreferences("wa_vault_diag",MODE_PRIVATE);VaultDb.Stats st;try{st=db.getStats();}catch(Throwable t){st=new VaultDb.Stats();}
        StringBuilder b=new StringBuilder(4096);b.append("WA Vault - diagnostico tecnico\n");b.append("version=").append(appVersionName()).append('\n');b.append("generated_at=").append(System.currentTimeMillis()).append('\n');
        b.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        b.append("protection=").append(protectionHeadline()).append('\n');b.append("notification_listener=").append(notificationAccess()).append('\n');b.append("media_permissions=").append(hasAllMediaPermissions()).append('\n');b.append("all_files_access=").append(Build.VERSION.SDK_INT<30||Environment.isExternalStorageManager()).append('\n');b.append("battery_unrestricted=").append(ignoresBatteryOptimizations()).append('\n');
        b.append("direct_voice=").append(DirectVoiceWatcher.isAvailable(this)&&DirectVoiceWatcher.isHealthy()).append('\n');b.append("direct_media=").append(DirectMediaWatcher.isAvailable(this)&&DirectMediaWatcher.isHealthy()).append('\n');b.append("mediastore=").append(MediaStoreWatcher.isHealthy()).append('\n');
        b.append("free_bytes=").append(StorageAnalyzer.freeBytes(this)).append('\n');b.append("vault_bytes=").append(StorageAnalyzer.vaultPhysicalBytes(this)).append('\n');b.append("video_limit_bytes=").append(MediaLimits.maxVideoBytes(this)).append('\n');b.append("document_limit_bytes=").append(MediaLimits.maxDocumentBytes(this)).append('\n');
        b.append("keystore_fail_count=").append(d.getInt("keystore_fail_count",0)).append('\n');b.append("keystore_fail_at=").append(d.getLong("keystore_fail_at",0L)).append('\n');b.append("keystore_fail_last=").append(d.getString("keystore_fail_last","")).append('\n');b.append("crash_count=").append(d.getInt("crash_count",0)).append('\n');b.append("crash_last_at=").append(d.getLong("crash_last_at",0L)).append('\n');b.append("crash_last_type=").append(d.getString("crash_last_type","")).append('\n');b.append("crash_last_component=").append(d.getString("crash_last_component","")).append('\n');b.append("legacy_plain_remaining=").append(d.getInt("legacy_plain_remaining",0)).append('\n');b.append("media_migration_remaining=").append(getSharedPreferences("wa_vault_settings",MODE_PRIVATE).getInt("media_migration_remaining",0)).append('\n');b.append("metadata_source_remaining=").append(d.getInt("metadata_source_remaining",0)).append('\n');b.append("media_count=").append(st.media).append('\n');b.append("saved_files=").append(st.savedFiles).append('\n');b.append("detected_files=").append(st.detectedFiles).append('\n');b.append("recovery_issues=").append(st.recoveryIssues).append('\n');b.append("delete_unverifiable_count=").append(d.getInt("delete_unverifiable_count",0)).append('\n');
        b.append("last_direct_media_at=").append(d.getLong("last_direct_media_at",0L)).append('\n');b.append("last_mediastore_at=").append(d.getLong("last_mediastore_at",0L)).append('\n');b.append("last_audio_at=").append(d.getLong("notif_audio_at",0L)).append('\n');
        b.append("integrity=").append(CaptureIntegritySelfTest.summary(this).replace('\n',' ')).append("\n\nrecent_event_codes:\n");
        try{for(VaultDb.Event e:db.listEvents(30)){if(e==null)continue;b.append(e.timestamp).append(' ').append(e.code==null?"EVENT":e.code).append('\n');}}catch(Throwable ignored){}
        b.append("\nprivacy=No chat text, sender names, conversation names, media names, paths or event details are included.\n");return b.toString();
    }

    private void showPartialAttemptsDialog(){
        List<VaultDb.CaptureAttempt> attempts;try{attempts=db.listCaptureAttempts(30);}catch(Throwable t){attempts=new ArrayList<>();}
        ArrayList<String> rows=new ArrayList<>();
        for(VaultDb.CaptureAttempt a:attempts){if(a==null||!"PARTIAL".equals(a.state))continue;String when=DateFormat.format("dd/MM HH:mm:ss",new Date(a.createdAt)).toString();rows.add(when+" · "+("video".equals(a.type)?"Video":"Foto")+" · "+human(a.bytes)+"\n"+CaptureProcessingEngine.friendly(a.reason,a.type,a.bytes));}
        if(rows.isEmpty()){Toast.makeText(this,"No quedan recuperaciones parciales pendientes",Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(this).setTitle("Recuperaciones parciales").setItems(rows.toArray(new String[0]),null).setPositiveButton("Cerrar",null).show();
    }

    private String humanLastCapture(SharedPreferences d){
        long last=Math.max(d.getLong("last_direct_media_at",0L),Math.max(d.getLong("last_mediastore_at",0L),d.getLong("notif_audio_at",0L)));
        if(last<=0)return "sin datos todavía";long sec=Math.max(0L,(System.currentTimeMillis()-last)/1000L);if(sec<5)return "ahora";if(sec<60)return "hace "+sec+" s";return "hace "+(sec/60)+" min";
    }
    private String healthHistory24h(List<VaultDb.Event> source){
        int captures=0,fail=0,dupes=0,repairs=0;if(source!=null)for(VaultDb.Event e:source){String c=e==null||e.code==null?"":e.code;if(c.contains("CAPTURE")||c.contains("MEDIA_DIRECT")||c.contains("FILE_OBSERVER")||c.contains("NOTIF_MEDIA"))captures++;if(c.contains("FAIL")||c.contains("ERROR")||c.contains("INCOMPLETE"))fail++;if(c.contains("DUPLICATE_AVOIDED"))dupes++;if(c.contains("RECOVERY")||c.contains("MAINTENANCE"))repairs++;}
        return "Capturas "+captures+"   ·   Fallos "+fail+"   ·   Duplicados evitados "+dupes+"   ·   Reparaciones "+repairs;
    }

    private void addDiagnosticLine(LinearLayout box,String label,boolean ok,String detail){
        TextView t=text((ok?"● ":"● ")+label+" · "+detail,12,ok?green:Color.rgb(255,190,110),ok);box.addView(t);
    }
    private TextView diagRow(String label,String value){return text(label+"\n"+(value==null||value.isEmpty()?"Sin datos":value),12,muted,false);}
    private String friendlyEventCode(String code){
        if(code==null)return "Evento";
        if(code.contains("MESSAGE"))return "Mensaje capturado";
        if(code.contains("FAST_CAPTURE_FULL"))return "Fast Capture · completo";
        if(code.contains("FAST_CAPTURE_PARTIAL"))return "Fast Capture · recuperación parcial";
        if(code.contains("CAPTURE_FAIL_"))return "Captura · fallo registrado";
        if(code.contains("FAST_CAPTURE_FAIL"))return "Fast Capture · no pudo preservar bytes";
        if(code.contains("VIDEO_PREVIEW_SALVAGED"))return "Vista previa de video rescatada";
        if(code.contains("MEDIA_DIRECT_EARLY")||code.contains("FILE_OBSERVER_MEDIA"))return "Archivo capturado directamente";
        if(code.contains("MEDIASTORE"))return "Archivo detectado por MediaStore";
        if(code.contains("NOTIF_MEDIA"))return "Archivo desde notificación";
        if(code.contains("AUDIO"))return "Audio";
        if(code.contains("DELETE"))return "Borrado detectado";
        return code.replace('_',' ');
    }

    private void showStorageManager(){
        VaultDb.Stats st;try{st=db.getStats();}catch(Throwable t){st=new VaultDb.Stats();}
        String msg="WA Vault usa "+human(st.totalBytes)+"\n\n"
                +"Fotos  "+human(st.imageBytes)+"\n"
                +"Videos  "+human(st.videoBytes)+"\n"
                +"Audio  "+human(st.audioBytes)+"\n\n"
                +st.media+" archivos guardados"
                +(st.unlinkedMedia>0?"\n"+st.unlinkedMedia+" sin mensaje asociado":"")
                +"\n\nEstas limpiezas solo borran copias de WA Vault; no modifican WhatsApp.";
        String[] opts={"Ver archivos más grandes","Ver duplicados evitados","Borrar archivos sin mensaje asociado","Borrar recuperaciones parciales/corruptas","Limpiar temporales y caché","Borrar archivos >30 días","Borrar archivos >90 días","Borrar todos los archivos"};
        new AlertDialog.Builder(this).setTitle("Almacenamiento recuperado").setMessage(msg+"\nEspacio libre: "+human(StorageAnalyzer.freeBytes(this))).setItems(opts,(d,w)->{
            if(w==0)showLargestRecovered();
            else if(w==1)showDuplicateSummary();
            else if(w==2)confirmDeleteUnlinkedMedia();
            else if(w==3)new AlertDialog.Builder(this).setTitle("Recuperaciones parciales").setMessage("¿Eliminar fragmentos parciales/corruptos? No se borran recuperaciones completas.").setNegativeButton("Cancelar",null).setPositiveButton("Eliminar",(a,b)->new Thread(()->{int n=db.clearPartialCaptureAttempts();postUiIfAlive(()->Toast.makeText(this,"Parciales eliminados: "+n,Toast.LENGTH_SHORT).show());},"wa-vault-partial-clean").start()).show();
            else if(w==4){new Thread(()->{int n=StorageAnalyzer.cleanupTemporary(getApplicationContext());MediaThumbnailLoader.clearDiskCache(getApplicationContext());postUiIfAlive(()->Toast.makeText(this,"Temporales eliminados: "+n,Toast.LENGTH_SHORT).show());},"wa-vault-temp-clean").start();}
            else if(w==5)confirmDeleteOldMedia(30);
            else if(w==6)confirmDeleteOldMedia(90);
            else if(w==7)confirmClear(true);
        }).setNegativeButton("Cerrar",null).show();
    }

    private void showLargestRecovered(){List<VaultDb.Media> list;try{list=db.listMedia(20);}catch(Throwable t){list=new ArrayList<>();}ArrayList<String> rows=new ArrayList<>();for(VaultDb.Media m:list)rows.add((m.favorite?"★ ":"")+(m.name==null?friendlyMediaName(m.type):m.name)+" · "+human(m.size));new AlertDialog.Builder(this).setTitle("Archivos más grandes").setItems(rows.toArray(new String[0]),null).setPositiveButton("Cerrar",null).show();}
    private void showDuplicateSummary(){int n=0;try{for(VaultDb.Event e:db.listEventsSince(System.currentTimeMillis()-30L*86400000L,1000))if(e.code!=null&&e.code.contains("DUPLICATE_AVOIDED"))n++;}catch(Throwable ignored){}new AlertDialog.Builder(this).setTitle("Duplicados").setMessage("WA Vault evita duplicados por origen y SHA-256 antes de guardar una segunda copia. Duplicados evitados en los últimos 30 días: "+n+".").setPositiveButton("OK",null).show();}

    private void confirmDeleteUnlinkedMedia(){
        new AlertDialog.Builder(this).setTitle("Limpiar archivos sin mensaje").setMessage("¿Eliminar las copias que no pudieron asociarse a ningún mensaje? Esto ayuda a limpiar capturas antiguas de versiones previas. No modifica WhatsApp.").setNegativeButton("Cancelar",null).setPositiveButton("Eliminar",(d,w)->runMediaCleanup("unlinked",0)).show();
    }

    private void confirmDeleteOldMedia(int days){
        new AlertDialog.Builder(this).setTitle("Liberar espacio").setMessage("¿Eliminar las copias de más de "+days+" días? Esto no modifica WhatsApp.").setNegativeButton("Cancelar",null).setPositiveButton("Eliminar",(d,w)->runMediaCleanup("old",days)).show();
    }

    private void runMediaCleanup(String mode,int days){
        Toast.makeText(this,"Limpiando en segundo plano…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            int n=0;try{if("unlinked".equals(mode))n=db.deleteUnlinkedMediaPermanently();else n=db.deleteMediaOlderThanPermanently(System.currentTimeMillis()-Math.max(1,days)*86400000L);}catch(Throwable t){recordUiError("MEDIA_CLEANUP",t);}
            final int removed=n;postUiIfAlive(()->{Toast.makeText(this,"Eliminados: "+removed,Toast.LENGTH_SHORT).show();if("recovered".equals(currentScreen))showMedia();else if("home".equals(currentScreen))showHome();});
        },"wa-vault-cleanup").start();
    }

    private void confirmClear(boolean media){
        new AlertDialog.Builder(this).setTitle("Confirmar borrado")
                .setMessage(media?"¿Eliminar todas las copias recuperadas de fotos, videos, audios y documentos?":"¿Eliminar todos los mensajes guardados?")
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Eliminar",(d,w)->{
                    new Thread(()->{
                        if(media)db.deleteAllMediaPermanently();
                        else db.clearMessages();
                        postUiIfAlive(()->{if(media)showMedia();else showMessages();});
                    },"wa-vault-clear").start();
                }).show();
    }

    private boolean hasAllMediaPermissions() {
        if(Build.VERSION.SDK_INT>=30 && Environment.isExternalStorageManager()) return true;
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        boolean read=checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if(Build.VERSION.SDK_INT<=28)return read&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
        return read;
    }

    private boolean hasPartialVisualMediaAccess(){
        return Build.VERSION.SDK_INT>=34
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED
                && (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)!=PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)!=PackageManager.PERMISSION_GRANTED);
    }

    private void startPermissionFlow() {
        if (permissionFlowStarted) return;
        permissionFlowStarted = true;
        continueSetupAfterPermissions();
    }

    private void continueSetupAfterPermissions() {
        SharedPreferences setupPrefs=getSharedPreferences("wa_vault_settings",MODE_PRIVATE);
        // Android 14+ may deliberately grant only Selected Photos Access. Treat denial/partial
        // access as a degraded capture mode after one prompt instead of trapping the user in an
        // endless runtime-permission loop. Notification URIs and all-files access remain separate.
        if (!hasAllMediaPermissions() && !setupPrefs.getBoolean("media_permissions_prompted_v0530", false)) {
            setupPrefs.edit().putBoolean("media_permissions_prompted_v0530", true).apply();
            requestMediaPermissions();
            return;
        }
        if (!notificationAccess()) {
            openNotificationSettings();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            SharedPreferences sp = getSharedPreferences("wa_vault_settings", MODE_PRIVATE);
            if (!sp.getBoolean("fast_storage_prompted_v034", false)) {
                sp.edit().putBoolean("fast_storage_prompted_v034", true).apply();
                requestFastStorageAccess();
                return;
            }
        }
        try { DirectVoiceWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
        try { DirectMediaWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
        try { MediaStoreWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
        if (!ignoresBatteryOptimizations()) {
            SharedPreferences sp = getSharedPreferences("wa_vault_settings", MODE_PRIVATE);
            if (!sp.getBoolean("battery_exemption_prompted", false)) {
                sp.edit().putBoolean("battery_exemption_prompted", true).apply();
                requestBatteryExemption();
                return;
            }
        }
        if("samsung".equalsIgnoreCase(Build.MANUFACTURER)){
            SharedPreferences sp=getSharedPreferences("wa_vault_settings",MODE_PRIVATE);
            if(!sp.getBoolean("samsung_never_sleep_prompted_v034",false)){
                sp.edit().putBoolean("samsung_never_sleep_prompted_v034",true).apply();
                openSamsungNeverSleeping();
                return;
            }
        }
        // No full-gallery scan here: opening Inicio must stay instant. Event-driven watchers
        // handle new content; only pending-message fallbacks are scanned on demand.
        getWindow().getDecorView().postDelayed(this::maybeOfferVoiceBank, 700);
    }

    private void requestFastStorageAccess() {
        if (Build.VERSION.SDK_INT < 30) return;
        if (Environment.isExternalStorageManager()) {
            try { DirectVoiceWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
            try { DirectMediaWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
            return;
        }
        openedAllFilesSettings = true;
        Toast.makeText(this, "Activa ‘Permitir acceso para administrar todos los archivos’. Se usa para vigilar WhatsApp/Media sin escaneos lentos.", Toast.LENGTH_LONG).show();
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Throwable ignored) {}
        }
    }

    private boolean ignoresBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) { return false; }
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23 || ignoresBatteryOptimizations()) return;
        openedBatterySettings = true;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Throwable ignored) {}
        }
    }

    private void openSamsungNeverSleeping() {
        openedSamsungSettings=true;
        Toast.makeText(this,"Samsung no permite que una app se agregue sola. Se abrirá ‘Aplicaciones sin autosuspensión’: pulsa + y elige WA Vault una sola vez.",Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent();
            intent.setAction("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY");
            intent.setPackage("com.samsung.android.lool");
            intent.putExtra("activity_type", 2);
            startActivity(intent);
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)); } catch (Throwable ignored) {}
        }
    }

    private void maybeOfferVoiceBank() {
        // If direct Android/media access is already available, avoid interrupting the user
        // with a folder picker. SAF remains a fallback only.
        if(DirectVoiceWatcher.isAvailable(this) && DirectMediaWatcher.isAvailable(this)) return;
        // Reuse any WhatsApp tree permission granted to an earlier WA Vault build.
        try { MediaArchiver.adoptPersistedWhatsAppTree(getApplicationContext()); } catch (Throwable ignored) {}
        if (MediaArchiver.hasVoiceBank(this)) return;
        SharedPreferences sp = getSharedPreferences("wa_vault_settings", MODE_PRIVATE);
        if (sp.getBoolean("voice_bank_auto_picker_shown", false)) return;
        sp.edit().putBoolean("voice_bank_auto_picker_shown", true).apply();
        Toast.makeText(this, "Solo falta una confirmación de Android: pulsa ‘Usar esta carpeta’. WA Vault ya intenta abrir WhatsApp/Media.", Toast.LENGTH_LONG).show();
        getWindow().getDecorView().postDelayed(this::chooseVoiceBankFolder, 450L);
    }

    private void chooseVoiceBankFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            try {
                Uri initial = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Android/media/com.whatsapp/WhatsApp/Media");
                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
            } catch (Throwable ignored) {}
            Toast.makeText(this, "Android exige una confirmación: si ves WhatsApp/Media, toca ‘Usar esta carpeta’. Después queda automático.", Toast.LENGTH_LONG).show();
            startActivityForResult(i, VOICE_BANK_REQ);
        } catch (Throwable t) {
            Toast.makeText(this, "Android no pudo abrir el selector de carpetas", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==DIAGNOSTIC_EXPORT_REQ){
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null)finishTechnicalDiagnosticsExport(data.getData());
            else pendingDiagnosticExport=null;
            return;
        }
        if(requestCode==EXPORT_REQ){
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null)finishExport(data.getData());
            else {pendingExportFile=null;pendingExportName=null;pendingExportMime=null;}
            return;
        }
        if (requestCode == CREDENTIAL_REQ) {
            unlockPromptOpen=false;
            if (resultCode == RESULT_OK) { uiUnlocked=true;backgroundAt=0L;renderCurrentScreen();getWindow().getDecorView().postDelayed(MainActivity.this::startPermissionFlow,350L); }
            else { uiUnlocked=false;showLockedScreen(); }
            return;
        }
        if (requestCode != VOICE_BANK_REQ || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            int persist = flags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (persist == 0) persist = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, persist);
        } catch (Throwable ignored) {}
        getSharedPreferences("wa_vault_settings", MODE_PRIVATE).edit()
                .putString("voice_bank_tree_uri", MetadataPrivacy.seal(getApplicationContext(),uri.toString()))
                .putString("voice_bank_last_error", "")
                .putLong("voice_bank_armed_at", System.currentTimeMillis())
                .putLong("voice_bank_index_at", 0L)
                .putString("voice_bank_hot_dirs", "")
                .putBoolean("voice_bank_v2_baselined", false)
                .apply();
        try { db.clearBankSeen(); } catch (Throwable ignored) {}
        try { DirectVoiceWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
        try { DirectMediaWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
        Toast.makeText(this, "Ruta alternativa de audio activada. El modo rápido directo se usa primero cuando Android lo permite.", Toast.LENGTH_LONG).show();
        probeVoiceBankAsync();
    }

    private void probeVoiceBankAsync() {
        if (!MediaArchiver.hasVoiceBank(this)) { chooseVoiceBankFolder(); return; }
        new Thread(() -> {
            try { MediaArchiver.probeVoiceBank(getApplicationContext()); } catch (Throwable ignored) {}
            SharedPreferences p = getSharedPreferences("wa_vault_settings", MODE_PRIVATE);
            final int count = p.getInt("voice_bank_probe_audio", 0);
            final int vn = p.getInt("voice_bank_probe_voice_notes", 0);
            final int wa = p.getInt("voice_bank_probe_whatsapp_audio", 0);
            postUiIfAlive(() -> {
                String msg = "Audios visibles para WA Vault: "+count+"\nVoice Notes: "+vn+"\nWhatsApp Audio: "+wa;
                if (count == 0) msg += "\n\nWA Vault no está viendo los binarios de audio. Cambia la carpeta a Android/media/com.whatsapp/WhatsApp/Media y activa en WhatsApp: Ajustes > Chats > Visibilidad de archivos multimedia.";
                else msg += "\n\nLa carpeta sí es legible. Desde ahora, un audio NUEVO debería entrar en cuarentena cuando aparezca físicamente.";
                new AlertDialog.Builder(this).setTitle("Prueba del Banco").setMessage(msg).setPositiveButton("OK", null).show();
                renderCurrentScreen();
            });
        }, "wa-vault-bank-probe").start();
    }

    private void scanVoiceBankAsync() {
        if (!MediaArchiver.hasVoiceBank(this)) { chooseVoiceBankFolder(); return; }
        new Thread(() -> {
            int n = 0;
            try { n = MediaArchiver.scanVoiceBank(getApplicationContext()); } catch (Throwable ignored) {}
            final int copied = n;
            postUiIfAlive(() -> {
                Toast.makeText(this, copied > 0 ? ("Banco: "+copied+" audio(s) en cuarentena 30 s") : "Banco armado · índice actualizado", Toast.LENGTH_LONG).show();
                renderCurrentScreen();
            });
        }, "wa-vault-bank-manual").start();
    }

    private void refreshEverything(boolean returnToMessages) {
        Toast.makeText(this,"Sincronizando pendientes…",Toast.LENGTH_SHORT).show();
        try { WhatsAppNotificationListener.refreshNow(); } catch (Throwable ignored) {}
        try { DirectVoiceWatcher.start(getApplicationContext()); DirectVoiceWatcher.fastScanAsync(getApplicationContext(),0L,System.currentTimeMillis()); } catch (Throwable ignored) {}
        try { DirectMediaWatcher.start(getApplicationContext()); DirectMediaWatcher.scanPendingNow(getApplicationContext()); } catch (Throwable ignored) {}
        try { MediaStoreWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
        new Thread(()->{
            int found=0;
            try{
                Context app=getApplicationContext();VaultDb local=new VaultDb(app);
                for(VaultDb.PendingManualMedia x:local.listPendingManualMedia("",30)){
                    found+=MediaArchiver.scanRecentDownloadedMedia(app,0L,x.armedAt,x.type);
                }
                local.reconcileAllPendingMedia();
                MediaArchiver.adoptPersistedWhatsAppTree(app);
                if(MediaArchiver.hasVoiceBank(app))found+=MediaArchiver.scanVoiceBankFast(app,0L,System.currentTimeMillis());
            }catch(Throwable ignored){}
            final int n=found;postUiIfAlive(()->{
                Toast.makeText(this,n>0?("Recuperados: "+n):"Vigilancia activa · sin pendientes nuevos",Toast.LENGTH_SHORT).show();
                if(returnToMessages&&"messages".equals(currentScreen))scheduleVisibleDataRefresh("message");
                else if("recovered".equals(currentScreen))scheduleVisibleDataRefresh("media");
            });
        },"wa-vault-pending-refresh").start();
    }

    private void requestMediaPermissions(){
        if(Build.VERSION.SDK_INT>=33){
            ArrayList<String> missing = new ArrayList<>();
            if(checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.READ_MEDIA_IMAGES);
            if(checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.READ_MEDIA_VIDEO);
            if(checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.READ_MEDIA_AUDIO);
            if(Build.VERSION.SDK_INT>=34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
            if(!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]),MEDIA_REQ);
            else if(!notificationAccess()) openNotificationSettings();
        } else {
            ArrayList<String> missing=new ArrayList<>();
            if(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if(Build.VERSION.SDK_INT<=28&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),MEDIA_REQ);
            else if(!notificationAccess())openNotificationSettings();
        }
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==MEDIA_REQ) {
            Toast.makeText(this, hasAllMediaPermissions()?"Permisos de medios concedidos":"Algunos permisos de medios siguen desactivados",Toast.LENGTH_SHORT).show();
            continueSetupAfterPermissions();
        }
    }

    private void openNotificationSettings() {
        if (notificationAccess()) return;
        openedNotificationSettings = true;
        Toast.makeText(this,"Activa el interruptor de WA Vault y vuelve atrás",Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Throwable e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Throwable ignored) {}
        }
    }

    private void scanMediaAsync(boolean navigateToMedia){
        refreshEverything(false);
        if(navigateToMedia)showMedia();
    }
}

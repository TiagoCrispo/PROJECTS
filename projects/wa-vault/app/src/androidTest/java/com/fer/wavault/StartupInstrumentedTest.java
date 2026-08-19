package com.fer.wavault;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Device/emulator smoke tests. Keep them deterministic: no Settings UI should steal focus. */
@RunWith(AndroidJUnit4.class)
public final class StartupInstrumentedTest {

    private static Context app() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    }

    private static void suppressOneTimeSetupDiversions() {
        SharedPreferences p=app().getSharedPreferences("wa_vault_settings",Context.MODE_PRIVATE);
        p.edit()
                .putBoolean("media_permissions_prompted_v0530",true)
                .putBoolean("fast_storage_prompted_v034",true)
                .putBoolean("battery_exemption_prompted",true)
                .putBoolean("samsung_never_sleep_prompted_v034",true)
                .putBoolean("voice_bank_auto_picker_shown",true)
                .apply();
    }

    @Test public void cleanLaunchDoesNotCrashAndOpensDeletedMessages() {
        suppressOneTimeSetupDiversions();
        ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class);
        try {
            scenario.onActivity(StartupInstrumentedTest::assertDeletedMessagesVisible);
        } finally { scenario.close(); }
    }

    @Test public void activityRecreateStillOpensDeletedMessages() {
        suppressOneTimeSetupDiversions();
        ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class);
        try {
            scenario.onActivity(StartupInstrumentedTest::assertDeletedMessagesVisible);
            scenario.recreate();
            scenario.onActivity(StartupInstrumentedTest::assertDeletedMessagesVisible);
        } finally { scenario.close(); }
    }

    @Test public void notificationListenerManifestContractIsPrivateAndPermissionProtected() throws Exception {
        PackageManager pm=app().getPackageManager();
        ServiceInfo info=pm.getServiceInfo(new ComponentName(app(),WhatsAppNotificationListener.class),0);
        assertNotNull(info);
        assertFalse(info.exported);
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",info.permission);
    }


    @Test public void android16AndPermissionSurfaceMatchesSecurityContract() throws Exception {
        PackageManager pm=app().getPackageManager();
        ApplicationInfo ai=pm.getApplicationInfo(app().getPackageName(),0);
        assertEquals(36,ai.targetSdkVersion);
        assertEquals(0,ai.flags & ApplicationInfo.FLAG_ALLOW_BACKUP);

        PackageInfo pi=pm.getPackageInfo(app().getPackageName(),PackageManager.GET_PERMISSIONS);
        Set<String> requested=new HashSet<>(Arrays.asList(pi.requestedPermissions==null?new String[0]:pi.requestedPermissions));
        assertTrue(requested.contains("android.permission.USE_BIOMETRIC"));
        assertTrue(requested.contains("android.permission.MANAGE_EXTERNAL_STORAGE"));
        assertTrue(requested.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"));
        assertFalse(requested.contains("android.permission.INTERNET"));
        assertFalse(requested.contains("android.permission.POST_NOTIFICATIONS"));

        PermissionInfo internal=pm.getPermissionInfo(VaultUiNotifier.INTERNAL_PERMISSION,0);
        assertNotNull(internal);
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, internal.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
    }

    @Test public void android15PlusVaultRootIsScreenShareSensitive() {
        if(Build.VERSION.SDK_INT<35)return;
        suppressOneTimeSetupDiversions();
        ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class);
        try {
            scenario.onActivity(activity->{
                ViewGroup content=activity.findViewById(android.R.id.content);
                assertNotNull(content);assertTrue(content.getChildCount()>0);
                assertEquals(View.CONTENT_SENSITIVITY_SENSITIVE,content.getChildAt(0).getContentSensitivity());
            });
        } finally { scenario.close(); }
    }
    @Test public void bootReceiverAndShareProviderArePrivate() throws Exception {
        PackageManager pm=app().getPackageManager();
        ActivityInfo receiver=pm.getReceiverInfo(new ComponentName(app(),BootReceiver.class),0);
        assertNotNull(receiver);assertFalse(receiver.exported);
        ProviderInfo provider=pm.getProviderInfo(new ComponentName(app(),VaultShareProvider.class),0);
        assertNotNull(provider);assertFalse(provider.exported);assertTrue(provider.grantUriPermissions);
    }

    private static void assertDeletedMessagesVisible(MainActivity activity) {
        assertNotNull(activity);
        AtomicBoolean found=new AtomicBoolean(false);
        walk(activity.getWindow().getDecorView(),found);
        assertTrue("Expected launch screen title Mensajes borrados",found.get());
    }

    private static void walk(View view, AtomicBoolean found) {
        if(found.get()||view==null)return;
        if(view instanceof TextView){CharSequence text=((TextView)view).getText();if(text!=null&&"Mensajes borrados".contentEquals(text)){found.set(true);return;}}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)walk(group.getChildAt(i),found);}
    }
}

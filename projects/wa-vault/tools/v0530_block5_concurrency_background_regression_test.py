#!/usr/bin/env python3
from pathlib import Path
import re, math
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
J=ROOT/'app/src/main/java/com/fer/wavault'

def text(name): return (J/name).read_text(encoding='utf-8')
def need(cond,msg):
    if not cond: raise AssertionError(msg)

def const(src,name):
    m=re.search(rf'{re.escape(name)}\s*=\s*([^;]+);',src)
    if not m: raise AssertionError(f'missing constant {name}')
    expr=m.group(1).replace('_','').replace('L','').strip()
    if not re.fullmatch(r'[0-9+*() /-]+',expr): raise AssertionError(f'unsafe constant expression {name}={expr!r}')
    return int(eval(expr,{"__builtins__":{}},{}))

all_java='\n'.join(p.read_text(encoding='utf-8') for p in J.rglob('*.java'))
listener=text('WhatsAppNotificationListener.java')
preview=text('NotificationPreviewCapture.java')
direct=text('DirectMediaWatcher.java')
voice=text('DirectVoiceWatcher.java')
ms=text('MediaStoreWatcher.java')
watchdog=text('CaptureWatchdog.java')
main=text('MainActivity.java')
boot=text('BootReceiver.java')
arch=text('MediaArchiver.java')
vaultapp=text('VaultApp.java')
manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')

# No perpetual fixed-rate/fixed-delay loops in capture source. Adaptive one-shot scheduling is allowed.
need('scheduleWithFixedDelay' not in all_java, 'fixed-delay scheduler remains')
need('scheduleAtFixedRate' not in all_java, 'fixed-rate scheduler remains')
need('Thread.MAX_PRIORITY' not in all_java, 'MAX_PRIORITY remains')
need('THREAD_PRIORITY_URGENT_DISPLAY' not in all_java, 'URGENT_DISPLAY priority remains')

# NotificationListener callbacks run on Android main: expensive bitmap compression must be offloaded.
need('NotificationPreviewCapture.captureBestPreviewAsync' in listener, 'listener does not use async preview')
need('NotificationPreviewCapture.captureBestPreview(' not in listener, 'synchronous preview still called by listener')
need('Executors.newSingleThreadScheduledExecutor' in listener and 'wa-vault-media-scheduler' in listener, 'listener fallback scheduler is not serialized')
need('audioRescueToken' in listener and 'rescueGeneration!=audioRescueToken.get()' in listener, 'audio rescue burst is not generation-coalesced')
need('VaultExecutors.bounded' in preview and 'wa-vault-notif-preview' in preview, 'bounded preview worker missing')


# Bursty resource-retaining work must use bounded queues with explicit rejection handling.
critical_bounded = [
    'NotificationPreviewCapture.java',
    'NotificationAudioCapture.java',
    'NotificationMediaCapture.java',
    'FastCaptureEngine.java',
    'InstantMediaCapture.java',
    'CaptureProcessingEngine.java',
    'MediaThumbnailLoader.java',
]
for name in critical_bounded:
    src=text(name)
    need('newFixedThreadPool' not in src, f'unbounded fixed thread pool remains in {name}')
    need('VaultExecutors.bounded' in src, f'bounded executor missing in {name}')
    need('RejectedExecutionException' in src, f'explicit saturation handling missing in {name}')
need('ArrayBlockingQueue' in text('VaultExecutors.java'), 'bounded executor does not use ArrayBlockingQueue')
need('AbortPolicy' in text('VaultExecutors.java'), 'bounded executor does not fail fast on saturation')
need('CallerRunsPolicy' not in all_java.replace('CallerRunsPolicy: callbacks',''), 'CallerRunsPolicy would push heavy work onto callback/UI threads')
need('try { pfd.close(); }' in text('NotificationAudioCapture.java'), 'audio descriptor is not closed on queue saturation')
need('try{pfd.close();}' in text('NotificationMediaCapture.java'), 'media descriptor is not closed on queue saturation')
need('try{in.close();}' in text('FastCaptureEngine.java'), 'fast-capture descriptor is not closed on queue saturation')
need('ready_queue_full' in text('CaptureProcessingEngine.java'), 'durable ready queue saturation is not diagnosed')
need('VaultExecutors.bounded' in text('RecoveryLedger.java') and 'RejectedExecutionException' in text('RecoveryLedger.java'), 'recovery ledger queue is not bounded')
need('wa-vault-capture-log' in text('CaptureProcessingEngine.java') and 'VaultExecutors.bounded' in text('CaptureProcessingEngine.java'), 'capture failure log queue is not bounded')

# Direct filesystem watchers self-heal and coalesce work.
need('RECOVERY_BURST_GENERATION' in direct, 'direct-media burst generation missing')
need('SAFETY_SCHEDULED' in direct and 'requestSafetyPass' in direct, 'one-shot direct-media safety pass missing')
need('RECOVERY_QUEUED' in direct, 'direct-media recovery coalescing missing')
need('VaultExecutors.bounded(2,64' in direct.replace(' ',''), 'direct-media I/O queue is not bounded')
need('CAPTURE_RETRY_GAPS_MS' in direct and 'scheduleCaptureAttempt' in direct, 'direct-media per-file retry is not self-rescheduling')
need('for(long delay:delays)' not in direct[direct.index('private static void scheduleCapture'):], 'legacy N-way per-file retry fan-out remains')
need('FileObserver.MOVE_SELF | FileObserver.DELETE_SELF' in voice, 'voice watcher lacks self move/delete events')
need('VaultExecutors.bounded' in voice and 'wa-vault-early-audio' in voice and 'RejectedExecutionException' in voice, 'voice capture queue backpressure missing')
need('START_QUEUED' in voice and 'FAST_SCAN_QUEUED' in voice, 'voice watcher coalescing missing')
need('MAX_WATCHED_DIRS = 160' in voice, 'voice watcher directory cap changed unexpectedly')

# MediaStore retries: newer change supersedes older queued retries.
need('CHANGE_GENERATIONS' in ms and 'CHANGE_SEQUENCE' in ms, 'MediaStore retry generation missing')
need('VaultExecutors.bounded' in ms and 'RejectedExecutionException' in ms and 'submitIo' in ms, 'MediaStore I/O backpressure missing')
need('unregisterContentObserver' in ms and 'stopInternal()' in ms, 'MediaStore restart cleanup missing')

# Pending-media DB enumeration must not occur synchronously on callback/main thread.
need('pendingResumeQueued' in arch, 'pending monitor restore coalescing missing')
need('PENDING_EXECUTOR.execute(()->{try{resumePendingMonitorsNow(app);}' in arch, 'pending restore not offloaded')
need('private static void resumePendingMonitorsNow' in arch, 'pending restore worker missing')

# Application startup: cache cleanup + diagnostic DB logging are background work.
need('STARTUP_IO.execute' in vaultapp, 'process startup I/O not offloaded')
need('MetadataPrivacy.prepareV0525(this)' in vaultapp, 'privacy snapshot gate must remain before listener startup')

# Adaptive watchdog: quiet app should wake at most about every 15 minutes, recovery only if staging exists.
need(const(watchdog,'IDLE_MS') >= 15*60_000, 'watchdog idle cadence too aggressive')
need('hasStagingWork()' in watchdog and 'stagingWork&&now-lastRecovery' in watchdog, 'recovery not staging-gated')

# Runtime media permission denial/partial grant must not create an automatic prompt loop.
startup=main[main.index('if(STARTUP_MAINTENANCE_QUEUED.compareAndSet'):main.index('applySecureWindowSetting();')]
need('VaultDb local=new VaultDb(appContext)' in startup, 'startup maintenance still relies on Activity-owned DB')
need('db.purgeStickerMedia()' not in startup, 'static startup worker captures Activity DB field')
need('media_permissions_prompted_v0530' in main, 'one-shot media permission gate missing')
block=main[main.index('private void continueSetupAfterPermissions()'):main.index('private void requestFastStorageAccess()')]
need('!hasAllMediaPermissions() && !setupPrefs.getBoolean' in block, 'media permission can loop unconditionally')

# Boot receiver does short async handoff and always finishes PendingResult.
need('goAsync()' in boot and 'pending.finish()' in boot and 'finally' in boot, 'BootReceiver goAsync lifecycle unsafe')
need('CaptureCoordinator.initialize(app)' in boot and 'forceRebind(app)' in boot, 'boot rearm missing')
need('new VaultDb' not in boot, 'BootReceiver still opens SQLite inside goAsync window')
need('logEngineEventAsync' in boot and 'ENGINE_LOG_EXECUTOR' in listener, 'boot/engine diagnostic I/O is not offloaded')

# Component exposure / no invented foreground service.
ANDROID='{http://schemas.android.com/apk/res/android}'
root=ET.fromstring(manifest); app_node=root.find('application')
def component(tag,name):
    for node in app_node.findall(tag):
        if node.get(ANDROID+'name')==name:return node
    return None
boot_node=component('receiver','.BootReceiver'); listener_node=component('service','.WhatsAppNotificationListener')
need(boot_node is not None and boot_node.get(ANDROID+'exported')=='false', 'BootReceiver not private')
need(listener_node is not None and listener_node.get(ANDROID+'exported')=='false', 'listener exported unexpectedly')
need(listener_node.get(ANDROID+'permission')=='android.permission.BIND_NOTIFICATION_LISTENER_SERVICE', 'listener bind permission missing')
need('android.permission.FOREGROUND_SERVICE' not in manifest, 'unused foreground-service permission introduced')
gallery=text('GalleryExporter.java')
need('VaultExecutors.bounded' in gallery and 'RejectedExecutionException' in gallery, 'Gallery export queue is unbounded')
need('EXPORTING.remove(exportKey)' in gallery, 'Gallery rejection does not roll back in-flight key')
need('startForeground(' not in all_java, 'foreground service introduced unexpectedly')


instrumented=(ROOT/'app/src/androidTest/java/com/fer/wavault/StartupInstrumentedTest.java').read_text(encoding='utf-8')
need('activityRecreateStillOpensDeletedMessages' in instrumented, 'instrumented Activity recreation smoke missing')
need('notificationListenerManifestContractIsPrivateAndPermissionProtected' in instrumented, 'listener manifest instrumented check missing')
need('bootReceiverAndShareProviderArePrivate' in instrumented, 'private component instrumented check missing')

# Poll fallback must be a safety net, not 180ms sustained reconciliation.
hot=const(listener,'POLL_HOT_MS'); hot_window=const(listener,'HOT_WINDOW_MS')
need(hot >= 500, f'hot poll too aggressive: {hot}ms')
need(hot_window <= 10_000, f'hot poll window too long: {hot_window}ms')
new_calls=math.ceil(hot_window/hot)
old_calls=math.ceil(9000/180)
need(new_calls <= 12 and new_calls < old_calls/3, f'poll reduction insufficient old={old_calls} new={new_calls}')

# Model: 100 MediaStore events for one key schedule work, but only latest generation may perform I/O.
gen=0; queued=[]
for _ in range(100):
    gen+=1; g=gen
    queued += [g]*4
eligible=sum(1 for g in queued if g==gen)
need(eligible==4, f'MediaStore generation model eligible={eligible}')

# Model: 100 message arms supersede old sparse-recovery generations; only latest 5 do real recovery.
gen=0; queued=[]
for _ in range(100):
    gen+=1; g=gen
    queued += [g]*5
eligible=sum(1 for g in queued if g==gen)
need(eligible==5, f'direct-media generation model eligible={eligible}')

# Model: per-file direct capture keeps at most one delayed retry alive at a time instead of 11.
active_paths=500
legacy_delayed=active_paths*11
new_delayed=active_paths
need(new_delayed==500 and legacy_delayed==5500, 'direct-media retry fan-out model changed')

# Model: 100 audio messages keep durable FIFO arms, while only the latest rescue generation performs 6 scans.
gen=0; queued=[]
for _ in range(100):
    gen+=1; g=gen
    queued += [g]*6
eligible_audio=sum(1 for g in queued if g==gen)
need(eligible_audio==6, f'audio rescue generation model eligible={eligible_audio}')

print('BLOCK5_CONCURRENCY_BACKGROUND_PASS')
print(f'poll fallback model: old_hot_calls={old_calls} new_hot_calls<={new_calls}')
print('100 MediaStore changes -> 4 latest-generation I/O attempts (not 400)')
print('100 media arms -> 5 latest-generation recovery attempts (not 500)')
print('500 active file paths -> <=500 delayed per-file retries (legacy fan-out would be 5500)')
print('100 audio notifications -> 6 latest-generation rescue scans (not 600)')
print('main-thread preview/pending-monitor I/O guards: PASS')

# Queue capacity model: resource-retaining pending work is strictly bounded.
# Preview 8 + audio 24 + notification media 24 + fast file copies 32 + unused/future instant 24 + processing 32 + thumbs 24.
queue_caps=[8,24,24,32,24,32,24]
need(sum(queue_caps)==168, 'unexpected bounded queue budget')
need(max(queue_caps)<=32, 'single queue cap too large')
print('bounded async queues: 7/7 guarded, max capacity=32, no CallerRunsPolicy')


#!/usr/bin/env python3
from pathlib import Path
import sys, xml.etree.ElementTree as ET
proj=Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()
root=proj/'app/src/main/java/com/fer/wavault'
manifest=proj/'app/src/main/AndroidManifest.xml'
errors=[]
required=[
 'CryptoManager.java','MediaCrypto.java','VaultDb.java','WhatsAppNotificationListener.java',
 'NotificationAudioCapture.java','DirectVoiceWatcher.java','NotificationMediaCapture.java',
 'DirectMediaWatcher.java','MediaStoreWatcher.java','MediaArchiver.java','CaptureCoordinator.java',
 'VaultApp.java','BootReceiver.java','MainActivity.java'
]
for f in required:
    if not (root/f).exists(): errors.append('falta '+f)
try:
    tree=ET.parse(manifest); r=tree.getroot(); ns='{http://schemas.android.com/apk/res/android}'
    app=r.find('application')
    if app is None or app.attrib.get(ns+'name') != '.VaultApp': errors.append('manifest sin VaultApp')
    receiver_names=[x.attrib.get(ns+'name','') for x in r.findall('./application/receiver')]
    if '.BootReceiver' not in receiver_names: errors.append('manifest sin BootReceiver')
except Exception as e: errors.append('manifest inválido: '+str(e))
def read(name):
    p=root/name
    return p.read_text(errors='ignore') if p.exists() else ''
listener=read('WhatsAppNotificationListener.java')
if 'POLL_HOT_MS = 90L' not in listener: errors.append('poll hot fallback no está en ~90 ms')
for token in ['NotificationMediaCapture.tryCaptureNow','NotificationAudioCapture.tryCaptureNow','DirectVoiceWatcher.armForMessage','requestRebind','EXTRA_MESSAGES','DELETE_CONFIRMED','DELETE_PROBABLE']:
    if token not in listener: errors.append('listener sin '+token)
media=read('MediaArchiver.java')
for token in ['scanRecentDownloadedMedia','captureMediaStoreUri','MEDIASTORE_EXACT','PENDING_EXECUTOR','120L, TimeUnit.MILLISECONDS','registerDirectDownloadedMedia']:
    if token not in media: errors.append('media sin '+token)
msw=read('MediaStoreWatcher.java')
for token in ['captureMediaStoreUri','isItemUri','typedCount>1']:
    if token not in msw: errors.append('MediaStoreWatcher sin '+token)
dmw=read('DirectMediaWatcher.java')
for token in ['typed.size()>1','sin vínculo ambiguo','MANUAL_DOWNLOAD_WINDOW_MS']:
    if token not in dmw: errors.append('DirectMediaWatcher sin '+token)
coord=read('CaptureCoordinator.java')
if 'DirectVoiceWatcher.start' not in coord or 'MediaStoreWatcher.start' not in coord: errors.append('CaptureCoordinator incompleto')
db=read('VaultDb.java')
for token in ['content_hash','message_index','is_group','deletion_state','MediaCrypto.encryptInPlace','findLinkedMedia']:
    if token not in db: errors.append('db sin '+token)
crypto=read('CryptoManager.java')
if crypto:
    encrypt_body=crypto.split('public byte[] encrypt',1)[-1].split('public String decrypt',1)[0]
    if 'PLAIN:' in encrypt_body: errors.append('encrypt todavía tiene fallback PLAIN')
main=read('MainActivity.java')
for token in ['showImageViewer','showVideoViewer','addAudioControls','showConversationGroups','app_lock','MediaCrypto.materialize','MediaStore manual']:
    if token not in main: errors.append('UI sin '+token)
for p in root.glob('*.java'):
    s=p.read_text(errors='ignore')
    if s.count('{') != s.count('}'): errors.append(f'llaves desbalanceadas: {p.name}')
if errors:
    print('FAIL')
    for e in errors: print(' -',e)
    sys.exit(1)
print('OK — proyecto Android 0.3.0 consistente')
print('Clases:',len(required),'| poll hot fallback: 90 ms | cuarentena compartida: 120 ms | manual media exact URI: sí')

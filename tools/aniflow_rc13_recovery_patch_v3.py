#!/usr/bin/env python3
from pathlib import Path
import sys

root_arg = sys.argv[1] if len(sys.argv) > 1 else None
if not root_arg:
    raise SystemExit('RC13_V3_FAIL: target root missing')

# Reuse every tested RC13 V2 transform, then tighten only the MIME/media guard.
v2 = Path(__file__).with_name('aniflow_rc13_recovery_patch_v2.py')
sys.argv = [str(v2), root_arg]
namespace = {'__name__': '__main__', '__file__': str(v2)}
exec(compile(v2.read_text(), str(v2), 'exec'), namespace, namespace)

root = Path(root_arg)
block8 = root / 'scripts/validate-block8.py'
text = block8.read_text()
old = 'req("text/html" in resolver.lower(), "HTML response classification missing")'
new = '''for token in [
    "detectResponseType(contentType: String?",
    "application/vnd.apple.mpegurl",
    "application/dash+xml",
    'mime.startsWith("video/")',
    "else -> null",
]:
    req(token in resolver, f"direct-media MIME whitelist missing: {token}")'''
if old not in text:
    raise SystemExit('RC13_V3_FAIL: expected brittle Block8 MIME guard not found')
text = text.replace(old, new, 1)
block8.write_text(text)

resolver = (root / 'app/src/main/java/com/aniflow/app/data/repository/RemotePlaybackResolver.kt').read_text()
for token in [
    'application/vnd.apple.mpegurl',
    'application/dash+xml',
    'mime.startsWith("video/")',
    'else -> null',
]:
    if token not in resolver:
        raise SystemExit('RC13_V3_FAIL: resolver media whitelist missing: ' + token)

# Block 14 was introduced by RC10 and still asserted that exact historical version.
# Keep every latency/quality assertion, but require the recovered RC13 metadata instead.
block14 = root / 'scripts/validate-block14.py'
text14 = block14.read_text()
old_version = 'versionName = "1.0.9-rc10"'
new_version = 'versionName = "1.0.12-rc13"'
if old_version not in text14:
    raise SystemExit('RC13_V3_FAIL: expected RC10 Block14 version guard not found')
text14 = text14.replace(old_version, new_version, 1)
text14 = text14.replace('rc10 version', 'recovered rc13 version')
block14.write_text(text14)

build = (root / 'app/build.gradle.kts').read_text()
if new_version not in build:
    raise SystemExit('RC13_V3_FAIL: recovered RC13 version metadata missing')

print('ANIFLOW_RC13_RECOVERY_V3_OK')

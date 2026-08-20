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

print('ANIFLOW_RC13_RECOVERY_V3_OK')

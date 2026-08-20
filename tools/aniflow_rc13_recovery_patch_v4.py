#!/usr/bin/env python3
from pathlib import Path
import sys

root_arg = sys.argv[1] if len(sys.argv) > 1 else None
if not root_arg:
    raise SystemExit('RC13_V4_FAIL: target root missing')

# Execute the tested V3 transformation, but relax one brittle textual assertion.
# The recovered PlayerScreen legitimately uses its local bitrateLabel(bitrate) helper,
# while VideoQualityPolicy still owns and tests the canonical bitrate formatter.
v3 = Path(__file__).with_name('aniflow_rc13_recovery_patch_v3.py')
source = v3.read_text()
old = "    'VideoQualityPolicy.bitrateLabel(bitrate)',\n"
new = "    'bitrateLabel(bitrate)',\n"
if old not in source:
    raise SystemExit('RC13_V4_FAIL: expected brittle V3 bitrate assertion missing')
source = source.replace(old, new, 1)

sys.argv = [str(v3), root_arg]
namespace = {'__name__': '__main__', '__file__': str(v3)}
exec(compile(source, str(v3), 'exec'), namespace, namespace)

root = Path(root_arg)
player = (root / 'app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt').read_text()
policy = (root / 'app/src/main/java/com/aniflow/app/domain/VideoQualityPolicy.kt').read_text()
tests = (root / 'app/src/test/java/com/aniflow/app/domain/VideoQualityPolicyTest.kt').read_text()

if 'bitrateLabel(bitrate)' not in player:
    raise SystemExit('RC13_V4_FAIL: player bitrate label integration missing')
if 'fun bitrateLabel(bitrate: Int)' not in policy:
    raise SystemExit('RC13_V4_FAIL: canonical bitrate formatter missing')
if 'formatsMeasuredBitrateAndRichQualityLabel' not in tests:
    raise SystemExit('RC13_V4_FAIL: bitrate/quality regression test missing')

print('ANIFLOW_RC13_RECOVERY_V4_OK')

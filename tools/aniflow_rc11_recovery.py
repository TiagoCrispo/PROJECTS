#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
policy = root / 'app/src/main/java/com/aniflow/app/domain/VideoQualityPolicy.kt'
policy.write_text('''package com.aniflow.app.domain

object VideoQualityPolicy {
    const val AUTO = 0
    const val P1080 = 1080
    const val P720 = 720
    const val P480 = 480
    val manualHeights = listOf(P1080, P720, P480)

    fun preferredMaxHeight(requestedHeight: Int): Int = when {
        requestedHeight <= 0 -> P1080
        requestedHeight >= P1080 -> P1080
        requestedHeight >= P720 -> P720
        else -> P480
    }

    fun labelForActualHeight(height: Int): String = when {
        height >= 2160 -> "2160p"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        height > 0 -> "${height}p"
        else -> "Auto"
    }
}
''')

test = root / 'app/src/test/java/com/aniflow/app/domain/VideoQualityPolicyTest.kt'
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text('''package com.aniflow.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQualityPolicyTest {
    @Test fun autoCapsAt1080ByDefault() {
        assertEquals(1080, VideoQualityPolicy.preferredMaxHeight(VideoQualityPolicy.AUTO))
    }

    @Test fun clampsManualRequestsToSupportedLevels() {
        assertEquals(1080, VideoQualityPolicy.preferredMaxHeight(1440))
        assertEquals(720, VideoQualityPolicy.preferredMaxHeight(900))
        assertEquals(480, VideoQualityPolicy.preferredMaxHeight(360))
    }

    @Test fun reportsActualResolutionWithoutPretending() {
        assertEquals("1080p", VideoQualityPolicy.labelForActualHeight(1080))
        assertEquals("720p", VideoQualityPolicy.labelForActualHeight(720))
        assertEquals("Auto", VideoQualityPolicy.labelForActualHeight(0))
    }
}
''')

player = root / 'app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt'
source = player.read_text()
helper = '''
private fun applyAniFlowVideoQuality(player: ExoPlayer, preferredHeight: Int) {
    val maxHeight = com.aniflow.app.domain.VideoQualityPolicy.preferredMaxHeight(preferredHeight)
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
        .setForceHighestSupportedBitrate(true)
        .build()
}
'''
if 'private fun applyAniFlowVideoQuality(' not in source:
    source += helper

if 'applyAniFlowVideoQuality(player, state.preferredVideoHeight)' not in source.replace(helper, ''):
    anchor = '    var isPlaying by remember { mutableStateOf(false) }\n'
    if anchor not in source:
        raise SystemExit('RC11_RECOVERY_FAIL: player state anchor missing')
    source = source.replace(anchor, anchor + '''
    LaunchedEffect(player, state.preferredVideoHeight) {
        applyAniFlowVideoQuality(player, state.preferredVideoHeight)
    }
''', 1)
player.write_text(source)

build = root / 'app/build.gradle.kts'
b = build.read_text()
b = re.sub(r'(?m)^(\s*)versionCode\s*=\s*\d+\s*$', r'\1versionCode = 1000011', b)
b = re.sub(r'(?m)^(\s*)versionName\s*=\s*"[^"]+"\s*$', r'\1versionName = "1.0.10-rc11"', b)
build.write_text(b)

validator = root / 'scripts/validate-block15.py'
validator.write_text('''#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PLAYER=(ROOT/"app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt").read_text()
POLICY=(ROOT/"app/src/main/java/com/aniflow/app/domain/VideoQualityPolicy.kt").read_text()

def req(ok,msg):
    if not ok: raise SystemExit("BLOCK15_FAIL: "+msg)
req("setMaxVideoSize(Int.MAX_VALUE, maxHeight)" in PLAYER, "Media3 1080p cap missing")
req("setForceHighestSupportedBitrate(true)" in PLAYER, "highest bitrate preference missing")
req("state.preferredVideoHeight" in PLAYER, "quality preference not wired")
req("P1080 = 1080" in POLICY, "1080p policy missing")
req("labelForActualHeight" in POLICY, "actual resolution labels missing")
print("BLOCK15_1080P_INTELLIGENCE_OK")
''')
validator.chmod(0o755)
print('ANIFLOW_RC11_RECOVERY_OK')

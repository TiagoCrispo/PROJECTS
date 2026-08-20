#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root_arg = sys.argv[1] if len(sys.argv) > 1 else None
if not root_arg:
    raise SystemExit('RC13_V3_FAIL: target root missing')

# Reuse every tested RC13 V2 transform, then fix the integration seams exposed
# by the first real Android/Kotlin compilation of the recovered source.
v2 = Path(__file__).with_name('aniflow_rc13_recovery_patch_v2.py')
sys.argv = [str(v2), root_arg]
namespace = {'__name__': '__main__', '__file__': str(v2)}
exec(compile(v2.read_text(), str(v2), 'exec'), namespace, namespace)

root = Path(root_arg)

# Block 8: native playback accepts only actual media response types.
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
# Keep every latency/quality assertion, but require the recovered RC13 metadata.
block14 = root / 'scripts/validate-block14.py'
text14 = block14.read_text()
old_version = 'versionName = "1.0.9-rc10"'
new_version = 'versionName = "1.0.12-rc13"'
if old_version not in text14:
    raise SystemExit('RC13_V3_FAIL: expected RC10 Block14 version guard not found')
text14 = text14.replace(old_version, new_version, 1)
text14 = text14.replace('rc10 version', 'recovered rc13 version')
old_actual_format = ' "actual format":(\'app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt\',\'videoFormat\'),'
new_actual_format = ' "selected stream telemetry":(\'app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt\',\'actualVideoBitrate = measured?.bitrate\'),'
if old_actual_format not in text14:
    raise SystemExit('RC13_V3_FAIL: expected old Block14 actual-format guard not found')
text14 = text14.replace(old_actual_format, new_actual_format, 1)
block14.write_text(text14)

build = (root / 'app/build.gradle.kts').read_text()
if new_version not in build:
    raise SystemExit('RC13_V3_FAIL: recovered RC13 version metadata missing')

# RC11 replaced VideoQualityPolicy wholesale, while RC10 PlayerScreen still called
# adaptiveMaxHeight/codecLabel/qualityLabel/bitrateLabel. Merge both contracts.
policy = root / 'app/src/main/java/com/aniflow/app/domain/VideoQualityPolicy.kt'
policy.write_text('''package com.aniflow.app.domain

import java.util.Locale

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

    fun adaptiveMaxHeight(requestedHeight: Int): Int = preferredMaxHeight(requestedHeight)

    fun labelForActualHeight(height: Int): String = when {
        height >= 2160 -> "2160p"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        height > 0 -> "${height}p"
        else -> "Auto"
    }

    fun codecLabel(codecs: String?, sampleMimeType: String?): String? {
        val codecText = codecs?.trim()?.lowercase().orEmpty()
        val mime = sampleMimeType?.trim()?.lowercase().orEmpty()
        return when {
            codecText.contains("avc1") || codecText.contains("avc3") || mime == "video/avc" -> "H.264"
            codecText.contains("hev1") || codecText.contains("hvc1") || mime == "video/hevc" -> "HEVC"
            codecText.contains("av01") || mime == "video/av01" -> "AV1"
            codecText.contains("vp09") || codecText.contains("vp9") || mime == "video/x-vnd.on2.vp9" -> "VP9"
            codecText.contains("mp4a") || mime == "audio/mp4a-latm" -> "AAC"
            mime == "audio/opus" -> "Opus"
            mime == "audio/ac3" -> "AC-3"
            mime == "audio/eac3" -> "E-AC-3"
            codecText.isNotBlank() -> codecs?.trim()
            mime.isNotBlank() -> sampleMimeType?.substringAfter('/')?.uppercase(Locale.US)
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    fun bitrateLabel(bitrate: Int): String? = bitrate.takeIf { it > 0 }?.let {
        if (it >= 1_000_000) String.format(Locale.US, "%.1f Mbps", it / 1_000_000f)
        else "${it / 1000} kbps"
    }

    fun qualityLabel(height: Int, bitrate: Int, codec: String?, hdr: Boolean): String =
        listOfNotNull(
            labelForActualHeight(height),
            bitrateLabel(bitrate),
            codec?.takeIf { it.isNotBlank() },
            "HDR".takeIf { hdr },
        ).joinToString(" · ")
}
''')

# PLAYER-X10 now owns measured width/height/bitrate/codec. Remove the obsolete
# actualVideoDetail state instead of threading a stale derived string into PlayerControls.
player_path = root / 'app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt'
player = player_path.read_text()
old_display = 'actualVideoDetail.ifBlank { actualResolutionLabel(actualVideoWidth, actualVideoHeight) }'
new_display = 'actualStreamLabel(actualVideoWidth, actualVideoHeight, actualVideoBitrate, actualVideoCodec)'
if old_display not in player:
    raise SystemExit('RC13_V3_FAIL: expected out-of-scope actualVideoDetail display not found')
player = player.replace(old_display, new_display, 1)
player = player.replace('    var actualVideoDetail by remember { mutableStateOf("") }\n', '', 1)
player = re.sub(r'(?m)^\s*actualVideoDetail = player\.currentVideoDetail\(\)\s*\n', '', player)
player = player.replace('        actualVideoDetail = ""\n', '', 1)
player = re.sub(
    r'\nprivate fun ExoPlayer\.currentVideoDetail\(\): String \{.*?\n\}\n\n(?=private fun ExoPlayer\.trackChoices)',
    '\n',
    player,
    count=1,
    flags=re.S,
)
if 'actualVideoDetail' in player:
    raise SystemExit('RC13_V3_FAIL: obsolete actualVideoDetail state still present')
if 'currentVideoDetail()' in player:
    raise SystemExit('RC13_V3_FAIL: obsolete currentVideoDetail helper still present')
for token in [
    new_display,
    'VideoQualityPolicy.codecLabel(format.codecs, format.sampleMimeType)',
    'VideoQualityPolicy.qualityLabel(height, bitrate, codec, hdr)',
    'VideoQualityPolicy.adaptiveMaxHeight(preferredHeight)',
    'VideoQualityPolicy.bitrateLabel(bitrate)',
]:
    if token not in player:
        raise SystemExit('RC13_V3_FAIL: merged player quality contract missing: ' + token)
player_path.write_text(player)

# Extend the static PLAYER-X10 gate with the exact call that previously failed Kotlin scope checks.
block17 = root / 'scripts/validate-block17.py'
text17 = block17.read_text()
anchor17 = 'req("actualVideoBitrate" in PLAYER and "actualVideoCodec" in PLAYER, "real stream telemetry missing")\n'
extra17 = 'req("actualStreamLabel(actualVideoWidth, actualVideoHeight, actualVideoBitrate, actualVideoCodec)" in PLAYER, "measured stream label not wired into controls")\n'
if anchor17 not in text17:
    raise SystemExit('RC13_V3_FAIL: Block17 telemetry anchor missing')
if extra17 not in text17:
    text17 = text17.replace(anchor17, anchor17 + extra17, 1)
block17.write_text(text17)

# Unit tests lock the merged RC10+RC11 quality contract so this integration seam cannot regress.
policy_test = root / 'app/src/test/java/com/aniflow/app/domain/VideoQualityPolicyTest.kt'
policy_test.write_text('''package com.aniflow.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQualityPolicyTest {
    @Test fun autoCapsAt1080ByDefault() {
        assertEquals(1080, VideoQualityPolicy.preferredMaxHeight(VideoQualityPolicy.AUTO))
        assertEquals(1080, VideoQualityPolicy.adaptiveMaxHeight(VideoQualityPolicy.AUTO))
    }

    @Test fun clampsManualRequestsToSupportedLevels() {
        assertEquals(1080, VideoQualityPolicy.preferredMaxHeight(1440))
        assertEquals(720, VideoQualityPolicy.preferredMaxHeight(900))
        assertEquals(480, VideoQualityPolicy.preferredMaxHeight(360))
        assertEquals(720, VideoQualityPolicy.adaptiveMaxHeight(720))
    }

    @Test fun reportsActualResolutionWithoutPretending() {
        assertEquals("1080p", VideoQualityPolicy.labelForActualHeight(1080))
        assertEquals("720p", VideoQualityPolicy.labelForActualHeight(720))
        assertEquals("Auto", VideoQualityPolicy.labelForActualHeight(0))
    }

    @Test fun recognizesCommonVideoCodecs() {
        assertEquals("H.264", VideoQualityPolicy.codecLabel("avc1.640028", "video/avc"))
        assertEquals("HEVC", VideoQualityPolicy.codecLabel("hvc1.1.6.L120", "video/hevc"))
        assertEquals("AV1", VideoQualityPolicy.codecLabel("av01.0.08M.08", "video/av01"))
        assertEquals("VP9", VideoQualityPolicy.codecLabel("vp09.00.51.08", "video/x-vnd.on2.vp9"))
    }

    @Test fun formatsMeasuredBitrateAndRichQualityLabel() {
        assertEquals("6.5 Mbps", VideoQualityPolicy.bitrateLabel(6_500_000))
        assertEquals("850 kbps", VideoQualityPolicy.bitrateLabel(850_000))
        assertEquals("1080p · 6.5 Mbps · HEVC · HDR", VideoQualityPolicy.qualityLabel(1080, 6_500_000, "HEVC", true))
    }
}
''')

print('ANIFLOW_RC13_RECOVERY_V3_OK')

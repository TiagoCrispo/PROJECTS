#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
player = root / 'app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt'
vm = root / 'app/src/main/java/com/aniflow/app/feature/player/PlayerViewModel.kt'
shell = root / 'app/src/main/java/com/aniflow/app/feature/shell/AniFlowApp.kt'
build = root / 'app/build.gradle.kts'


def req(ok, msg):
    if not ok:
        raise SystemExit('RC13_RECOVERY_FAIL: ' + msg)


def remove_function(source: str, function_name: str) -> str:
    m = re.search(r'(?m)^@Composable\s*\nprivate fun\s+' + re.escape(function_name) + r'\s*\(', source)
    if not m:
        return source
    opening = source.find('{', m.start())
    req(opening >= 0, f'opening brace for {function_name}')
    depth = 0
    state = 'code'
    quote = ''
    i = opening
    while i < len(source):
        c = source[i]
        n = source[i + 1] if i + 1 < len(source) else ''
        if state == 'code':
            if c == '/' and n == '/':
                state = 'line'; i += 2; continue
            if c == '/' and n == '*':
                state = 'block'; i += 2; continue
            if c in ('"', "'"):
                quote = c; state = 'string'; i += 1; continue
            if c == '{': depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    end = i + 1
                    while end < len(source) and source[end] in '\r\n': end += 1
                    return source[:m.start()] + source[end:]
            i += 1
        elif state == 'line':
            if c == '\n': state = 'code'
            i += 1
        elif state == 'block':
            if c == '*' and n == '/': state = 'code'; i += 2
            else: i += 1
        else:
            if c == '\\': i += 2; continue
            if c == quote: state = 'code'
            i += 1
    raise SystemExit(f'RC13_RECOVERY_FAIL: unmatched braces for {function_name}')

s = player.read_text()

# Hard native-only contract: remove the provider-page playback branch and implementation.
s = re.sub(r'\n\s*val activeEmbeddedProvider = state\.activeEmbeddedProvider\s*\n', '\n', s, count=1)
branch = re.compile(r'''\n\s*activeEmbeddedProvider != null -> EmbeddedProviderPlayer\(.*?\n\s*\)''', re.S)
s, branch_count = branch.subn('', s, count=1)
req(branch_count == 1 or 'EmbeddedProviderPlayer(' not in s, 'provider branch could not be removed')
s = remove_function(s, 'EmbeddedProviderPlayer')
s = re.sub(r'\n\s*onProviderFailed: \(\) -> Unit,', '', s)
s = re.sub(r'\n\s*onNextProvider: \(\) -> Unit,', '', s)
# Remove WebView-only imports physically.
s = '\n'.join(line for line in s.splitlines() if not line.startswith('import android.webkit.')) + '\n'

# Measured real stream telemetry.
anchor = '    var actualVideoHeight by remember { mutableIntStateOf(0) }\n'
req(anchor in s, 'actualVideoHeight state missing')
if 'var actualVideoBitrate by remember' not in s:
    s = s.replace(anchor, anchor + '    var actualVideoBitrate by remember { mutableIntStateOf(0) }\n    var actualVideoCodec by remember { mutableStateOf<String?>(null) }\n')
ended_anchor = '    var endedHandled by remember(state.mediaId, state.episode) { mutableStateOf(false) }\n'
req(ended_anchor in s, 'endedHandled state missing')
if 'var autoplayCountdown by remember' not in s:
    s = s.replace(ended_anchor, ended_anchor + '    var autoplayCountdown by remember(state.mediaId, state.episode) { mutableIntStateOf(0) }\n    var autoplayCancelled by remember(state.mediaId, state.episode) { mutableStateOf(false) }\n')

tracks_old = 'override fun onTracksChanged(tracks: Tracks) { tracksVersion++ }'
if tracks_old in s:
    s = s.replace(tracks_old, '''override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                val measured = selectedVideoStats(tracks)
                actualVideoBitrate = measured?.bitrate ?: 0
                actualVideoCodec = measured?.codec
            }''', 1)
req('actualVideoBitrate = measured?.bitrate' in s, 'track telemetry listener missing')

reset_old = '        actualVideoHeight = 0\n'
req(reset_old in s, 'video reset marker missing')
if '        actualVideoBitrate = 0\n' not in s:
    s = s.replace(reset_old, reset_old + '        actualVideoBitrate = 0\n        actualVideoCodec = null\n        autoplayCountdown = 0\n        autoplayCancelled = false\n', 1)

# Crunchy-like next episode countdown, rather than an invisible immediate jump.
old_autoplay = re.search(r'''    LaunchedEffect\(playbackState, state\.next, state\.autoplay\) \{.*?\n    \}\n''', s, re.S)
req(old_autoplay is not None, 'autoplay effect missing')
new_autoplay = '''    LaunchedEffect(playbackState, state.next, state.autoplay) {
        if (playbackState == Player.STATE_ENDED && !endedHandled) {
            endedHandled = true
            val safeDuration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: duration
            val endPosition = if (safeDuration > 0L) safeDuration else player.currentPosition.coerceAtLeast(0L)
            onAutoComplete(endPosition, safeDuration.coerceAtLeast(0L))
            val nextTarget = state.next
            if (state.autoplay && nextTarget != null) {
                autoplayCancelled = false
                for (second in 8 downTo 1) {
                    if (autoplayCancelled) break
                    autoplayCountdown = second
                    delay(1_000L)
                }
                if (!autoplayCancelled) onNext(nextTarget)
                autoplayCountdown = 0
            }
        }
    }
'''
s = s[:old_autoplay.start()] + new_autoplay + s[old_autoplay.end():]

# Feed stream telemetry into controls and settings.
marker = '                actualVideoHeight = actualVideoHeight,\n'
req(marker in s, 'controls video height argument missing')
s = s.replace(marker, marker + '                actualVideoBitrate = actualVideoBitrate,\n                actualVideoCodec = actualVideoCodec,\n', 1)
marker2 = '            actualVideoHeight = actualVideoHeight,\n            subtitlesEnabled = state.subtitlesEnabled,'
req(marker2 in s, 'settings video height argument missing')
s = s.replace(marker2, '            actualVideoHeight = actualVideoHeight,\n            actualVideoBitrate = actualVideoBitrate,\n            actualVideoCodec = actualVideoCodec,\n            subtitlesEnabled = state.subtitlesEnabled,', 1)

# Native autoplay overlay.
box_tail = '''        errorText?.let { message ->
            Text(
                message,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.75f)).padding(12.dp),
            )
        }
    }
'''
req(box_tail in s, 'player Box tail missing')
s = s.replace(box_tail, '''        errorText?.let { message ->
            Text(
                message,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.75f)).padding(12.dp),
            )
        }

        if (autoplayCountdown > 0 && state.next != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
                    .background(Color.Black.copy(alpha = 0.86f))
                    .padding(14.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Siguiente episodio en ${autoplayCountdown}s", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(state.next.compactLabel, color = AniMuted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { autoplayCancelled = true; autoplayCountdown = 0 }) { Text("Cancelar") }
                    Button(onClick = { autoplayCancelled = true; autoplayCountdown = 0; onNext(state.next) }) { Text("Ver ahora") }
                }
            }
        }
    }
''', 1)

# Extend control/settings contracts.
s = s.replace('    actualVideoHeight: Int,\n    onBack: () -> Unit,', '    actualVideoHeight: Int,\n    actualVideoBitrate: Int,\n    actualVideoCodec: String?,\n    onBack: () -> Unit,', 1)
s = s.replace('                    actualResolutionLabel(actualVideoWidth, actualVideoHeight),', '                    actualStreamLabel(actualVideoWidth, actualVideoHeight, actualVideoBitrate, actualVideoCodec),', 1)
s = s.replace('    actualVideoHeight: Int,\n    subtitlesEnabled: Boolean,', '    actualVideoHeight: Int,\n    actualVideoBitrate: Int,\n    actualVideoCodec: String?,\n    subtitlesEnabled: Boolean,', 1)
s = s.replace('                val realQuality = actualVideoHeight.takeIf { it > 0 }?.let { " · real ${it}p" }.orEmpty()\n', '                val realQuality = actualVideoHeight.takeIf { it > 0 }?.let { " · real " + actualStreamLabel(0, actualVideoHeight, actualVideoBitrate, actualVideoCodec) }.orEmpty()\n', 1)

# Quality/audio/text choices show real bitrate and codec when Media3 exposes them.
s = s.replace('            val candidate = QualityChoice("${height}p", height, bitrate, group.mediaTrackGroup, i)', '            val candidate = QualityChoice(formatQualityChoice(format), height, bitrate, group.mediaTrackGroup, i)')
old_label = '''            val language = format.language?.uppercase()
            val label = format.label?.takeIf { it.isNotBlank() }
                ?: language?.takeIf { it.isNotBlank() }
                ?: "Pista ${choices.size + 1}"
            choices += TrackChoice(label, group.mediaTrackGroup, i)'''
new_label = '''            val language = format.language?.uppercase()
            val baseLabel = format.label?.takeIf { it.isNotBlank() }
                ?: language?.takeIf { it.isNotBlank() }
                ?: "Pista ${choices.size + 1}"
            val label = listOfNotNull(baseLabel, codecLabel(format.sampleMimeType), bitrateLabel(format.bitrate)).joinToString(" · ")
            choices += TrackChoice(label, group.mediaTrackGroup, i)'''
req(old_label in s, 'track label block changed unexpectedly')
s = s.replace(old_label, new_label, 1)

if 'private data class VideoStats' not in s:
    idx = s.index('private data class QualityChoice')
    s = s[:idx] + 'private data class VideoStats(val bitrate: Int, val codec: String?)\n' + s[idx:]

helper_anchor = 'private fun actualResolutionLabel(width: Int, height: Int): String = when {'
req(helper_anchor in s, 'actualResolutionLabel helper missing')
helpers = '''private fun selectedVideoStats(tracks: Tracks): VideoStats? {
    var best: VideoStats? = null
    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSelected(i)) continue
            val format = group.getTrackFormat(i)
            val candidate = VideoStats(format.bitrate.coerceAtLeast(0), codecLabel(format.sampleMimeType))
            if (best == null || candidate.bitrate > (best?.bitrate ?: 0)) best = candidate
        }
    }
    return best
}

private fun codecLabel(mime: String?): String? = when (mime?.lowercase()) {
    "video/avc" -> "H.264"
    "video/hevc" -> "HEVC"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "audio/mp4a-latm" -> "AAC"
    "audio/opus" -> "Opus"
    "audio/ac3" -> "AC-3"
    "audio/eac3" -> "E-AC-3"
    else -> mime?.substringAfter('/')?.uppercase()?.takeIf { it.isNotBlank() }
}

private fun bitrateLabel(bitrate: Int): String? = bitrate.takeIf { it > 0 }?.let {
    if (it >= 1_000_000) String.format(java.util.Locale.US, "%.1f Mbps", it / 1_000_000f)
    else "${it / 1000} kbps"
}

private fun formatQualityChoice(format: androidx.media3.common.Format): String {
    val height = format.height.takeIf { it > 0 }?.let { "${it}p" } ?: "Vídeo"
    return listOfNotNull(height, bitrateLabel(format.bitrate), codecLabel(format.sampleMimeType)).joinToString(" · ")
}

private fun actualStreamLabel(width: Int, height: Int, bitrate: Int, codec: String?): String =
    listOfNotNull(actualResolutionLabel(width, height), bitrateLabel(bitrate), codec).joinToString(" · ")

'''
s = s.replace(helper_anchor, helpers + helper_anchor, 1)

# No web rendering survives in player source.
for forbidden in ['WebView(', 'WebViewClient', 'WebChromeClient', '.loadUrl(', 'evaluateJavascript(', 'import android.webkit.']:
    req(forbidden not in s, f'web playback still present: {forbidden}')
req('EmbeddedProviderPlayer(' not in s, 'embedded provider player still present')
player.write_text(s)

# Remove dead callback wiring from navigation shell.
sh = shell.read_text()
sh = re.sub(r'\n\s*onProviderFailed = playerVm::reportProviderFailure,', '', sh)
sh = re.sub(r'\n\s*onNextProvider = playerVm::selectNextProvider,', '', sh)
shell.write_text(sh)

# Provider metadata can remain diagnostic, but after direct sources are exhausted the UI stays native.
v = vm.read_text()
v = v.replace('"La fuente directa falló; probando proveedor integrado."', '"La fuente directa falló. Buscando otra fuente nativa…"')
v = v.replace('"Probando otro proveedor dentro de AniFlow…"', '"Buscando otra fuente nativa…"')
v = v.replace('"No encontré un stream directo ni un proveedor integrable para este episodio."', '"No encontré un stream nativo compatible para este episodio."')
vm.write_text(v)

# Final recovered release metadata.
b = build.read_text()
b = re.sub(r'(?m)^(\s*)versionCode\s*=\s*\d+\s*$', r'\1versionCode = 1000013', b)
b = re.sub(r'(?m)^(\s*)versionName\s*=\s*"[^"]+"\s*$', r'\1versionName = "1.0.12-rc13"', b)
build.write_text(b)

# Recreate the no-WebView gate that the broken RC12 chain never produced.
block16 = root / 'scripts/validate-block16.py'
block16.write_text('''#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/"app/src/main/java"
PLAYER=(SRC/"com/aniflow/app/feature/player/PlayerScreen.kt").read_text()

def req(ok,msg):
    if not ok: raise SystemExit("BLOCK16_FAIL: "+msg)
for path in SRC.rglob("*.kt"):
    text=path.read_text()
    for forbidden in ["WebView(", "WebViewClient", "WebChromeClient", ".loadUrl(", "evaluateJavascript("]:
        req(forbidden not in text, f"web playback remains: {forbidden} in {path}")
req("PlayerView" in PLAYER, "Media3 PlayerView missing")
req("setForceHighestSupportedBitrate(true)" in PLAYER, "1080p preference regressed")
print("BLOCK16_NO_WEBVIEW_PLAYER_OK")
''')
block16.chmod(0o755)

block17 = root / 'scripts/validate-block17.py'
block17.write_text('''#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PLAYER=(ROOT/"app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt").read_text()
SHELL=(ROOT/"app/src/main/java/com/aniflow/app/feature/shell/AniFlowApp.kt").read_text()

def req(ok,msg):
    if not ok: raise SystemExit("BLOCK17_FAIL: "+msg)
req("EmbeddedProviderPlayer(" not in PLAYER, "provider page remains a playback route")
req("actualVideoBitrate" in PLAYER and "actualVideoCodec" in PLAYER, "real stream telemetry missing")
req("Siguiente episodio en" in PLAYER and "autoplayCountdown" in PLAYER, "autoplay countdown missing")
req("formatQualityChoice" in PLAYER and "Mbps" in PLAYER, "rich quality labels missing")
req("onProviderFailed =" not in SHELL and "onNextProvider =" not in SHELL, "dead provider callbacks still wired")
req("setForceHighestSupportedBitrate(true)" in PLAYER, "1080p intelligence regressed")
print("BLOCK17_PLAYER_X10_OK")
''')
block17.chmod(0o755)

print('ANIFLOW_RC13_RECOVERY_PATCH_OK')

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
        raise SystemExit('RC13_PATCH_FAIL: ' + msg)


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
            if c == '/' and n == '/': state = 'line'; i += 2; continue
            if c == '/' and n == '*': state = 'block'; i += 2; continue
            if c in ('"', "'"): quote = c; state = 'string'; i += 1; continue
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
    raise SystemExit(f'RC13_PATCH_FAIL: unmatched braces for {function_name}')

s = player.read_text()
# Native-only route: provider web targets can remain as diagnostics metadata but can never become UI playback.
s = re.sub(r'\n\s*val activeEmbeddedProvider = state\.activeEmbeddedProvider\s*\n', '\n', s, count=1)
s = re.sub(
    r'''\n\s*activeEmbeddedProvider != null -> EmbeddedProviderPlayer\(.*?\n\s*\)''',
    '', s, count=1, flags=re.S,
)
s = remove_function(s, 'EmbeddedProviderPlayer')
# Remove provider callbacks from public PlayerScreen contract.
s = re.sub(r'\n\s*onProviderFailed: \(\) -> Unit,', '', s)
s = re.sub(r'\n\s*onNextProvider: \(\) -> Unit,', '', s)

# Add measured track state.
anchor = '    var actualVideoHeight by remember { mutableIntStateOf(0) }\n'
req(anchor in s, 'actualVideoHeight state missing')
if 'var actualVideoBitrate by remember' not in s:
    s = s.replace(anchor, anchor + '    var actualVideoBitrate by remember { mutableIntStateOf(0) }\n    var actualVideoCodec by remember { mutableStateOf<String?>(null) }\n')
if 'var autoplayCountdown by remember' not in s:
    ended_anchor = '    var endedHandled by remember(state.mediaId, state.episode) { mutableStateOf(false) }\n'
    req(ended_anchor in s, 'endedHandled state missing')
    s = s.replace(ended_anchor, ended_anchor + '    var autoplayCountdown by remember(state.mediaId, state.episode) { mutableIntStateOf(0) }\n    var autoplayCancelled by remember(state.mediaId, state.episode) { mutableStateOf(false) }\n')

# Measure selected track whenever track groups change.
s = s.replace(
    'override fun onTracksChanged(tracks: Tracks) { tracksVersion++ }',
    '''override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                val measured = selectedVideoStats(tracks)
                actualVideoBitrate = measured?.bitrate ?: 0
                actualVideoCodec = measured?.codec
            }''',
)
# Reset measured state per source.
s = s.replace(
    '        actualVideoHeight = 0\n',
    '        actualVideoHeight = 0\n        actualVideoBitrate = 0\n        actualVideoCodec = null\n        autoplayCountdown = 0\n        autoplayCancelled = false\n',
    1,
)

# Replace immediate autoplay with Crunchy-like countdown.
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

# Pass real stream metadata into controls and settings.
s = s.replace(
    '                actualVideoHeight = actualVideoHeight,\n',
    '                actualVideoHeight = actualVideoHeight,\n                actualVideoBitrate = actualVideoBitrate,\n                actualVideoCodec = actualVideoCodec,\n',
    1,
)
s = s.replace(
    '            actualVideoHeight = actualVideoHeight,\n            subtitlesEnabled = state.subtitlesEnabled,',
    '            actualVideoHeight = actualVideoHeight,\n            actualVideoBitrate = actualVideoBitrate,\n            actualVideoCodec = actualVideoCodec,\n            subtitlesEnabled = state.subtitlesEnabled,',
    1,
)

# Add native next-episode countdown overlay.
box_end_marker = '''        errorText?.let { message ->
            Text(
                message,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.75f)).padding(12.dp),
            )
        }
    }
'''
req(box_end_marker in s, 'player Box tail missing')
countdown_ui = '''        errorText?.let { message ->
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
'''
s = s.replace(box_end_marker, countdown_ui, 1)

# PlayerControls signature + display.
s = s.replace(
    '    actualVideoHeight: Int,\n    onBack: () -> Unit,',
    '    actualVideoHeight: Int,\n    actualVideoBitrate: Int,\n    actualVideoCodec: String?,\n    onBack: () -> Unit,',
    1,
)
s = s.replace(
    '                    actualResolutionLabel(actualVideoWidth, actualVideoHeight),',
    '                    actualStreamLabel(actualVideoWidth, actualVideoHeight, actualVideoBitrate, actualVideoCodec),',
    1,
)

# Settings signature + quality line.
s = s.replace(
    '    actualVideoHeight: Int,\n    subtitlesEnabled: Boolean,',
    '    actualVideoHeight: Int,\n    actualVideoBitrate: Int,\n    actualVideoCodec: String?,\n    subtitlesEnabled: Boolean,',
    1,
)
s = s.replace(
    '                val realQuality = actualVideoHeight.takeIf { it > 0 }?.let { " · real ${it}p" }.orEmpty()\n',
    '                val realQuality = actualVideoHeight.takeIf { it > 0 }?.let { " · real " + actualStreamLabel(0, actualVideoHeight, actualVideoBitrate, actualVideoCodec) }.orEmpty()\n',
    1,
)

# Rich track labels.
s = s.replace(
    '            val candidate = QualityChoice("${height}p", height, bitrate, group.mediaTrackGroup, i)',
    '            val candidate = QualityChoice(formatQualityChoice(format), height, bitrate, group.mediaTrackGroup, i)',
)
old_track_label = '''            val language = format.language?.uppercase()
            val label = format.label?.takeIf { it.isNotBlank() }
                ?: language?.takeIf { it.isNotBlank() }
                ?: "Pista ${choices.size + 1}"
            choices += TrackChoice(label, group.mediaTrackGroup, i)'''
new_track_label = '''            val language = format.language?.uppercase()
            val baseLabel = format.label?.takeIf { it.isNotBlank() }
                ?: language?.takeIf { it.isNotBlank() }
                ?: "Pista ${choices.size + 1}"
            val codec = codecLabel(format.sampleMimeType)
            val rate = bitrateLabel(format.bitrate)
            val label = listOfNotNull(baseLabel, codec, rate).joinToString(" · ")
            choices += TrackChoice(label, group.mediaTrackGroup, i)'''
req(old_track_label in s, 'track label block changed unexpectedly')
s = s.replace(old_track_label, new_track_label, 1)

# Add helpers once.
if 'private data class VideoStats' not in s:
    insertion = s.index('private data class QualityChoice')
    s = s[:insertion] + 'private data class VideoStats(val bitrate: Int, val codec: String?)\n' + s[insertion:]

helper_anchor = 'private fun actualResolutionLabel(width: Int, height: Int): String = when {'
req(helper_anchor in s, 'actualResolutionLabel helper missing')
helper_code = '''private fun selectedVideoStats(tracks: Tracks): VideoStats? {
    var best: VideoStats? = null
    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSelected(i)) continue
            val format = group.getTrackFormat(i)
            val candidate = VideoStats(format.bitrate.coerceAtLeast(0), codecLabel(format.sampleMimeType))
            if (best == null || candidate.bitrate > best!!.bitrate) best = candidate
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
s = s.replace(helper_anchor, helper_code + helper_anchor, 1)

# Explicitly make HTML/web playback impossible in UI source.
for forbidden in ['WebView(', 'WebViewClient', 'WebChromeClient', '.loadUrl(', 'evaluateJavascript(']:
    req(forbidden not in s, f'forbidden web playback still in PlayerScreen: {forbidden}')

player.write_text(s)

# Remove dead web-provider callbacks from shell wiring.
sh = shell.read_text()
sh = re.sub(r'\n\s*onProviderFailed = playerVm::reportProviderFailure,', '', sh)
sh = re.sub(r'\n\s*onNextProvider = playerVm::selectNextProvider,', '', sh)
shell.write_text(sh)

# Keep legacy provider metadata for older regression guards but never present it as a playable path.
v = vm.read_text()
v = v.replace('"La fuente directa falló; probando proveedor integrado."', '"La fuente directa falló. Buscando otra fuente nativa…"')
v = v.replace('"Probando otro proveedor dentro de AniFlow…"', '"Buscando otra fuente nativa…"')
v = v.replace('"No encontré un stream directo ni un proveedor integrable para este episodio."', '"No encontré un stream nativo compatible para este episodio."')
vm.write_text(v)

# Release metadata.
b = build.read_text()
b = re.sub(r'(?m)^(\s*)versionCode\s*=\s*\d+\s*$', r'\1versionCode = 1000013', b)
b = re.sub(r'(?m)^(\s*)versionName\s*=\s*"[^"]+"\s*$', r'\1versionName = "1.0.12-rc13"', b)
build.write_text(b)

validator = root / 'scripts/validate-block17.py'
validator.write_text('''#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PLAYER=(ROOT/"app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt").read_text()
SHELL=(ROOT/"app/src/main/java/com/aniflow/app/feature/shell/AniFlowApp.kt").read_text()

def req(ok,msg):
    if not ok: raise SystemExit("BLOCK17_FAIL: "+msg)
for forbidden in ["WebView(", "WebViewClient", "WebChromeClient", ".loadUrl(", "evaluateJavascript("]:
    req(forbidden not in PLAYER, "web playback returned: "+forbidden)
req("EmbeddedProviderPlayer(" not in PLAYER, "provider page remains a player route")
req("actualVideoBitrate" in PLAYER and "actualVideoCodec" in PLAYER, "real stream telemetry missing")
req("Siguiente episodio en" in PLAYER and "autoplayCountdown" in PLAYER, "autoplay countdown missing")
req("formatQualityChoice" in PLAYER and "Mbps" in PLAYER, "rich quality labels missing")
req("onProviderFailed =" not in SHELL and "onNextProvider =" not in SHELL, "dead web-provider callbacks still wired")
req("setForceHighestSupportedBitrate(true)" in PLAYER, "1080p intelligence regressed")
print("BLOCK17_PLAYER_X10_OK")
''')
validator.chmod(0o755)

print('ANIFLOW_RC13_PATCH_OK')

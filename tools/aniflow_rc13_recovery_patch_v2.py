#!/usr/bin/env python3
from pathlib import Path
import sys

root_arg = sys.argv[1] if len(sys.argv) > 1 else None
if not root_arg:
    raise SystemExit('RC13_V2_WRAPPER_FAIL: target root missing')

original = Path(__file__).with_name('aniflow_rc13_recovery_patch.py')
script = original.read_text()
old = '''tracks_old = 'override fun onTracksChanged(tracks: Tracks) { tracksVersion++ }'
if tracks_old in s:
    s = s.replace(tracks_old, ''' + "'''" + '''override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                val measured = selectedVideoStats(tracks)
                actualVideoBitrate = measured?.bitrate ?: 0
                actualVideoCodec = measured?.codec
            }''' + "'''" + ''', 1)
req('actualVideoBitrate = measured?.bitrate' in s, 'track telemetry listener missing')
'''
new = '''tracks_signature = 'override fun onTracksChanged(tracks: Tracks)'
if 'actualVideoBitrate = measured?.bitrate' not in s:
    start = s.find(tracks_signature)
    req(start >= 0, 'onTracksChanged listener missing')
    opening = s.find('{', start + len(tracks_signature))
    req(opening >= 0, 'onTracksChanged opening brace missing')

    def _matching_method_brace(text: str, opening_index: int) -> int:
        depth = 0
        state = 'code'
        quote = ''
        i = opening_index
        while i < len(text):
            c = text[i]
            n = text[i + 1] if i + 1 < len(text) else ''
            if state == 'code':
                if c == '/' and n == '/':
                    state = 'line'; i += 2; continue
                if c == '/' and n == '*':
                    state = 'block'; i += 2; continue
                if c in ('"', "'"):
                    quote = c; state = 'string'; i += 1; continue
                if c == '{':
                    depth += 1
                elif c == '}':
                    depth -= 1
                    if depth == 0:
                        return i
                i += 1
            elif state == 'line':
                if c == '\\n': state = 'code'
                i += 1
            elif state == 'block':
                if c == '*' and n == '/':
                    state = 'code'; i += 2
                else:
                    i += 1
            else:
                if c == '\\\\':
                    i += 2; continue
                if c == quote:
                    state = 'code'
                i += 1
        raise SystemExit('RC13_RECOVERY_FAIL: unmatched onTracksChanged braces')

    closing = _matching_method_brace(s, opening)
    # Preserve every RC10 callback statement and append only our measurement.
    indent = '                '
    telemetry = (
        '\\n' + indent + 'val measured = selectedVideoStats(tracks)'
        '\\n' + indent + 'actualVideoBitrate = measured?.bitrate ?: 0'
        '\\n' + indent + 'actualVideoCodec = measured?.codec'
        '\\n            '
    )
    s = s[:closing] + telemetry + s[closing:]
req('actualVideoBitrate = measured?.bitrate' in s, 'track telemetry listener missing')
'''
if old not in script:
    raise SystemExit('RC13_V2_WRAPPER_FAIL: old telemetry patch block not found')
script = script.replace(old, new, 1)

# Execute the original transform with only the telemetry injector changed.
sys.argv = [str(original), root_arg]
namespace = {'__name__': '__main__', '__file__': str(original)}
exec(compile(script, str(original), 'exec'), namespace, namespace)

root = Path(root_arg)
player_path = root / 'app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt'
player = player_path.read_text()
resolver = (root / 'app/src/main/java/com/aniflow/app/data/repository/RemotePlaybackResolver.kt').read_text()
policy = (root / 'app/src/main/java/com/aniflow/app/domain/ProviderSecurityPolicy.kt').read_text()
network = (root / 'app/src/main/res/xml/network_security_config.xml').read_text()
container = (root / 'app/src/main/java/com/aniflow/app/core/AppContainer.kt').read_text()
capabilities = (root / 'app/src/main/java/com/aniflow/app/domain/ProviderCapabilities.kt').read_text()
models = (root / 'app/src/main/java/com/aniflow/app/domain/Models.kt').read_text()

# Block 6 originally required the old instant autoplay call. PLAYER-X10 intentionally
# replaces it with an 8-second countdown before calling onNext(nextTarget).
block6 = root / 'scripts/validate-block6.py'
text = block6.read_text()
old_guard = 'state.next?.let(onNext)'
if old_guard in text:
    text = text.replace(old_guard, 'onNext(nextTarget)')
block6.write_text(text)
if 'Siguiente episodio en' not in player or 'onNext(nextTarget)' not in player:
    raise SystemExit('RC13_V2_WRAPPER_FAIL: countdown autoplay contract missing')

# Block 8 originally validated WebView hardening. Native-only playback removes WebView
# entirely, while retaining HTTPS/network policy, shared HTTP pooling, and secure probing.
block8 = root / 'scripts/validate-block8.py'
block8.write_text('''#!/usr/bin/env python3
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/"app/src/main/java/com/aniflow/app"

def req(ok,msg):
    if not ok: raise AssertionError(msg)
def text(rel): return (SRC/rel).read_text()
build=(ROOT/"app/build.gradle.kts").read_text()
m=re.search(r"versionCode\\s*=\\s*(\\d+)", build)
req(m is not None and int(m.group(1)) >= 1000006, "Block 8 requires RC6 or newer")
policy=text("domain/ProviderSecurityPolicy.kt")
for token in ["isTrustedEmbeddedUrl", "isAllowedNavigation", "https", "youtube.com", "crunchyroll.com"]:
    req(token in policy, f"provider security policy missing: {token}")
resolver=text("data/repository/RemotePlaybackResolver.kt")
for token in ["coroutineScope", "async(Dispatchers.IO)", "awaitAll()", "ProviderSecurityPolicy::isHttpsUrl", "response.isSuccessful", "newBuilder()"]:
    req(token in resolver, f"resolver hardening/latency feature missing: {token}")
player=text("feature/player/PlayerScreen.kt")
for forbidden in ["WebView(", "WebViewClient", "WebChromeClient", ".loadUrl(", "evaluateJavascript(", "import android.webkit."]:
    req(forbidden not in player, f"native-only playback violated: {forbidden}")
req("PlayerView" in player and "ExoPlayer" in player, "native Media3 playback missing")
req("text/html" in resolver.lower(), "HTML response classification missing")
network=(ROOT/"app/src/main/res/xml/network_security_config.xml").read_text()
req('cleartextTrafficPermitted="false"' in network, "cleartext traffic not explicitly disabled")
container=text("core/AppContainer.kt")
req("AniListApi.defaultClient()" in container and "AniListApi(httpClient)" in container and "RemotePlaybackResolver(httpClient)" in container, "shared HTTP client not wired")
print("BLOCK8_SECURITY_POLICY_OK")
print("BLOCK8_NATIVE_ONLY_PLAYER_OK")
print("BLOCK8_PARALLEL_RESOLVER_OK")
print("BLOCK8_SHARED_HTTP_POOL_OK")
''')
block8.chmod(0o755)

# Block 11 used to require rendering provider pages. Provider capability metadata remains
# useful for diagnostics, but WEB_PORTAL/EMBED_PLAYER must never become player UI routes.
block11 = root / 'scripts/validate-block11.py'
block11.write_text('''#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
cap=(ROOT/'app/src/main/java/com/aniflow/app/domain/ProviderCapabilities.kt').read_text()
models=(ROOT/'app/src/main/java/com/aniflow/app/domain/Models.kt').read_text()
resolver=(ROOT/'app/src/main/java/com/aniflow/app/data/repository/RemotePlaybackResolver.kt').read_text()
ui=(ROOT/'app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt').read_text()
assert 'enum class ProviderPlaybackMode' in cap
assert 'EMBED_PLAYER' in cap and 'WEB_PORTAL' in cap
assert 'family == "youtube"' in cap
assert 'val playbackMode: ProviderPlaybackMode' in models
assert 'ProviderCapabilities.forUrl(url)' in resolver
assert 'ProviderPlaybackMode.EMBED_PLAYER' in resolver
for forbidden in ['WebView(', 'EmbeddedProviderPlayer(', '.loadUrl(', 'WebChromeClient']:
    assert forbidden not in ui, 'provider page leaked back into native player: '+forbidden
assert 'PlayerView' in ui and 'ExoPlayer' in ui
print('BLOCK11_PROVIDER_CAPABILITIES_METADATA_OK')
print('BLOCK11_PROVIDER_WEB_PLAYBACK_DISABLED_OK')
''')
block11.chmod(0o755)

print('ANIFLOW_RC13_RECOVERY_V2_OK')

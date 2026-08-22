#!/usr/bin/env bash
set -euxo pipefail

ROOT="${ROOT:-/tmp/aniflow-rc22}"
: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

rm -rf "$ROOT" /tmp/base
mkdir -p "$ROOT" /tmp/base

gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/9454593341/zip" > /tmp/base.zip
echo "5c6f820cdeec2fc5c3ef0e8b41bf30109e641e1aa7cb385afbf979bc57b7ac3c  /tmp/base.zip" | sha256sum -c -
unzip -q /tmp/base.zip -d /tmp/base
unzip -q /tmp/base/AniFlow-v1.0.16-rc17-source.zip -d "$ROOT"

cat "$GITHUB_WORKSPACE"/.ci/rc21/parts/part-* > /tmp/rc21.b64
test "$(wc -c < /tmp/rc21.b64)" = 46872
base64 -d /tmp/rc21.b64 > /tmp/rc21.gz
echo "3fb3cbd791c64044f456b131d04dcc7f2d94069d0922b7b5fe9a2472bdcfd889  /tmp/rc21.gz" | sha256sum -c -
gzip -dc /tmp/rc21.gz > /tmp/rc21.patch
echo "495f31394da8ea022d37b4ae67bce8358e8901b46b5067bf294f91319cbef466  /tmp/rc21.patch" | sha256sum -c -
(cd "$ROOT" && patch -p1 --forward --batch < /tmp/rc21.patch)

cat \
  "$GITHUB_WORKSPACE/.ci/rc22/parts/part-00" \
  "$GITHUB_WORKSPACE/.ci/rc22/parts/part-01" \
  "$GITHUB_WORKSPACE/.ci/rc22/fix/part-02-00" \
  "$GITHUB_WORKSPACE/.ci/rc22/fix/part-02-01" \
  "$GITHUB_WORKSPACE/.ci/rc22/fix-small/p2-0" \
  "$GITHUB_WORKSPACE/.ci/rc22/fix-small/p2-1" \
  "$GITHUB_WORKSPACE/.ci/rc22/fix-small/p2-2" \
  "$GITHUB_WORKSPACE/.ci/rc22/fix-small/p3-0" \
  "$GITHUB_WORKSPACE/.ci/rc22/fix-small/p3-1" \
  "$GITHUB_WORKSPACE/.ci/rc22/fix-small/p3-2" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p00" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p01" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p02" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p03" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p04" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p05" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p06a" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p06b" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p07" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p08a" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p08b" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p09" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p10" \
  "$GITHUB_WORKSPACE/.ci/rc22/part03-small/p11" \
  "$GITHUB_WORKSPACE/.ci/rc22/parts/part-04" \
  "$GITHUB_WORKSPACE/.ci/rc22/parts/part-05" > /tmp/rc22.b64

test "$(wc -c < /tmp/rc22.b64)" = 71320
echo "4241bfd76ce3b6b165f38c028ac7fb5b4c1496c8b84077e537763bdd0df717fe  /tmp/rc22.b64" | sha256sum -c -
base64 -d /tmp/rc22.b64 > /tmp/rc22.gz
echo "d2595eba913d2f6d6a1f38f41b2550401bafd7ce6675f6125d7bd47d709d4035  /tmp/rc22.gz" | sha256sum -c -
gzip -dc /tmp/rc22.gz > /tmp/rc22.patch
echo "155d5649ab70d3310baf9e4eff9282b3d5759d728d7411c05522f533580ab89e  /tmp/rc22.patch" | sha256sum -c -
(cd "$ROOT" && patch -p1 --forward --batch < /tmp/rc22.patch)

grep -q 'versionCode = 1000022' "$ROOT/app/build.gradle.kts"
grep -q 'versionName = "1.0.21-rc22"' "$ROOT/app/build.gradle.kts"

python3 - <<'PY'
from pathlib import Path
p = Path('/tmp/aniflow-rc22/app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt')
s = p.read_text()
old_import = 'import androidx.compose.foundation.layout.weight\n'
if s.count(old_import) != 1:
    raise SystemExit(f'unexpected weight import count: {s.count(old_import)}')
s = s.replace(old_import, '', 1)
anchor = '''    when {\n        state.loading -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {\n'''
replacement = '''    val activeEmbeddedProvider = state.activeEmbeddedProvider\n    when {\n        state.loading -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {\n'''
if s.count(anchor) != 1:
    raise SystemExit(f'unexpected PlayerScreen when anchor count: {s.count(anchor)}')
s = s.replace(anchor, replacement, 1)
old = '''        state.activeEmbeddedProvider?.isPlayableOfficialEmbed == true -> OfficialYouTubePlayer(\n            state = state,\n            target = state.activeEmbeddedProvider,\n'''
new = '''        activeEmbeddedProvider?.isPlayableOfficialEmbed == true -> OfficialYouTubePlayer(\n            state = state,\n            target = activeEmbeddedProvider,\n'''
if s.count(old) != 1:
    raise SystemExit(f'unexpected embedded provider branch count: {s.count(old)}')
s = s.replace(old, new, 1)

old_feature = '''                            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {\n                                failProvider("Tu Android System WebView no admite el canal seguro requerido por AniFlow. Actualiza Android System WebView/Chrome.")\n                                return@apply\n                            }\n                            WebViewCompat.addWebMessageListener(\n                                this,\n                                "AniFlowBridge",\n                                setOf(origin),\n                            ) { _, message, sourceOrigin, isMainFrame, _ ->\n                                if (isMainFrame && sourceOrigin.toString().removeSuffix("/") == origin) {\n                                    bridge.onMessage(message.data.orEmpty())\n                                }\n                            }\n'''
new_feature = '''                            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {\n                                WebViewCompat.addWebMessageListener(\n                                    this,\n                                    "AniFlowBridge",\n                                    setOf(origin),\n                                ) { _, message, sourceOrigin, isMainFrame, _ ->\n                                    if (isMainFrame && sourceOrigin.toString().removeSuffix("/") == origin) {\n                                        bridge.onMessage(message.data.orEmpty())\n                                    }\n                                }\n                            } else {\n                                failProvider("Tu Android System WebView no admite el canal seguro requerido por AniFlow. Actualiza Android System WebView/Chrome.")\n                                return@apply\n                            }\n'''
if s.count(old_feature) != 1:
    raise SystemExit(f'unexpected WebView feature block count: {s.count(old_feature)}')
s = s.replace(old_feature, new_feature, 1)
p.write_text(s)

p = Path('/tmp/aniflow-rc22/app/src/test/java/com/aniflow/app/data/repository/RemotePlaybackResolverTest.kt')
s = p.read_text()
old_test = '?autoplay=1&playsinline=1&rel=0'
if s.count(old_test) != 2:
    raise SystemExit(f'unexpected stale rel=0 expectation count: {s.count(old_test)}')
p.write_text(s.replace(old_test, '?autoplay=1&playsinline=1'))
PY

! grep -q '^import androidx.compose.foundation.layout.weight$' "$ROOT/app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt"
grep -q 'val activeEmbeddedProvider = state.activeEmbeddedProvider' "$ROOT/app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt"
grep -q 'target = activeEmbeddedProvider' "$ROOT/app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt"
grep -q 'if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {' "$ROOT/app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt"
! grep -q 'if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))' "$ROOT/app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt"
! grep -q 'playsinline=1&rel=0' "$ROOT/app/src/test/java/com/aniflow/app/data/repository/RemotePlaybackResolverTest.kt"

cd "$ROOT"
bash scripts/validate-ui-syntax.sh

echo RC22_BUILDFIX3_RECONSTRUCTION_OK

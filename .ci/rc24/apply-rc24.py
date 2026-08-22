#!/usr/bin/env python3
from pathlib import Path
import os

root = Path(os.environ.get('ROOT', '/tmp/aniflow-rc24'))

def read(rel):
    return (root / rel).read_text()

def write(rel, text):
    (root / rel).write_text(text)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 anchor, found {count}')
    return text.replace(old, new, 1)

rel = 'app/build.gradle.kts'
s = read(rel)
s = replace_once(s, 'versionCode = 1000023', 'versionCode = 1000024', 'versionCode')
s = replace_once(s, 'versionName = "1.0.22-rc23"', 'versionName = "1.0.23-rc24"', 'versionName')
write(rel, s)

rel = 'app/src/main/java/com/aniflow/app/data/repository/RemotePlaybackResolver.kt'
s = read(rel)
s = replace_once(s, 'import com.aniflow.app.domain.PlaybackSource\n', 'import com.aniflow.app.domain.PlaybackSource\nimport com.aniflow.app.domain.PlaybackSourceOrigin\n', 'resolver import')
anchor = '    suspend fun resolveCatalogDemo(\n'
insert = '''    suspend fun resolveUserDirectSource(\n        mediaId: Long,\n        episode: Int,\n        url: String,\n    ): PlaybackSource? {\n        require(mediaId > 0)\n        require(episode > 0)\n        val clean = url.trim()\n        if (!ProviderSecurityPolicy.isSafeDirectMediaUrl(clean)) return null\n        val detected = directUrlType(clean)\n        val seed = PlaybackSource(\n            mediaId = mediaId,\n            episode = episode,\n            uri = clean,\n            mimeType = detected?.mimeType,\n            sourceType = detected?.sourceType ?: PlaybackSourceType.HTTP,\n            displayName = "Fuente directa",\n            origin = PlaybackSourceOrigin.USER_BOUND,\n            priority = 0,\n            persistable = true,\n        )\n        val probed = probeCatalogSource(seed) ?: return null\n        return probed.copy(\n            displayName = "Fuente directa",\n            origin = PlaybackSourceOrigin.USER_BOUND,\n            priority = 0,\n            persistable = true,\n        )\n    }\n\n'''
if anchor not in s or 'resolveUserDirectSource' in s:
    raise SystemExit('resolver function anchor missing or already transformed')
s = s.replace(anchor, insert + anchor, 1)
write(rel, s)

rel = 'app/src/main/java/com/aniflow/app/feature/player/PlayerViewModel.kt'
s = read(rel)
s = s.replace('import com.aniflow.app.domain.PlaybackSourceOrigin\n', '')
s = s.replace('import com.aniflow.app.domain.PlaybackSourceType\n', '')
s = replace_once(s, '    val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),\n    val demoMode: Boolean = false,\n    val demoLabel: String? = null,\n', '    val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),\n    val officialPlayerRequested: Boolean = false,\n', 'ui state demo fields')
s = replace_once(s, '        _state.value = _state.value.copy(\n            diagnostics = PlaybackDiagnostics(),\n            demoMode = false,\n            demoLabel = null,\n        )', '        _state.value = _state.value.copy(\n            diagnostics = PlaybackDiagnostics(),\n            officialPlayerRequested = false,\n        )', 'retry reset')
start = s.find('    fun useCatalogDemo() {')
end = s.find('    fun reportSourceFailure(', start)
if start < 0 or end < 0:
    raise SystemExit('demo method block not found')
replacement = '''    fun useOfficialProvider() {\n        val current = _state.value\n        val providerIndex = current.embeddedProviders.indexOfFirst {\n            it.isPlayableOfficialEmbed && it.url !in failedProviderUrls && !it.isExpired(System.currentTimeMillis())\n        }\n        if (providerIndex < 0) {\n            _state.value = current.copy(error = "No hay un reproductor oficial disponible para este episodio.")\n            return\n        }\n        _state.value = current.copy(\n            officialPlayerRequested = true,\n            activeProviderIndex = providerIndex,\n            sourceMissing = false,\n            error = null,\n        )\n    }\n\n    fun useDirectUrl(rawUrl: String) {\n        if (_state.value.loading) return\n        val url = rawUrl.trim()\n        if (url.isBlank()) {\n            _state.value = _state.value.copy(error = "Pega una URL HTTPS directa HLS, DASH, MP4 o WebM.")\n            return\n        }\n        viewModelScope.launch {\n            val current = _state.value\n            _state.value = current.copy(loading = true, error = null)\n            val source = try {\n                remotePlaybackResolver.resolveUserDirectSource(\n                    mediaId = mediaId,\n                    episode = episode,\n                    url = url,\n                )\n            } catch (cancelled: CancellationException) {\n                throw cancelled\n            } catch (_: Exception) {\n                null\n            }\n            if (source == null) {\n                _state.value = current.copy(\n                    loading = false,\n                    error = "La URL no respondió como HLS/DASH/MP4/WebM reproducible. AniFlow no extrae vídeo desde páginas web.",\n                )\n                return@launch\n            }\n            val persisted = runCatching {\n                videoSourceRepository.bind(\n                    mediaId = mediaId,\n                    episode = episode,\n                    uri = source.uri,\n                    mimeType = source.mimeType,\n                    displayName = source.displayName,\n                )\n            }.getOrNull() ?: source\n            failedSourceUris.remove(source.uri)\n            _state.value = current.copy(\n                source = persisted,\n                fallbackSources = emptyList(),\n                loading = false,\n                sourceMissing = false,\n                officialPlayerRequested = false,\n                failoverResumePositionMs = current.savedProgress?.positionMs?.coerceAtLeast(0L) ?: 0L,\n                error = null,\n            )\n        }\n    }\n\n'''
s = s[:start] + replacement + s[end:]
demo_failure = '''        if (current.demoMode) {\n            _state.value = current.copy(\n                source = null,\n                fallbackSources = emptyList(),\n                sourceMissing = true,\n                loading = false,\n                error = "El stream de prueba del catálogo falló. Reintenta el demo.",\n                diagnostics = updatedDiagnostics,\n            )\n            return\n        }\n\n'''
if demo_failure not in s:
    raise SystemExit('demo failure branch missing')
s = s.replace(demo_failure, '', 1)
auto_provider = '''        val providerIndex = current.embeddedProviders.indexOfFirst {\n            it.isPlayableOfficialEmbed && it.url !in failedProviderUrls && !it.isExpired(System.currentTimeMillis())\n        }\n        if (providerIndex >= 0) {\n            _state.value = current.copy(\n                source = null,\n                fallbackSources = emptyList(),\n                sourceMissing = false,\n                failoverResumePositionMs = resumePosition,\n                activeProviderIndex = providerIndex,\n                loading = false,\n                error = "La fuente directa falló. Cambiando al reproductor oficial…",\n                diagnostics = updatedDiagnostics,\n            )\n            return\n        }\n\n'''
opt_provider = '''        val providerIndex = current.embeddedProviders.indexOfFirst {\n            it.isPlayableOfficialEmbed && it.url !in failedProviderUrls && !it.isExpired(System.currentTimeMillis())\n        }\n        if (providerIndex >= 0) {\n            _state.value = current.copy(\n                source = null,\n                fallbackSources = emptyList(),\n                sourceMissing = true,\n                officialPlayerRequested = false,\n                failoverResumePositionMs = resumePosition,\n                activeProviderIndex = providerIndex,\n                loading = false,\n                error = "Las fuentes nativas fallaron. Hay un reproductor oficial opcional disponible.",\n                diagnostics = updatedDiagnostics,\n            )\n            return\n        }\n\n'''
s = replace_once(s, auto_provider, opt_provider, 'automatic provider failover')
s = replace_once(s, '            _state.value = current.copy(\n                sourceMissing = true,\n                error = "No quedan fuentes oficiales compatibles para este episodio. Puedes reintentar la búsqueda.",\n                diagnostics = updatedDiagnostics,\n            )', '            _state.value = current.copy(\n                sourceMissing = true,\n                officialPlayerRequested = false,\n                error = "El reproductor oficial no pudo iniciar. Puedes reintentar o usar una fuente directa autorizada.",\n                diagnostics = updatedDiagnostics,\n            )', 'provider exhausted')
s = s.replace('        if (_state.value.demoMode) return\n', '')
s = replace_once(s, '            _state.value = _state.value.copy(\n                loading = true,\n                error = null,\n                demoMode = false,\n                demoLabel = null,\n            )', '            _state.value = _state.value.copy(\n                loading = true,\n                error = null,\n                officialPlayerRequested = false,\n            )', 'load reset')
s = replace_once(s, '                    sourceMissing = resolvedSource == null && officialProviders.isEmpty(),\n                    // Direct Media3 wins when available; otherwise the official provider player can render immediately.\n                    loading = false,', '                    sourceMissing = resolvedSource == null,\n                    officialPlayerRequested = false,\n                    // Native Media3 is the default experience. Official embeds are opt-in only.\n                    loading = false,', 'native default state')
s = replace_once(s, 'val noPlayableTarget = resolvedSource == null && officialProviders.isEmpty()', 'val noPlayableTarget = resolvedSource == null', 'native no target')
s = replace_once(s, '                            else -> "No hay una fuente autorizada reproducible disponible para este episodio."', '                            officialProviders.isNotEmpty() -> "No hay una fuente nativa disponible. Puedes usar el reproductor oficial opcional o añadir una fuente directa autorizada."\n                            else -> "No hay una fuente nativa autorizada disponible para este episodio."', 'missing source message')
if 'demoMode' in s or 'demoLabel' in s or 'useCatalogDemo' in s or 'openLicensedDemoSources' in s or 'morevnaOfficialDemoEmbed' in s:
    raise SystemExit('demo code survived PlayerViewModel transform')
write(rel, s)

rel = 'app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt'
s = read(rel)
s = replace_once(s, 'import androidx.compose.material3.HorizontalDivider\n', 'import androidx.compose.material3.HorizontalDivider\nimport androidx.compose.material3.OutlinedTextField\n', 'text field import')
s = replace_once(s, '    onRetry: () -> Unit,\n    onCatalogDemo: () -> Unit,\n    onSourceFailed:', '    onRetry: () -> Unit,\n    onUseOfficialProvider: () -> Unit,\n    onUseDirectUrl: (String) -> Unit,\n    onSourceFailed:', 'screen callbacks')
s = replace_once(s, '        activeEmbeddedProvider?.isPlayableOfficialEmbed == true -> OfficialYouTubePlayer(', '        state.officialPlayerRequested && activeEmbeddedProvider?.isPlayableOfficialEmbed == true -> OfficialYouTubePlayer(', 'official opt in branch')
s = replace_once(s, '            onRetry = onRetry,\n            onCatalogDemo = onCatalogDemo,\n        )', '            onRetry = onRetry,\n            onUseOfficialProvider = onUseOfficialProvider,\n            onUseDirectUrl = onUseDirectUrl,\n        )', 'missing source args')
s = replace_once(s, '    onRetry: () -> Unit,\n    onCatalogDemo: () -> Unit,\n) {', '    onRetry: () -> Unit,\n    onUseOfficialProvider: () -> Unit,\n    onUseDirectUrl: (String) -> Unit,\n) {', 'missing source signature')
start = s.find('        Column(\n            modifier = Modifier.align(Alignment.Center).padding(28.dp),', s.find('private fun MissingSource'))
end = s.find('    }\n    if (showDiagnostics)', start)
if start < 0 or end < 0:
    raise SystemExit('MissingSource UI block not found')
new_column = '''        Column(\n            modifier = Modifier.align(Alignment.Center).padding(28.dp),\n            horizontalAlignment = Alignment.CenterHorizontally,\n            verticalArrangement = Arrangement.spacedBy(12.dp),\n        ) {\n            var directUrl by remember { mutableStateOf("") }\n            Text(state.animeTitle.ifBlank { "Anime" }, color = Color.White, style = MaterialTheme.typography.titleLarge)\n            Text(state.compactEpisodeLabel, color = AniMuted)\n            Text("Este episodio no tiene una fuente nativa reproducible disponible en AniFlow.", color = Color.White)\n            Text("AniFlow no sustituirá el episodio por un vídeo de prueba ni abrirá una página cualquiera como si fuera un stream.", color = AniMuted, fontSize = 12.sp)\n            Button(onClick = onRetry) { Text("Buscar fuentes otra vez") }\n            if (state.activeEmbeddedProvider?.isPlayableOfficialEmbed == true) {\n                TextButton(onClick = onUseOfficialProvider) { Text("Usar reproductor oficial · ${state.activeEmbeddedProvider?.name ?: "Proveedor"}") }\n            }\n            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))\n            Text("Fuente directa autorizada", color = Color.White, fontSize = 13.sp)\n            OutlinedTextField(value = directUrl, onValueChange = { directUrl = it }, singleLine = true, label = { Text("HTTPS HLS / DASH / MP4 / WebM") }, modifier = Modifier.fillMaxWidth())\n            Button(onClick = { onUseDirectUrl(directUrl) }, enabled = directUrl.isNotBlank()) { Text("Reproducir fuente directa") }\n            Text("La URL debe apuntar al archivo o manifiesto de vídeo, no a una página web. Úsala solo si tienes permiso para reproducir ese contenido.", color = AniMuted, fontSize = 10.sp)\n            TextButton(onClick = { showDiagnostics = true }) { Text("Ver diagnóstico") }\n            Text("Diag ${state.diagnostics.compactSummary()}", color = AniMuted, fontSize = 10.sp)\n            Text("AniFlow ${BuildConfig.VERSION_NAME}", color = AniMuted.copy(alpha = 0.7f), fontSize = 10.sp)\n            if (state.error != null) { Text(state.error, color = AniMuted, fontSize = 12.sp) }\n        }\n'''
s = s[:start] + new_column + s[end:]
demo_start = s.find('        if (state.demoMode) {')
if demo_start >= 0:
    demo_end = s.find('\n\n        if (playbackState == Player.STATE_BUFFERING)', demo_start)
    if demo_end < 0:
        raise SystemExit('active player demo overlay end missing')
    s = s[:demo_start] + s[demo_end + 2:]
if 'PROBAR CATÁLOGO · MOREVNA CC' in s or 'onCatalogDemo' in s or 'state.demoMode' in s:
    raise SystemExit('demo UI survived PlayerScreen transform')
write(rel, s)

rel = 'app/src/main/java/com/aniflow/app/feature/shell/AniFlowApp.kt'
s = read(rel)
s = replace_once(s, '                        onRetry = playerVm::retry,\n                        onCatalogDemo = playerVm::useCatalogDemo,', '                        onRetry = playerVm::retry,\n                        onUseOfficialProvider = playerVm::useOfficialProvider,\n                        onUseDirectUrl = playerVm::useDirectUrl,', 'shell callbacks')
write(rel, s)

rel = 'scripts/validate-block26.py'
s = read(rel)
s = s.replace("assert 'fun useCatalogDemo()' in vm", "assert 'fun useDirectUrl(rawUrl: String)' in vm and 'fun useOfficialProvider()' in vm")
s = s.replace("assert 'demoMode: Boolean = false' in vm and 'demoLabel: String? = null' in vm", "assert 'officialPlayerRequested: Boolean = false' in vm")
s = s.replace("assert vm.count('if (_state.value.demoMode) return') >= 3", "assert 'demoMode' not in vm and 'demoLabel' not in vm")
s = s.replace("assert 'PROBAR CATÁLOGO · MOREVNA CC' in player", "assert 'PROBAR CATÁLOGO · MOREVNA CC' not in player and 'Reproducir fuente directa' in player")
s = s.replace("assert 'no representa el episodio seleccionado' in player.lower()", "assert 'no sustituirá el episodio por un vídeo de prueba' in player.lower()")
s = s.replace("assert 'onCatalogDemo = playerVm::useCatalogDemo' in shell", "assert 'onUseDirectUrl = playerVm::useDirectUrl' in shell and 'onUseOfficialProvider = playerVm::useOfficialProvider' in shell")
s = s.replace("print('BLOCK26_DEPLOYED_CATALOG_DEMO_INTEGRATION_OK')", "print('BLOCK26_RC24_NATIVE_SOURCE_FLOW_OK')")
write(rel, s)

rel = 'scripts/validate-block27.py'
s = read(rel)
s = s.replace("assert 'openLicensedDemoSources()' in vm\n", '')
s = s.replace("assert vm.count('persistable = false') >= 3", "assert 'fun useDirectUrl(rawUrl: String)' in vm")
s = s.replace("assert 'Wikimedia Commons · VP9 720p compat' in vm", "assert 'resolveUserDirectSource' in vm")
s = s.replace("assert 'Wikimedia Commons · VP9 1080p' in vm", "assert 'officialPlayerRequested = false' in vm")
s = s.replace("assert 'Morevna Project · YouTube oficial' in vm", "assert 'useOfficialProvider()' in vm")
s = s.replace("assert '(remoteSources + openLicensedDemoSources()).distinctBy { it.uri }' in vm", "assert 'openLicensedDemoSources' not in vm and 'morevnaOfficialDemoEmbed' not in vm")
s = s.replace("assert 'no representa el episodio seleccionado' in player.lower()", "assert 'reproducir fuente directa' in player.lower()")
s = s.replace("print('BLOCK27_OPEN_LICENSED_ROOT_FAILOVER_OK')", "print('BLOCK27_RC24_NATIVE_PLAYBACK_UI_OK')")
write(rel, s)

rel = 'scripts/validate-block28.py'
s = read(rel)
s = replace_once(s, "assert 'versionCode = 1000023' in build", "assert 'versionCode = 1000024' in build", 'block28 version code')
s = replace_once(s, "assert 'versionName = \"1.0.22-rc23\"' in build", "assert 'versionName = \"1.0.23-rc24\"' in build", 'block28 version name')
write(rel, s)

rel = 'scripts/validate-viewmodels-kotlin.sh'
s = read(rel)
old = '''    suspend fun resolveCatalogDemo(
        mediaId: Long,
        episode: Int,
        preferredHeight: Int = 1080,
    ): com.aniflow.app.data.network.PlaybackCatalogResult =
        com.aniflow.app.data.network.PlaybackCatalogResult(emptyList(), 8)
'''
new = '''    suspend fun resolveUserDirectSource(
        mediaId: Long,
        episode: Int,
        url: String,
    ): com.aniflow.app.domain.PlaybackSource? = null
    suspend fun resolveCatalogDemo(
        mediaId: Long,
        episode: Int,
        preferredHeight: Int = 1080,
    ): com.aniflow.app.data.network.PlaybackCatalogResult =
        com.aniflow.app.data.network.PlaybackCatalogResult(emptyList(), 8)
'''
s = replace_once(s, old, new, 'viewmodel resolver stub')
write(rel, s)

orig = root / 'app/src/main/java/com/aniflow/app/feature/player/PlayerViewModel.kt.orig'
if orig.exists(): orig.unlink()
vm = read('app/src/main/java/com/aniflow/app/feature/player/PlayerViewModel.kt')
player = read('app/src/main/java/com/aniflow/app/feature/player/PlayerScreen.kt')
shell = read('app/src/main/java/com/aniflow/app/feature/shell/AniFlowApp.kt')
resolver = read('app/src/main/java/com/aniflow/app/data/repository/RemotePlaybackResolver.kt')
assert 'versionCode = 1000024' in read('app/build.gradle.kts')
assert 'versionName = "1.0.23-rc24"' in read('app/build.gradle.kts')
assert 'Morevna' not in vm
assert 'PROBAR CATÁLOGO' not in player
assert 'resolveUserDirectSource' in resolver
assert 'onUseDirectUrl = playerVm::useDirectUrl' in shell
print('RC24_TRANSFORM_OK')

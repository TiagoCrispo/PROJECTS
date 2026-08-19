## 0.5.31 — Release Engineering / Android 16
- versionCode 81 / versionName 0.5.31.
- targetSdk/compileSdk 36 with AGP 8.13.2 + Gradle 8.13 + Java 17.
- Release builds now use R8 full-mode minification, optimized resource shrinking and `proguard-android-optimize.txt`.
- Release signing is environment-driven; no keystore or credential is stored in source.
- CI builds debug, AndroidTest, optimized release APK and AAB; verifies package/security contract, lint, R8 mapping and SHA-256 outputs.
- Added reproducibility check for unsigned optimized release builds.
- Consolidated old per-block/version guides into canonical README/ARCHITECTURE/SECURITY/TESTING/RELEASE documentation; historical regression tests remain executable.
- Status remains evidence-based: STATIC_PASS does not imply emulator/device/production verification.

## 0.5.30 — Production X10 / No False Deletes
- Source-first; se descartan los hotfix DEX de 0.5.28/0.5.29 como mecanismo de release.
- Baseline lifecycle gate: ninguna detección de borrado antes de una lectura válida de notificaciones activas.
- POLL_SYNC solo incorpora presencia positiva; ausencia/removal/APP_CANCEL nunca confirma borrado.
- Confirmación únicamente 1:1 por marcador REAL_POST singular + timestamp estable + exactamente un match histórico confiable.
- Marcadores ambiguos/plurales/unstables quedan UNKNOWN; sin placeholders confirmados ni batch amplification.
- DB deletion transition atómica/idempotente y logging estructurado WHY_DETECTED/SOURCE_EVENT/MATCH_METHOD/CONFIDENCE.
- Inicio directo en Mensajes borrados.
- Tests host + instrumentados + scripts de build/ADB; estado actual STATIC_PASS — REAL_DEVICE_PENDING.

## 0.5.26 — Final Reliability Freeze

## 0.5.27 — Consistency Freeze
- Unified duplicate-media deletion under verified/retryable physical cleanup.
- Preserved Gallery/favorite state across duplicate collapse.
- Prevented duplicate manual Gallery exports and export double-taps.
- Diagnostics version is derived from the APK build.
- Added bounded low-priority migration retries.

- Borrado conjunto Galería/Vault reintentable y reemplazos con borrado físico verificado.
- HMAC/Keystore para huellas multimedia y claves de notificación.
- Coordinador secuencial de migraciones; eliminación de prefs de exportación obsoletas.

## v0.5.25 — Final Hardening
- La exportación manual a Galería registra inmediatamente su URI cifrada; si no puede registrarla, revierte la copia para no dejar archivos externos huérfanos.
- DirectVoiceWatcher ya no conserva nombres originales en diagnósticos ni en staging.
- Fingerprints de mensajes, snapshots de conversación y miniaturas pasan de hashes deterministas sin clave a HMAC protegido por Android Keystore.
- La migración de privacidad queda versionada, serializada, reanudable y de una sola ejecución efectiva; ya no se relanza al reconectar el listener.
- Guardar en Descargas funciona también en Android 8/9 mediante la ruta legacy con WRITE_EXTERNAL_STORAGE.
- El motor confirmed-only/tombstones/FIFO/correlación estricta no cambia.

## v0.5.24 — Reliability & Privacy
- Borrado permanente verificado: la fila no desaparece hasta confirmar el borrado físico; fallos quedan pendientes y se reintentan.
- Clave HMAC de metadatos migrada/importada a Android Keystore conservando los identificadores existentes; sin fallback determinista en claro.
- Callbacks de tareas en segundo plano protegidos por ciclo de vida para no mostrar UI sobre una Activity destruida.
- Arranque de watchers con período de asentamiento acotado antes de declarar fallos.
- Inicio y Diagnóstico cargan estadísticas/historial pesado fuera del hilo principal.
- Motor confirmed-only, tombstones, FIFO/micro-cohortes y correlación multimedia sin cambios.

## v0.5.23 — Privacy Closure
- Metadatos sensibles de origen/ruta quedan HMAC-opacos o cifrados; preferencias operativas antiguas se limpian/migran.
- Exportación a Galería exclusivamente manual: WA Vault no crea copias descifradas automáticamente.
- Staging y temporales usan nombres opacos; restos heredados reconocibles se retiran durante la migración.
- Autocomprobación prueba también la clave AES de texto/metadatos y exige migraciones pendientes en cero.
- Diagnóstico actualizado a 0.5.23 y terminología de “ruta alternativa”; motor de detección sin cambios.

## v0.5.21 — Stable Privacy Freeze
- Cifrado multimedia obligatorio; se elimina la opción de desactivarlo o limitarlo.
- Migración única y verificada de blobs heredados en claro a AES-GCM.
- Temporales de compartir/abrir reducidos a minutos y limpiados al volver a la app.
- Watchdog adaptativo: 45 s con actividad/problemas y 5 min cuando el sistema está sano.
- Registro local de crash sin contenido sensible, integrado en diagnóstico y autoprueba.
- Motor strict confirmed-only, tombstones, FIFO, cohortes y correlación multimedia sin cambios.

## v0.5.20 — Privacy & Durability Final
- Stronger atomic media durability, Keystore retry diagnostics, 5-minute decrypted temp cleanup, deep-idle battery backoff.
- Detection/correlation core unchanged.

# v0.5.17 — Stability / Precision
- Marcadores plurales requieren prueba estructural exacta; frases humanas no confirman borrados.
- Normalización segura de marcadores singulares.
- APP_CANCEL_ALL filtrado antes de candidatos.
- Contador diagnóstico de borrados no verificables.
- Reserva de almacenamiento protegida + límites adaptativos para documentos.
- Fallback de scroll en Recuperados si desaparece el ancla.

## 0.5.16 — Strict Delete Evidence + Media Cohorts
- `APP_CANCEL` deja de ser prueba de borrado: sin marcador explícito de WhatsApp, cualquier candidato probable se revierte y nunca aparece como confirmado.
- Limpieza acotada al actualizar para degradar confirmaciones heredadas de la antigua ruta `APP_CANCEL directo=` de v0.5.15.
- Marcadores de borrado más estrictos: fragmentos humanos genéricos como `mensaje eliminado` / `message deleted` no bastan.
- Documentos pasan a usar pending persistente, MediaStore.Files y reconciliación confirmada igual que foto/video/audio.
- FIFO por micro-cohortes de una conversación y ráfaga compacta; exactitud 1:1 o el contenido permanece oculto.
- Arms persistentes unificados a 10 min y polling alternativo reducido a ~180 ms en ráfaga / ~4 s en reposo.
- Recuperados conserva ancla, offset y cantidad cargada durante refrescos.
- Video adaptativo 40/100/200 MB según espacio libre, siempre por streaming y sin archivos truncados.
- Inicio muestra `WA Vault activo` sin ocultar el estado global ni los componentes que requieren atención.

## 0.5.14 — Precision Core + Minimal UI
- Recovery Center estrictamente derivado de `DELETE_CONFIRMED`; contenido normal/probable y legado no confirmado vuelve a cuarentena oculta.
- Identidad persistente de lote de captura, correlación exacta/guardada y FIFO seguro para audios consecutivos.
- La UI ya no enlaza multimedia por proximidad temporal; toda asociación la resuelve el motor de captura.
- Parser multimedia estricto: sin activación por palabras sueltas, sin confundir horas como `12:34` con audio y stickers excluidos.
- Tombstones para borrado definitivo: un archivo eliminado por el usuario no puede resucitar por un watcher o deduplicación automática.
- Limpieza, papelera, selección y mantenimiento paginados sin techos silenciosos de 10k/12k elementos.
- Estadísticas separadas para recuperados, papelera, temporal oculto y uso físico real de WA Vault.
- Registro compacto de fallos críticos de captura y prueba de integridad no destructiva desde Diagnóstico.
- Rediseño minimalista graphite + mint con navegación simplificada: Inicio, Borrados, Recuperados y Ajustes.

## 0.5.5
- Deduplicación lógica de mensajes y adjuntos bajo captura agresiva.
- Cierre de carreras entre capturadores de audio/media.
- Reparación conservadora de duplicados ya guardados.
- Selección múltiple por toque simple tras iniciar selección y botón Seleccionar todo.

## 0.5.3
- Remote-delete reconciliation on WhatsApp app-cancel/empty replacement.
- Per-audio rescue path; no generic voice re-arming from non-audio messages.
- Persistent deletion diagnostics.

## 0.5.1
- Corrige detección de mensajes borrados en lote y tras reposts de notificaciones.
- Snapshot estable por conversación + huella de mensajes.
- Ventana anti-falsos-positivos para borrados probables.
- Más marcadores de borrado y diagnóstico detallado.

# v0.5.0 — Recovery Center 2.0
- RecyclerView + ListAdapter/DiffUtil para archivos recuperados.
- Paginación de 80 elementos con filtros DB-side y contexto por lote.
- Selección múltiple, favoritos masivos y envío a papelera.
- Papelera de 7 días con restauración y borrado definitivo.
- Timeline por archivo y confianza de asociación.
- Calendario de recuperaciones y álbumes por conversación.
- SQLite v12 con índices de papelera y migración desde v11.

# v0.4.1
- Actualización in-place de Mensajes.
- Centro de recuperación con carga progresiva.
- Consultas de contexto por lote.
- SQLite v11 e índices nuevos.
- Migración de cifrado reanudable por lotes.
- Registro de errores internos y diagnóstico reforzado.

# v0.4.0 — Recovery Center
- Recovery lifecycle persistente y contadores detectado/guardado separados.
- Centro Todo lo recuperado con búsqueda, fecha, favoritos y documentos.
- Cifrado configurable y migración de archivos antiguos.
- Escaneo histórico bajo demanda, diagnóstico, almacenamiento y limpieza mejorados.
- Prueba guiada 5/5 y soporte de documentos en detección/archivo.

# WA Vault v0.3.4

- Mensajes visibles se actualizan automáticamente mediante señal interna; no hace falta recargar.
- La actualización conserva la pantalla/posición y no navega a Inicio.
- Fotos/videos: captura directa solo cuando existe un mensaje pendiente, apertura temprana del archivo al CREATE/MOVED_TO y descriptor mantenido durante escritura.
- MediaStore solo captura cuando existe asociación pendiente; se elimina el escaneo completo de galería en Inicio.
- Inicio usa estadísticas SQL agregadas y ya no descifra miles de registros al abrir.
- Nueva sección Recuperados: archivos, tamaño, contexto, visor/reproductor, borrado individual y limpieza centralizada.
- Limpieza de archivos no asociados, >30 días, >90 días, caché y todo.
- Diagnóstico avanzado separado, legible y con cronología amigable.
- Botones adaptativos de 52dp mínimo, dos líneas y sin animaciones que interfieran con toques.
- Flujo Samsung abre automáticamente la pantalla oficial de Aplicaciones sin autosuspensión una vez; Samsung exige confirmación del usuario.
- Explicaciones dentro de Ajustes para cifrado de archivos antiguos y bloqueo biométrico/PIN.

# WA Vault 0.3.2 — Precision + Media

## Precisión extrema
- Los URI multimedia de una notificación se abren antes de SQLite/cifrado/correlación; la asociación con el mensaje se completa después.
- En audio y multimedia existe una cola de enlaces pendientes para no perder la ventaja de abrir el descriptor primero.
- Se mantiene NotificationListener + FileObserver + MediaStore como rutas complementarias, no como escaneos competitivos.

## Fotos y videos descargados manualmente
- `DirectMediaWatcher` ahora observa también el directorio padre `WhatsApp/Media`, por lo que detecta carpetas Images/Video que WhatsApp cree después de iniciar WA Vault.
- Reintentos cortos y coalescidos cuando el archivo todavía está creciendo o MediaStore aún lo marca como pendiente.
- `MediaStoreWatcher` observa Images, Video y MediaStore.Files e implementa callbacks con URI/flags de Android 30+.
- Asociación pendiente foto/video persistida en SQLite por 10 minutos: sobrevive si Android mata el proceso entre recibir y descargar.
- Recuperación al reiniciar el proceso: busca descargas recientes cuando solo existe un candidato no ambiguo.
- Identificación por `RELATIVE_PATH` y `OWNER_PACKAGE_NAME` (WhatsApp/WhatsApp Business) con `queries` de visibilidad de paquete.
- Soporte para acceso parcial de fotos/videos en Android 14+ y diagnóstico de permisos.

## Medios — rendimiento/UI
- Pantalla principal reducida a filtros + un único menú `⋮`.
- Acciones técnicas (buscar, ordenar, temporales, almacenamiento, borrar todo) movidas al menú.
- Consulta SQLite de medios fuera del hilo principal y filtrada/ordenada en la base.
- Filas ListView reciclables con ViewHolder real.
- Máximo 2 workers de miniaturas.
- Caché LRU de miniaturas en RAM + caché JPEG en disco.
- Un archivo cifrado ya no se descifra de nuevo en cada scroll para reconstruir la miniatura.
- Audio abre un reproductor limpio en vez de incrustar controles pesados en cada fila.
- Orden por defecto: más recientes.

## Diseño
- Nuevo icono WA Vault generado para la app.
- Tarjetas de Medios con menos texto técnico y mejor jerarquía visual.

## Seguridad
- Se mantiene AES-GCM + Android Keystore, sin fallback nuevo a texto plano.
- No se implementa ni intenta archivar contenido “Ver una vez”.


## 0.5.2
- Fixed deleted-message UI persistence: explicit deletion signals now always produce a visible deleted row.
- Added honest deletion tombstones when original text cannot be correlated; no arbitrary latest-message guessing.

## v0.5.6
- Expand capped deletion-marker batches from the active compact conversation burst so 6 consecutive deletions are not truncated to the 4 rows exposed by WhatsApp/Android.

## 0.5.7
- Fixed batch-delete reconstruction order that could cap an 8-message delete at six.
- Raised aggressive captured-burst working set to 64 and added cleanup between resolved batches.
- Added 40 MiB full-video archive cap across notification/FileObserver/MediaStore routes.
- Added visible video pending/oversize placeholders so large or fleeting videos no longer disappear silently from Files.

## 0.5.15
- Eliminado nearest-timestamp como criterio ganador de correlación multimedia.
- Asociación exacta por batch / arm único / FIFO 1:1 de una sola conversación; ambigüedad queda oculta.
- APP_CANCEL separa evidencia observada de backfill SQLite; DB-only no se confirma sin marcador explícito.
- Menor polling y fanout de MediaStore/FileObserver/watchdog para reducir batería/CPU.
- Eliminación definitiva puede limitarse a WA Vault o incluir la copia exportada por WA Vault a Galería.
- Papelera paginada, estado de protección COMPLETA/LIMITADA/ATENCIÓN y acción Reparar protección.
- Logs críticos ampliados y limpieza de manifest/.orig.


## 0.5.29
- Clean NO-FALSE-DELETES rebuild from v0.5.27 stable baseline.
- Poll/restart becomes synchronization only; no deletion diff.
- Empty/current-missing and notification removal are fail-closed.
- Marker-only cannot map historical messages; burst expansion/aggressive fill disabled.
- Deleted messages screen is launch destination.
- Regression tests for restart/process-death/1000 messages/20 restarts.

## v0.5.30 Block 4 — media/storage hardening
- DB v15: physical media blobs separated from many-to-many message links.
- Crash-safe ciphertext-only permanent media commit pipeline.
- Recoverable part/ready staging and encrypted-ready process-death recovery.
- Strict audio/video/document completeness checks before permanent commit.
- 24h global hash retry shield + durable exact-source tombstones.
- Notification URI late-link race hardened with timestamp-scoped pending links and TTL pruning.
- Removed persistent plaintext thumbnail cache and legacy early-audio plaintext staging.
- Generic cleanup can no longer age-delete completed ready captures before recovery.
- Quarantine monitor now self-stops instead of waking every 120ms forever.
- Legacy Android 8/9 public exports roll back partial files on failure and fsync completed writes.

## v0.5.30 — Block 5 runtime/background hardening
- Moved notification preview decode/compress/archive work off NotificationListener main callbacks.
- Made pending-media monitor restoration asynchronous and coalesced.
- Replaced permanent DirectMedia maintenance with one-shot pending-only safety passes.
- Added generation coalescing for DirectMedia and MediaStore retry bursts.
- Coalesced DirectVoice watcher startup/fast scans and self-repair on MOVE_SELF/DELETE_SELF.
- Removed MAX/URGENT_DISPLAY priorities from media copy work.
- Reduced fallback notification polling from 180ms/9s to 750ms/6s and increased idle cadences.
- Made watchdog recovery staging-aware and quiet idle cadence 15 minutes.
- Coalesced Activity startup/cleanup work and moved process cache cleanup/diagnostic DB writes off main startup.
- Made Android 14 partial/denied media-permission setup one-shot instead of prompt-looping.

# A53 Performance v1.15.7 — THERMAL / ANALYSIS

Package: `com.fer.a53performance`  
versionCode: `29`  
versionName: `1.15.7`

## Cambios
- Fotos similares sin límite fijo de 2.500: análisis completo por bloques con progreso persistente en caché.
- Doble firma perceptual por imagen (`dHash` + `aHash`) más relación de aspecto para reducir falsos positivos.
- Pausa térmica real en estado severo: se detiene el trabajo pesado, conserva firmas ya calculadas y continúa cuando baja la temperatura.
- Caché analítica SQLite v3 con quick fingerprint, SHA-256, dHash, aHash y aspecto; poda ampliada a 50.000 entradas.
- Shizuku UserService endurecido: operaciones cerradas, reconexión única y circuit breaker de 8 segundos.
- El servicio privilegiado devuelve solo paquetes de usuario en ejecución; eliminado `QUERY_ALL_PACKAGES`.
- Perfiles verificados después de aplicarlos: frecuencia mínima/máxima, ahorro y Data Saver se leen de vuelta antes de marcar éxito.
- Auto-perfil registra resultado aplicado/verificado y estado local de ejecución.
- Refactor parcial: filtrado/orden de Cleaner y diagnóstico de permisos separados de MainActivity.
- Diagnóstico interno local para Auto, análisis y últimas operaciones; sin telemetría externa.
- Se mantienen RecyclerView virtualizado, paginación, miniaturas limitadas, anti-OOM, búsqueda debounce, Papelera, RAM real y protección de apps críticas.

## Validación
GitHub Actions run `31924913814`: **SUCCESS**.

Build:
- Release / Debug / AndroidTest: PASS.
- package/version metadata: PASS.
- ausencia de POST_NOTIFICATIONS, PACKAGE_USAGE_STATS, WRITE_SETTINGS y QUERY_ALL_PACKAGES: PASS.
- ZIP integrity + zipalign: PASS.

Android API 35:
- 3 tests instrumentados: PASS.
- 10.000 filas con IDs estables y paginación: PASS.
- caché dual visual: PASS.
- Shizuku ausente/offline no provoca crash: PASS.
- 10 ciclos force-stop/start: PASS.
- recreación/orientación ejercitada: PASS.
- presión de memoria ejercitada; Android rechazó repeticiones de un trim ya aplicado, sin crash de la app.
- 400 Monkey events: PASS.
- proceso vivo después del estrés: PASS.
- sin `FATAL EXCEPTION` de `com.fer.a53performance`.

El broadcast protegido `MY_PACKAGE_REPLACED` no puede ser inyectado por `adb shell` en Android 35; su receptor compila y permanece declarado, pero la ruta real se valida al actualizar/reiniciar el dispositivo.

## APK final
SHA-256: `6494cdcbfc0b7c08f420516861c80a4a9699d4dd157634380bc69e001eba9638`

Signer certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`.
- APK Signature Scheme v2: PASS.
- APK Signature Scheme v3: PASS.
- RSA 3072.
- Misma línea de firma que v1.15.4/v1.15.5/v1.15.6: actualización in-place compatible.

## Instalación
Se instala directamente encima de v1.15.4, v1.15.5 o v1.15.6 y conserva los datos. La integración Shizuku + One UI y la pausa térmica real se terminan de validar en el Galaxy A53 físico.

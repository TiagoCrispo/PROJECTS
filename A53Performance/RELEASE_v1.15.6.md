# A53 Performance v1.15.6 — HARDENED

Package: `com.fer.a53performance`  
versionCode: `28`  
versionName: `1.15.6`

## Cambios
- Shizuku endurecido con AIDL de operaciones tipadas/cerradas. El UserService ya no acepta comandos shell arbitrarios.
- Operaciones privilegiadas limitadas a frecuencia de pantalla, Low Power, Data Saver, listado de procesos y force-stop de nombres de paquete validados.
- Mantenimiento interno reducido: poda de caché solo cuando pasaron ~7 días y con batería no baja; sin worker periódico de 12 h.
- Auto de perfil solo se agenda tras reinicio/actualización si el usuario lo activó.
- Duplicados con huella rápida cacheada de bloques inicio/medio/final antes de calcular SHA-256 completo.
- Fotos similares con buckets de Hamming para limitar candidatos, manteniendo comparación contra representante y análisis bajo demanda.
- Caché SQLite v2: quick fingerprint + SHA-256 + dHash por archivo/tamaño/fecha.
- RecyclerView usa MediaStore `_ID` real o FNV-1a 64-bit como fallback, evitando IDs estables de 32 bits.
- Ajustes incorpora diagnóstico de permisos y botón para reparar permisos faltantes.
- `android:allowBackup=false`.
- Eliminados permisos heredados no utilizados: `POST_NOTIFICATIONS`, `PACKAGE_USAGE_STATS`, `WRITE_SETTINGS`.
- Se mantienen paginación, miniaturas limitadas, anti-OOM, búsqueda debounce, borrado/Papelera, perfiles, RAM real y protección de apps críticas.

## Validación
GitHub Actions run `31924056406`: **SUCCESS**.

Build:
- Release / Debug / AndroidTest: PASS.
- package/version metadata: PASS.
- checks de permisos eliminados: PASS.
- ZIP integrity + zipalign: PASS.

Android API 35 hardened emulator:
- 2 tests instrumentados: PASS (5.000 filas con IDs únicos + caché quick/SHA/dHash).
- 5 ciclos force-stop/start: PASS.
- `RUNNING_LOW` memory trim: PASS.
- 150 Monkey events: PASS.
- proceso vivo después del estrés: PASS.
- escaneo de `FATAL EXCEPTION` de la app: PASS.

## APK final
SHA-256: `3781ad474966af1fc2a77429489fd733bcb6e1a4342a5b464879a2e5fc3bd15f`

Signer certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`.
- APK Signature Scheme v2: PASS.
- APK Signature Scheme v3: PASS.
- RSA 3072.
- Misma línea de firma que v1.15.4/v1.15.5: actualización in-place compatible.

## Instalación
Se instala directamente encima de v1.15.4 o v1.15.5 y conserva datos. Si todavía está instalada v1.15.3 o anterior de la firma histórica perdida, se requiere desinstalar esa línea una única vez.

El emulador valida comportamiento Android general y estrés; la integración Shizuku real/One UI se termina de validar en el Galaxy A53 físico.

# A53 Performance v1.15.5 — STABILITY CORE

Package: `com.fer.a53performance`  
versionCode: `27`  
versionName: `1.15.5`

## Cambios
- Migración de comandos privilegiados desde `Shizuku.newProcess`/reflection a `Shizuku.UserService` + AIDL.
- UserService con timeout interno, proceso privilegiado separado y destrucción limpia.
- Trabajo persistente con WorkManager y BootReceiver.
- Auto de perfil es opcional y solo se reaplica tras reinicio/actualización; abrir la app o el mantenimiento periódico no reaplica perfiles.
- Mantenimiento periódico solo poda cachés internas y exige batería no baja.
- Duplicados y fotos similares pasan a ser bajo demanda: no arrancan automáticamente después de cada escaneo.
- Caché SQLite persistente de SHA-256/dHash por archivo+tamaño+fecha, con poda acotada.
- Análisis térmicamente limitado y cancelable.
- Fotos similares usan agrupación contra representante en vez de encadenamiento transitivo, reduciendo falsos positivos.
- RecyclerView/paginación, miniaturas limitadas, búsqueda debounce, borrado/Papelera y protección de apps de v1.15.4 se mantienen.
- Perfiles y RAM siguen serializados, con resultados reales y sin porcentajes/boost falsos.

## Validación
GitHub Actions run `31923205496`: SUCCESS.
- Release + Debug build: PASS.
- package/version metadata: PASS.
- ZIP integrity + zipalign: PASS.
- Android API 35 emulator install/startup: PASS.
- Main process alive after startup: PASS.
- Fatal-crash scan: PASS.

Final APK SHA-256: `202add0e38c83143f9fff23478fd06a80bfa9a3bd63cf2f510870ac65dbe4542`.
Signer certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`.
Signer matches v1.15.4: PASS.
APK Signature Scheme v2: PASS. APK Signature Scheme v3: PASS.

## Instalación
v1.15.5 se instala directamente encima de v1.15.4 y conserva sus datos. Si el teléfono todavía tiene v1.15.3, esa versión pertenece a la firma antigua perdida y requiere desinstalarla una única vez antes de entrar en la línea v1.15.4+.

El emulador valida Android/startup, pero Shizuku real y comportamiento específico del Galaxy A53 requieren el teléfono físico.

# A53 Performance v1.15.4 — PERFORMANCE / STABILITY

Package: `com.fer.a53performance`  
versionCode: `26`  
versionName: `1.15.4`

## Correcciones principales
- RecyclerView virtualizado y páginas progresivas de 60 archivos para no crear cientos/miles de vistas simultáneamente.
- Conserva ancla/offset de scroll, búsqueda, filtro, orden y cantidad ya cargada.
- Miniaturas con solo 2 workers, caché LRU limitada, cancelación al salir de Cleaner y recorte bajo presión de memoria.
- Escaneo y borrado en una cola I/O separada con cancelación por generación.
- Borrado definitivo elimina filas/contadores al instante y vuelve a consultar espacio libre real sin reescaneo completo.
- Papelera mediante MediaStore en Android 11+, ocultando elementos enviados a Papelera del índice normal.
- Búsqueda con debounce para evitar filtrar miles de filas en cada pulsación.
- Duplicados exactos por tamaño + SHA-256 y fotos similares por dHash en una fase de fondo de baja prioridad.
- RAM serializada, cancelable, con timeout por app y medición real antes/después; sin porcentajes inventados.
- Perfiles Clases/Gaming/Rendimiento/Balanced/Cool/Batería/Datos serializados y aplicados fuera del hilo de UI.
- Protección central para Gmail, Mensajes Google/Samsung, Reloj Google/Samsung, Brave, ChatGPT, Samsung Voice Recorder, Teléfono/Contactos y paquetes críticos del sistema.
- Flujo inicial de permisos encadenado: permisos runtime, acceso de archivos y luego Shizuku.
- Sin promesas falsas de overclock, CPU/GPU o RAM liberada.

## Validación
GitHub Actions run `31922466577`: SUCCESS. Compilación Release, package/version metadata, integridad ZIP y zipalign pasaron.

Final APK SHA-256: `9460c14c5efcdad6db487532a4199d4f78b741a5f093a92c3f4cb22c65901248`.
Signer certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`.
APK Signature Scheme v2: PASS. APK Signature Scheme v3: PASS.

## Firma / instalación
La clave privada original de v1.15.3 no pudo recuperarse del material sobreviviente del proyecto. v1.15.4 inicia una nueva línea de firma estable. Android no permite actualizar una v1.15.3 instalada con una clave distinta: hay que desinstalar v1.15.3 una sola vez antes de instalar v1.15.4. Las siguientes versiones pueden actualizar v1.15.4 in-place usando el respaldo privado de esta nueva firma.

La validación realizada aquí cubre build, metadata, ZIP/alineación y firma criptográfica. El comportamiento físico específico del Galaxy A53 se termina de validar al probar esta APK en el teléfono.

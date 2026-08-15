# WA Vault v0.5.0 — Plan base

Base obligatoria: WA Vault v0.4.1 estable, manteniendo `com.fer.wavault`, migraciones de datos y la misma clave de firma iniciada en v0.4.0.

## Objetivos principales

1. **Arquitectura modular**
   - Dividir `MainActivity` en controladores/componentes para Home, Mensajes, Recuperación, Ajustes, Diagnóstico y reproductores.
   - Separar lógica de UI, consultas de datos, captura, almacenamiento y cifrado.

2. **Mensajes realmente incrementales**
   - Migrar la lista principal a RecyclerView + DiffUtil/ListAdapter.
   - Inserciones/actualizaciones puntuales sin reconstruir la pantalla.
   - Mantener pestaña, búsqueda, filtros y scroll incluso bajo ráfagas de eventos.

3. **Recovery Center 2.0**
   - Paginación real basada en cursor/ID y consultas SQLite filtradas.
   - Timeline por archivo: detectado → copiado → verificado → cifrado → guardado/exportado o error.
   - Explicación visible de por qué una recuperación falló.
   - Asociación archivo ↔ mensaje/contacto con nivel de confianza y posibilidad de corregir asociación.
   - Acciones masivas: seleccionar, exportar, compartir, favorito, borrar y mover a papelera.
   - Papelera con restauración y caducidad configurable.
   - Vista calendario, álbumes automáticos por chat y estadísticas de almacenamiento.

4. **Backup/restore independiente del Keystore**
   - Formato de respaldo propio de WA Vault completamente offline.
   - Clave de recuperación protegida por contraseña elegida por el usuario.
   - Exportación/importación verificable antes de reemplazar datos.
   - No subir contenido a nube de forma automática.

5. **Diagnóstico y autorreparación 2.0**
   - Estado de listener, permisos, batería, almacenamiento, última captura, última copia y errores recientes.
   - Pruebas guiadas por tipo de contenido.
   - Reintento seguro de recuperaciones incompletas sin duplicar archivos.

6. **Rendimiento**
   - Carga asíncrona de DB y thumbnails.
   - Caché limitada de miniaturas.
   - Consultas indexadas y por páginas, sin N+1.
   - Trabajo pesado fuera del hilo de UI y por lotes interrumpibles.

7. **Compatibilidad y seguridad**
   - Mantener Fast Capture / FD-first como principio central.
   - Mantener AES-GCM y Android Keystore para almacenamiento local.
   - No prometer acceso a datos privados que Android/WhatsApp no expongan.
   - No intentar saltar scoped storage, permisos o protecciones del sistema.

## Criterio de salida v0.5.0

No llamar v0.5.0 final hasta que compile, firme con la misma clave, migre una base v0.4.1, soporte bibliotecas grandes sin reconstrucciones visibles y pase la matriz de pruebas automatizables. Las pruebas físicas específicas de dispositivo se documentarán por separado.

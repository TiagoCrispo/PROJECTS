# WA Vault v0.5.16 — Borrado estricto + Cohortes multimedia

Versión: 0.5.16 (versionCode 66)  
Base de datos: v13 (sin migración de esquema)

## Objetivo
v0.5.16 corrige una fuente real de falsos positivos de v0.5.15 y termina de endurecer la correlación multimedia sin volver a usar proximidad temporal como ganador.

Principio de esta versión:
> Una notificación que desaparece no significa que el mensaje fue borrado. Solo un marcador explícito de WhatsApp puede elevar ese evento a borrado confirmado.

## 1. Corrección crítica: mensajes normales ya no deben convertirse en “Mensaje borrado”
En v0.5.15, `REASON_APP_CANCEL` podía terminar en `DELETE_CONFIRMED` cuando una notificación desaparecía durante dos verificaciones, incluso sin un texto explícito de eliminación. WhatsApp/Android también usa APP_CANCEL al reagrupar, refrescar o retirar notificaciones normales.

v0.5.16 cambia la regla:
- `APP_CANCEL` por sí solo nunca confirma un borrado.
- Si no existe marcador literal de eliminación, cualquier `DELETE_PROBABLE` creado durante esa ruta se revierte.
- Solo marcadores completos de WhatsApp, como `Se eliminó este mensaje` / `Este mensaje fue eliminado` / equivalentes completos en idiomas soportados, pueden confirmar.
- Frases humanas genéricas como `mensaje eliminado`, `message deleted` o `mensagem apagada` ya no son prueba fuerte por sí solas.
- Se mantiene el historial interno de candidatos para diagnóstico, pero no se muestra al usuario como borrado.

### Limpieza al actualizar desde v0.5.15
Al primer arranque, una reparación acotada revisa el audit log reciente de v0.5.15 y degrada confirmaciones que puedan vincularse específicamente con la vieja ruta `APP_CANCEL directo=`. Los medios vinculados vuelven a cuarentena oculta. Los borrados confirmados por marcador explícito no se tocan.

## 2. Documentos con la misma correlación persistente que el resto
PDF, DOC/DOCX, XLS/XLSX, PPT/PPTX, ZIP/RAR/7Z, CSV, RTF y EPUB ahora pueden:
- crear `pending_manual_media` persistente;
- sobrevivir a reinicio del proceso durante la ventana de captura;
- recuperarse mediante MediaStore.Files cuando Android los expone allí;
- participar en reconciliación final tras borrado confirmado.

## 3. Micro-cohortes multimedia
La reconciliación FIFO ya no intenta resolver todos los arms vivos de un tipo como un único lote gigante.

Ahora cada cohorte exige:
- misma conversación;
- ráfaga compacta (gap consecutivo máximo ~9 s);
- mismo número de arms y archivos;
- cada par dentro de una ventana estricta aproximada de -1.5 s / +8 s.

Si una cohorte es ambigua, queda oculta. Una cohorte independiente y demostrable puede resolverse aunque exista otra ambigua más tarde.

## 4. Arms persistentes uniformes
- MediaStore: máximo 10 min.
- DirectMedia: máximo 10 min.
- Audio persistente: máximo 10 min.

La asociación directa sigue usando ventanas mucho más cortas; los 10 minutos son solo el máximo de vida del contexto persistido/cuarentena.

## 5. Menor actividad del NotificationListener
Los callbacks siguen siendo la ruta principal. El polling queda como respaldo:
- actividad reciente: ~180 ms;
- reposo: ~4 s;
- ventana “hot”: ~9 s.

Esto reduce trabajo constante sin sacrificar la reacción durante una ráfaga real.

## 6. Recuperados conserva posición
Cuando llega una actualización con Recuperados abierto:
- conserva el ID del primer archivo visible;
- conserva su offset vertical;
- conserva la cantidad de filas ya cargadas;
- recarga lo suficiente para restaurar exactamente el ancla.

No debería volver a saltar al principio mientras el usuario está revisando contenido.

## 7. Videos: límite adaptativo
El límite deja de ser siempre 40 MB y depende del espacio libre del dispositivo:
- almacenamiento bajo: 40 MB;
- normal: 100 MB;
- holgado: 200 MB.

La copia continúa siendo por streaming. Un video que excede el límite se descarta completo; nunca se conserva un MP4 truncado.

## 8. Inicio simple, sin ocultar el estado
Cuando todo está operativo, el encabezado muestra `WA Vault activo`.
Debajo se mantiene el estado global (`Protección completa/limitada/Requiere atención`) y el estado visible de:
- Notificaciones;
- Archivos;
- Batería;
- Fotos y videos;
- Audios;
- MediaStore;
- Autorreparación.

`Reparar protección` permanece accesible.

## Invariantes conservados
- Recovery Center solo muestra `DELETE_CONFIRMED`.
- No existe un ganador por “timestamp más cercano”.
- Tombstones evitan resurrección tras borrado definitivo.
- Stickers siguen excluidos.
- UI no modifica correlaciones al visualizar contenido.
- SQLite continúa en v13.
- Mismo package y misma clave de firma para actualizar in-place.

## Prueba de dispositivo prioritaria
1. Una segunda persona envía 10–20 mensajes normales y NO borra ninguno.
2. Dejar que WhatsApp agrupe/republique/retire notificaciones, abrir algunas y esperar.
3. Resultado esperado: **0 mensajes nuevos en Borrados**.
4. Después borrar explícitamente 1 mensaje desde el emisor.
5. Resultado esperado: solo ese mensaje aparece como borrado confirmado si Android/WhatsApp expone el marcador.

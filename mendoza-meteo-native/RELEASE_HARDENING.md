# Mendoza Meteo X10 v6.3 — Release hardening

Este documento define el gate físico previo a generar el APK final. CI no sustituye estas pruebas porque Android/One UI, Doze, permisos, launcher y la red real del teléfono pueden comportarse distinto a un runner Linux.

## Gate automático

Debe quedar verde en el mismo SHA que se pretenda liberar:

- `provider-contract`: contratos Open-Meteo Best Match, GFS y ECMWF.
- `official-contract`: SMN cuando el origen permite acceso; si GitHub recibe 403, debe quedar explícitamente `RESTRICTED`, nunca falsamente `OK`. Mendoza DCC debe seguir validándose.
- `release-contract`: versión, permisos, no background location, no exact alarms, widget 2x2, aislamiento de caché local/UTN, ausencia de material de firma y ausencia de referencias de versión obsoletas.
- `testDebugUnitTest`.
- `lintRelease`.
- `assembleDebug` y `assembleRelease` limpios.
- `zipalign`, integridad ZIP y contrato del APK `versionCode 63` / `6.3-native-dev`.

## Gate físico Samsung / One UI

Usar una instalación limpia para el test de release; no actualizar encima de un APK antiguo.

1. **Primer arranque**
   - Instalar desde cero.
   - Abrir desde launcher.
   - Verificar que no crashea ni queda pantalla negra.
   - Android 13+ debe solicitar notificaciones una sola vez.
   - Después debe solicitar ubicación desde la pantalla principal.
   - Rechazar cada permiso por separado y comprobar que la app sigue utilizable.

2. **Ubicación**
   - Con ubicación precisa: debe mostrar ubicación local y persistirla para background.
   - Con ubicación aproximada: no debe forzar GPS preciso.
   - Revocar ubicación después de obtener una posición: widget/notificaciones pueden reutilizar la posición persistida hasta 48 h, sin consultar GPS en background.
   - Pasadas 48 h o sin posición guardada: background debe usar referencia UTN.
   - Cambio material de zona: no deben sobrevivir alertas ni cachés pertenecientes a la zona anterior.

3. **Widget 2x2**
   - Añadirlo desde el selector de One UI y confirmar tamaño inicial 2x2.
   - Deben verse: temperatura actual + 7 filas + máxima/mínima + % lluvia.
   - Tocar widget: debe entrar por `LauncherActivity`, no saltarse el gate de notificaciones.
   - Sin red: debe mostrar solo caché válido del mismo contexto geográfico.
   - Pasar de ubicación personalizada a fallback UTN: nunca debe presentar el forecast local como si fuera UTN.
   - Cambiar más de 10 km: un caché de widget anterior debe invalidarse antes de mostrarse.
   - Verificar contraste, recortes de texto y que One UI no reduzca el contenido a menos de 2x2.

4. **Red y caché**
   - Wi‑Fi funcional → datos móviles → modo avión → Wi‑Fi.
   - Forecast fresco debe reemplazar caché.
   - Caché stale debe indicarse con `↻`; very stale con `⚠`.
   - Caché expirado no debe mostrarse como actual.
   - Un error HTTP permanente no debe generar un ciclo de retry agresivo.

5. **Notificaciones**
   - Desactivar todas las notificaciones desde Ajustes: el worker no debe intentar publicar tarjetas.
   - Desactivar solo un canal: no debe quedar marcado como entregado si Android lo bloqueó.
   - Nueva alerta oficial: notifica.
   - Amarillo→Naranja/Rojo: actualiza inmediatamente.
   - Naranja→Amarillo: actualiza inmediatamente y no deja la tarjeta vieja.
   - Evento resuelto: elimina tarjeta.
   - Oficial y X10 equivalentes en la misma ventana: solo oficial.
   - Tormenta X10 debe absorber lluvia X10 equivalente.
   - Duplicado idéntico: no repetir spam.

6. **Background / batería**
   - Cerrar la app normalmente: WorkManager debe seguir programado.
   - Reiniciar el teléfono y abrir una vez: comprobar que el scheduler vuelve a estar operativo.
   - Dejar el teléfono en reposo/Doze: aceptar retrasos del sistema; no exigir tiempo real.
   - `Force stop`: documentar que Android bloquea background hasta volver a abrir la app.
   - Confirmar ausencia de foreground service y alarmas exactas.

7. **SMN real**
   - Ejecutar desde Wi‑Fi o datos móviles argentinos/no-datacenter.
   - Confirmar acceso a CAP o API SMN con TLS válido.
   - Si existe alerta activa, comprobar parseo de nivel, fenómeno, área y vigencia.
   - Si no hay alertas activas, respuesta vacía válida debe considerarse éxito, no error.

## Criterio de aceptación

No generar ni distribuir APK final mientras exista alguno de estos fallos: crash, widget que mezcle ubicaciones, dato expirado mostrado como fresco, alerta oficial duplicada por X10, notificación obsoleta que no se limpia, background location no solicitada explícitamente, tráfico HTTP claro, versión desincronizada o CI rojo.

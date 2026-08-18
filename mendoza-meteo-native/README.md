# Mendoza Meteo X10 — Native v6.3

Rebuild nativo del paquete `com.mendozameteo.x10`, sin parches DEX.

- Android nativo Java, API 24–36.
- Pantalla: 7 días → 24 h → ubicación/precaución → UTN Mendoza.
- Widget real 2x2: temperatura actual + 7 días + máxima/mínima + % lluvia.
- Widget v6.3 location-safe: no consulta GPS en background, usa la última ubicación persistida por foreground hasta 48 h, separa caché `local`/`utn` y rechaza caché de otra zona (>10 km).
- Open-Meteo mediante HTTPS con timeouts, validación, caché y fallback.
- Alertas oficiales separadas de heurísticas X10: SMN CAP/API + Contingencias Climáticas Mendoza.
- Cachés oficiales SMN/Mendoza separados, TTL independiente y reutilización limitada al contexto geográfico consultado.
- Notificaciones Android con `POST_NOTIFICATIONS`, canales por prioridad y WorkManager 2.11.2.
- Prioridad oficial real: una alerta SMN/Mendoza se procesa antes que el forecast heurístico X10.
- Anti-spam persistente: nuevo aviso y escalada inmediata; de-escaladas y cambios oficiales de horario inmediatos; cambios textuales al mismo nivel con cooldown; una señal X10 idéntica no se convierte en recordatorio periódico.
- Supresión X10/oficial por familia **y ventana temporal**: una alerta oficial de mañana no oculta una señal X10 distinta de esta noche.
- Estado de notificaciones v2: expiración oficial separada del guard interno anti-stale, limpieza de señales X10 resueltas y reset al cambiar materialmente de zona.
- Background sin `ACCESS_BACKGROUND_LOCATION`: usa la última ubicación guardada en foreground hasta 48 h y luego referencia UTN Mendoza sin contaminar el caché `local`.
- Retry oficial selectivo: fallas transitorias pueden reintentarse una vez; HTTP permanente/invalid data no entra en bucle.
- Señales X10 conservadoras para lluvia/tormenta y posible Zonda; nunca cambian el nivel de una alerta oficial.
- CI v6.3: provider smoke + official-source smoke + release contract + unit tests + Lint + debug/release build + zipalign + contrato APK.
- CI actualizado a Node 24 mediante `actions/checkout@v5` y `android-actions/setup-android@v4`; instala solo los paquetes Android SDK necesarios.
- Sin analytics, sin WebView, sin claves API persistentes, sin material de firma en Git y sin tráfico HTTP claro.

Versión de desarrollo actual: `6.3-native-dev` (`versionCode 63`).

Antes del APK final queda obligatorio el smoke físico descrito en `RELEASE_HARDENING.md`, especialmente Samsung/One UI, widget 2x2 y acceso SMN desde una red real no bloqueada por datacenter.

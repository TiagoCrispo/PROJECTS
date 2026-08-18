# Mendoza Meteo X10 — Native v6.2

Rebuild nativo del paquete `com.mendozameteo.x10`, sin parches DEX.

- Android nativo Java, API 24–36.
- Pantalla: 7 días → 24 h → ubicación/precaución → UTN Mendoza.
- Widget real 2x2: temperatura actual + 7 días + máxima/mínima + % lluvia.
- Open-Meteo mediante HTTPS con timeouts, validación, caché y fallback.
- Alertas oficiales separadas de heurísticas X10: SMN CAP/API + Contingencias Climáticas Mendoza.
- Notificaciones Android v6.2 con `POST_NOTIFICATIONS`, canales por prioridad y WorkManager 2.11.2.
- Prioridad oficial real: una alerta SMN/Mendoza se procesa antes que el forecast heurístico X10.
- Anti-spam persistente: nuevo aviso y escalada inmediata; cambios oficiales de horario inmediatos; cambios textuales al mismo nivel con cooldown; una señal X10 idéntica no se convierte en recordatorio periódico.
- Supresión X10/oficial por familia **y ventana temporal**: una alerta oficial de mañana no oculta una señal X10 distinta de esta noche.
- Estado de notificaciones v2: expiración oficial separada del guard interno anti-stale y limpieza de señales X10 resueltas.
- Background sin `ACCESS_BACKGROUND_LOCATION`: usa la última ubicación guardada en foreground hasta 48 h y luego referencia UTN Mendoza.
- Señales X10 conservadoras para lluvia/tormenta y posible Zonda; nunca cambian el nivel de una alerta oficial.
- Sin analytics, sin WebView, sin claves API persistentes y sin tráfico HTTP claro.

Versión de desarrollo actual: `6.2-native-dev` (`versionCode 62`).

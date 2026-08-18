# Mendoza Meteo X10 — Native v6.6

Rebuild nativo del paquete `com.mendozameteo.x10`, sin parches DEX.

- Android nativo Java, API 24–36.
- Pantalla: 7 días → 24 h → ubicación/precaución → UTN Mendoza.
- Widget real 2x2: temperatura actual + 7 días + máxima/mínima + % lluvia.
- Widget v6.6 location-safe: no consulta GPS en background, usa la última ubicación persistida por foreground hasta 48 h, separa caché `local`/`utn` y rechaza caché de otra zona (>10 km).
- Open-Meteo mediante HTTPS con timeouts, validación, caché y fallback.
- Freshness hardening: timestamps de forecast o ubicación absurdamente futuros se invalidan; solo se tolera un pequeño desfase de reloj de hasta 10 min.
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
- Toolchain: AGP 9.3.1 + Gradle Wrapper 9.5.0 + JDK 17 + Build Tools 36.0.0.
- Gradle Wrapper versionado y protegido con SHA-256 oficial de la distribución; clones y CI usan `./gradlew`, sin depender de un Gradle global.
- User-Agent Android sincronizado automáticamente con `BuildConfig.VERSION_NAME`; el provider smoke deriva la versión directamente de `app/build.gradle`.
- CI v6.6: provider smoke + official-source smoke + release contract + unit tests + Lint + debug/release build + zipalign + contrato APK.
- CI Node 24: `actions/checkout@v7`, `android-actions/setup-android@v4`, `gradle/actions/setup-gradle@v6` con `cache-provider: basic`.
- Warning-free gate: `javac -Werror` convierte warnings Java no suprimidos en fallo y Gradle usa `--warning-mode fail`, por lo que una deprecación de build/tooling ya no puede quedar escondida detrás de un CI verde.
- La ruta legacy de ubicación para API 24–29 está aislada explícitamente; Android 30+ usa `getCurrentLocation()`.
- Sin analytics, sin WebView, sin claves API persistentes, sin material de firma en Git y sin tráfico HTTP claro.

Versión de desarrollo actual: `6.6-native-dev` (`versionCode 66`).

Antes del APK final queda obligatorio el smoke físico descrito en `RELEASE_HARDENING.md`, especialmente Samsung/One UI, widget 2x2 y acceso SMN desde una red real no bloqueada por datacenter.

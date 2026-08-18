# Mendoza Meteo X10 — Native v6

Rebuild nativo del paquete `com.mendozameteo.x10`, sin parches DEX.

- Android nativo Java, API 24–36.
- Pantalla: 7 días → 24 h → ubicación/precaución → UTN Mendoza.
- Widget real 2x2: temperatura actual + 7 días + máxima/mínima + % lluvia.
- Open-Meteo mediante HTTPS con timeouts y validación.
- Widget con caché y última ubicación válida; fallback UTN FR Mendoza.
- Alertas internas conservadoras para lluvia/tormenta y señal de Zonda.
- Sin analytics, sin WebView, sin claves API.

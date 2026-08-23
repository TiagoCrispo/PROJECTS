# Damián Crispo — Software Projects

Developer portfolio focused on **Android applications, Windows performance tooling, local-first software, diagnostics, AI-assisted learning and reliable product engineering**.

I build software around real problems and try to make the engineering visible: clear state, useful diagnostics, reversible changes, explicit validation and honest failure modes instead of simulated success.

## Selected work

| Project | Focus | Status |
|---|---|---|
| **[ProAim](./projects/proaim.md)** | Windows 11 performance, latency and hardware diagnostics for competitive gaming | Stable · v26.7 |
| **[UTN Scholar](./projects/utn-scholar.md)** | Cross-device study workspace for university documents and AI-assisted learning | Active development · 0.59.0+59 |
| **[FurnitureShot AI](./projects/furnitureshot-ai.md)** | Android product-photo processing with conservative fidelity controls | Active beta · v0.7.1-beta22 |
| **[BandLab](./projects/bandlab.md)** | Local-first Android training companion with Xiaomi Smart Band, GPS and workout analytics | Verified release · 2.6.0 |
| **[Galaxy A53 Performance](./projects/galaxy-a53-performance/README.md)** | Reversible Android performance, storage and device diagnostics | Release candidate · v1.20.0 beta |
| **[WA Vault](./projects/wa-vault.md)** | Privacy-focused Android event/media preservation with durable local state | Active release · v0.5.31 |
| **[Meteora Weather](./projects/meteora-weather.md)** | Weather client with caching, validation and explicit degraded states | Active development |

## What this portfolio demonstrates

- **Android engineering:** lifecycle reliability, local persistence, background work, Bluetooth LE, GPS/GNSS, Android Keystore, system capabilities and device-specific validation.
- **Windows tooling:** diagnostics, controlled performance experiments, rollback-oriented tuning and evidence-driven troubleshooting.
- **Reliability:** fail-closed behavior, idempotent processing, recovery after restart/process death, bounded work and explicit error states.
- **Product thinking:** clear UX states, accessibility, conservative automation and features designed around real user workflows.
- **Release discipline:** reproducible builds, regression gates, package/signature checks and a clear distinction between static/CI validation and physical-device acceptance.

## Engineering principles

1. **Root cause before patching.** Understand and reproduce a problem before changing behavior.
2. **Evidence over assumptions.** Missing or unverified data stays missing or unverified.
3. **Reversible by default.** System-changing features should snapshot, verify and preserve rollback paths.
4. **Local-first when appropriate.** Sensitive or personal data should not leave the device unless the product genuinely requires it.
5. **Validation is part of the product.** Builds, tests and diagnostics matter, but they are not presented as proof of runtime behavior they did not actually test.
6. **Readable software matters.** Documentation, naming, state feedback and failure handling are treated as engineering work.

## Project catalog

The full portfolio, current status and individual project pages are organized under **[`projects/`](./projects/)**.

Repository-wide engineering standards are documented in **[`AGENTS.md`](./AGENTS.md)**.

## Earlier work

**[Global Food](./projects/global-food.md)** is a small HTML/CSS project from 2021 kept as a visible starting point and a record of progression from static frontend work into native applications and systems-oriented tooling.

---

*This repository is a portfolio and engineering workspace. Project pages distinguish implemented features, verified releases, release candidates and work that still requires physical-device validation.*

# ProAim v26.7 — PROAIM-ULTIMATE-X50

ProAim is an evidence-first Windows 11 performance and latency utility for competitive gaming, with a native Win32 GUI written in Go.

## Release philosophy

The project does not treat popular tweaks as improvements. V26.7 uses a feedback loop:

`Runtime Validator -> USER-PC baseline -> root-cause ranking -> controlled experiment -> regression gate -> KEEP/ROLLBACK -> repeated game profile`

## Major systems

- **Input Feel Engine:** Raw Input, USB/RF, polling, DPC/ISR, PresentMon, GPU/frame pacing, display and network evidence.
- **Frame / Cap Lab:** average FPS, 1% low, 0.1% low, P95/P99, stutter and repeated cap samples.
- **Ethernet / Wi-Fi Lab:** gateway/upstream/Internet path, jitter, loss, loaded latency and controlled NIC A/B.
- **NVIDIA Latency Lab:** driver/runtime state, GPU saturation, HAGS and Reflex guidance without undocumented profile writes.
- **CPU / Power Lab:** temporary ProAim-owned power plans, repeated benchmarks and exact rollback.
- **Driver Lifecycle:** BEFORE Validator -> NOW -> official dated reference, followed by re-testing.
- **Recommendation Engine:** `WHY MOUSE FEELS BAD?` and `WHAT SHOULD I FIX FIRST?`.
- **Experiment Engine:** local evidence ledger with baseline, after, delta, samples, confidence, decision and rollback metadata.
- **Validator Feedback Loop:** imports bounded Validator ZIP evidence and converts it into a timestamped USER-PC baseline.

## Safety model

No HPET/BCD timer packs, global timer forcing, REALTIME priority, generic TCP/Nagle packs, MouseDataQueueSize/MouseTransmitTimeout writes, blanket checksum-offload disable, RAM cleaners, mass service disabling, Defender/Firewall disabling, process injection, hidden hooks or automatic BIOS writes.

Experimental settings require snapshots, multiple samples, regression checks and rollback.

## Release metadata

- Version: **26.7.0**
- Channel: **STABLE**
- Release date: **2026-08-20**
- Target: **Windows 11 x64**
- Build: **Go/amd64, CGO disabled, Windows GUI subsystem**
- Source: **54 Go files / 31,685 lines**
- EXE SHA-256: `9cc38e4c591f9b5da69b2002da9609d8aff5256b7a25eb82c98842385f69bcec`
- Stable ZIP SHA-256: `9783112234bb7eeb8383a35adc716b9b23624a719e22532cc59c2fd067a38945`
- Source archive SHA-256: `9a32b8f4ce3c5249fde368192127e8dde5c9d39eff36eb6f23a2b83c9aea187a`

## Documentation

- [CHANGELOG](./CHANGELOG.md)
- [VALIDATION](./VALIDATION.md)
- [ACCEPTANCE CRITERIA](./ACCEPTANCE_CRITERIA.md)
- [USER-PC BASELINE](./USER_PC_BASELINE.md)
- [SOURCE INDEX](./SOURCE_INDEX.md)
- [RELEASE HASHES](./RELEASE_HASHES.md)

## Runtime boundary

Cross-build/package integrity is certifiable outside Windows. Physical Raw Input, USB/RF behavior, real NIC reset/property semantics, Wi-Fi RF, DPC/ISR under gameplay and PresentMon after current driver changes remain target-PC acceptance items. Missing evidence is **N/D**, never a fabricated PASS.

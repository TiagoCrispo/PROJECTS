# ProAim

**Current stable release: ProAim v26.7 — PROAIM-ULTIMATE-X50**

ProAim is a native Windows 11 performance, latency and diagnostics utility built in Go for competitive gaming workflows, especially CS2 and VALORANT.

## Current direction

V26.7 moves the project beyond tweak packs. The application imports a Runtime Validator report, builds a local USER-PC baseline, ranks the most likely bottleneck, recommends what to fix first, and records controlled experiments with confidence and rollback evidence.

The core loop is:

`Validator ZIP -> USER-PC BASELINE -> diagnosis -> controlled A/B test -> regression gate -> KEEP/ROLLBACK -> repeated game profile`

## Current capabilities

- Input Feel analysis across Raw Input, USB/RF, polling, DPC/ISR, frame pacing, GPU state, display and network evidence.
- PresentMon-based FPS, 1% low, 0.1% low, P95/P99 and repeated cap experiments.
- Ethernet/Wi-Fi path, jitter, loss, loaded latency, bufferbloat and controlled NIC A/B.
- NVIDIA latency guidance without undocumented/private NVAPI profile writes.
- CPU/Power experiments with owned temporary plans, repeated samples and exact rollback.
- Driver Lifecycle view: Validator BEFORE -> NOW -> official dated reference, followed by re-testing instead of assuming newer is faster.
- WHY MOUSE FEELS BAD? root-cause ranking and WHAT SHOULD I FIX FIRST? prioritization.
- Local experiment/evidence tree with no automatic telemetry upload.

## Safety principles

ProAim does not use HPET/BCD forcing, global timer-resolution packs, REALTIME priority, generic TCP/Nagle registry packs, MouseDataQueueSize/MouseTransmitTimeout writes, blanket checksum-offload disabling, RAM cleaners, mass service disabling, Defender/Firewall disabling, process injection or automatic BIOS writes.

Experimental changes require snapshot, repeated evidence, regression checks and rollback.

## Release target

Windows 11 x64 · Go/amd64 · CGO disabled · native Win32 GUI · STABLE channel.

Detailed release documentation is kept under [`projects/proaim/`](./proaim/README.md).

# ProAim

> Evidence-driven Windows 11 performance and latency diagnostics for competitive gaming.

| | |
|---|---|
| **Status** | Stable · **v26.7 — PROAIM-ULTIMATE-X50** |
| **Platform** | Windows 11 x64 |
| **Implementation** | Go/amd64 · native Win32 GUI · CGO disabled |
| **Focus** | Latency · frame pacing · input feel · network · hardware diagnostics |

## Overview

ProAim is a native Windows utility built for competitive gaming workflows, especially **CS2** and **VALORANT**. Instead of applying generic “gaming tweaks”, it builds a local baseline, ranks likely bottlenecks and turns changes into controlled experiments with measurable results and rollback evidence.

The core workflow is:

`Validator report → local baseline → diagnosis → controlled A/B test → regression gate → keep / rollback`

## What it demonstrates

- root-cause ranking across input, USB/RF, DPC/ISR, frame pacing, GPU state, display and network evidence;
- PresentMon-based FPS, 1% low, 0.1% low, P95/P99 and repeated frame-cap experiments;
- Ethernet/Wi-Fi path analysis, jitter, loss, loaded latency and controlled NIC comparisons;
- CPU/power experiments using owned temporary plans and exact rollback;
- driver lifecycle comparison based on observed evidence rather than assuming newer is faster;
- prioritized **“what should I fix first?”** diagnostics instead of a long list of undifferentiated tweaks;
- local experiment history with no automatic telemetry upload.

## Engineering approach

The project deliberately avoids common high-risk optimization patterns such as forced HPET/BCD changes, blanket service disabling, RAM cleaners, REALTIME priority, generic TCP registry packs, Defender/Firewall disabling, process injection and automatic BIOS writes.

Experimental changes are expected to have:

1. a baseline;
2. a snapshot or reversible state;
3. repeated measurements;
4. a regression check;
5. an explicit keep/rollback decision.

## Why this project matters

ProAim is less about “tweaking Windows” and more about building a **diagnostic decision system**: collect evidence, isolate a likely cause, change one controlled variable and verify whether the result is actually better.

## Release target

**Windows 11 x64 · stable channel**

Detailed release and engineering documentation lives under **[`projects/proaim/`](./proaim/README.md)**.

# ProAim v26.7 — USER-PC Baseline

Source: ProAim Runtime Validator v1.0 report captured 2026-08-18. This is a historical BEFORE baseline; drivers changed after the capture must be re-measured.

## Hardware truth

- Board: ASUSTeK PRIME X570-P
- BIOS: 5044
- CPU: AMD Ryzen 7 5700X · 8C/16T
- RAM: 16 GB Corsair DDR4 · 3000 MT/s configured
- GPU: NVIDIA GeForce RTX 2060
- GPU driver at baseline: 610.74
- Display: 1920×1080 @ ~143/144 Hz
- Ethernet: Realtek PCIe GbE Family Controller · 1 Gbps
- Ethernet driver at baseline: 1.0.0.14 · 2017-08-10 · rtcx21x64.sys
- G HUB observed at baseline: 2026.5.939708
- Storage: WD Green SN350 1TB NVMe SSD + SATA3 1TB SSD; both Healthy

## Network baseline

- Internet idle: median 19 ms · 0% loss
- Download loaded: median 19 ms · delta 0 ms · 0% loss
- Upload loaded: median 19 ms · delta 0 ms · 0% loss
- Grade: A
- Gateway ICMP: 2/20 replies / 90% apparent loss while Internet had 20/20. This is treated as possible gateway ICMP filtering/rate limiting, not proof of 90% Ethernet packet loss.

## Mouse / input baseline

- Validator reported: Mouse VID_046D PID_C547
- Reported estimate: 125 Hz; observed 133.4 Hz; P50 8.000 ms; P99 14.829 ms; stability 98.9/100
- Quality warning: Validator v1.0 may have sampled an ambiguous Logitech receiver HID collection. V26.7 marks this polling value UNTRUSTED for root-cause decisions.
- User annotation recorded in the Validator: mouse felt heavy in VALORANT, especially for micro-adjustments.

## VALORANT frame baseline

The original Validator summary was contaminated by PresentMon attach/startup warm-up.

After discarding the first 2000 ms:

- Frames retained: 3217
- Average FPS: ~179.10
- 1% low: ~96.12 FPS
- 0.1% low: ~59.60 FPS
- Median frametime: ~5.537 ms
- P95: ~6.350 ms
- P99: ~7.838 ms
- Worst retained frame: ~18.457 ms
- Stutter > max(20 ms, 2.5× median): 0% in retained window
- Average CPU busy: ~5.505 ms
- Average GPU busy: ~2.317 ms
- Software-visible input-to-photon column when present: median ~7.06 ms · P95 ~10.84 ms · P99 ~12.42 ms
- Config annotation: cap 180 · Reflex on+boost

## Interpretation boundary

This sample does not prove the source of subjective mouse heaviness. At baseline, catastrophic GPU saturation and network loaded-latency are not supported. Highest-value next evidence is fresh mouse-path polling/USB/RF plus repeated cap/Reflex/background samples after driver changes.

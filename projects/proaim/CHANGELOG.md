# Changelog

## v26.7 — PROAIM-ULTIMATE-X50 — 2026-08-20

### Added
- Safe Runtime Validator ZIP import and local USER-PC baseline.
- PresentMon Validator re-parser that discards the first 2 seconds of attach/startup warm-up.
- Ambiguous Logitech receiver HID handling: old PID_C547 low-rate samples are not treated as physical mouse polling truth.
- `WHY MOUSE FEELS BAD?` root-cause ranking.
- `WHAT SHOULD I FIX FIRST?` impact/confidence/risk/effort prioritizer.
- Driver Lifecycle BEFORE -> NOW -> official reference view.
- Unified experiment ledger and evidence tree.
- Confidence gates based on sample count and repeatability.

### Experiment-engine integration
- CPU/Power A/B records baseline, after, delta, confidence, decision and rollback metadata.
- Ethernet A/B X30 records idle network score, loaded latency, packet loss and DPC/ISR evidence.
- CAP/FRAME single passes are `SAMPLE_ONLY`; one run cannot become a winner.
- Game Feel Profile single passes are `PROFILE_SAMPLE`; repeated ranking still requires multiple captures.

### Fixed
- Startup recovery notes no longer overwrite an earlier unexpected-close warning.
- Production Check and SELF TEST release identity updated to v26.7.
- Current driver lifecycle probes run concurrently rather than serially.
- AMD X570 official reference updated to **8.08.12.551 (2026-08-14)**.

### Safety preserved
No BCD/HPET packs, global timer forcing, REALTIME priority, generic TCP/Nagle packs, MouseDataQueueSize/MouseTransmitTimeout writes, blanket checksum disable, RAM cleaners, Defender/Firewall disabling, process injection or automatic BIOS writes.

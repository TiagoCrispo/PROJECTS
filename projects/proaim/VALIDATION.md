# Validation — ProAim v26.7

## Build gate
- 54 Go files / 31,685 source lines.
- `gofmt`: PASS.
- `go vet` for Windows/amd64: PASS.
- Two frozen-source Windows x64 builds: byte-identical PASS.
- PE32+ / AMD64 / Windows GUI subsystem: PASS.
- EXE SHA-256: `9cc38e4c591f9b5da69b2002da9609d8aff5256b7a25eb82c98842385f69bcec`.

## UI/runtime structure
- Source-audit button calls: 345.
- Intentional disabled informational controls: 3 (`PROTEGIDO`, `MANTENER`, `REVISAR`).
- `runTimeout` call sites: 129.
- `nativeAsync` call sites: 291.
- Direct `os.WriteFile` call sites: 0.

## Ultimate-X50 gates
- Validator ZIP import is bounded and rejects traversal: PASS.
- USER-PC baseline is local/atomic and source-labelled: PASS.
- Validator 1.0 ambiguous Logitech receiver polling is marked untrusted: PASS.
- PresentMon old Validator CSV warm-up correction (first 2000 ms): PASS.
- CPU/Power and Ethernet A/B emit structured experiment/rollback records: PASS.
- CAP and profile single samples cannot become causal winners: PASS.
- One sample cannot become MEDIUM/HIGH confidence: PASS.

## Static safety gate
Zero operational matches for BCD/HPET forcing, `NtSetTimerResolution`, REALTIME priority, process injection primitives, generic TCPAckFrequency/TcpNoDelay packs and security-disabling logic. `timeBeginPeriod(1)` remains one paired Aim-Trainer-only call with matching `timeEndPeriod(1)`. One runtime `taskkill.exe` path remains only for explicit user-confirmed suspected leak restart.

## Package gate
- Manifest: 85/85 PASS.
- Total package files including manifest: 86.
- Exactly one EXE: PASS.
- Old ProAim EXEs: 0.
- Clean extracted manifest: 85/85 PASS.
- Extracted EXE byte-identical: PASS.
- Stable ZIP SHA-256: `9783112234bb7eeb8383a35adc716b9b23624a719e22532cc59c2fd067a38945`.

## Runtime boundary
Physical Raw Input/USB/RF, NIC reset semantics, Wi-Fi RF, DPC/ISR under gameplay, PresentMon after current driver changes, HAGS reboot experiments and subjective mouse feel require target-Windows testing and are deliberately not fabricated as build-time PASS results.

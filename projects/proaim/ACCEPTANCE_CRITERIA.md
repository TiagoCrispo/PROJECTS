# ProAim v26.7 — Acceptance Criteria

## Release identity

- appVersion `26.7.0`
- visible label `V26.7 / STABLE`
- current scripts target `ProAimOptimizer_v26.7.exe`
- no old ProAim executable included

## Build / package

- `gofmt` clean
- `go vet` Windows/amd64 PASS
- Windows amd64 CGO=0 build PASS
- second frozen-source build byte-identical
- PE32+ x86-64 / Windows GUI subsystem
- checksum manifest PASS before ZIP
- ZIP integrity PASS
- clean extracted package manifest PASS
- extracted EXE byte-identical to delivered EXE
- exactly one current EXE in package

## Ultimate X50

- Validator import bounded and fail-closed
- no arbitrary ZIP extraction / no path traversal
- USER-PC baseline stores evidence source + confidence + timestamps
- Validator 1.0 ambiguous HID polling is marked untrusted
- PresentMon warm-up correction discards first 2000 ms
- WHY MOUSE FEELS BAD does not call a software proxy physical motion-to-photon
- WHAT SHOULD I FIX FIRST ranks evidence without a one-sample causal claim
- driver lifecycle distinguishes BEFORE / NOW / dated REF
- official REF cannot independently create UPDATE
- CPU A/B and NIC A/B emit experiment records with rollback data
- CAP/FRAME one-sample record remains SAMPLE_ONLY
- confidence cannot be MEDIUM/HIGH with one sample
- all new evidence files stay local under the ProAimSuite evidence tree

## Safety / regression

- no operational BCD/HPET/useplatformclock/disabledynamictick tweaks
- no `NtSetTimerResolution`
- no REALTIME priority
- no process injection
- no Defender/Firewall disable
- no generic TCPAckFrequency/TcpNoDelay pack
- no operational EmptyWorkingSet/RAM purge
- no MouseDataQueueSize/MouseTransmitTimeout writes
- no blind BIOS writes
- `timeBeginPeriod` remains isolated and paired only in Aim Trainer
- `taskkill` remains the explicit confirmed leak-restart path only

## Target-PC runtime acceptance

These items must run on the real Windows target and are never fabricated by cross-build validation:

- SELF TEST UI / CORE
- IMPORT VALIDATOR REPORT
- fresh Raw Input / Mouse Lab identification
- PresentMon after current driver changes
- Network X10 after current Ethernet driver changes
- DPC/ISR under real gaming workload
- CPU/Power/NIC/HAGS experiments only after snapshot and repeated evidence

# A53 Performance v1.15.9 — STORAGE / SAFE RAM

- Package: `com.fer.a53performance`
- versionCode: `31`
- versionName: `1.15.9`
- Canonical CI run: `31926793401` — SUCCESS
- Build: Release + Debug + AndroidTest — PASS
- Emulator Android 30: 7 instrumented tests, 8 restart cycles, reinstall/update smoke, orientation recreation, 250 Monkey events, final process/crash check — PASS
- Emulator Android 33: 7 instrumented tests, 8 restart cycles, reinstall/update smoke, orientation recreation, 250 Monkey events, final process/crash check — PASS
- Emulator Android 35: 7 instrumented tests, 12 restart cycles, reinstall/update smoke, orientation recreation, 700 Monkey events, final process/crash check — PASS
- Final signed APK SHA-256: `e141b656c673a4d176da5b2425d1a5cb758bd3b99a997fd29c37078b2406f558`
- Installer ZIP SHA-256: `c2baf29507fb02ca4e2a866b80f945a2aff08ed204546b1bb76100dae4cd5da7`
- Signer certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`
- APK Signature Scheme v2: PASS
- APK Signature Scheme v3: PASS
- ZIP integrity: PASS

## Release changes

- Multi-volume MediaStore support scans internal/removable external volumes separately and uses volume-safe stable IDs.
- The v1.15.8 storage-index migration deliberately invalidates the old signature once, forcing a correct first v1.15.9 multi-volume reindex.
- Persistent storage indexing reuses unchanged volumes and, when possible, merges generation-modified rows with the cached volume while detecting deletions by current IDs; it falls back to a full volume scan on inconsistency.
- Every performance profile is deterministic: peak/min refresh rate, Battery Saver and Data Saver are all explicitly set and verified, with rollback to readable previous values on partial application.
- RAM Cleaner requires Shizuku for trustworthy process handling, excludes default/user-protected apps plus detectable active foreground-service/media/focused apps, and reports requests, processes no longer visible, timeouts and measured RAM before/after.
- Exact-duplicate keeper selection prefers Camera/DCIM/Pictures and original-looking names over Downloads/copy-style names while still retaining one exact copy per group.
- Similar-photo results expose review groups and are manual-review only; there is no automatic similar-photo deletion.
- Storage analysis is cancelled before Trash/permanent-delete operations; storage mutations remain serialized through the repository I/O executor.
- CI now covers Android API 30, 33 and 35.

Physical Galaxy A53 + Samsung One UI, a real removable microSD, and a real Shizuku Manager session still require device-side validation; Android emulators cannot reproduce those environments exactly.

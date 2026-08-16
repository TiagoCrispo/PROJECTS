# A53 Performance v1.15.10 — DEVICE HARDENING

- Package: `com.fer.a53performance`
- versionCode: `32`
- versionName: `1.15.10`
- Canonical CI run: `31927757936` — SUCCESS
- Build: Release + Debug + AndroidTest — PASS
- Emulator Android 30: 9 instrumented tests, 8 restart cycles, reinstall smoke, orientation recreation, RUNNING_LOW trim, 300 Monkey events, final process/crash check — PASS
- Emulator Android 33: 9 instrumented tests, 8 restart cycles, reinstall smoke, orientation recreation, RUNNING_LOW trim, 300 Monkey events, final process/crash check — PASS
- Emulator Android 35: 9 instrumented tests, 14 restart cycles, reinstall smoke, orientation recreation, RUNNING_LOW trim, 800 Monkey events, final process/crash check — PASS
- Upgrade smoke v1.15.8 → v1.15.10: preferences, custom protected-app list and persistent app data preserved — PASS
- Final signed APK SHA-256: `8558fe0f0b900fb92f0fbaedcdda52ea98fbfbc9694d6e2bb5bae7d2bd439648`
- Installer ZIP SHA-256: `ab944e5046c59496f0e71b4c2fb23f47aa9b04552a711d1540668a8b9f5ed6aa`
- Final APK size: `2305666` bytes
- Installer ZIP size: `2226567` bytes
- Signer certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`
- APK Signature Scheme v2: PASS
- APK Signature Scheme v3: PASS
- ZIP integrity: PASS

## Release changes

- RAM Cleaner is fail-safe: if running/sensitive foreground app detection cannot be verified, no apps are force-stopped.
- A harmless typed Shizuku self-test runs before RAM cleanup and profile application.
- Shizuku uses official binder received/dead listeners so service death is detected immediately, with the existing reconnect/circuit-breaker behavior retained.
- Internal storage and removable microSD are reported separately with used/free/total space when Android exposes both volumes.
- Cleaner adds `Todos los almacenamientos / Interno / microSD` filtering and each file shows its storage origin.
- MediaStore indexing uses generation deltas for normal changes and performs a full live-ID reconciliation only periodically or when consistency requires it, reducing repeated large-volume traversals while still detecting external deletions.
- Exact duplicate groups can be reviewed before cleanup. The UI explicitly shows which copy will be retained, its storage source/path/size, and lets the user choose a different keeper.
- Safe duplicate selection and final delete safety respect any manual keeper override and still retain one exact copy per group.
- Upgrade CI installs v1.15.8, seeds representative preferences/app data, updates in place to v1.15.10, and verifies data preservation.
- Android API 30, 33 and 35 CI coverage remains active.

Physical Samsung Galaxy A53 + One UI, a real removable microSD, and a real Shizuku Manager session still require device-side validation; Android emulators cannot reproduce those environments exactly.

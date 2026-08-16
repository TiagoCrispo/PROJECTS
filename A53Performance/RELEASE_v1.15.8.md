# A53 Performance v1.15.8 — SAFETY / SMART CLEANER

- Package: `com.fer.a53performance`
- versionCode: `30`
- versionName: `1.15.8`
- Canonical CI run: `31925788830` — SUCCESS
- Build: Release + Debug + AndroidTest — PASS
- Emulator Android 35: 5 instrumented tests, 12 restart cycles, reinstall/update smoke, orientation recreation, memory-pressure attempts, 600 Monkey events, final process/crash check — PASS
- Final signed APK SHA-256: `1fc118e131d72f68753ee69ac5c2f03fd4671cf491fca21b30a365183872281f`
- Signer certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`
- APK Signature Scheme v2: PASS
- APK Signature Scheme v3: PASS
- ZIP integrity: PASS

## Release changes

- Verified profile rollback: snapshot previous Android settings, apply, verify, and restore readable prior values when a profile only applies partially.
- Auto restore uses bounded retries and defers until the app is opened when Shizuku remains unavailable.
- Shizuku reconnect/circuit-breaker is reserved for transport failures rather than normal Android command rejection.
- Smart exact-duplicate groups select all safe removable copies while preserving one keeper per group and show recoverable bytes.
- Cleaner keeps selection across virtualized pages, clears stale selection on filter/rescan changes, and confirms bytes/files before Trash or permanent delete.
- Similar-photo analysis reuses exact-duplicate results when already available.
- Persistent MediaStore-generation-aware storage index reduces unnecessary rescans.
- Analysis cache pruning is based on live files, age, and database size instead of a fixed 50,000-row cap.
- User-configurable `Nunca cerrar` app protections use scoped launcher visibility; `QUERY_ALL_PACKAGES` remains absent.
- No exportable diagnostic report was added.

Physical Galaxy A53 + One UI/Shizuku behavior still requires device-side validation; CI does not emulate Samsung One UI or a real Shizuku manager session.

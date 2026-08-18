# Galaxy A53 Performance

Galaxy A53 Performance is an Android device-maintenance and performance utility originally built around the Samsung Galaxy A53 5G. The current portfolio release is **v1.16.0 FINAL** (`versionCode 38`).

## Engineering focus

The project deliberately avoids placebo booster behavior. Changes are treated as valid only when they can be measured or read back from Android/Shizuku, and ambiguous results are reported as **NC / not confirmed** instead of being presented as success.

Core areas include:

- reversible Gaming / Battery / Cool profiles;
- Data Saver and per-UID network policy management;
- AppOps-based background isolation with rollback;
- Shizuku capability checks instead of assuming root-like access;
- manual RAM cleanup with before/after measurement;
- cache cleanup using cache-only paths rather than app-data deletion;
- storage indexing, duplicate/similar-file review and resumable scanning;
- protected storage rules for WhatsApp, documents, recordings, recent files and user-protected folders;
- an application-managed trash workflow with recovery support;
- Android thermal-status integration and bounded foreground work.

## v1.16.0 FINAL stability hotfix

An experimental P16 binary rewrite of `TrashStore.writeAll()` correlated with a real-device crash report. The final release does **not** patch that method. Its complete DEX `code_item`, including the original try/catch structure, is restored byte-for-byte to the known v1.15.2/P12 implementation.

The release keeps the accumulated X10 fixes from P2-P12 and updates the product identity to `1.16.0` / `38`.

## Validation contract

The final APK passed ZIP integrity, DEX SHA-1/Adler32 checks, structural control-flow validation across all DEX code methods, four-byte APK entry alignment, APK Signature Scheme v2 digest recomputation and RSA/SHA-256 verification. `res/`, `assets/` and `resources.arsc` remain byte-identical to the v1.15.2 SAFE_AREA visual baseline.

Static validation is explicitly not treated as physical-device validation. Samsung/One UI/Shizuku behavior must still be exercised on the target Galaxy A53.

See [`release/v1.16.0/`](./release/v1.16.0/) for the release notes and verification record.

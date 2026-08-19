# Galaxy A53 Performance v1.20.0 — Doctor 2.0 BETA

`versionCode 43`

Current test candidate built on the physically accepted v1.19.0 Gaming 2.0 base.

## Added in v1.20

Galaxy Doctor 2.0 is a read-only dashboard that centralizes real Android/device state:

- Android thermal status;
- battery level, charging state and battery temperature;
- RAM available/total plus Android `lowMemory` and threshold;
- internal storage available/total;
- current display refresh rate;
- min/peak refresh-rate read-back through Shizuku;
- Data Saver read-back through Shizuku;
- Shizuku, Usage Access, All Files Access, WRITE_SETTINGS and notification capability state;
- number of active Background Center rules;
- pending/active Gaming rollback snapshot state;
- actionable recommendations based only on observed state;
- copyable diagnostic report.

Doctor itself does not write performance settings. Its buttons only open Android Settings or request Shizuku authorization.

## Status

GitHub Actions compile and static APK integration/signing validation: **PASS**.

Physical Galaxy A53 acceptance: **PENDING**. Do not call this build final/accepted until launch and runtime checks pass on-device.

## Downloads

Binary delivery is kept outside this public source repository. See `SHA256.md` for exact artifact hashes.

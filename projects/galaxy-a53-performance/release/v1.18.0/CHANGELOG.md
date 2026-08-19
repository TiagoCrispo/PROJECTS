# Changelog — v1.18.0 Background Center BETA

## Added
- Per-app Background Center.
- UsageStats last-use display when Usage Access is granted.
- Battery-optimization / Doze exemption display.
- Filters for 24-hour use, 7+ day inactivity, sensitive apps and Galaxy-managed apps.
- Sort by recent use, name and oldest use.
- Per-app Shizuku read-back for `RUN_ANY_IN_BACKGROUND`, Data Saver allowlist/blacklist and standby bucket.
- Reversible `Optimizar fondo` and `No restringir` policies.
- Exact pre-change rollback snapshot.
- Sensitive-app warning before restriction.

## Safety corrections made before packaging
- Snapshot persistence changed from asynchronous `apply()` to checked synchronous `commit()` before any shell modification.
- Compile-only `ShizukuShell.Result` stub fields changed from constant `final` values to non-constant declarations so javac/D8 cannot inline fake values. Final DEX references the runtime `Result.code`, `Result.err`, and `Result.out` fields.

## Deliberately not added
- No `RUN_IN_BACKGROUND` writes.
- No standby-bucket writes.
- No `am kill`.
- No `force-stop`.
- No continuous task killer.
- No changes to Gallery 2.0 or the v1.17 core outside the Activity overlay chain needed for composition.

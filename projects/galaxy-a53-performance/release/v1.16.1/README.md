# Release v1.16.1 SAFE RECOVERY

This release exists because v1.16.0 still crashed on the physical Galaxy A53.

## Root-cause boundary

The v1.16.0 binary still inherited several manually edited DEX instructions from the P2-P12 chain (`goto`/`if`/early-return/method-reference/constant patches). Those edits passed structural checks but were never executed under Android ART in the build environment. ART performs deeper bytecode/type verification than the custom static checker used during binary patching.

v1.16.1 therefore removes every executable DEX patch and returns all four DEX files byte-for-byte to the known-working v1.15.2 SAFE_AREA baseline.

## Intentional changes only

- `versionName`: `1.16.1`
- `versionCode`: `39`
- `debuggable=false`
- `allowBackup=false`
- X10 signing identity retained for upgrade compatibility with the X10 branch.

No `res/`, `assets/`, `resources.arsc`, DEX instructions, DEX strings or executable methods are modified.

## Runtime gate

This is deliberately named SAFE RECOVERY rather than FINAL until it survives clean launch on the physical A53. The distributed package includes an ADB clean-install + crash-capture script. If launch fails, the resulting `CRASH_A53_*.zip` is the authoritative artifact for the next fix.

# Release v1.16.0 FINAL

This release is the crash-safe X10 consolidation of Galaxy A53 Performance.

## Root-cause decision
The exact on-device exception needs Logcat, but the regression boundary is narrow: P16 changed only the manifest and `classes4.dex`, with its only new executable rewrite being `TrashStore.writeAll()` plus five custom DEX try/catch regions. P12's `writeAll()` is byte-for-byte identical to the original v1.15.2 implementation. v1.16.0 FINAL restores that complete original method rather than trying to patch the failed P16 patch.

ART performs runtime bytecode verification beyond simple ZIP/checksum/signature validity, so a hand-edited DEX can pass static packaging checks and still fail class verification on the phone. This release therefore returns to the last conservative binary base and only changes release identity after the P2-P12 fixes.

## SHA-256
`3b9b26d3b1bb3af8d693bb8579104d7448adf99f3afe202a994c3c5c2170b231  Galaxy_A53_Performance_v1.16.0_FINAL.apk`

## Installation
- From an X10 build: v1.16.0 uses the same X10 certificate and versionCode 38, so it can upgrade P16.
- From the original v1.15.2 SAFE_AREA: the historical signing certificate differs, so use a clean install.
- Before uninstalling any older build, restore anything still stored in Galaxy's app-managed trash if you need it.

## Runtime acceptance
After installation, validate launch three times, background/resume, Shizuku unavailable/available states, Gaming/AUTO/Data Saver, scan cancel/finish, duplicate/similar review and Trash move/restore. A static build report is not considered proof of Samsung/One UI behavior.

# Galaxy A53 Performance v1.16.0 FINAL

## Crash / stability

- Removed the experimental P16 rewrite of `TrashStore.writeAll()`.
- Restored the complete original v1.15.2/P12 DEX method body and try/catch table for that method.
- Updated release identity to `versionName 1.16.0` and `versionCode 38`.

## Accumulated safe X10 changes

- Gaming and per-game Gaming keep Data Saver enabled.
- Scan progress no longer republishes notification 4101 for every item.
- AUTO OFF restores the saved profile snapshot.
- Automatic Optimize no longer behaves as a task killer; RAM cleanup remains explicit/manual.
- AUTO polling reduced from 15 s to 20 s; dashboard polling reduced to 2/6/15 s.
- `debuggable=false` and `allowBackup=false`.
- Ambiguous read-backs are reported as NC/PARTIAL instead of false success.
- Removed redundant global Data Saver blacklisting and broad Doze exemptions.
- NORMAL no longer overwrites restored AppOps state; explicit per-app rules remain.
- Destructive storage actions inherit the delicate-file safety policy.
- `.bak` is not treated as automatic junk.
- Storage checkpoint cadence 20 -> 40 directories, progress persistence ~700 -> 1000 ms, external-sort chunk 3000 -> 5000 lines.

## Deliberately not binary-patched

Source-level refactors such as Android 15 `Service.onTimeout(int,int)`, moving Trash dialog I/O off the UI thread and coordinated pruning of cleanup transaction history are documented as future source-reconstruction work. They are not implemented via risky DEX surgery in this final binary.

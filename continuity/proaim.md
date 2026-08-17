# ProAim — continuation state

**Canonical project:** ProAim  
**Old names:** app para optimizar mi PC, optimización mouse y pantalla, PC optimizer, ProAim Optimizer  
**Current baseline:** **ProAim V25.0 — Block 11: Lifecycle, Integrity & Recovery**  
**Current executable target:** `ProAimOptimizer_v25.0.exe`

ProAim is not meant to be a generic “FPS booster”. It should measure, explain, verify and restore the small set of system/input/display settings it actually owns.

## Important cumulative behavior

The current baseline includes the earlier performance/input/display work as well as Block 11. Do not strip those older modules while changing lifecycle code.

Key principles from the existing app:

- Windows mouse 1:1 checks and reversible application;
- true Raw Input polling is measured, not guessed from a configured target;
- weak polling samples remain inconclusive;
- display inventory uses the current exact resolution and advertised refresh rates;
- best-refresh changes Hz only, not resolution, and verifies after applying;
- mouse/display mutations use snapshots/restore logic;
- HID/PnP and DPC/ISR evidence remain diagnostics rather than fake mouse “fixes”;
- adaptive/smart optimization uses bounded evidence, exact owned-state restore and regression guards rather than broad Windows tweaking.

## Block 11 — lifecycle / integrity / recovery

### Package integrity

- `SISTEMA > CICLO / RECUP.` is the lifecycle/recovery center.
- Installed package files can be checked against `CHECKSUMS_SHA256.txt` with SHA-256.
- Missing, changed and malformed manifest entries are detected.
- Manifest paths are normalized; traversal and absolute paths are rejected.
- Integrity verification is **diagnostic only**: never “repair” an unknown/mismatched binary by guessing.
- The UI must keep **integrity** separate from **authenticity/signature trust**.

### Configuration export / import

Only an allow-list of ProAim-owned preferences is portable:

- process policy;
- background preferences;
- game profiles;
- Adaptive mode preferences;
- Maintenance preferences.

Do **not** turn this into a Windows/user-file backup feature. It does not export Documents, Downloads, games, traces, crash logs, benchmark data, hardware history or unrelated Windows data.

Imports use metadata format `proaim-config-v1` and reject:

- ZIP traversal;
- absolute paths;
- symlinks;
- unknown files;
- oversized entries;
- malformed JSON.

A restore-point directory is created before existing ProAim preferences are replaced. State audit remains read-only and should not silently delete/rewrite corrupt preference files.

### Local update inbox

- local inbox file: `ProAim_Update.zip`;
- extraction/staging is isolated, traversal-safe, symlink-safe and bounded by count/size limits;
- staged content must contain exactly one `ProAimOptimizer_v*.exe` and a valid checksum manifest;
- self-contained SHA-256 values prove internal integrity, **not authorship**;
- therefore Block 11 deliberately does **not** auto-install a package based only on those hashes;
- no remote updater/server dependency was added.

### Upgrade continuity

- recovery recognizes previous visible v24.8 and v24.9 builds;
- if Block 10 Health Guardian was already explicitly enabled, V25.0 may refresh only that existing opt-in scheduled-task binding to the current executable;
- users with Background Guardian disabled must not get a scheduled task created automatically.

## Safety rules that are part of the product

Do not add these as “optimizations” unless a future design explicitly re-evaluates them with evidence and reversibility:

- HPET/BCD tweaks;
- forced timer resolution;
- REALTIME priority;
- process injection;
- working-set/RAM purge;
- generic TCP latency tweaks;
- service-disable paths;
- Defender/Firewall disabling;
- automatic personal-file deletion;
- unsigned/untrusted automatic updater;
- personal-file/cloud backup scanning.

## Exact next step

The code/source baseline is V25.0 Block 11. The next useful work is **target-PC validation and bounded completion of the lifecycle/update path**, not another broad optimization rewrite.

Validate on the real Windows machine:

- scheduled-task rebinding only when it was already opted in;
- Explorer/folder actions used by lifecycle flows;
- `%LOCALAPPDATA%` permissions and restore/config paths;
- checksum/integrity performance on the installed package;
- local update staging behavior and failure recovery.

Automatic package replacement remains intentionally unimplemented until authenticity/trust can be established safely. Do not bypass that design just to make the updater look “finished”.

## Before delivering a future ProAim build

- start from V25.0 or a newer documented continuation state;
- keep exact restore/rollback semantics for every owned mutation;
- keep diagnostic-only problems diagnostic unless the user explicitly chooses a safe action;
- run same-PC pre/post regression gates where the feature already relies on them;
- verify package/recovery scripts point at the new executable version;
- report real Windows validation separately from source/build validation;
- update this file with version, block, new owned settings, regression results and exact next action.

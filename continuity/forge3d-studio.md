# Forge3D Studio — continuation state

**Canonical project:** Forge3D Studio  
**Old names:** generador 3D para Roblox, Compatibilidad Forge3D y Roblox, Forge3D  
**Current baseline:** **v7.0.9 — Block 9: Codex Agent Layer**  
**Canonical package:** `Forge3D_Studio_v7.0.9_Block9_Codex_Agent_Layer.zip`  
**Pipeline:** **Codex → Forge3D → Roblox Studio**  
**Source of truth inside a project:** ForgeWorld OmniGraph + Project Vault canonical `working.glb`

## Completed blocks

1. Production Foundation
2. Unified Viewer + Scene/Transform Core
3. Create3D + Asset Compiler
4. Character / Rig / Gear
5. Animation + Character Movement
6. Ability + VFX Compiler
7. World / Terrain / Water / Environment
8. MU World Reconstruction
9. Codex Agent Layer

All nine are part of the baseline. A future update must not recreate an earlier architecture and call it a new version.

## Important state carried from Block 8

- MU MAP/ATT/OBJ decode paths are validated for the supported plain/FileCryptor/known Modulus variants.
- Lorencia `World1` live world-audit reached score 100 with **2,835 OBJ placements** decoded.
- Layer1/Layer2/alpha terrain shader, baked fallback, TerrainLight, NoGround, Object Registry, minimap validation and Reference/Enhanced/Remaster modes remain part of the world pipeline.
- The reference library audit reached **10,067 files / 54 World folders / 55 Object folders / 4,210 BMD files**.

## Block 9 behavior that must remain

- bounded semantic context slices by stable ID, world region and MU WorldN/ObjectN;
- dependency graph with impact/compile analysis;
- compound plans carrying revision, fingerprint, world digest, operation digest and plan digest;
- server-side **Plan → Validate → Commit** for destructive, multi-domain and compound jobs;
- stale revision/fingerprint/world digest rejection with minimal reconcile context;
- automatic repair loop capped at two attempts and **never auto-commit**;
- domain-targeted CI before plan commit;
- persistent plan/command/CI/ACK journal;
- Agent Health distinguishes a loaded bridge with no project from a real bridge outage;
- MCP surface has **92 unique tools**, with INSPECT separated from MUTATE/PLAN operations;
- CLI includes health/deps/journal/policy/slice/plan/repair/commit/ci/handoff;
- Triad Hub Agent Layer UI;
- `forge3d.roblox.handoff.v1` prepared as the handoff contract for the next block.

## Last regression state

- Foundation: 13/13
- Block 2: 25/25
- Block 3: 29/29
- Block 4: 41/41
- Block 5: 69/69
- Block 6: 98/98
- Twisting Slash Golden: 36/36
- Block 7: 63/63
- Block 8: 56/56
- Block 9 full/live: 61/61
- Test Lab: 18/18
- Cross-regression Blocks 1–5: 59/59
- Production CI fixture: PASS, 0 errors / 0 warnings
- Python compile-all: PASS
- JavaScript syntax-all: PASS

## Do not fake or silently change these limitations

- BMD14 is still **semantic proxy-only**. Do not invent or claim a decoder that does not exist.
- Unknown MU private-server crypto variants must fail explicitly rather than guess.
- Full per-instance skeletal BMD animation is not yet equivalent to the original MU runtime.
- A real Roblox Studio playtest/materialization was not available in the Block 9 build environment and is not considered passed.
- Codex CLI was not available in that Linux build environment; Agent Health should report the manual MCP/CLI step without treating the bridge itself as failed.
- Binary Roblox asset publication and AssetId versioning are **not Block 9 features**.

## Exact next step

**Block 10 — Roblox Compiler + Live Link.**

Start from `NEXT_UPDATE_PLAN.md` and `forge3d.roblox.handoff.v1`. Do **not** skip directly to Blocks 11/12 and do not rebuild Block 9 from scratch.

Block 10 owns the Roblox-side compiler/live-link responsibilities, including the publication/versioning area deliberately left out of Block 9. Preserve partial rebuild, ownership, ACK/drift semantics from the handoff contract.

## Before delivering a future Forge3D build

- confirm v7.0.9 Block 9 is the actual input baseline or document why a newer verified baseline supersedes it;
- run the old block regressions plus the new block regression;
- keep `working.glb`/OmniGraph identity stable through changes;
- report physical Roblox Studio validation separately from source/test-suite validation;
- update this file with package name, block number, test counts, unresolved limitations and the exact next block.

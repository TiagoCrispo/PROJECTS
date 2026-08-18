# Forge3D Studio

Forge3D Studio is a desktop 3D production toolkit designed around Roblox-oriented workflows. It is intended to inspect, prepare, transform, compile and assemble game assets before they reach Roblox Studio.

## Current development snapshot

**Forge3D Studio v7.0.3 — Create3D + Asset Compiler · Block 3**

Pipeline: **Codex → Forge3D OmniGraph → asset/character/world compilers → production gate → Roblox Studio**.

### Block 3 foundation

- GLB is the canonical working asset format.
- Each `AssetId` keeps source, working, compiled, textures, LOD, history and manifest data separated.
- GLTF packages preserve `.gltf` + `.bin` + textures as source while reopening through a self-contained working GLB.
- OBJ can keep MTL/textures; FBX conversion uses Blender when available.
- AI generation and procedural generation are explicitly separated so procedural output is not presented as generative AI.
- Asset Compiler writes `compiled.glb` separately from `working.glb` and ties compile reports/SHA-256 evidence to the asset identity.
- Static meshes can use Blender-backed cleanup, LODs and collision generation; skinned/morph assets stay in a safer non-destructive path.
- UV audit, material compilation, Roblox triangle-budget reporting, split guidance and reproducible thumbnails are included in the compiler workflow.
- Blocks 1 and 2 remain the foundation: production shell plus Unified Viewer / Scene Core.

## Boundaries

Without Blender, compilation remains in a safer limited mode and does not pretend physical LOD/collision generation occurred. Unknown proprietary formats should fail explicitly rather than being presented as fully decoded. Final import, materials and performance still require validation in Roblox Studio.

Large source datasets such as the Lorencia MU map corpus are intentionally kept outside the public code snapshot rather than being mixed into the application source.

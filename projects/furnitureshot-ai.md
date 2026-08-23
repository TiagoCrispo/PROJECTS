# FurnitureShot AI

> Android product-photo processing focused on cleaner catalog images without sacrificing the physical identity of the photographed furniture.

| | |
|---|---|
| **Status** | Active beta · **v0.7.1-beta22** |
| **Platform** | Android |
| **Stack** | Kotlin · Jetpack Compose · ML Kit-assisted segmentation |
| **Focus** | Product fidelity · segmentation · compositing · conservative image enhancement |

## Overview

FurnitureShot AI turns ordinary furniture photos into cleaner catalog/marketplace images while treating the **original object as the visual source of truth**. The project is intentionally conservative: a more dramatic edit is not considered better if it changes product geometry, texture or believable contact with the scene.

## Current pipeline

- coarse subject segmentation assisted by ML Kit;
- higher-resolution edge refinement around the detected object;
- catalog composition constrained to uniform scale and translation rather than free-form geometry changes;
- bounded texture/detail enhancement with fallback when fidelity checks detect excessive divergence;
- real contact-shadow reuse only when confidence and attachment checks pass;
- clean-background fallback when a trustworthy shadow cannot be recovered;
- a single consolidated finishing pipeline instead of repeated destructive re-detection/recomposition passes.

## Quality contract

The project follows a simple rule: **product identity is more important than visual drama**.

When segmentation, fidelity or composite checks are weak, the application should degrade conservatively rather than inventing structure, texture or shadow. Synthetic oval/drop shadows are not generated as a substitute for evidence from the source image.

## Engineering focus

The interesting problem is not merely background removal. It is preserving geometry, edges, texture and contact cues across segmentation and recomposition while preventing later processing stages from undoing earlier fidelity work.

The beta22 build workflow assembles the consolidated processing overlay, creates a debug APK and verifies the generated archive before publishing the CI artifact.

## Validation status

CI/build success confirms that the software can be assembled and packaged; it is **not treated as proof of photographic quality**. Broader real-photo and physical-device validation remains part of the current beta work.

## Source

Android source: **[`projects/furnitureshot-ai/`](./furnitureshot-ai/)**  
Current beta22 overlay: **[`projects/furnitureshot-ai-overlays/v0.7.1-beta22/`](./furnitureshot-ai-overlays/v0.7.1-beta22/)**

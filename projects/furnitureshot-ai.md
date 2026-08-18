# FurnitureShot AI

FurnitureShot AI is an Android-only product-photo workflow for turning ordinary furniture photos into cleaner catalog/marketplace images while protecting the physical identity of the photographed object.

## Current GitHub build

**v0.7.1-beta22 — consolidated fidelity pipeline**

The current build focuses on a single high-fidelity furniture-photo pipeline instead of stacking multiple destructive post-processing passes.

### Current direction

- Kotlin + Jetpack Compose Android application.
- Source image remains the visual source of truth; the app avoids reconstructing or warping product geometry.
- ML Kit subject segmentation is used as a coarse guide, followed by higher-resolution edge refinement.
- Object geometry is constrained to uniform scale + translation for catalog composition.
- Texture/detail enhancement is bounded and can fall back when fidelity checks detect excessive divergence from the source.
- Synthetic oval/drop shadows are not generated. A real contact shadow is reused only when confidence and attachment checks pass; otherwise the background stays clean.
- The finishing stage no longer performs a second JPEG re-detection/recomposition pass that could create halos, texture loss or false shadows.
- The beta22 workflow assembles the consolidated processing overlay, builds a debug APK and verifies the produced archive before publishing the CI artifact.

## Quality principles

The original product identity is more important than making the edit look dramatic. When segmentation, fidelity or composite checks are not reliable enough, the pipeline should degrade conservatively rather than invent structure, texture or shadow.

Current work is still subject to physical-device and broader real-photo validation; CI/build success is not treated as proof of photographic quality.

The Android source lives at [`projects/furnitureshot-ai/`](./furnitureshot-ai/) and the beta22 consolidation overlay at [`projects/furnitureshot-ai-overlays/v0.7.1-beta22/`](./furnitureshot-ai-overlays/v0.7.1-beta22/).

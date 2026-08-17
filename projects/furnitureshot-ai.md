# FurnitureShot AI

FurnitureShot AI is an Android-only product-photo workflow for turning ordinary furniture photos into cleaner catalog/marketplace images while protecting the physical identity of the photographed object.

## Current build

**v0.1.0-alpha01**

The first implementation intentionally starts with the reliability layer rather than claiming a complete generative-AI stack.

### Implemented

- Android native app structure in Kotlin + Jetpack Compose.
- Target devices: Galaxy A53 5G and Galaxy S20 FE.
- First-launch runtime permission gate.
- Photo Picker import without broad gallery permission.
- Camera capture.
- Immutable internal source copy.
- Four product-photo presets.
- Prompt-like local instruction parser.
- Fidelity Lock for structural edit requests.
- Conservative exposure/contrast/color improvement.
- Conservative uniform-background isolation to studio white with fail-safe fallback.
- Cancelable local processing.
- Original/result preview.
- Gallery export.
- Local history.
- GitHub Actions Android build job.

### Deliberately pending

- ML segmentation model.
- High-quality edge/hair/glass matte refinement.
- Local tiled super-resolution.
- Full-resolution no-downsample processing for very large 50–64 MP camera files.
- Novel-view synthesis / 0°–270° generated views.
- Physical-device validation on the two Samsung targets.

## Engineering rule

The original image is never overwritten. A failed or low-confidence background isolation keeps the original background instead of silently damaging the furniture geometry.

The project lives at [`projects/furnitureshot-ai/`](./furnitureshot-ai/).

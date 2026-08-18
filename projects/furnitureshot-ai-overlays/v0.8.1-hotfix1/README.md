# FurnitureShot AI v0.8.1-hotfix1

Critical runtime hotfix for v0.8.0-final.

Root cause: `recoverLinearStructures()` iterated from index 2 while reading +/-3 pixels, causing deterministic ArrayIndexOutOfBoundsException on every processed photo.

Hotfix:
- fixes loop bounds to 3..size-3;
- reduces full-frame memory ceilings to avoid heap spikes from multiple bitmap/array clones;
- isolates optional topology/background refiners so a non-OOM refinement bug cannot abort the whole photo;
- preserves MAX-quality behavior with stable high-resolution processing.

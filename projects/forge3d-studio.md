# Forge3D Studio

Forge3D Studio is a desktop 3D production toolkit designed around Roblox-oriented workflows. The goal is to make it easier to inspect, prepare, transform and assemble game assets before they reach Roblox Studio.

## What it does

- imports and inspects models, characters, armor, weapons, wings, props and other assets independently;
- provides scene, transform and asset-compilation workflows;
- supports character, rig and equipment preparation;
- includes animation and character-movement tooling;
- includes ability and VFX-oriented workflows;
- supports terrain, water, environment and world-building work;
- includes MU Online world reconstruction tooling for supported map/object data;
- provides structured automation/agent interfaces for inspecting projects and planning controlled changes;
- prepares project data for Roblox-side compilation and integration.

## Engineering focus

Forge3D is intentionally broader than a single file converter. It treats a project as a production environment with stable asset identity, dependencies, validation and controlled mutation. Destructive or multi-domain operations are designed around explicit planning and validation instead of blind automation.

The project also distinguishes supported decoding from approximation. Unsupported or unknown proprietary formats should fail clearly rather than being presented as fully decoded.

## Direction

The long-term direction is a practical bridge between 3D asset preparation, automation and Roblox Studio, while keeping models, characters, equipment, animation, VFX and world data usable as separate parts of the same pipeline.

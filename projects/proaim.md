# ProAim

ProAim is a Windows utility focused on understanding and tuning the parts of a gaming PC that affect input feel, display behavior, hardware health and latency.

## Current development snapshot

**ProAim v26.0.2 — Hardware Truth & Diagnostics Fix**

This release keeps the v26.0.1 runtime-stability architecture and corrects hardware/driver interpretation discovered during real Windows testing.

### Current capabilities

- Driver Center inventories exact active PnP-signed drivers where possible and compares against live official vendor information without inventing an update when a page cannot be verified.
- Logitech G HUB detection uses registry, files and process fallbacks rather than one fragile exact display name.
- Storage UI renders all detected physical disks and all lettered volumes instead of only the first disk / C:.
- Windows Health surfaces DISM repairable and pending-reboot state; the repair path runs RestoreHealth + SFC only when repairable state is actually confirmed.
- Process modes are explained in-product and protect Windows/security/audio-critical components and Memory Compression.
- Power modes distinguish AUTO, COMPETITIVE and MAX; COMPETITIVE is not claimed to be universally faster and should be kept only when measurements justify it.
- Mouse, display, benchmark and tuning workflows continue to prefer measurable evidence and reversible changes.

## Safety principles

ProAim does not use HPET/BCD forcing, REALTIME process priority, generic RAM purging, process injection, Defender/Firewall disabling or broad low-level tweak packs as a substitute for evidence. Diagnostics and fixes remain separate, and system mutations should be understandable and reversible.

## Release target

Windows 11 x64, native Win32 GUI, Go/amd64 with CGO disabled.

# ProAim

ProAim is a Windows utility focused on understanding and tuning the parts of a gaming PC that affect input feel, display behavior and latency.

## What it does

- checks Windows mouse configuration and 1:1 input-related settings;
- measures input/polling evidence instead of assuming a configured value is being achieved;
- inventories connected displays, active resolution and available refresh rates;
- can apply supported display changes while preserving the selected resolution;
- collects HID, PnP and latency-related diagnostic evidence;
- uses snapshots and restore logic for settings it changes;
- supports bounded, evidence-based optimization instead of broad registry tweaking;
- includes package-integrity, configuration and recovery tools for the application itself.

## Engineering focus

ProAim is deliberately not a generic “FPS booster”. A setting is only useful when the application can explain what it changes, verify the result where possible and restore the previous state.

Diagnostics are kept separate from fixes: detecting DPC/ISR activity, a device issue or an inconclusive polling sample does not automatically mean Windows should be modified.

## Safety principles

The project avoids aggressive shortcuts such as disabling security features, forcing unsafe process priority, generic network tweaks, arbitrary service removal or deleting personal files. Optimizations are intended to stay narrow, understandable and reversible.

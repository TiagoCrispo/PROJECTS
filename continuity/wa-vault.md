# WA Vault — continuation state

**Canonical project:** WA Vault  
**Old names:** WSP V2, app para recuperar mensajes, recuperar mensajes  
**Current baseline:** **v0.5.21 — Stable Privacy Freeze**

This is a local Android reliability project. Future changes should improve event/media correlation and durability without turning it into a cloud backup product or weakening privacy.

## Non-negotiable privacy state

- file encryption is **mandatory** in the v0.5.21 baseline;
- older plaintext records are migrated automatically and definitively into the encrypted format;
- the old backup system was intentionally removed in the preceding privacy/durability work;
- **do not reintroduce backup functionality** under another name;
- do not re-enable Android backup/export behavior merely for convenience;
- do not add portable/password/cloud backup flows unless the product direction is explicitly changed later.

The absence of backup is deliberate product behavior, not a missing feature to “fix”.

## Reliability behavior to preserve

The app has evolved around Android timing/order edge cases. Do not simplify these into a single naive notification handler.

Preserve the concepts of:

- local message/event capture;
- strict protection against false “deleted message” classifications;
- delayed media/file association;
- restart-safe pending work;
- FIFO/batch correlation where multiple files/events belong together;
- duplicate protection;
- group/batch association rather than assuming every media callback is independent;
- handling images, video, audio/voice notes and documents through durable pending-media logic;
- late-arriving files remaining associable after short timing gaps/restarts;
- privacy-first local storage.

## Deleted-message precision rule

Do not mark generic WhatsApp counters/status text as deleted content. Previous hardening deliberately became stricter because phrases such as message counts or notification summaries could create false positives.

Any future parser expansion should prefer **unknown/not-deleted** over inventing a deletion event when evidence is weak.

## Media/document rule

Documents must receive the same durable association discipline as image/video/audio instead of being a second-class path that can lose its event after delay or restart.

Changes to watcher timing, pending queues or batch identity must be reviewed together. Do not change one timeout in isolation without checking the other pending/event windows.

## Current privacy freeze

The v0.5.21 baseline is the point after:

- removal of remaining internal backup remnants in v0.5.20;
- mandatory encrypted file storage;
- final migration of historical plaintext state;
- reliability/precision hardening from the v0.5.17–v0.5.21 line.

Treat that as a floor: a later version must not silently go back to plaintext, backups or weaker deleted-message heuristics.

## Exact next step

Future improvements start **on top of v0.5.21**. First verify the requested change against the privacy freeze and the event/media pipeline. If the change touches watchers, pending queues, encryption or migration, run regression scenarios for delayed media, restart, duplicates, multi-file batches and documents before delivering an APK.

## Before delivering a future WA Vault APK

- confirm v0.5.21 or a newer documented state is the input baseline;
- search for accidental Android backup enablement / old backup code before release;
- verify no plaintext persistence path was reintroduced;
- run false-positive deleted-message cases;
- run delayed/restart/multi-file/document association cases;
- keep queue/batch semantics stable unless deliberately migrated;
- record APK/version/signing result and privacy/reliability regressions here;
- update the exact next continuation point instead of relying on chat history.

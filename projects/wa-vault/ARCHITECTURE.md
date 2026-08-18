# WA Vault v0.5.31 — Architecture

## 1. Trust model

WA Vault observes Android notification/storage surfaces; it does **not** read WhatsApp's private message database and does not possess WhatsApp's internal message IDs. Therefore:

`MESSAGE_NOT_VISIBLE != MESSAGE_DELETED`

The system intentionally prefers an unknown/fail-closed state over a false deletion.

## 2. Notification lifecycle

`WhatsAppNotificationListener` separates event sources: `BASELINE_SYNC`, `POLL_SYNC`, `REAL_POST` and `REAL_REMOVE`.

A process/session moves through initialization → baseline building → live. Deletion evaluation is closed until a valid active-notification baseline is established. Polling can contribute positive presence, but absence from polling cannot confirm a deletion. `onNotificationRemoved()` is notification lifecycle evidence only.

## 3. Confirmed deletion gate

A historical row may become `DELETE_CONFIRMED` only when all strong conditions hold:

1. source is a fresh `REAL_POST` event;
2. the session baseline is ready and trusted for that conversation scope;
3. evidence is singular and not previously consumed;
4. the deletion marker carries a stable MessagingStyle timestamp;
5. exactly one missing historical message matches that timestamp;
6. evidence insert + message state transition commit atomically in SQLite.

Plural/ambiguous markers, empty snapshots, app cancel/removal, restarts and unstable identity remain `UNKNOWN`/`REJECTED`.

## 4. Identity and persistence

Conversation scope includes package identity (WhatsApp vs WhatsApp Business), Android shortcut when available, and normalized conversation identity. Message persistence uses a Keystore-backed HMAC fingerprint plus `identity_slot` so repeated identical messages at the same timestamp can coexist without replay duplication.

SQLite schema v15 stores messages, media, deletion evidence and many-to-many media/message links. Critical deletion evidence is transactional with the message transition.

## 5. Media pipeline

Capture paths include Notification URI, direct file observers when Android grants access, MediaStore and conservative recovery fallbacks.

Permanent media path:

`capture → app-private staging → fsync → validation → plaintext hash in memory → AES-256-GCM encryption → durable move → SQLite commit`

Permanent vault/quarantine files are ciphertext. Recovery understands interrupted plaintext-ready/encrypted-ready states and never lets generic maintenance delete recoverable `ready_*` files.

Physical content deduplication is separate from logical association: one encrypted blob can link to multiple legitimate messages.

## 6. Concurrency/background

Notification callbacks stay lightweight. Expensive preview/media/database scans run off the main thread. Resource-retaining executors use bounded queues and rejection policies; rejected tasks never fall back to caller execution. Poll/retry loops are coalesced or self-rescheduling rather than permanently fan-out scheduling thousands of delayed tasks.

Android may kill the cached process at any time. Durable SQLite/staging state is authoritative; timers and in-memory maps are accelerators only.

## 7. Security boundaries

- messages/metadata: AES-GCM with Android Keystore;
- media: separate AES-256-GCM Keystore key;
- stable metadata tokens: HMAC-SHA256 Keystore key;
- no Internet permission;
- backups/device-transfer data extraction excluded;
- `VaultShareProvider`, boot receiver and notification listener are not exported;
- internal broadcasts require a signature-level permission and non-exported receiver registration;
- UI uses screenshot/screen-share protections and optional biometric/device authentication.

## 8. Release model

Debug builds remain unminified for diagnosis. Release builds use R8 full-mode code optimization and optimized resource shrinking. Mapping files are release artifacts and must be retained privately with the corresponding build for retraceable crash diagnostics.

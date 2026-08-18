# Security — WA Vault v0.5.31

## Data protection

WA Vault is local-first and does not request `android.permission.INTERNET`. Sensitive message data and media are encrypted with Android Keystore-backed keys. Permanent media storage accepts only encrypted payloads; plaintext is confined to app-private staging or short-lived user-requested share/export paths.

## Android component exposure

- Launcher Activity: exported only because Android must launch it.
- NotificationListenerService: non-exported and protected by `BIND_NOTIFICATION_LISTENER_SERVICE`.
- BootReceiver: non-exported.
- VaultShareProvider: non-exported, read-only, temporary URI grants only.
- Internal app events: signature-level custom permission + non-exported dynamic receiver where supported.

## Backups

`allowBackup=false` is complemented by explicit legacy backup and Android 12+ data-extraction rules that exclude database, preferences, files and external app data from cloud backup and device transfer.

## Android 15 notification-listener limitation

Android 15 may redact OTP/one-time-code content delivered to notification listeners that the platform does not treat as trusted. WA Vault treats such redaction as unavailable notification content, never as deletion evidence. QA must not classify an Android 15 OTP redaction as a WA Vault deleted-message bug.

## Release secrets

Never commit APK signing material. `.gitignore` rejects common keystore/key/archive/build artifacts. Release signing is environment-variable driven and CI must use repository secrets or an external secure signer. `mapping.txt` is not secret but should be retained alongside the exact release artifact for crash retracing.

## Reporting

For a suspected security flaw, do not publish private user data or cryptographic material in a public issue. Record only reproducible technical metadata and sanitized logs.

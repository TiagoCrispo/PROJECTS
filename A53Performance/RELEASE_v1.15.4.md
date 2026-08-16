# A53 Performance v1.15.4 — PERFORMANCE / STABILITY

Package: `com.fer.a53performance`  
versionCode: `26`  
versionName: `1.15.4`

## Performance/stability hardening
- RecyclerView virtualization and 60-item progressive pages.
- Persistent scroll anchor/offset, query, filter, sort and loaded-count state.
- Bounded two-worker thumbnail queue with LRU memory cache and memory-pressure trimming.
- Cancelled visual work when leaving Cleaner.
- Single-thread storage scan/delete queue with generation-based scan cancellation.
- Live deletion updates for rows, counts and storage free space.
- Debounced search.
- Serialized/cancellable RAM and performance-profile jobs.
- Per-app RAM timeout and real before/after Android memory measurement.
- Central protected-app policy for Gmail, Google/Samsung Messages, Google/Samsung Clock, Brave, ChatGPT, Samsung Voice Recorder, Phone/Contacts and critical system packages.
- No fabricated RAM percentages, CPU/GPU boost claims or per-app exceptions to global Android settings.

## Validation
GitHub Actions run `31921874583`: SUCCESS. Release build, package/version metadata, ZIP integrity and zipalign validation passed.

Final locally signed APK SHA-256: `bef0911312c1aec7b5ebe3c4f6308dbe065e3a8e2f9ecdd2fd1f43e700ffa8d2`.
Signer certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`.
APK Signature Scheme v2: PASS. APK Signature Scheme v3: PASS.

## Signing transition
The original v1.15.3 private signing key was not recoverable from the surviving project material. v1.15.4 therefore starts a new stable signing lineage. It cannot update an installed v1.15.3 in place; uninstall v1.15.3 once before installing v1.15.4. Preserve the private v1.15.4 signing backup outside the public repository for future in-place updates.

# UTN Scholar / UTN AI

UTN Scholar is a study workspace built around university material. It is designed to turn scattered PDFs, notes, slides and other course files into an organized environment for reading, studying and AI-assisted work across Windows and Android.

## Current development snapshot

**UTN AI 0.59.0+59 — Block 59 Hotfix 14**

Hotfix 14 hardens the physical Windows/Android build toolchain. In particular, the Flutter bridge now invokes `flutter config --jdk-dir ...` and `flutter config --android-sdk ...` correctly while keeping Windows paths with spaces, parentheses or Unicode safe through environment-variable passing.

### Verified in the current line

- Flutter quality gate and backend regression suite are part of the release validation flow.
- Windows release build and Windows integration smoke are validated in the current Block 59 line.
- Android bootstrap checks JDK, SDK packages, command-line tools, licenses and critical package files instead of trusting a single marker.
- Long-running build/toolchain commands use bounded supervision, activity heartbeats and explicit failure propagation.
- Validation avoids leaving Python bytecode/cache artifacts behind.

### Remaining physical validation

Hotfix 14 specifically replaces the earlier Android configuration failures. The next physical gate is a clean extraction followed by the Android build/device validation on the real Windows machine; this should not be described as PASS until that run succeeds.

## Product capabilities

- organizes course documents and subject-specific material;
- supports intelligent import and document-centered study workflows;
- provides AI-assisted explanations, summaries, exams, flashcards and study tools;
- supports Windows and Android with adaptive navigation;
- supports local-network pairing and degraded/offline behavior;
- includes diagnostics for storage, backend, AI, pairing and toolchain health;
- includes recovery/regression checks around important workflows;
- includes accessibility options such as text scaling, reduced motion and high-contrast support.

## Engineering focus

Reliability is treated as part of the product: local state, pairing, recovery, startup behavior, credentials, diagnostics and build reproducibility are first-class concerns rather than add-ons around an AI prompt box.

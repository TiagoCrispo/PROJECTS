# UTN Scholar / UTN AI

> Cross-device study workspace for turning university material into an organized, AI-assisted learning environment.

| | |
|---|---|
| **Status** | Active development · **0.59.0+59 — Block 59 Hotfix 14** |
| **Platforms** | Windows · Android |
| **Core technology** | Flutter · local/backend integration · document-centered workflows |
| **Focus** | Study organization · AI assistance · diagnostics · cross-device reliability |

## Overview

UTN Scholar is designed around a common student problem: course material arrives as scattered PDFs, slides, notes and files, but studying requires a coherent workspace. The project organizes that material and adds AI-assisted tools without treating the AI prompt itself as the whole product.

## Product capabilities

- intelligent import and organization of course documents;
- document-centered reading and study workflows;
- AI-assisted explanations, summaries, exams, flashcards and study tools;
- adaptive Windows and Android navigation;
- local-network pairing and degraded/offline behavior;
- diagnostics for storage, backend, AI, pairing and toolchain health;
- recovery and regression checks around important workflows;
- accessibility support including text scaling, reduced motion and high-contrast behavior.

## Engineering focus

Reliability is a first-class feature. The project explicitly handles local state, credentials, startup behavior, pairing, recovery and build reproducibility instead of leaving those concerns outside the learning experience.

The current Block 59 line also hardens the physical build toolchain:

- Flutter quality gates and backend regression tests are part of release validation;
- Windows release build and integration smoke are validated in the current line;
- Android bootstrap verifies JDK, SDK packages, command-line tools, licenses and critical package files;
- long-running build/toolchain commands use bounded supervision, activity heartbeats and explicit failure propagation;
- Windows paths containing spaces, parentheses or Unicode are handled safely when configuring Flutter/JDK/Android SDK paths.

## Validation status

The Windows-side release and smoke gates are validated in the current line. The latest Android configuration fixes still require a **clean physical build/device validation on the real Windows machine** before Android can be described as accepted.

That distinction is intentional: toolchain changes are not marked as PASS until the environment they target has actually run them successfully.

## Why this project matters

UTN Scholar combines product UX, document workflows, AI assistance and cross-platform reliability into one system. It demonstrates that an AI-enabled application still needs strong state management, diagnostics, recovery and reproducible delivery around the model itself.

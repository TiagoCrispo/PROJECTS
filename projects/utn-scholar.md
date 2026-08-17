# UTN Scholar

UTN Scholar is a study workspace built around university material. It is designed to turn scattered PDFs, notes, slides and other course files into an organized environment for reading, studying and AI-assisted work.

## What it does

- organizes course documents and subject-specific material;
- supports intelligent import and document-centered study workflows;
- provides AI-assisted explanations, summaries and study tools;
- supports Windows and Android use with adaptive navigation;
- includes desktop-friendly library interactions such as drag and drop and contextual file actions;
- supports secure local-network pairing between devices;
- keeps a usable local/degraded mode when backend or AI services are unavailable;
- includes diagnostics for storage, backend, AI, pairing and toolchain health;
- provides accessibility options such as text scaling, reduced motion and high-contrast support;
- includes automated quality, recovery and regression checks around important workflows.

## Engineering focus

The project is built around reliability rather than a single AI prompt box. Local state, pairing, recovery, startup behavior and error reporting are treated as first-class parts of the application.

Private network traffic is protected instead of relying on an open LAN endpoint, and diagnostic exports are designed to avoid leaking credentials or unrelated private data.

## Direction

The goal is a practical personal study system that can grow with real university material while remaining understandable, recoverable and useful even when online AI services are temporarily unavailable.

# Repository Engineering Standard

These rules apply across the software projects in this repository unless a project documents a stricter requirement.

## Work sequence

1. Understand the expected behavior before changing code.
2. Reproduce or characterize the bug/requirement with evidence.
3. Find the root cause; do not stack patches on top of an unexplained failure.
4. Make the smallest structural change that fixes the cause without breaking established behavior.
5. Run the relevant static checks, tests, builds and smoke tests available for the project.
6. Check regressions in adjacent workflows, error paths and recovery paths.
7. For UI work, verify hierarchy, spacing, typography, accessibility, responsive/adaptive behavior and state feedback.
8. For motion, prefer purposeful microinteractions and restrained transitions; reduced-motion behavior must remain usable.
9. Check performance and memory impact when the change touches image processing, rendering, I/O, networking or background work.
10. Update project status, version notes and technical decisions when behavior changes materially.

## Debugging

- Prefer root-cause analysis over symptom suppression.
- Preserve useful diagnostics; errors should fail clearly instead of being disguised as success.
- Do not fabricate capabilities, versions, benchmark results, successful validation or external data.
- Keep rollback/recovery paths when the project mutates user or system state.

## Context discipline

- Read the smallest relevant set of files first, then expand only when necessary.
- Reuse existing architecture and project conventions instead of re-deriving them on every change.
- Keep stable project decisions in repository documentation rather than relying on transient chat context.
- Avoid loading generated artifacts, binaries, datasets or logs unless the task requires them.

## UI/UX quality

- Design complete states: loading, empty, success, error, disabled, offline/degraded and recovery where relevant.
- Favor clear information hierarchy and predictable interaction over decorative complexity.
- Animation must communicate state or continuity, not delay the user.
- Preserve accessibility: contrast, text scaling, keyboard/focus behavior where applicable, touch targets and reduced motion.

## Release discipline

- Never commit secrets, API keys, private credentials, local machine paths, generated caches or unnecessary binaries.
- Keep source and reproducible build instructions as the canonical record.
- A release/version claim must be backed by code plus the corresponding validation evidence available for that project.

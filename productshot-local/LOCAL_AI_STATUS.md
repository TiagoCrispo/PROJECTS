# ProductShot Local AI status

Goal: private Android app with no ProductShot daily quota, no backend dependency, and on-device image processing.

Implemented so far:
- Android API 29-36 project
- ONNX Runtime Android
- pinned ISNet segmentation model download with SHA-256 verification
- on-device foreground segmentation
- exact-pixel product cutout
- local catalog composition
- gallery + delegated camera capture
- MediaStore save + Android share sheet
- HTTPS-only one-time model download
- debug-only SmokeActivity for real model-download + ONNX-inference + JPEG CI validation
- CI build/lint/APK verification and API 29/33/35/36 lifecycle matrix

Quality policy:
- the original product pixels stay locked in the final catalog composition
- a future generative engine may create studio/background content, but it must not redraw the product unless fidelity benchmarks prove that safe
- no internal photo/day/month quota is planned

Next quality stages:
- validate real ISNet inference on Android CI
- improve local matte edge cleanup and relighting
- local high-resolution enhancement
- evaluate native stable-diffusion.cpp for generative backgrounds only if it materially improves catalog quality without degrading product fidelity

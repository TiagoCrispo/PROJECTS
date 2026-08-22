# ProductShot Local AI status

Goal: private Android app with no ProductShot daily quota, no backend dependency, and on-device image processing.

Implemented so far:
- Android API 29-36 project
- ONNX Runtime Android
- pinned ISNet segmentation model download with SHA-256 verification
- on-device foreground segmentation
- exact-pixel product cutout
- local catalog composition
- MediaStore save
- CI build/lint/APK verification

Next quality stages:
- improved local relighting and edge cleanup
- local high-resolution enhancement
- optional native diffusion/image-edit engine if it materially improves quality without degrading product fidelity

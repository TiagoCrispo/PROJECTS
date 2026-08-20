# ProductShot Local AI

Private Android prototype that processes product photos entirely on-device after a one-time model download.

Stage 1 deliberately locks product identity: ISNet extracts the product and the catalog composer reuses the original product pixels instead of asking a generative model to redraw the object. This avoids hallucinating hidden geometry.

Current pipeline: photo picker -> local ISNet segmentation -> exact-pixel cutout -> catalog composition -> MediaStore save.

No ProductShot image quota, no backend and no per-image API charge. Internet is only required to download model files that are then checksum-verified and cached in app-private storage.

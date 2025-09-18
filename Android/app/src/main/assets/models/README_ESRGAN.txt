ESRGAN Model Asset Placeholder
--------------------------------
Expected file: Real-ESRGAN-General-x4v3.tflite (4x super-resolution Real-ESRGAN variant)
Place the TensorFlow Lite model file in this directory with the exact name:

  app/src/main/assets/models/Real-ESRGAN-General-x4v3.tflite

Requirements / Notes:
- Model scale factor: 4x per inference pass.
- Must accept 3-channel RGB (uint8 or float32). Implementation currently assumes float32 input/output in range [0,1]. Adjust EsganUpscaler if your model differs (see NORMALIZATION section in code).
- If your model requires custom ops, keep the `tensorflow-lite-select-tf-ops` dependency. If not, you may remove it later.

Recommended Model Check:
- Validate with a tiny test bitmap (e.g. 16x16) to confirm output size is exactly 64x64.
- If output size mismatch occurs, update SCALE_FACTOR constant in EsganUpscaler (currently 4).

Git Ignore:
- The actual .tflite file may be large and is intentionally not committed here. Add it manually before building the app.

Security:
- Only load trusted model binaries.

After adding the model, no further action needed—the upscaler will attempt lazy initialization at first use.

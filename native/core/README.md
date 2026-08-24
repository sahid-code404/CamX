# CamX native core

The native core is a C++20 library for measured data-plane work. Current executable foundations are
move-only public-NDK owners, a bounded timestamp index, bounded buffer pool, primitive trace ring,
resource counters, one coarse JNI health snapshot, and host tests.

Source ownership is organized by concern:

- `src/jni`: versioned coarse Kotlin/native boundary;
- `src/memory` and `src/buffer`: deterministic owners, counters, bounded pools;
- `src/trace`: primitive hot-path diagnostics;
- future `src/camera` and `src/metadata`: public Camera NDK evidence only, never device control;
- future `src/topology`: measured evidence normalization helpers, not global policy;
- future `src/raw`: AImage/AHardwareBuffer transaction ownership;
- future image/alignment/demosaic/fusion/denoise/superres/tonemap/vulkan directories are created only
  when their implementation ticket begins.

Kotlin remains the policy oracle and Android control plane. See
`docs/NATIVE_MEMORY_MODEL.md` and ADR-002/ADR-008.

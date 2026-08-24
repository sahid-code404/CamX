# Native Memory and JNI Model

Native code is used only when it removes measured allocation, copy, parsing, or processing overhead.
It does not bypass CameraService/HAL and does not own Android lifecycle or ordinary Camera2 control.

## Ownership rules

- Native ownership is RAII only. Owning bare pointers, owning `void*`, and manual multi-exit cleanup
  are forbidden.
- `CameraManagerOwner`, `CameraIdListOwner`, `CameraMetadataOwner`, `NativeImageOwner`, and
  `HardwareBufferOwner` wrap public NDK handles with one destructor.
- `NativeBufferPool`, `BoundedTimestampIndex`, work queues, and `NativeTraceBuffer` require capacity
  at construction. Overflow has an explicit drop/reject action.
- JNI global references require a named owner and debug counter. No native object retains an
  Activity/View/Surface longer than its documented lease.
- Large buffers are returned or released on pause; diagnostic snapshots contain counts, not buffers.
- Destructors do not call Kotlin, block on unbounded work, or hide failures.

## Coarse JNI boundary

JNI accepts or returns validated batches: compact metadata evidence, trace snapshots, resource
counters, or future frame-set handles with explicit close. There is no call per pixel, frame field,
or metadata key. Kotlin validates sizes before entry; native validates again before allocation.
Exceptions are translated at the boundary and never cross native worker threads.

The foundation JNI call returns ABI/schema/resource-health data and proves packaging. Camera NDK
metadata parsing and RAW ownership are later Tier-A tickets gated by benchmarks.

## Future processing dispatch

`RawFrame -> RawFrameSet.takeFrames() -> ProcessingGraph -> ImageProcessor` is the stable one-time
ownership-transfer boundary; owning pair/frame-set wrappers are intentionally non-copyable.
Scalar code is the reference. Optional ARM64 NEON is selected by runtime CPU feature detection, not
vendor identity, and must pass numerical equivalence tests. Vulkan is lazy, post-frame,
capability-detected, failure-isolated, cached, and always has a CPU fallback.

## Leak acceptance

Debug counters cover native images, hardware buffers, allocated buffer bytes, worker count, queue
depth, and JNI global references. After repeated switch/capture/pause/resume cycles, counts must
return to the same quiescent band. Sanitizer/host tests cover bounded structures; hardware soak tests
cover public NDK owners.

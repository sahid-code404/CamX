#include <android/api-level.h>
#include <jni.h>

#include <array>
#include <cstdint>

#include "camx/resource_counters.hpp"

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_sahidcode404_camx_core_camera_diagnostics_NativeCore_nativeSnapshot(
    JNIEnv* environment,
    jobject /* receiver */) {
  constexpr std::int64_t kSchema = 1;
  constexpr std::size_t kCounterCount =
      static_cast<std::size_t>(camx::NativeResource::kCount);
  const auto resources = camx::GlobalResourceCounters().snapshot();
  constexpr std::size_t kHeaderSize = 3U;
  std::array<jlong, kHeaderSize + kCounterCount> values{};
  values[0] = kSchema;
  values[1] = android_get_device_api_level();
  values[2] = static_cast<std::int64_t>(sizeof(void*) * 8U);
  for (std::size_t index = 0U; index < resources.size(); ++index) {
    values[kHeaderSize + index] = resources[index];
  }

  jlongArray result = environment->NewLongArray(static_cast<jsize>(values.size()));
  if (result == nullptr) {
    return nullptr;
  }
  environment->SetLongArrayRegion(
      result,
      0,
      static_cast<jsize>(values.size()),
      values.data());
  if (environment->ExceptionCheck() == JNI_TRUE) {
    return nullptr;
  }
  return result;
}

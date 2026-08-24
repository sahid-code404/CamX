#pragma once

#include <android/hardware_buffer.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <utility>

namespace camx {

template <typename Handle, auto Release>
class UniqueNdkOwner final {
 public:
  UniqueNdkOwner() noexcept = default;
  explicit UniqueNdkOwner(Handle* handle) noexcept : handle_(handle) {}
  ~UniqueNdkOwner() { reset(); }

  UniqueNdkOwner(const UniqueNdkOwner&) = delete;
  UniqueNdkOwner& operator=(const UniqueNdkOwner&) = delete;

  UniqueNdkOwner(UniqueNdkOwner&& other) noexcept
      : handle_(std::exchange(other.handle_, nullptr)) {}

  UniqueNdkOwner& operator=(UniqueNdkOwner&& other) noexcept {
    if (this != &other) {
      reset(std::exchange(other.handle_, nullptr));
    }
    return *this;
  }

  [[nodiscard]] Handle* get() const noexcept { return handle_; }
  [[nodiscard]] explicit operator bool() const noexcept { return handle_ != nullptr; }

  [[nodiscard]] Handle* release() noexcept { return std::exchange(handle_, nullptr); }

  void reset(Handle* replacement = nullptr) noexcept {
    if (handle_ != nullptr) {
      Release(handle_);
    }
    handle_ = replacement;
  }

 private:
  Handle* handle_ = nullptr;
};

using CameraManagerOwner = UniqueNdkOwner<ACameraManager, ACameraManager_delete>;
using CameraMetadataOwner = UniqueNdkOwner<ACameraMetadata, ACameraMetadata_free>;
using NativeImageOwner = UniqueNdkOwner<AImage, AImage_delete>;
using NativeImageReaderOwner = UniqueNdkOwner<AImageReader, AImageReader_delete>;
using HardwareBufferOwner = UniqueNdkOwner<AHardwareBuffer, AHardwareBuffer_release>;

class CameraIdListOwner final {
 public:
  CameraIdListOwner() noexcept = default;
  explicit CameraIdListOwner(ACameraIdList* list) noexcept : list_(list) {}
  ~CameraIdListOwner() { reset(); }

  CameraIdListOwner(const CameraIdListOwner&) = delete;
  CameraIdListOwner& operator=(const CameraIdListOwner&) = delete;

  CameraIdListOwner(CameraIdListOwner&& other) noexcept
      : list_(std::exchange(other.list_, nullptr)) {}
  CameraIdListOwner& operator=(CameraIdListOwner&& other) noexcept {
    if (this != &other) {
      reset(std::exchange(other.list_, nullptr));
    }
    return *this;
  }

  [[nodiscard]] ACameraIdList* get() const noexcept { return list_; }

  void reset(ACameraIdList* replacement = nullptr) noexcept {
    if (list_ != nullptr) {
      ACameraManager_deleteCameraIdList(list_);
    }
    list_ = replacement;
  }

 private:
  ACameraIdList* list_ = nullptr;
};

}  // namespace camx

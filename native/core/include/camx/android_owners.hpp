#pragma once

#include <utility>

namespace camx {

/**
 * API-neutral move-only owner used by optional public-NDK capability modules.
 *
 * The API-23 baseline deliberately does not include or bind Camera NDK,
 * AImageReader, AImage, or AHardwareBuffer symbols. A future optional target
 * may instantiate this owner only at that target's capability-gated API level.
 */
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

}  // namespace camx

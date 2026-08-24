#include "camx/android_owners.hpp"

#include <type_traits>

static_assert(!std::is_copy_constructible_v<camx::CameraManagerOwner>);
static_assert(std::is_move_constructible_v<camx::CameraManagerOwner>);
static_assert(!std::is_copy_constructible_v<camx::CameraMetadataOwner>);
static_assert(!std::is_copy_constructible_v<camx::NativeImageOwner>);
static_assert(!std::is_copy_constructible_v<camx::NativeImageReaderOwner>);
static_assert(!std::is_copy_constructible_v<camx::HardwareBufferOwner>);
static_assert(!std::is_copy_constructible_v<camx::CameraIdListOwner>);

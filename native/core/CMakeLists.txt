cmake_minimum_required(VERSION 3.22.1)
project(camx_core LANGUAGES CXX)

add_library(camx_core SHARED
    src/jni/camx_jni.cpp
    src/buffer/native_buffer_pool.cpp
    src/memory/android_owners_compile.cpp
    src/memory/resource_counters.cpp
    src/trace/native_trace_buffer.cpp
)

target_compile_features(camx_core PUBLIC cxx_std_20)
target_include_directories(camx_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_compile_options(camx_core PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)
target_link_options(camx_core PRIVATE "-Wl,--exclude-libs,ALL")

find_library(android_library android REQUIRED)
find_library(camera_ndk_library camera2ndk REQUIRED)
find_library(log_library log REQUIRED)
find_library(media_ndk_library mediandk REQUIRED)

target_link_libraries(camx_core PRIVATE
    "${android_library}"
    "${camera_ndk_library}"
    "${log_library}"
    "${media_ndk_library}"
)

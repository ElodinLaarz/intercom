# Android NDK build of libintercore.so. Wired into the APK via
# externalNativeBuild in app/build.gradle.kts, so `assembleDebug` exercises the
# NDK + cmake toolchain on every build — the M0 toolchain probe.
cmake_minimum_required(VERSION 3.22.1)
project(intercore CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(intercore SHARED
    intercore_jni.cpp
    voice_audio.cpp)

# Shared, codec-agnostic core (host-tested under /native).
target_include_directories(intercore PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/core
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/codec
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../proto)

find_library(log-lib log)
target_link_libraries(intercore ${log-lib} oboe::oboe)

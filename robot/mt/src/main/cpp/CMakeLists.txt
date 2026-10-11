# librobotmt.so, Robot's fast translation engine (docs/conversation-mode.md 4.3): slimt (DavidVentura's fork) with its
# ruy, cpuinfo and SentencePiece, statically linked with Robot's wrapper (robotmt.cpp) and three JNI functions
# (robotmt_jni.cpp), for the :mt process. Built by tools/build_robotmt.sh, which fetches the pinned sources and passes
# SLIMT_SOURCE_DIR; Gradle doesn't run this (the .so goes into mt/src/main/jniLibs, not in git).
# For Android (the NDK toolchain): the shared library. On the build machine: robotmt_check, the same wrapper as a
# command-line check against the reference outputs.
cmake_minimum_required(VERSION 3.18)
project(robotmt CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(SLIMT_SOURCE_DIR "" CACHE PATH "slimt source tree, with 3rd-party/ruy (and its third_party/cpuinfo) and 3rd-party/sentencepiece")
if(NOT EXISTS "${SLIMT_SOURCE_DIR}/slimt/Frontend.hh" OR NOT EXISTS "${SLIMT_SOURCE_DIR}/3rd-party/ruy/third_party/cpuinfo/CMakeLists.txt")
  message(FATAL_ERROR "SLIMT_SOURCE_DIR must be the slimt tree tools/build_robotmt.sh fetches")
endif()

# As slimt-sys builds slimt: small sections, hidden symbols, no debug info, and no fused multiply-add contraction (the
# same arithmetic on every architecture). Source paths are mapped so the library doesn't depend on where it was built.
set(ROBOTMT_FLAGS "-ffunction-sections -fdata-sections -fvisibility=hidden -g0 -ffile-prefix-map=${SLIMT_SOURCE_DIR}=slimt -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=robotmt")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${ROBOTMT_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${ROBOTMT_FLAGS} -fvisibility-inlines-hidden -ffp-contract=off -Wno-unused-command-line-argument")

# slimt: the static library only (no tests, Python, Java or shared builds) and its built-in SentencePiece. Its own NEON
# float code (USE_NEON) stays off by default: with it, a few of the arm64 build's translations differ from the x86 build's
# (1 of the 23 test lines under qemu-aarch64), so the build machine could no longer give the phone's exact reference
# outputs. The int8 matrix products go through ruy either way, which picks its NEON (dot-product) kernels at run time.
option(ROBOTMT_NEON "slimt's NEON float code and decode-time gemv on arm64 (faster; outputs differ from x86 on some lines)" OFF)
set(WITH_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_PYTHON OFF CACHE BOOL "" FORCE)
set(BUILD_JNI OFF CACHE BOOL "" FORCE)
set(USE_BUILTIN_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(USE_NEON ${ROBOTMT_NEON} CACHE BOOL "" FORCE)
if(ROBOTMT_NEON AND NOT ANDROID)
  message(FATAL_ERROR "ROBOTMT_NEON is for the arm64 build")
endif()
# slimt's sources include "slimt/…" from the top of their tree, and its generated Version.hh from the build tree.
include_directories(${SLIMT_SOURCE_DIR} ${CMAKE_BINARY_DIR}/slimt)
add_subdirectory(${SLIMT_SOURCE_DIR} slimt EXCLUDE_FROM_ALL)

add_library(robotmt_engine STATIC robotmt.cpp)
target_link_libraries(robotmt_engine PUBLIC slimt-static)

if(ANDROID)
  add_library(robotmt SHARED robotmt_jni.cpp)
  target_link_libraries(robotmt PRIVATE robotmt_engine)
  # Only the three JNI functions are exported; the C++ runtime is linked in (nothing but libc, libm and libdl needed);
  # 16 KB pages supported (Android 15 and later can run with them).
  target_link_options(robotmt PRIVATE -Wl,--gc-sections -Wl,--strip-all -Wl,--exclude-libs,ALL -static-libstdc++
    -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1)
else()
  add_executable(robotmt_check robotmt_check.cpp)
  target_link_libraries(robotmt_check PRIVATE robotmt_engine)
endif()

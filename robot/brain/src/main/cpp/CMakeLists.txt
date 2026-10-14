# Robot's llama.cpp library for its isolated :llama process (0.4.5): llama.cpp, ggml and KleidiAI from their pinned sources,
# built as static libraries and linked with Robot's JNI bridge (robot_llama.cpp) into one shared library,
# librobot_llama.so, that exports only its JNI functions. Configured by tools/build_llama.sh, which fetches and checks
# the sources first (THIRD_PARTY.md); never by Gradle, so the app still builds without it.
#
# The CPU only: no Vulkan, no OpenCL (the Pixel 10's GPU path is unreliable), no OpenMP, no llamafile, no network code.
# ggml's own kernels are built for Armv8.6-A with dotprod, i8mm and fp16 (the Tensor G5 is Armv9.2-A and has them all;
# LlamaEngine checks /proc/cpuinfo before it loads this library), and KleidiAI's kernels are chosen at run time.
cmake_minimum_required(VERSION 3.22)
project(robot_llama C CXX)

if(NOT DEFINED LLAMA_SRC OR NOT EXISTS "${LLAMA_SRC}/include/llama.h")
    message(FATAL_ERROR "Set LLAMA_SRC to the checked llama.cpp source (tools/build_llama.sh does)")
endif()
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "arm64-v8a only")
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Each function and object in its own section, so the linker drops what Robot's bridge never reaches.
add_compile_options(-ffunction-sections -fdata-sections -fvisibility=hidden)

# The library doesn't depend on where it was built (tools/repro_check.sh --native): the folders of llama.cpp, KleidiAI,
# Robot's bridge, the build and the NDK are mapped to fixed names in __FILE__ (ggml's asserts name their source file)
# and in the unstripped copy's debug info, as mt's CMakeLists.txt does for slimt. None of the folders holds another.
set(ROBOT_PREFIX_MAPS "${LLAMA_SRC}=llama.cpp" "${CMAKE_CURRENT_SOURCE_DIR}=robot_llama" "${CMAKE_BINARY_DIR}=build" "${CMAKE_ANDROID_NDK}=ndk")
if(FETCHCONTENT_SOURCE_DIR_KLEIDIAI)
    list(APPEND ROBOT_PREFIX_MAPS "${FETCHCONTENT_SOURCE_DIR_KLEIDIAI}=kleidiai")
endif()
foreach(map ${ROBOT_PREFIX_MAPS})
    add_compile_options("-ffile-prefix-map=${map}")
endforeach()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
foreach(off LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_BUILD_UI
        LLAMA_BUILD_MTMD LLAMA_OPENSSL LLAMA_SUBPROCESS LLAMA_LLGUIDANCE LLAMA_USE_PREBUILT_UI LLAMA_TOOLS_INSTALL
        GGML_NATIVE GGML_OPENMP GGML_LLAMAFILE GGML_BACKEND_DL GGML_CPU_ALL_VARIANTS GGML_VULKAN GGML_OPENCL GGML_CUDA
        GGML_HIP GGML_METAL GGML_SYCL GGML_RPC GGML_BLAS GGML_WEBGPU)
    set(${off} OFF CACHE BOOL "" FORCE)
endforeach()
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.6-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)

add_subdirectory("${LLAMA_SRC}" llama EXCLUDE_FROM_ALL)

add_library(robot_llama SHARED robot_llama.cpp)
target_link_libraries(robot_llama PRIVATE llama-common llama)
# Only the JNI entry points are exported; nothing of llama.cpp, ggml or the C++ library is visible to anyone else.
# fork and execlp (ggml's debugger backtrace) are wrapped by robot_llama.cpp's stand-ins, which always fail: the library
# can't start a process (tools/build_llama.sh checks its imports).
target_link_options(robot_llama PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
    -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--no-undefined -Wl,-z,max-page-size=16384
    -Wl,--wrap=fork -Wl,--wrap=execlp -Wl,--build-id=sha1)

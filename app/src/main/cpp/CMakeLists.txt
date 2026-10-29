cmake_minimum_required(VERSION 3.22.1)
project(sharpr_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Prebuilt ncnn (Vulkan), fetched by scripts/setup-native.sh / setup-native.ps1
set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

# RNNoise 0.1.x (BSD-3-Clause), vendored with its trained weights.
file(GLOB RNNOISE_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/*.c)
add_library(rnnoise STATIC ${RNNOISE_SOURCES})
target_include_directories(rnnoise PUBLIC rnnoise/include PRIVATE rnnoise)
target_compile_options(rnnoise PRIVATE -O3 -w)

add_library(sharpr_native SHARED
        jni_bridge.cpp
        sr_engine.cpp
        audio_dsp.cpp)

target_link_libraries(sharpr_native ncnn rnnoise jnigraphics android log m)
target_link_options(sharpr_native PRIVATE -Wl,-z,max-page-size=16384)

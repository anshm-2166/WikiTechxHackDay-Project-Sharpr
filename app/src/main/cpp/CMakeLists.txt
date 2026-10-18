cmake_minimum_required(VERSION 3.22.1)
project(sharpr_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Prebuilt ncnn (Vulkan) — fetched by scripts/setup-native.ps1
set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(sharpr_native SHARED sharpr_native.cpp)

target_link_libraries(sharpr_native ncnn jnigraphics android log)
target_link_options(sharpr_native PRIVATE -Wl,-z,max-page-size=16384)

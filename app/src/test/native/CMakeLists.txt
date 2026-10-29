# Desktop tests for the platform-neutral native core (super-resolution tiling and audio DSP).
#
#   cmake -S app/src/test/native -B build/native-test -Dncnn_DIR=<ncnn install>/lib/cmake/ncnn
#   cmake --build build/native-test -j && build/native-test/sharpr_native_tests app/src/main/assets/models
#
# CI builds a CPU-only ncnn for this (see .github/workflows/android.yml).
cmake_minimum_required(VERSION 3.16)
project(sharpr_native_tests C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif ()

find_package(ncnn REQUIRED)

set(CORE ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)
file(GLOB RNNOISE_SOURCES ${CORE}/rnnoise/*.c)

add_executable(sharpr_native_tests
        test_main.cpp
        ${CORE}/sr_engine.cpp
        ${CORE}/audio_dsp.cpp
        ${RNNOISE_SOURCES})
target_include_directories(sharpr_native_tests PRIVATE ${CORE} ${CORE}/rnnoise ${CORE}/rnnoise/include)
target_link_libraries(sharpr_native_tests ncnn m)

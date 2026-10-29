// Sharpr super-resolution core: tiled ncnn inference for every model family Sharpr ships.
//
// Platform neutral on purpose (no JNI, no Android headers) so the exact same code is unit tested on
// the desktop against the real models (see app/src/test/native).
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

#include "net.h"
#include "sharpr_common.h"

namespace sharpr {

/** How a network eats and returns pixels. Measured per model, see Models.kt. */
struct ModelSpec {
    std::string param;
    std::string bin;
    std::string inBlob = "data";
    std::string outBlob = "output";
    int scale = 4;      // the network's native upscale factor
    int crop = 0;       // input pixels the network trims from every side of its input
    int prepad = 10;    // context pixels fed around each tile (must be >= crop)
    int align = 1;      // padded tile sides must be multiples of this
    int tilePct = 100;  // auto tile size scaled by this (compact nets afford bigger tiles)
    bool residual = false;  // network predicts a residual over the nearest-neighbour upscale (Real-CUGAN 4x)
};

struct EngineConfig {
    bool gpu = true;
    int threads = 0;    // 0 = performance cores
    int tile = 0;       // 0 = auto
    int precision = 1;  // 0 = fp32, 1 = fp16 storage, 2 = fp16 storage + arithmetic
    bool tta = false;   // 8x self-ensemble (flips + transpose), slower but cleaner
};

/** Loads param + bin into a net; Android reads from the APK assets, tests from disk. */
using ModelLoader = std::function<bool(ncnn::Net&, const ModelSpec&)>;

bool gpuAvailable();
std::string gpuName();
int gpuHeapBudgetMb();
int performanceCores();

/** One loaded network on one backend. Reuse it across images and video frames. */
class SrSession {
public:
    SrSession() = default;
    ~SrSession();
    SrSession(const SrSession&) = delete;
    SrSession& operator=(const SrSession&) = delete;

    int load(const ModelSpec& spec, const EngineConfig& cfg, const ModelLoader& loader);

    /**
     * RGBA8888 in, RGBA8888 out. [outScale] must divide the native scale; smaller scales are box
     * averaged down from the native output (supersampled restoration).
     * dst must be (w * outScale) x (h * outScale).
     */
    int process(const uint8_t* src, int w, int h, int srcStride, uint8_t* dst, int dstStride, int outScale,
                ProgressSink* sink);

    bool usesGpu() const { return gpu_; }
    const std::string& deviceName() const { return device_; }
    int tileSize() const { return tile_; }
    const ModelSpec& spec() const { return spec_; }

private:
    int runTile(const ncnn::Mat& in, ncnn::Mat& out);

    std::unique_ptr<ncnn::Net> net_;
    ModelSpec spec_;
    EngineConfig cfg_;
    bool gpu_ = false;
    bool verified_ = false;  // GPU output passed the sanity check at least once
    int tile_ = 0;
    std::string device_;
};

}  // namespace sharpr

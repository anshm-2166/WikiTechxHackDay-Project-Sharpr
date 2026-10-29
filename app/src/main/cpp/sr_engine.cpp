#include "sr_engine.h"

#include <algorithm>
#include <cmath>
#include <mutex>

#include "cpu.h"
#if NCNN_VULKAN
#include "gpu.h"
#endif

namespace sharpr {

namespace {

#if NCNN_VULKAN
std::once_flag g_gpuOnce;
bool g_gpuReady = false;

void ensureGpu() {
    std::call_once(g_gpuOnce, [] {
        int rc = ncnn::create_gpu_instance();
        g_gpuReady = rc == 0 && ncnn::get_gpu_count() > 0;
    });
}
#endif

inline uint8_t toByte(float v) {
    v = v * 255.f + 0.5f;
    return (uint8_t) (v < 0.f ? 0.f : (v > 255.f ? 255.f : v));
}

inline int roundUp(int v, int m) { return m <= 1 ? v : (v + m - 1) / m * m; }

// Test-time augmentation: bit 0 flips x, bit 1 flips y, bit 2 transposes (transpose applied first).
ncnn::Mat transposed(const ncnn::Mat& m) {
    ncnn::Mat r(m.h, m.w, m.c);
    for (int c = 0; c < m.c; c++) {
        const float* s = m.channel(c);
        float* d = r.channel(c);
        for (int y = 0; y < m.h; y++)
            for (int x = 0; x < m.w; x++) d[(size_t) x * m.h + y] = s[(size_t) y * m.w + x];
    }
    return r;
}

void flipX(ncnn::Mat& m) {
    for (int c = 0; c < m.c; c++) {
        float* p = m.channel(c);
        for (int y = 0; y < m.h; y++) std::reverse(p + (size_t) y * m.w, p + (size_t) (y + 1) * m.w);
    }
}

void flipY(ncnn::Mat& m) {
    for (int c = 0; c < m.c; c++) {
        float* p = m.channel(c);
        for (int y = 0; y < m.h / 2; y++)
            std::swap_ranges(p + (size_t) y * m.w, p + (size_t) (y + 1) * m.w, p + (size_t) (m.h - 1 - y) * m.w);
    }
}

ncnn::Mat forward(const ncnn::Mat& m, int t) {
    ncnn::Mat r = (t & 4) ? transposed(m) : m.clone();
    if (t & 1) flipX(r);
    if (t & 2) flipY(r);
    return r;
}

ncnn::Mat inverse(const ncnn::Mat& m, int t) {
    ncnn::Mat r = m.clone();
    if (t & 1) flipX(r);
    if (t & 2) flipY(r);
    return (t & 4) ? transposed(r) : r;
}

int autoTile(bool gpu) {
#if NCNN_VULKAN
    if (gpu) {
        int budget = gpuHeapBudgetMb();
        if (budget > 3500) return 256;
        if (budget > 1900) return 200;
        if (budget > 550) return 100;
        if (budget > 190) return 64;
        return 32;
    }
#endif
    (void) gpu;
    return 128;
}

}  // namespace

bool gpuAvailable() {
#if NCNN_VULKAN
    ensureGpu();
    return g_gpuReady;
#else
    return false;
#endif
}

std::string gpuName() {
#if NCNN_VULKAN
    if (gpuAvailable()) return ncnn::get_gpu_info(0).device_name();
#endif
    return "";
}

int gpuHeapBudgetMb() {
#if NCNN_VULKAN
    if (gpuAvailable()) {
        ncnn::VulkanDevice* dev = ncnn::get_gpu_device(0);
        return dev ? (int) dev->get_heap_budget() : 0;
    }
#endif
    return 0;
}

int performanceCores() {
    int big = ncnn::get_big_cpu_count();
    return big > 0 ? big : std::max(1, ncnn::get_cpu_count());
}

SrSession::~SrSession() { net_.reset(); }

int SrSession::load(const ModelSpec& spec, const EngineConfig& cfg, const ModelLoader& loader) {
    if (spec.scale < 1 || spec.prepad < spec.crop || spec.align < 1) return RC_BAD_INPUT;
    spec_ = spec;
    cfg_ = cfg;
    net_.reset(new ncnn::Net());
    ncnn::Option& opt = net_->opt;

    gpu_ = false;
#if NCNN_VULKAN
    if (cfg.gpu) {
        if (!gpuAvailable()) return RC_NO_GPU;
        gpu_ = true;
        opt.use_vulkan_compute = true;
        net_->set_vulkan_device(0);
    }
#else
    if (cfg.gpu) return RC_NO_GPU;
#endif

    const bool fp16 = cfg.precision >= 1;
    opt.use_fp16_packed = fp16;
    opt.use_fp16_storage = fp16;
    opt.use_fp16_arithmetic = cfg.precision >= 2;
    opt.use_bf16_storage = false;
    opt.lightmode = true;

    if (gpu_) {
        device_ = std::string("GPU \xC2\xB7 ") + gpuName();
    } else {
        ncnn::set_cpu_powersave(cfg.threads > 0 ? 0 : 2);  // auto: big cores only
        int threads = cfg.threads > 0 ? cfg.threads : performanceCores();
        opt.num_threads = threads;
        device_ = "CPU \xC2\xB7 " + std::to_string(threads) + " threads";
    }

    if (!loader(*net_, spec_)) {
        net_.reset();
        return RC_MODEL_LOAD;
    }

    int base = cfg.tile > 0 ? cfg.tile : autoTile(gpu_) * spec.tilePct / 100;
    // A tile must be clearly larger than the context the network throws away.
    tile_ = std::max(base, std::max(32, spec.crop * 2));
    verified_ = !gpu_;
    return RC_OK;
}

int SrSession::runTile(const ncnn::Mat& in, ncnn::Mat& out) {
    ncnn::Extractor ex = net_->create_extractor();
    if (ex.input(spec_.inBlob.c_str(), in) != 0) return RC_INFERENCE;
    if (ex.extract(spec_.outBlob.c_str(), out) != 0 || out.empty() || out.c != 3) return RC_INFERENCE;
    return RC_OK;
}

int SrSession::process(const uint8_t* src, int w, int h, int srcStride, uint8_t* dst, int dstStride,
                       int outScale, ProgressSink* sink) {
    if (!net_) return RC_MODEL_LOAD;
    const int s = spec_.scale;
    if (!src || !dst || w <= 0 || h <= 0 || outScale <= 0 || s % outScale != 0) return RC_BAD_INPUT;

    const int k = s / outScale;  // box-downsample factor (1 = native)
    const int P = spec_.prepad;
    const int tile = tile_;
    const int xtiles = (w + tile - 1) / tile;
    const int ytiles = (h + tile - 1) / tile;
    const int total = xtiles * ytiles;
    const int passes = cfg_.tta ? 8 : 1;
    if (sink) sink->progress(0, total);

    int done = 0;
    for (int yi = 0; yi < ytiles; yi++) {
        for (int xi = 0; xi < xtiles; xi++) {
            if (sink && sink->cancelled()) return RC_CANCELLED;

            const int x0 = xi * tile, y0 = yi * tile;
            const int tw = std::min(tile, w - x0), th = std::min(tile, h - y0);
            const int pw = roundUp(tw + 2 * P, spec_.align), ph = roundUp(th + 2 * P, spec_.align);

            // Padded input tile: real neighbours where they exist, edge replication beyond the image.
            ncnn::Mat in(pw, ph, 3);
            double inMean = 0.0;
            for (int y = 0; y < ph; y++) {
                const int sy = std::min(std::max(y0 - P + y, 0), h - 1);
                const uint8_t* row = src + (size_t) sy * srcStride;
                float* r = in.channel(0).row(y);
                float* g = in.channel(1).row(y);
                float* b = in.channel(2).row(y);
                for (int x = 0; x < pw; x++) {
                    const int sx = std::min(std::max(x0 - P + x, 0), w - 1);
                    const uint8_t* p = row + (size_t) sx * 4;
                    r[x] = p[0] * (1.f / 255.f);
                    g[x] = p[1] * (1.f / 255.f);
                    b[x] = p[2] * (1.f / 255.f);
                }
            }

            ncnn::Mat acc;
            for (int t = 0; t < passes; t++) {
                ncnn::Mat out;
                int rc = runTile(cfg_.tta ? forward(in, t) : in, out);
                if (rc != RC_OK) return rc;
                if (cfg_.tta) out = inverse(out, t);
                if (t == 0) {
                    acc = cfg_.tta ? out : out.clone();
                } else {
                    const size_t n = (size_t) acc.w * acc.h;
                    if (out.w != acc.w || out.h != acc.h) return RC_INFERENCE;
                    for (int c = 0; c < 3; c++) {
                        float* a = acc.channel(c);
                        const float* o = out.channel(c);
                        for (size_t i = 0; i < n; i++) a[i] += o[i];
                    }
                }
            }
            if (passes > 1) {
                const float inv = 1.f / (float) passes;
                for (int c = 0; c < 3; c++) {
                    float* a = acc.channel(c);
                    for (size_t i = 0, n = (size_t) acc.w * acc.h; i < n; i++) a[i] *= inv;
                }
            }

            if (spec_.residual) {
                // Output pixel (x, y) sits over padded-input pixel (crop + x / s, crop + y / s).
                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < acc.h; y++) {
                        float* o = acc.channel(c).row(y);
                        const float* src = in.channel(c).row(std::min(ph - 1, spec_.crop + y / s));
                        for (int x = 0; x < acc.w; x++) o[x] += src[std::min(pw - 1, spec_.crop + x / s)];
                    }
                }
            }

            // Where the tile's first pixel lands in the network output.
            const int off = (P - spec_.crop) * s;
            if (acc.w < off + tw * s || acc.h < off + th * s) return RC_INFERENCE;

            if (!verified_) {
                // Some mobile Vulkan drivers "succeed" but return garbage. Compare the tile's own pixels.
                long n = 0;
                for (int c = 0; c < 3; c++) {
                    for (int y = P; y < P + th; y += 2) {
                        const float* row = in.channel(c).row(y);
                        for (int x = P; x < P + tw; x += 2, n++) inMean += row[x];
                    }
                }
                inMean /= (double) std::max(1L, n);
                double outMean = 0.0;
                n = 0;
                for (int c = 0; c < 3; c++) {
                    for (int y = off; y < off + th * s; y += 3) {
                        const float* row = acc.channel(c).row(y);
                        for (int x = off; x < off + tw * s; x += 3, n++) outMean += row[x];
                    }
                }
                outMean /= (double) std::max(1L, n);
                if (!std::isfinite(outMean) || std::fabs(outMean - inMean) > 0.25) return RC_INFERENCE;
                verified_ = true;
            }

            // Trim the context, optionally box-downsample, write RGBA.
            const int ow = tw * s / k, oh = th * s / k;
            const int dx0 = x0 * outScale, dy0 = y0 * outScale;
            const float invK2 = 1.f / (float) (k * k);
            const float* ch[3] = {acc.channel(0), acc.channel(1), acc.channel(2)};
            for (int y = 0; y < oh; y++) {
                uint8_t* drow = dst + (size_t) (dy0 + y) * dstStride + (size_t) dx0 * 4;
                for (int x = 0; x < ow; x++) {
                    uint8_t* d = drow + (size_t) x * 4;
                    for (int c = 0; c < 3; c++) {
                        float v;
                        if (k == 1) {
                            v = ch[c][(size_t) (off + y) * acc.w + off + x];
                        } else {
                            float sum = 0.f;
                            for (int ky = 0; ky < k; ky++) {
                                const float* rp = ch[c] + (size_t) (off + y * k + ky) * acc.w + off + x * k;
                                for (int kx = 0; kx < k; kx++) sum += rp[kx];
                            }
                            v = sum * invK2;
                        }
                        d[c] = toByte(v);
                    }
                    d[3] = 255;
                }
            }
            if (sink) sink->progress(++done, total);
        }
    }
    return RC_OK;
}

}  // namespace sharpr

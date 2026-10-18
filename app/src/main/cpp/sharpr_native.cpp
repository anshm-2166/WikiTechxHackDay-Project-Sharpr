// Sharpr native core: tiled Real-ESRGAN style super-resolution on top of ncnn.
//
// Engines:  0 = AUTO (Vulkan GPU, fall back to CPU), 1 = GPU, 2 = CPU.
// The Kotlin side owns pre/post processing (OpenCV); this file only does the heavy
// network inference tile by tile, straight from/to Android bitmaps.

#include <jni.h>
#include <android/bitmap.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include "cpu.h"
#include "gpu.h"
#include "net.h"

#define LOG_TAG "SharprNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int ENGINE_AUTO = 0;
constexpr int ENGINE_GPU = 1;
constexpr int ENGINE_CPU = 2;

constexpr int RC_OK = 0;
constexpr int RC_CANCELLED = 1;
constexpr int RC_BAD_BITMAP = -1;
constexpr int RC_MODEL_LOAD = -2;
constexpr int RC_INFERENCE = -3;
constexpr int RC_NO_GPU = -4;

constexpr int PRE_PAD = 10;  // context pixels around each tile, trimmed after inference

std::atomic<bool> g_cancel{false};
std::once_flag g_gpuOnce;
bool g_gpuReady = false;

void ensureGpuInstance() {
    std::call_once(g_gpuOnce, [] {
        int rc = ncnn::create_gpu_instance();
        g_gpuReady = (rc == 0) && ncnn::get_gpu_count() > 0;
        LOGI("vulkan instance rc=%d gpus=%d", rc, ncnn::get_gpu_count());
    });
}

bool gpuAvailable() {
    ensureGpuInstance();
    return g_gpuReady;
}

struct Listener {
    JNIEnv* env;
    jobject obj;
    jmethodID onProgress;
    jmethodID onBackend;

    Listener(JNIEnv* e, jobject o) : env(e), obj(o), onProgress(nullptr), onBackend(nullptr) {
        if (!obj) return;
        jclass cls = env->GetObjectClass(obj);
        onProgress = env->GetMethodID(cls, "onProgress", "(II)V");
        onBackend = env->GetMethodID(cls, "onBackend", "(Ljava/lang/String;Z)V");
        env->DeleteLocalRef(cls);
    }
    void progress(int done, int total) const {
        if (obj && onProgress) env->CallVoidMethod(obj, onProgress, done, total);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    void backend(const std::string& name, bool fallback) const {
        if (obj && onBackend) {
            jstring s = env->NewStringUTF(name.c_str());
            env->CallVoidMethod(obj, onBackend, s, (jboolean) fallback);
            env->DeleteLocalRef(s);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
};

struct Job {
    AAssetManager* assets;
    std::string modelBase;
    int modelScale;
    int outScale;
    const uint8_t* src;
    int srcW, srcH, srcStride;
    uint8_t* dst;
    int dstStride;
    int threads;
    int tile;  // 0 = auto
    bool fp16;
};

inline uint8_t toByte(float v) {
    v = v * 255.f + 0.5f;
    return (uint8_t) (v < 0.f ? 0.f : (v > 255.f ? 255.f : v));
}

int autoTile(bool gpu) {
    if (!gpu) return 128;
    uint32_t budget = 0;
    ncnn::VulkanDevice* dev = ncnn::get_gpu_device(0);
    if (dev) budget = dev->get_heap_budget();
    if (budget > 1900) return 200;
    if (budget > 550) return 100;
    if (budget > 190) return 64;
    return 32;
}

int cpuThreads(int requested) {
    if (requested > 0) return requested;
    int big = ncnn::get_big_cpu_count();
    return big > 0 ? big : std::max(1, ncnn::get_cpu_count());
}

// Runs the whole image through the network on one backend.
int runJob(const Job& job, bool useGpu, const Listener& listener) {
    ncnn::Net net;
    net.opt.use_vulkan_compute = useGpu;
    net.opt.use_fp16_packed = job.fp16;
    net.opt.use_fp16_storage = job.fp16;
    net.opt.use_fp16_arithmetic = false;  // keeps colours stable on every driver
    net.opt.use_bf16_storage = false;
    net.opt.lightmode = true;

    std::string deviceName = "CPU";
    int threads = 1;
    if (useGpu) {
        net.set_vulkan_device(0);
        deviceName = std::string("GPU \xC2\xB7 ") + ncnn::get_gpu_info(0).device_name();
    } else {
        ncnn::set_cpu_powersave(job.threads > 0 ? 0 : 2);  // auto = big cores only
        threads = cpuThreads(job.threads);
        net.opt.num_threads = threads;
        deviceName = "CPU \xC2\xB7 " + std::to_string(threads) + " threads";
    }

    const std::string param = job.modelBase + ".param";
    const std::string bin = job.modelBase + ".bin";
    if (net.load_param(job.assets, param.c_str()) != 0 || net.load_model(job.assets, bin.c_str()) != 0) {
        LOGE("failed to load model %s", job.modelBase.c_str());
        return RC_MODEL_LOAD;
    }

    listener.backend(deviceName, false);

    const int s = job.modelScale;
    const int k = s / job.outScale;  // integer box-downsample factor (1 = native resolution)
    const int tile = job.tile > 0 ? job.tile : autoTile(useGpu);
    const int xtiles = (job.srcW + tile - 1) / tile;
    const int ytiles = (job.srcH + tile - 1) / tile;
    const int total = xtiles * ytiles;
    LOGI("job: %dx%d x%d (model x%d) tile=%d tiles=%d gpu=%d", job.srcW, job.srcH, job.outScale, s, tile, total,
         (int) useGpu);

    listener.progress(0, total);
    int done = 0;
    bool firstTile = true;

    for (int yi = 0; yi < ytiles; yi++) {
        for (int xi = 0; xi < xtiles; xi++) {
            if (g_cancel.load()) return RC_CANCELLED;

            const int x0 = xi * tile, y0 = yi * tile;
            const int x1 = std::min(x0 + tile, job.srcW), y1 = std::min(y0 + tile, job.srcH);
            const int tw = x1 - x0, th = y1 - y0;
            const int pw = tw + 2 * PRE_PAD, ph = th + 2 * PRE_PAD;

            // Build the padded input tile (edge-replicated), normalised to 0..1.
            ncnn::Mat in(pw, ph, 3);
            double inMean = 0.0;
            for (int y = 0; y < ph; y++) {
                int sy = std::min(std::max(y0 - PRE_PAD + y, 0), job.srcH - 1);
                const uint8_t* row = job.src + (size_t) sy * job.srcStride;
                float* r = in.channel(0).row(y);
                float* g = in.channel(1).row(y);
                float* b = in.channel(2).row(y);
                for (int x = 0; x < pw; x++) {
                    int sx = std::min(std::max(x0 - PRE_PAD + x, 0), job.srcW - 1);
                    const uint8_t* p = row + sx * 4;
                    r[x] = p[0] * (1.f / 255.f);
                    g[x] = p[1] * (1.f / 255.f);
                    b[x] = p[2] * (1.f / 255.f);
                    inMean += r[x] + g[x] + b[x];
                }
            }
            inMean /= (3.0 * pw * ph);

            ncnn::Mat out;
            {
                ncnn::Extractor ex = net.create_extractor();
                ex.input("data", in);
                int rc = ex.extract("output", out);
                if (rc != 0 || out.w != pw * s || out.h != ph * s || out.c != 3) {
                    LOGE("extract failed rc=%d (%dx%dx%d)", rc, out.w, out.h, out.c);
                    return RC_INFERENCE;
                }
            }

            if (firstTile && useGpu) {
                // Some mobile Vulkan drivers "succeed" but return garbage. Catch that early.
                double outMean = 0.0;
                long n = 0;
                for (int c = 0; c < 3; c++) {
                    const float* p = out.channel(c);
                    for (int i = 0; i < out.w * out.h; i += 7, n++) outMean += p[i];
                }
                outMean /= (double) std::max(1L, n);
                if (!std::isfinite(outMean) || std::fabs(outMean - inMean) > 0.25) {
                    LOGE("GPU output failed sanity check (in=%.3f out=%.3f)", inMean, outMean);
                    return RC_INFERENCE;
                }
            }
            firstTile = false;

            // Trim the context border, optionally box-downsample, and write RGBA.
            const int ow = tw * s / k, oh = th * s / k;
            const int dx0 = x0 * job.outScale, dy0 = y0 * job.outScale;
            const int cropX = PRE_PAD * s, cropY = PRE_PAD * s;
            const float invK2 = 1.f / (float) (k * k);
            for (int y = 0; y < oh; y++) {
                uint8_t* drow = job.dst + (size_t) (dy0 + y) * job.dstStride + (size_t) dx0 * 4;
                for (int x = 0; x < ow; x++) {
                    float acc[3] = {0, 0, 0};
                    for (int c = 0; c < 3; c++) {
                        const float* ch = out.channel(c);
                        if (k == 1) {
                            acc[c] = ch[(size_t) (cropY + y) * out.w + cropX + x];
                        } else {
                            float sum = 0.f;
                            for (int ky = 0; ky < k; ky++) {
                                const float* rp = ch + (size_t) (cropY + y * k + ky) * out.w + cropX + x * k;
                                for (int kx = 0; kx < k; kx++) sum += rp[kx];
                            }
                            acc[c] = sum * invK2;
                        }
                    }
                    uint8_t* d = drow + x * 4;
                    d[0] = toByte(acc[0]);
                    d[1] = toByte(acc[1]);
                    d[2] = toByte(acc[2]);
                    d[3] = 255;
                }
            }

            listener.progress(++done, total);
        }
    }
    return RC_OK;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_vytros_projects_sharpr_engine_NativeBridge_gpuInfo(JNIEnv* env, jobject) {
    if (!gpuAvailable()) return env->NewStringUTF("");
    std::string info = std::string(ncnn::get_gpu_info(0).device_name()) + "|" +
                       std::to_string(ncnn::get_gpu_device(0)->get_heap_budget());
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jint JNICALL
Java_com_vytros_projects_sharpr_engine_NativeBridge_cpuCores(JNIEnv*, jobject) {
    int big = ncnn::get_big_cpu_count();
    return big > 0 ? big : ncnn::get_cpu_count();
}

JNIEXPORT void JNICALL
Java_com_vytros_projects_sharpr_engine_NativeBridge_cancel(JNIEnv*, jobject) {
    g_cancel.store(true);
}

JNIEXPORT jint JNICALL
Java_com_vytros_projects_sharpr_engine_NativeBridge_upscale(
        JNIEnv* env, jobject, jobject assetManager, jstring modelBase, jint modelScale, jint outScale,
        jobject inBitmap, jobject outBitmap, jint engine, jboolean allowFallback, jint threads, jint tile,
        jboolean fp16, jobject listenerObj) {
    g_cancel.store(false);

    AndroidBitmapInfo inInfo, outInfo;
    if (AndroidBitmap_getInfo(env, inBitmap, &inInfo) != 0 || AndroidBitmap_getInfo(env, outBitmap, &outInfo) != 0)
        return RC_BAD_BITMAP;
    if (inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return RC_BAD_BITMAP;
    if (outScale <= 0 || modelScale % outScale != 0) return RC_BAD_BITMAP;
    if (outInfo.width != inInfo.width * outScale || outInfo.height != inInfo.height * outScale) return RC_BAD_BITMAP;

    void* inPixels = nullptr;
    void* outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, inBitmap, &inPixels) != 0) return RC_BAD_BITMAP;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != 0) {
        AndroidBitmap_unlockPixels(env, inBitmap);
        return RC_BAD_BITMAP;
    }

    const char* base = env->GetStringUTFChars(modelBase, nullptr);
    Job job;
    job.assets = AAssetManager_fromJava(env, assetManager);
    job.modelBase = base;
    env->ReleaseStringUTFChars(modelBase, base);
    job.modelScale = modelScale;
    job.outScale = outScale;
    job.src = (const uint8_t*) inPixels;
    job.srcW = (int) inInfo.width;
    job.srcH = (int) inInfo.height;
    job.srcStride = (int) inInfo.stride;
    job.dst = (uint8_t*) outPixels;
    job.dstStride = (int) outInfo.stride;
    job.threads = threads;
    job.tile = tile;
    job.fp16 = fp16;

    Listener listener(env, listenerObj);

    int rc;
    const bool wantGpu = engine != ENGINE_CPU;
    const bool fallbackOk = engine == ENGINE_AUTO || allowFallback;
    if (wantGpu && gpuAvailable()) {
        rc = runJob(job, true, listener);
        if (rc != RC_OK && rc != RC_CANCELLED && fallbackOk) {
            LOGW("GPU run failed (rc=%d), falling back to CPU", rc);
            listener.backend("CPU \xC2\xB7 fallback", true);
            rc = runJob(job, false, listener);
        }
    } else if (wantGpu && !fallbackOk) {
        rc = RC_NO_GPU;
    } else {
        if (wantGpu) listener.backend("CPU \xC2\xB7 fallback", true);
        rc = runJob(job, false, listener);
    }

    AndroidBitmap_unlockPixels(env, outBitmap);
    AndroidBitmap_unlockPixels(env, inBitmap);
    return rc;
}

}  // extern "C"

// JNI surface over the Sharpr native core (sr_engine + audio_dsp). Kotlin side: engine/NativeBridge.kt.
//
// Sessions keep a loaded network alive so a batch of images or every frame of a video reuses it.
// Engine: 0 = AUTO (Vulkan GPU, CPU when there is none or it misbehaves), 1 = GPU, 2 = CPU.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>

#include "audio_dsp.h"
#include "sr_engine.h"

#define LOG_TAG "SharprNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using namespace sharpr;

namespace {

constexpr int ENGINE_AUTO = 0;
constexpr int ENGINE_GPU = 1;
constexpr int ENGINE_CPU = 2;

std::atomic<bool> g_cancel{false};
std::atomic<int> g_lastError{0};

/** Forwards progress to a Kotlin NativeListener and answers cancellation from the global flag. */
class JniSink : public ProgressSink {
public:
    JniSink(JNIEnv* env, jobject obj) : env_(env), obj_(obj) {
        if (!obj_) return;
        jclass cls = env->GetObjectClass(obj);
        onProgress_ = env->GetMethodID(cls, "onProgress", "(II)V");
        onBackend_ = env->GetMethodID(cls, "onBackend", "(Ljava/lang/String;Z)V");
        env->DeleteLocalRef(cls);
    }

    void progress(int done, int total) override {
        if (obj_ && onProgress_) env_->CallVoidMethod(obj_, onProgress_, done, total);
        if (env_->ExceptionCheck()) env_->ExceptionClear();
    }

    bool cancelled() override { return g_cancel.load(); }

    void backend(const std::string& name, bool fallback) {
        if (obj_ && onBackend_) {
            jstring s = env_->NewStringUTF(name.c_str());
            env_->CallVoidMethod(obj_, onBackend_, s, (jboolean) fallback);
            env_->DeleteLocalRef(s);
        }
        if (env_->ExceptionCheck()) env_->ExceptionClear();
    }

private:
    JNIEnv* env_;
    jobject obj_;
    jmethodID onProgress_ = nullptr;
    jmethodID onBackend_ = nullptr;
};

struct Handle {
    jobject assetRef = nullptr;  // global ref keeps the AAssetManager alive
    AAssetManager* assets = nullptr;
    ModelSpec spec;
    EngineConfig cfg;
    bool allowFallback = true;
    std::unique_ptr<SrSession> session;
    bool fellBack = false;
    bool announced = false;
    std::mutex lock;

    ModelLoader loader() {
        AAssetManager* am = assets;
        return [am](ncnn::Net& net, const ModelSpec& s) {
            return net.load_param(am, s.param.c_str()) == 0 && net.load_model(am, s.bin.c_str()) == 0;
        };
    }

    int loadCpu() {
        EngineConfig c = cfg;
        c.gpu = false;
        std::unique_ptr<SrSession> s(new SrSession());
        int rc = s->load(spec, c, loader());
        if (rc == RC_OK) session = std::move(s);
        return rc;
    }
};

std::string str(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

/** Runs [work] on the session, falling back to the CPU once if the GPU fails. */
template <typename Work>
int runWithFallback(Handle* h, JniSink& sink, Work&& work) {
    if (!h->announced) {
        sink.backend(h->session->deviceName(), h->fellBack);
        h->announced = true;
    }
    int rc = work(*h->session);
    if (rc != RC_OK && rc != RC_CANCELLED && rc != RC_BAD_INPUT && h->session->usesGpu() && h->allowFallback) {
        LOGW("GPU run failed (rc=%d), falling back to CPU", rc);
        if (h->loadCpu() != RC_OK) return rc;
        h->fellBack = true;
        sink.backend("CPU \xC2\xB7 fallback", true);
        rc = work(*h->session);
    }
    return rc;
}

}  // namespace

#define JNI_FN(name) Java_com_vytros_projects_sharpr_engine_NativeBridge_##name

extern "C" {

JNIEXPORT jstring JNICALL JNI_FN(gpuInfo)(JNIEnv* env, jobject) {
    if (!gpuAvailable()) return env->NewStringUTF("");
    std::string info = gpuName() + "|" + std::to_string(gpuHeapBudgetMb());
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jint JNICALL JNI_FN(cpuCores)(JNIEnv*, jobject) { return performanceCores(); }

JNIEXPORT void JNICALL JNI_FN(cancel)(JNIEnv*, jobject) { g_cancel.store(true); }

JNIEXPORT void JNICALL JNI_FN(resetCancel)(JNIEnv*, jobject) { g_cancel.store(false); }

JNIEXPORT jint JNICALL JNI_FN(lastError)(JNIEnv*, jobject) { return g_lastError.load(); }

JNIEXPORT jlong JNICALL JNI_FN(createSession)(
        JNIEnv* env, jobject, jobject assetManager, jstring param, jstring bin, jstring inBlob, jstring outBlob,
        jint scale, jint crop, jint prepad, jint align, jint tilePct, jboolean residual, jint engine,
        jboolean allowFallback, jint threads, jint tile, jint precision, jboolean tta) {
    std::unique_ptr<Handle> h(new Handle());
    h->assetRef = env->NewGlobalRef(assetManager);
    h->assets = AAssetManager_fromJava(env, assetManager);
    h->spec.param = str(env, param);
    h->spec.bin = str(env, bin);
    h->spec.inBlob = str(env, inBlob);
    h->spec.outBlob = str(env, outBlob);
    h->spec.scale = scale;
    h->spec.crop = crop;
    h->spec.prepad = prepad;
    h->spec.align = align;
    h->spec.tilePct = tilePct;
    h->spec.residual = residual;
    h->cfg.threads = threads;
    h->cfg.tile = tile;
    h->cfg.precision = precision;
    h->cfg.tta = tta;
    h->allowFallback = engine == ENGINE_AUTO || allowFallback;

    int rc = RC_NO_GPU;
    if (engine != ENGINE_CPU && gpuAvailable()) {
        h->cfg.gpu = true;
        h->session.reset(new SrSession());
        rc = h->session->load(h->spec, h->cfg, h->loader());
        if (rc != RC_OK) h->session.reset();
    }
    if (!h->session && (engine == ENGINE_CPU || h->allowFallback)) {
        h->fellBack = engine != ENGINE_CPU;
        rc = h->loadCpu();
    }
    if (!h->session) {
        g_lastError.store(rc);
        env->DeleteGlobalRef(h->assetRef);
        return 0;
    }
    LOGI("session %s on %s tile=%d", h->spec.param.c_str(), h->session->deviceName().c_str(), h->session->tileSize());
    g_lastError.store(RC_OK);
    return (jlong) h.release();
}

JNIEXPORT jstring JNICALL JNI_FN(sessionDevice)(JNIEnv* env, jobject, jlong handle) {
    auto* h = (Handle*) handle;
    if (!h || !h->session) return env->NewStringUTF("");
    return env->NewStringUTF(h->session->deviceName().c_str());
}

JNIEXPORT jboolean JNICALL JNI_FN(sessionFellBack)(JNIEnv*, jobject, jlong handle) {
    auto* h = (Handle*) handle;
    return h && h->fellBack;
}

JNIEXPORT void JNICALL JNI_FN(releaseSession)(JNIEnv* env, jobject, jlong handle) {
    auto* h = (Handle*) handle;
    if (!h) return;
    {
        std::lock_guard<std::mutex> g(h->lock);
        h->session.reset();
    }
    env->DeleteGlobalRef(h->assetRef);
    delete h;
}

JNIEXPORT jint JNICALL JNI_FN(processBitmap)(JNIEnv* env, jobject, jlong handle, jobject inBitmap,
                                              jobject outBitmap, jint outScale, jobject listener) {
    auto* h = (Handle*) handle;
    if (!h || !h->session) return RC_MODEL_LOAD;
    std::lock_guard<std::mutex> g(h->lock);

    AndroidBitmapInfo ii, oi;
    if (AndroidBitmap_getInfo(env, inBitmap, &ii) != 0 || AndroidBitmap_getInfo(env, outBitmap, &oi) != 0)
        return RC_BAD_INPUT;
    if (ii.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || oi.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return RC_BAD_INPUT;
    if (oi.width != ii.width * outScale || oi.height != ii.height * outScale) return RC_BAD_INPUT;

    void* inPx = nullptr;
    void* outPx = nullptr;
    if (AndroidBitmap_lockPixels(env, inBitmap, &inPx) != 0) return RC_BAD_INPUT;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPx) != 0) {
        AndroidBitmap_unlockPixels(env, inBitmap);
        return RC_BAD_INPUT;
    }
    JniSink sink(env, listener);
    int rc = runWithFallback(h, sink, [&](SrSession& s) {
        return s.process((const uint8_t*) inPx, (int) ii.width, (int) ii.height, (int) ii.stride, (uint8_t*) outPx,
                         (int) oi.stride, outScale, &sink);
    });
    AndroidBitmap_unlockPixels(env, outBitmap);
    AndroidBitmap_unlockPixels(env, inBitmap);
    return rc;
}

/** Tightly packed RGBA direct buffers; used for video frames. */
JNIEXPORT jint JNICALL JNI_FN(processBuffer)(JNIEnv* env, jobject, jlong handle, jobject inBuf, jint w, jint h,
                                              jobject outBuf, jint outScale, jobject listener) {
    auto* hd = (Handle*) handle;
    if (!hd || !hd->session) return RC_MODEL_LOAD;
    std::lock_guard<std::mutex> g(hd->lock);
    auto* in = (const uint8_t*) env->GetDirectBufferAddress(inBuf);
    auto* out = (uint8_t*) env->GetDirectBufferAddress(outBuf);
    if (!in || !out) return RC_BAD_INPUT;
    if (env->GetDirectBufferCapacity(inBuf) < (jlong) w * h * 4 ||
        env->GetDirectBufferCapacity(outBuf) < (jlong) w * outScale * h * outScale * 4)
        return RC_BAD_INPUT;
    JniSink sink(env, listener);
    return runWithFallback(hd, sink, [&](SrSession& s) {
        return s.process(in, w, h, w * 4, out, w * outScale * 4, outScale, &sink);
    });
}

/**
 * Returns [rc, outRate, outChannels, outFrames, humHz, inputLufs, outputLufs].
 */
JNIEXPORT jdoubleArray JNICALL JNI_FN(processAudio)(JNIEnv* env, jobject, jstring inPath, jstring outPath,
                                                     jint inRate, jint channels, jint mode, jfloat strength,
                                                     jint hum, jboolean highpass, jfloat clarity,
                                                     jboolean normalize, jfloat targetLufs, jobject listener) {
    AudioParams p;
    p.mode = mode;
    p.strength = strength;
    p.hum = hum;
    p.highpass = highpass;
    p.clarity = clarity;
    p.normalize = normalize;
    p.targetLufs = targetLufs;
    JniSink sink(env, listener);
    AudioResult r = processAudioFile(str(env, inPath), str(env, outPath), inRate, channels, p, &sink);
    const jdouble values[7] = {(double) r.rc, (double) r.outRate, (double) r.outChannels, (double) r.outFrames,
                               (double) r.humHz, r.inputLufs, r.outputLufs};
    jdoubleArray arr = env->NewDoubleArray(7);
    env->SetDoubleArrayRegion(arr, 0, 7, values);
    return arr;
}

}  // extern "C"

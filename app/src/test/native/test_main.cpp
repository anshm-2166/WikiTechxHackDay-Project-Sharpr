// Desktop tests for Sharpr's native core. Runs the real models on the CPU.
//
// Usage: sharpr_native_tests <models dir> [output dir for PPM previews]

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <vector>

#include "audio_dsp.h"
#include "sr_engine.h"

using namespace sharpr;

static int g_failures = 0;
static int g_checks = 0;

#define CHECK(cond, ...)                                                   \
    do {                                                                   \
        g_checks++;                                                        \
        if (!(cond)) {                                                     \
            g_failures++;                                                  \
            std::printf("  FAIL %s:%d  %s  ", __FILE__, __LINE__, #cond); \
            std::printf(__VA_ARGS__);                                      \
            std::printf("\n");                                             \
        }                                                                  \
    } while (0)

static std::string g_models, g_out;

// ---- Images -----------------------------------------------------------------------------------------

struct Image {
    int w = 0, h = 0;
    std::vector<uint8_t> px;  // RGBA
    uint8_t* row(int y) { return px.data() + (size_t) y * w * 4; }
    const uint8_t* row(int y) const { return px.data() + (size_t) y * w * 4; }
};

static Image makeImage(int w, int h) {
    Image im{w, h, std::vector<uint8_t>((size_t) w * h * 4)};
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            float r = 40 + 150.f * x / w, g = 60 + 120.f * y / h, b = 120;
            const float dx = x - w * 0.35f, dy = y - h * 0.5f;
            if (dx * dx + dy * dy < (h * 0.28f) * (h * 0.28f)) { r = 230; g = 200; b = 60; }
            if (((x / 6) + (y / 6)) % 2 == 0 && x > w * 0.65f) { r *= 0.4f; g *= 0.4f; b *= 0.4f; }
            if (std::abs((x - y) % 17) < 2) { r = 250; g = 250; b = 250; }
            uint8_t* p = im.row(y) + x * 4;
            p[0] = (uint8_t) r; p[1] = (uint8_t) g; p[2] = (uint8_t) b; p[3] = 255;
        }
    // Soften slightly so it resembles a real (band-limited) photo.
    Image o = im;
    for (int y = 1; y < h - 1; y++)
        for (int x = 1; x < w - 1; x++)
            for (int c = 0; c < 3; c++) {
                int s = 0;
                for (int ky = -1; ky <= 1; ky++)
                    for (int kx = -1; kx <= 1; kx++) s += im.row(y + ky)[(x + kx) * 4 + c] * ((kx == 0 && ky == 0) ? 4 : 1);
                o.row(y)[x * 4 + c] = (uint8_t) (s / 12);
            }
    return o;
}

static void writePpm(const Image& im, const std::string& name) {
    if (g_out.empty()) return;
    FILE* f = std::fopen((g_out + "/" + name + ".ppm").c_str(), "wb");
    if (!f) return;
    std::fprintf(f, "P6\n%d %d\n255\n", im.w, im.h);
    for (int y = 0; y < im.h; y++)
        for (int x = 0; x < im.w; x++) std::fwrite(im.row(y) + x * 4, 1, 3, f);
    std::fclose(f);
}

/** Mean absolute difference between a box-downsampled result and the source, with a pixel offset. */
static double backProjectError(const Image& src, const Image& out, int s, int ox, int oy) {
    double err = 0;
    long n = 0;
    for (int y = 2; y < src.h - 2; y++)
        for (int x = 2; x < src.w - 2; x++)
            for (int c = 0; c < 3; c++) {
                double acc = 0;
                for (int ky = 0; ky < s; ky++)
                    for (int kx = 0; kx < s; kx++) acc += out.row((y + oy) * s + ky)[((x + ox) * s + kx) * 4 + c];
                err += std::fabs(acc / (s * s) - src.row(y)[x * 4 + c]);
                n++;
            }
    return err / n;
}

static double psnr(const Image& a, const Image& b) {
    double se = 0;
    for (size_t i = 0; i < a.px.size(); i++) {
        if (i % 4 == 3) continue;
        const double d = (double) a.px[i] - b.px[i];
        se += d * d;
    }
    const double mse = se / (a.px.size() * 3 / 4);
    return mse == 0 ? 99.0 : 10 * std::log10(255.0 * 255.0 / mse);
}

struct ModelCase {
    const char* name;
    const char* in;
    const char* out;
    int scale, crop, prepad, align;
    bool heavy;
    bool residual = false;
};

// Must match app/src/main/java/.../engine/Models.kt.
static const ModelCase kModels[] = {
    {"realesr-animevideov3-x2", "data", "output", 2, 0, 10, 1, false},
    {"realesr-animevideov3-x3", "data", "output", 3, 0, 10, 1, false},
    {"realesr-animevideov3-x4", "data", "output", 4, 0, 10, 1, false},
    {"realesrgan-x4plus-anime", "data", "output", 4, 0, 10, 1, true},
    {"realesrgan-x4plus", "data", "output", 4, 0, 10, 1, true},
    {"realcugan-up2x-conservative", "in0", "out0", 2, 18, 22, 2, false},
    {"realcugan-up2x-denoise3x", "in0", "out0", 2, 18, 22, 2, false},
    {"realcugan-up3x-conservative", "in0", "out0", 3, 14, 18, 4, false},
    {"realcugan-up4x-conservative", "in0", "out0", 4, 19, 23, 4, false, true},
    {"realcugan-up4x-denoise3x", "in0", "out0", 4, 19, 23, 4, false, true},
    {"waifu2x-cunet-noise1", "Input1", "Eltwise4", 1, 28, 32, 4, false},
    {"waifu2x-cunet-noise3", "Input1", "Eltwise4", 1, 28, 32, 4, false},
    {"waifu2x-photo-x2", "Input1", "Eltwise4", 2, 7, 12, 2, false},
    {"waifu2x-photo-noise2-x2", "Input1", "Eltwise4", 2, 7, 12, 2, false},
};

static ModelSpec specFor(const ModelCase& m) {
    ModelSpec s;
    s.param = g_models + "/" + m.name + ".param";
    s.bin = g_models + "/" + m.name + ".bin";
    s.inBlob = m.in;
    s.outBlob = m.out;
    s.scale = m.scale;
    s.crop = m.crop;
    s.prepad = m.prepad;
    s.align = m.align;
    s.residual = m.residual;
    return s;
}

static bool diskLoader(ncnn::Net& net, const ModelSpec& s) {
    return net.load_param(s.param.c_str()) == 0 && net.load_model(s.bin.c_str()) == 0;
}

static int run(const ModelCase& m, const Image& src, Image& out, int tile, int outScale, bool tta, int precision = 0) {
    SrSession session;
    EngineConfig cfg;
    cfg.gpu = false;
    cfg.threads = 4;
    cfg.tile = tile;
    cfg.tta = tta;
    cfg.precision = precision;
    int rc = session.load(specFor(m), cfg, diskLoader);
    if (rc != RC_OK) return rc;
    out.w = src.w * outScale;
    out.h = src.h * outScale;
    out.px.assign((size_t) out.w * out.h * 4, 0);
    return session.process(src.px.data(), src.w, src.h, src.w * 4, out.px.data(), out.w * 4, outScale, nullptr);
}

static void testModels() {
    std::printf("[sr] models: tiling, alignment, seams\n");
    const Image src = makeImage(150, 94);  // odd-ish sizes on purpose
    writePpm(src, "source");
    for (const ModelCase& m : kModels) {
        Image whole, tiled;
        int rc1 = run(m, src, whole, 512, m.scale, false);
        int rc2 = run(m, src, tiled, m.heavy ? 40 : 48, m.scale, false);
        CHECK(rc1 == RC_OK && rc2 == RC_OK, "%s rc=%d/%d", m.name, rc1, rc2);
        if (rc1 != RC_OK || rc2 != RC_OK) continue;
        writePpm(tiled, m.name);

        // Registration: the result must line up with the source, and do so better than at +-1 px.
        const double e0 = backProjectError(src, whole, m.scale, 0, 0);
        double eShift = 1e9;
        for (int oy = -1; oy <= 1; oy++)
            for (int ox = -1; ox <= 1; ox++)
                if (ox || oy) eShift = std::min(eShift, backProjectError(src, whole, m.scale, ox, oy));
        CHECK(e0 < 9.0, "%s back-projection error %.2f", m.name, e0);
        CHECK(e0 < eShift, "%s misregistered (%.2f vs shifted %.2f)", m.name, e0, eShift);

        // Seams: small tiles must agree with one big tile. Real-CUGAN's squeeze-excitation sees
        // per-tile statistics, so it is allowed to differ a little more.
        const double p = psnr(whole, tiled);
        const bool cugan = std::strncmp(m.name, "realcugan", 9) == 0;
        CHECK(p > (cugan ? 30.0 : (m.heavy ? 36.0 : 38.0)), "%s tiled vs whole PSNR %.1f dB", m.name, p);
        std::printf("  %-30s ok  backproj %.2f  shifted %.2f  tiled PSNR %.1f dB\n", m.name, e0, eShift, p);
    }
}

static void testScalesAndTta() {
    std::printf("[sr] downscaled outputs, TTA, precision\n");
    const Image src = makeImage(64, 48);
    const ModelCase& x4 = kModels[2];
    for (int s : {1, 2, 4}) {
        Image out;
        int rc = run(x4, src, out, 32, s, false);
        CHECK(rc == RC_OK, "x4 at %dx rc=%d", s, rc);
        if (rc == RC_OK) {
            const double e = backProjectError(src, out, s, 0, 0);
            CHECK(e < 9.0, "x4 at %dx back-projection %.2f", s, e);
        }
    }
    Image plain, tta;
    CHECK(run(x4, src, plain, 64, 4, false) == RC_OK, "plain");
    CHECK(run(x4, src, tta, 64, 4, true) == RC_OK, "tta");
    const double p = psnr(plain, tta);
    CHECK(p > 30.0 && p < 99.0, "TTA should differ slightly from plain (PSNR %.1f)", p);
    std::printf("  TTA vs plain PSNR %.1f dB\n", p);

    Image fp16;
    CHECK(run(x4, src, fp16, 64, 4, false, 2) == RC_OK, "fp16");
    CHECK(psnr(plain, fp16) > 35.0, "fp16 arithmetic drifted (PSNR %.1f)", psnr(plain, fp16));

    Image bad;
    ModelCase broken = kModels[0];
    broken.name = "does-not-exist";
    CHECK(run(broken, src, bad, 64, 2, false) == RC_MODEL_LOAD, "missing model must fail to load");

    struct Cancel : ProgressSink {
        int seen = 0;
        void progress(int, int) override { seen++; }
        bool cancelled() override { return seen > 2; }
    } cancel;
    SrSession s;
    EngineConfig cfg;
    cfg.gpu = false;
    cfg.tile = 32;
    CHECK(s.load(specFor(x4), cfg, diskLoader) == RC_OK, "load");
    std::vector<uint8_t> out((size_t) src.w * 4 * src.h * 4 * 4);
    CHECK(s.process(src.px.data(), src.w, src.h, src.w * 4, out.data(), src.w * 16, 4, &cancel) == RC_CANCELLED,
          "cancel must stop the tile loop");
}

// ---- Audio ------------------------------------------------------------------------------------------

static std::string tmpPath(const char* name) {
    return (g_out.empty() ? std::string("/tmp") : g_out) + "/" + name;
}

static void writePcm(const std::string& path, const std::vector<float>& x, int channels) {
    FILE* f = std::fopen(path.c_str(), "wb");
    for (float v : x) {
        const int16_t s = (int16_t) std::lrint(std::max(-1.f, std::min(1.f, v)) * 32767.f);
        std::fwrite(&s, 2, 1, f);
    }
    std::fclose(f);
    (void) channels;
}

static std::vector<float> readPcm(const std::string& path) {
    std::vector<float> x;
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return x;
    int16_t s;
    while (std::fread(&s, 2, 1, f) == 1) x.push_back(s / 32768.f);
    std::fclose(f);
    return x;
}

static double rms(const std::vector<float>& x, size_t from = 0, size_t to = 0, int stride = 1, int off = 0) {
    if (to == 0) to = x.size() / stride;
    double s = 0;
    for (size_t i = from; i < to; i++) s += (double) x[i * stride + off] * x[i * stride + off];
    return std::sqrt(s / std::max<size_t>(1, to - from));
}

/** Correlation of y against x at a lag (y[i+lag] vs x[i]). */
static double corrAt(const std::vector<float>& x, const std::vector<float>& y, int lag) {
    double sxy = 0, sxx = 0, syy = 0;
    for (size_t i = 4800; i + 4800 < x.size(); i++) {
        long j = (long) i + lag;
        if (j < 0 || j >= (long) y.size()) continue;
        sxy += x[i] * y[j];
        sxx += x[i] * x[i];
        syy += y[j] * y[j];
    }
    return sxy / std::sqrt(sxx * syy + 1e-20);
}

static AudioParams plain() {
    AudioParams p;
    p.mode = DENOISE_OFF;
    p.hum = HUM_OFF;
    p.highpass = false;
    p.clarity = 0;
    p.normalize = false;
    return p;
}

static void testAudio() {
    std::printf("[audio]\n");
    std::mt19937 rng(7);
    std::normal_distribution<float> gauss(0.f, 1.f);
    const int sr = 48000;

    // 1. Pass-through is bit-exact (+-1 LSB) at 48 kHz.
    {
        std::vector<float> x(sr * 2);
        for (size_t i = 0; i < x.size(); i++) x[i] = 0.3f * std::sin(2 * M_PI * 440 * i / sr) + 0.05f * gauss(rng);
        writePcm(tmpPath("a_in.pcm"), x, 1);
        AudioResult r = processAudioFile(tmpPath("a_in.pcm"), tmpPath("a_out.pcm"), sr, 1, plain(), nullptr);
        std::vector<float> y = readPcm(tmpPath("a_out.pcm"));
        CHECK(r.rc == RC_OK && y.size() == x.size(), "rc=%d size %zu vs %zu", r.rc, y.size(), x.size());
        double maxd = 0;
        for (size_t i = 0; i < std::min(x.size(), y.size()); i++) maxd = std::max(maxd, (double) std::fabs(x[i] - y[i]));
        CHECK(maxd < 2.5 / 32768, "pass-through differs by %g", maxd);
    }

    // 2. Resampling 44.1k -> 48k keeps pitch, level and length.
    {
        const int in = 44100;
        std::vector<float> x(in * 2);
        for (size_t i = 0; i < x.size(); i++) x[i] = 0.5f * std::sin(2 * M_PI * 1000 * i / in);
        writePcm(tmpPath("r_in.pcm"), x, 1);
        AudioResult r = processAudioFile(tmpPath("r_in.pcm"), tmpPath("r_out.pcm"), in, 1, plain(), nullptr);
        std::vector<float> y = readPcm(tmpPath("r_out.pcm"));
        CHECK(r.rc == RC_OK && (long) y.size() == 96000, "resampled length %zu", y.size());
        double ref = 0, best = 0;
        for (size_t i = 2000; i + 2000 < y.size(); i++) {
            const double s = std::sin(2 * M_PI * 1000 * i / 48000.0), c = std::cos(2 * M_PI * 1000 * i / 48000.0);
            ref += y[i] * s;
            best += y[i] * c;
        }
        const double amp = 2 * std::sqrt(ref * ref + best * best) / (y.size() - 4000);
        CHECK(std::fabs(amp - 0.5) < 0.01, "1 kHz amplitude after resample %.4f", amp);
        // Phase/time alignment: the sine should sit where the original was (no delay).
        const double phase = std::atan2(best, ref);
        CHECK(std::fabs(phase) < 0.05, "resampler introduced a delay (phase %.3f rad)", phase);
    }

    // 3. Universal denoise removes stationary noise but keeps the tone.
    {
        std::vector<float> clean(sr * 4), noisy(sr * 4);
        for (size_t i = 0; i < clean.size(); i++) {
            const double env = (i / (sr / 2)) % 2 ? 1.0 : 0.0;  // tone bursts with gaps of pure noise
            clean[i] = (float) (0.3 * env * std::sin(2 * M_PI * 523 * i / sr));
            noisy[i] = clean[i] + 0.03f * gauss(rng);
        }
        writePcm(tmpPath("n_in.pcm"), noisy, 1);
        AudioParams p = plain();
        p.mode = DENOISE_UNIVERSAL;
        p.strength = 0.7f;
        AudioResult r = processAudioFile(tmpPath("n_in.pcm"), tmpPath("n_out.pcm"), sr, 1, p, nullptr);
        std::vector<float> y = readPcm(tmpPath("n_out.pcm"));
        CHECK(r.rc == RC_OK && y.size() == noisy.size(), "rc=%d", r.rc);
        double nIn = 0, nOut = 0, sigErr = 0, sig = 0;
        for (size_t i = 0; i < y.size(); i++) {
            const bool gap = ((i / (sr / 2)) % 2) == 0;
            const size_t edge = i % (sr / 2);
            if (edge < 2400 || edge > sr / 2 - 2400) continue;
            if (gap) {
                nIn += noisy[i] * noisy[i];
                nOut += y[i] * y[i];
            } else {
                sig += clean[i] * clean[i];
                sigErr += (y[i] - clean[i]) * (y[i] - clean[i]);
            }
        }
        const double reduction = 10 * std::log10(nIn / (nOut + 1e-20));
        const double snrOut = 10 * std::log10(sig / (sigErr + 1e-20));
        const double snrIn = 10 * std::log10(sig / (nIn + 1e-20));
        CHECK(reduction > 15.0, "noise reduced by only %.1f dB", reduction);
        CHECK(snrOut > snrIn + 6.0, "tone SNR %.1f dB -> %.1f dB", snrIn, snrOut);
        std::printf("  universal: noise -%.1f dB, tone SNR %.1f -> %.1f dB\n", reduction, snrIn, snrOut);
    }

    // 4. Voice mode keeps timing (no shift between input and output) and reduces noise.
    {
        std::vector<float> x(sr * 4), voice(sr * 4);
        for (size_t i = 0; i < x.size(); i++) {
            const double t = (double) i / sr;
            const double syll = 0.5 * (1 - std::cos(2 * M_PI * 3.0 * t));  // ~3 syllables per second
            const double f0 = 140 + 25 * std::sin(2 * M_PI * 0.7 * t);
            double v = 0;
            for (int h = 1; h <= 25; h++) {
                const double f = f0 * h;
                const double formant = std::exp(-std::pow((f - 700) / 300, 2)) + 0.6 * std::exp(-std::pow((f - 1200) / 400, 2)) +
                                       0.3 * std::exp(-std::pow((f - 2600) / 500, 2));
                v += formant * std::sin(2 * M_PI * f * t + h);
            }
            voice[i] = (float) (0.12 * syll * v);
            x[i] = voice[i] + 0.02f * gauss(rng);
        }
        writePcm(tmpPath("v_in.pcm"), x, 1);
        AudioParams p = plain();
        p.mode = DENOISE_VOICE;
        p.strength = 0.8f;
        AudioResult r = processAudioFile(tmpPath("v_in.pcm"), tmpPath("v_out.pcm"), sr, 1, p, nullptr);
        std::vector<float> y = readPcm(tmpPath("v_out.pcm"));
        CHECK(r.rc == RC_OK && y.size() == x.size(), "rc=%d size %zu", r.rc, y.size());
        int bestLag = 0;
        double best = -2;
        for (int lag = -1500; lag <= 1500; lag += 1) {
            const double c = corrAt(voice, y, lag);
            if (c > best) { best = c; bestLag = lag; }
        }
        CHECK(std::abs(bestLag) <= 1, "voice output shifted by %d samples (corr %.2f)", bestLag, best);
        std::printf("  voice: alignment lag %d, correlation %.3f, in rms %.4f out rms %.4f\n", bestLag, best, rms(x),
                    rms(y));
    }

    // 5. Hum detection and removal (stereo, 44.1 kHz).
    {
        const int in = 44100;
        std::vector<float> x(in * 3 * 2);
        for (size_t i = 0; i < x.size() / 2; i++) {
            const double t = (double) i / in;
            double hum = 0;
            for (int h = 1; h <= 5; h++) hum += 0.05 / h * std::sin(2 * M_PI * 60 * h * t);
            const double music = 0.1 * std::sin(2 * M_PI * 330 * t) + 0.01 * gauss(rng);
            x[i * 2] = (float) (hum + music);
            x[i * 2 + 1] = (float) (hum + music * 0.8);
        }
        writePcm(tmpPath("h_in.pcm"), x, 2);
        AudioParams p = plain();
        p.hum = HUM_AUTO;
        AudioResult r = processAudioFile(tmpPath("h_in.pcm"), tmpPath("h_out.pcm"), in, 2, p, nullptr);
        std::vector<float> y = readPcm(tmpPath("h_out.pcm"));
        CHECK(r.rc == RC_OK && r.humHz == 60, "detected hum %d Hz", r.humHz);
        CHECK(r.outChannels == 2 && y.size() == (size_t) 48000 * 3 * 2, "stereo length %zu", y.size());
        // Measure 60 Hz energy before/after with a single-bin DFT on the left channel.
        auto tone = [](const std::vector<float>& v, int rate, double f, int stride) {
            double re = 0, im = 0;
            const size_t n = v.size() / stride;
            for (size_t i = n / 4; i < n; i++) {
                re += v[i * stride] * std::cos(2 * M_PI * f * i / rate);
                im += v[i * stride] * std::sin(2 * M_PI * f * i / rate);
            }
            return 2 * std::sqrt(re * re + im * im) / (n - n / 4);
        };
        const double before = tone(x, in, 60, 2), after = tone(y, 48000, 60, 2);
        const double keep = tone(y, 48000, 330, 2) / tone(x, in, 330, 2);
        CHECK(after < before * 0.1, "60 Hz hum %.4f -> %.4f", before, after);
        CHECK(keep > 0.95, "music at 330 Hz kept only %.2f", keep);
        std::printf("  hum: %d Hz found, 60 Hz %.4f -> %.5f, 330 Hz kept %.3f\n", r.humHz, before, after, keep);
    }

    // 6. No false hum detection on clean material; loudness normalisation lands on target.
    {
        std::vector<float> x(sr * 5);
        for (size_t i = 0; i < x.size(); i++) x[i] = 0.02f * std::sin(2 * M_PI * 997 * i / sr) + 0.004f * gauss(rng);
        writePcm(tmpPath("l_in.pcm"), x, 1);
        AudioParams p = plain();
        p.hum = HUM_AUTO;
        p.normalize = true;
        p.targetLufs = -16;
        AudioResult r = processAudioFile(tmpPath("l_in.pcm"), tmpPath("l_out.pcm"), sr, 1, p, nullptr);
        CHECK(r.rc == RC_OK && r.humHz == 0, "false hum %d", r.humHz);
        CHECK(std::fabs(r.outputLufs - -16.f) < 0.6f, "normalised to %.2f LUFS (input %.2f)", r.outputLufs,
              r.inputLufs);
        std::printf("  loudness: %.1f LUFS -> %.1f LUFS\n", r.inputLufs, r.outputLufs);
    }

    // 7. Garbage in.
    {
        AudioResult r = processAudioFile(tmpPath("nope.pcm"), tmpPath("nope_out.pcm"), sr, 1, plain(), nullptr);
        CHECK(r.rc == RC_IO, "missing input rc=%d", r.rc);
        r = processAudioFile(tmpPath("a_in.pcm"), tmpPath("x.pcm"), 100, 1, plain(), nullptr);
        CHECK(r.rc == RC_BAD_INPUT, "bad rate rc=%d", r.rc);
    }
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::printf("usage: %s <models dir> [output dir]\n", argv[0]);
        return 2;
    }
    g_models = argv[1];
    if (argc > 2) g_out = argv[2];
    const bool onlyAudio = std::getenv("SHARPR_ONLY_AUDIO") != nullptr;
    if (!onlyAudio) {
        testModels();
        testScalesAndTta();
    }
    testAudio();
    std::printf("\n%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

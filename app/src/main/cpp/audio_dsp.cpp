#include "audio_dsp.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <vector>

extern "C" {
#include "rnnoise.h"
}

namespace sharpr {

namespace {

constexpr float kPi = 3.14159265358979323846f;
using cf = std::complex<float>;

// ---- FFT -------------------------------------------------------------------------------------------

class Fft {
public:
    explicit Fft(int n) : n_(n), rev_(n), tw_(n / 2) {
        int bits = 0;
        while ((1 << bits) < n) bits++;
        for (int i = 0; i < n; i++) {
            int r = 0;
            for (int b = 0; b < bits; b++) r |= ((i >> b) & 1) << (bits - 1 - b);
            rev_[i] = r;
        }
        for (int i = 0; i < n / 2; i++) tw_[i] = std::polar(1.f, -2.f * kPi * i / n);
    }

    void forward(cf* x) const { run(x, false); }

    /** Unnormalised: divide by n yourself. */
    void inverse(cf* x) const { run(x, true); }

    int size() const { return n_; }

private:
    void run(cf* x, bool inv) const {
        for (int i = 0; i < n_; i++)
            if (i < rev_[i]) std::swap(x[i], x[rev_[i]]);
        for (int len = 2; len <= n_; len <<= 1) {
            const int half = len / 2, step = n_ / len;
            for (int i = 0; i < n_; i += len) {
                for (int j = 0; j < half; j++) {
                    cf w = tw_[j * step];
                    if (inv) w = std::conj(w);
                    const cf u = x[i + j], v = x[i + j + half] * w;
                    x[i + j] = u + v;
                    x[i + j + half] = u - v;
                }
            }
        }
    }

    int n_;
    std::vector<int> rev_;
    std::vector<cf> tw_;
};

// ---- Biquad (RBJ cookbook) --------------------------------------------------------------------------

struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    double z1 = 0, z2 = 0;

    float run(float x) {
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return (float) y;
    }

    static Biquad raw(double b0, double b1, double b2, double a0, double a1, double a2) {
        Biquad q;
        q.b0 = b0 / a0; q.b1 = b1 / a0; q.b2 = b2 / a0; q.a1 = a1 / a0; q.a2 = a2 / a0;
        return q;
    }

    static Biquad highpass(double f, double q, double fs) {
        const double w = 2 * M_PI * f / fs, c = std::cos(w), al = std::sin(w) / (2 * q);
        return raw((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
    }

    static Biquad notch(double f, double q, double fs) {
        const double w = 2 * M_PI * f / fs, c = std::cos(w), al = std::sin(w) / (2 * q);
        return raw(1, -2 * c, 1, 1 + al, -2 * c, 1 - al);
    }

    static Biquad peak(double f, double q, double gainDb, double fs) {
        const double A = std::pow(10.0, gainDb / 40), w = 2 * M_PI * f / fs, c = std::cos(w),
                     al = std::sin(w) / (2 * q);
        return raw(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
    }

    static Biquad highShelf(double f, double gainDb, double fs) {
        const double A = std::pow(10.0, gainDb / 40), w = 2 * M_PI * f / fs, c = std::cos(w),
                     al = std::sin(w) / 2 * std::sqrt(2.0), sA = 2 * std::sqrt(A) * al;
        return raw(A * ((A + 1) + (A - 1) * c + sA), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sA),
                   (A + 1) - (A - 1) * c + sA, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sA);
    }
};

// ---- Resampler: windowed-sinc, polyphase table with linear phase interpolation ------------------------

class Resampler {
public:
    Resampler(int inRate, int outRate, int channels) : in_(inRate), out_(outRate), ch_(channels), hist_(channels) {
        bypass_ = inRate == outRate;
        if (bypass_) return;
        const double cutoff = std::min(1.0, (double) outRate / inRate) * 0.94;
        table_.assign((size_t) (kPhases + 1) * kTaps, 0.f);
        for (int p = 0; p <= kPhases; p++) {
            const double frac = (double) p / kPhases;
            double sum = 0;
            for (int t = 0; t < kTaps; t++) {
                const double x = (t - (kHalf - 1)) - frac;  // distance from the output instant
                const double s = x == 0 ? 1.0 : std::sin(M_PI * x * cutoff) / (M_PI * x * cutoff);
                const double r = x / kHalf;
                const double win = std::fabs(r) >= 1 ? 0 : bessel0(kBeta * std::sqrt(1 - r * r)) / bessel0(kBeta);
                table_[(size_t) p * kTaps + t] = (float) (s * win);
                sum += s * win;
            }
            for (int t = 0; t < kTaps; t++) table_[(size_t) p * kTaps + t] /= (float) sum;
        }
        for (auto& h : hist_) h.assign(kHalf, 0.f);  // virtual zeros before the first sample
        pos_ = kHalf;
    }

    /** Interleaved in, interleaved out (appended). */
    void push(const float* in, int frames, std::vector<float>& out) {
        if (bypass_) {
            out.insert(out.end(), in, in + (size_t) frames * ch_);
            return;
        }
        for (int c = 0; c < ch_; c++)
            for (int i = 0; i < frames; i++) hist_[c].push_back(in[(size_t) i * ch_ + c]);
        inFrames_ += frames;
        drain(out, false);
    }

    void flush(std::vector<float>& out) {
        if (bypass_) return;
        for (auto& h : hist_) h.insert(h.end(), kHalf + 1, 0.f);
        drain(out, true);
    }

private:
    static constexpr int kHalf = 24, kTaps = 2 * kHalf, kPhases = 512;
    static constexpr double kBeta = 8.6;

    static double bessel0(double x) {
        double sum = 1, term = 1;
        for (int k = 1; k < 32; k++) {
            term *= (x / (2 * k)) * (x / (2 * k));
            sum += term;
        }
        return sum;
    }

    void drain(std::vector<float>& out, bool final) {
        const double step = (double) in_ / out_;
        const long long wanted = (long long) std::llround((double) inFrames_ * out_ / in_);
        const size_t avail = hist_[0].size();
        while (true) {
            if (final && produced_ >= wanted) break;
            const long long base = (long long) std::floor(pos_);
            if (base + kHalf + 1 >= (long long) avail) break;
            const double frac = pos_ - base;
            const double fp = frac * kPhases;
            const int p = (int) fp;
            const float a = (float) (fp - p);
            const float* t0 = &table_[(size_t) p * kTaps];
            const float* t1 = t0 + kTaps;
            const long long start = base - (kHalf - 1);
            for (int c = 0; c < ch_; c++) {
                const float* h = hist_[c].data() + start;
                float s = 0.f;
                for (int t = 0; t < kTaps; t++) s += h[t] * (t0[t] + a * (t1[t] - t0[t]));
                out.push_back(s);
            }
            produced_++;
            pos_ += step;
        }
        // Drop what no future output can reach.
        const long long keepFrom = (long long) std::floor(pos_) - (kHalf - 1);
        if (keepFrom > 4096) {
            for (auto& h : hist_) h.erase(h.begin(), h.begin() + keepFrom);
            pos_ -= (double) keepFrom;
        }
    }

    int in_, out_, ch_;
    bool bypass_ = false;
    std::vector<std::vector<float>> hist_;
    std::vector<float> table_;
    double pos_ = 0;
    long long inFrames_ = 0, produced_ = 0;
};

// ---- Streaming stages with a fixed latency -------------------------------------------------------------

/** RNNoise needs 480-sample frames at 48 kHz in 16-bit range; it delays its output by one frame. */
class RnnoiseStage {
public:
    static constexpr int kFrame = 480;
    static constexpr int kLatency = 2 * kFrame;  // our block buffering + RNNoise's own frame delay

    explicit RnnoiseStage(float wet) : wet_(wet), st_(rnnoise_create(nullptr)) {
        outQ_.assign(kFrame, 0.f);
        dryLine_.assign(kLatency, 0.f);
    }
    ~RnnoiseStage() { rnnoise_destroy(st_); }

    void run(float* x, int n) {
        for (int i = 0; i < n; i++) {
            const float in = x[i];
            block_[fill_++] = in * 32768.f;
            if (fill_ == kFrame) {
                float out[kFrame];
                rnnoise_process_frame(st_, out, block_);
                for (float v : out) outQ_.push_back(v * (1.f / 32768.f));
                fill_ = 0;
            }
            const float wet = outQ_[head_++];
            if (head_ > 8192) {
                outQ_.erase(outQ_.begin(), outQ_.begin() + head_);
                head_ = 0;
            }
            const float dry = dryLine_[dryPos_];
            dryLine_[dryPos_] = in;
            dryPos_ = (dryPos_ + 1) % kLatency;
            x[i] = wet * wet_ + dry * (1.f - wet_);
        }
    }

private:
    float wet_;
    DenoiseState* st_;
    float block_[kFrame] = {};
    int fill_ = 0;
    std::vector<float> outQ_;
    size_t head_ = 0;
    std::vector<float> dryLine_;
    int dryPos_ = 0;
};

constexpr int kN = 1024, kHop = 256, kBins = kN / 2 + 1;

std::vector<float> sqrtHann() {
    std::vector<float> w(kN);
    for (int i = 0; i < kN; i++) w[i] = std::sqrt(0.5f * (1.f - std::cos(2.f * kPi * i / kN)));
    return w;
}

/** Short-time spectra of a stream, one callback per hop; optional overlap-add resynthesis. */
class Stft {
public:
    Stft() : fft_(kN), win_(sqrtHann()), frame_(kN, 0.f), acc_(kN, 0.f), buf_(kN) {}

    static constexpr int kLatency = kN;  // a hop is emitted one block after it was analysed

    template <typename F>
    void run(float* x, int n, bool synth, F&& onSpectrum) {
        for (int i = 0; i < n; i++) {
            frame_[kN - kHop + fill_] = x[i];
            if (synth) x[i] = out_[fill_];
            if (++fill_ == kHop) {
                fill_ = 0;
                for (int k = 0; k < kN; k++) buf_[k] = cf(frame_[k] * win_[k], 0.f);
                fft_.forward(buf_.data());
                onSpectrum(buf_.data());
                if (synth) {
                    for (int k = 1; k < kN / 2; k++) buf_[kN - k] = std::conj(buf_[k]);
                    fft_.inverse(buf_.data());
                    const float norm = 0.5f / kN;  // sqrt-Hann^2 at 75% overlap sums to 2
                    for (int k = 0; k < kN; k++) acc_[k] += buf_[k].real() * win_[k] * norm;
                    std::copy(acc_.begin(), acc_.begin() + kHop, out_);
                    std::copy(acc_.begin() + kHop, acc_.end(), acc_.begin());
                    std::fill(acc_.end() - kHop, acc_.end(), 0.f);
                }
                std::copy(frame_.begin() + kHop, frame_.end(), frame_.begin());
            }
        }
    }

private:
    Fft fft_;
    std::vector<float> win_, frame_, acc_;
    std::vector<cf> buf_;
    float out_[kHop] = {};
    int fill_ = 0;
};

/** Collects a per-bin histogram of smoothed power (in dB) so we can read a noise-floor percentile. */
class NoiseProfiler {
public:
    static constexpr int kHistBins = 360;  // 0.5 dB steps
    static constexpr float kMinDb = -160.f;

    NoiseProfiler() : hist_((size_t) kBins * kHistBins, 0), smooth_(kBins, 0.f) {}

    void add(const cf* X) {
        for (int k = 0; k < kBins; k++) {
            const float p = std::norm(X[k]);
            smooth_[k] = frames_ == 0 ? p : 0.8f * smooth_[k] + 0.2f * p;
            const float db = 10.f * std::log10(smooth_[k] + 1e-20f);
            const int b = std::min(kHistBins - 1, std::max(0, (int) ((db - kMinDb) * 2.f)));
            hist_[(size_t) k * kHistBins + b]++;
        }
        frames_++;
    }

    long frames() const { return frames_; }

    /** Expected noise power per bin: the 10th percentile, corrected for its bias on pure noise. */
    std::vector<float> noisePsd() const {
        std::vector<float> psd(kBins, 0.f);
        if (frames_ == 0) return psd;
        const long target = std::max(1L, frames_ / 10);
        for (int k = 0; k < kBins; k++) {
            long run = 0;
            int b = 0;
            for (; b < kHistBins; b++) {
                run += hist_[(size_t) k * kHistBins + b];
                if (run >= target) break;
            }
            const float db = kMinDb + (b + 0.5f) / 2.f;
            psd[k] = std::pow(10.f, db / 10.f) * kPercentileBias;
        }
        // Light smoothing across frequency.
        std::vector<float> s(psd);
        for (int k = 1; k < kBins - 1; k++) s[k] = 0.25f * psd[k - 1] + 0.5f * psd[k] + 0.25f * psd[k + 1];
        return s;
    }

    static constexpr float kPercentileBias = 1.55f;  // measured on white noise, see test_audio

private:
    std::vector<uint32_t> hist_;
    std::vector<float> smooth_;
    long frames_ = 0;
};

/** Decision-directed Wiener suppression against a fixed noise profile. */
class SpectralDenoiser {
public:
    SpectralDenoiser(std::vector<float> noise, float strength)
        : noise_(std::move(noise)), gPrev_(kBins, 1.f), gammaPrev_(kBins, 1.f), g_(kBins, 1.f) {
        over_ = 1.f + 2.f * strength;
        floor_ = std::pow(10.f, -(8.f + 22.f * strength) / 20.f);
    }

    static constexpr int kLatency = Stft::kLatency;

    void run(float* x, int n) {
        stft_.run(x, n, true, [this](cf* X) { apply(X); });
    }

private:
    void apply(cf* X) {
        for (int k = 0; k < kBins; k++) {
            const float nk = std::max(noise_[k] * over_, 1e-20f);
            const float gamma = std::norm(X[k]) / nk;
            const float xi = 0.98f * gPrev_[k] * gPrev_[k] * gammaPrev_[k] + 0.02f * std::max(gamma - 1.f, 0.f);
            g_[k] = std::max(xi / (1.f + xi), floor_);
            gammaPrev_[k] = std::min(gamma, 1e6f);
        }
        for (int k = 0; k < kBins; k++) {
            const float a = g_[std::max(0, k - 1)], b = g_[k], c = g_[std::min(kBins - 1, k + 1)];
            const float gs = 0.25f * a + 0.5f * b + 0.25f * c;
            gPrev_[k] = gs;
            X[k] *= gs;
        }
    }

    Stft stft_;
    std::vector<float> noise_, gPrev_, gammaPrev_, g_;
    float over_, floor_;
};

/** Mains hum finder: long averaged spectrum, compares 50 Hz and 60 Hz harmonic series. */
class HumDetector {
public:
    static constexpr int kLen = 16384;
    HumDetector() : fft_(kLen), win_(kLen), frame_(kLen, 0.f), acc_(kLen / 2, 0.0), buf_(kLen) {
        for (int i = 0; i < kLen; i++) win_[i] = 0.5f * (1.f - std::cos(2.f * kPi * i / kLen));
    }

    void push(float v) {
        frame_[fill_++] = v;
        if (fill_ == kLen) {
            for (int i = 0; i < kLen; i++) buf_[i] = cf(frame_[i] * win_[i], 0.f);
            fft_.forward(buf_.data());
            for (int k = 0; k < kLen / 2; k++) acc_[k] += std::norm(buf_[k]);
            frames_++;
            fill_ = 0;
        }
    }

    /** 50, 60 or 0. */
    int detect(int rate) const {
        if (frames_ < 2) return 0;
        auto score = [&](double f0) {
            double total = 0;
            for (int h = 1; h <= 4; h++) {
                const double f = f0 * h, bin = f * kLen / rate;
                const int c = (int) std::lround(bin);
                double peak = 0;
                for (int k = c - 1; k <= c + 1; k++) peak = std::max(peak, acc_[k]);
                std::vector<double> around;
                const int span = (int) std::lround(12.0 * kLen / rate), gap = (int) std::lround(3.0 * kLen / rate);
                for (int k = c - span; k <= c + span; k++)
                    if (std::abs(k - c) > gap && k > 0 && k < kLen / 2) around.push_back(acc_[k]);
                if (around.empty()) continue;
                std::nth_element(around.begin(), around.begin() + around.size() / 2, around.end());
                const double med = around[around.size() / 2] + 1e-20;
                total += 10.0 * std::log10(peak / med + 1e-12);
            }
            return total / 4.0;
        };
        const double s50 = score(50), s60 = score(60);
        const double best = std::max(s50, s60);
        if (best < 9.0) return 0;
        return s50 >= s60 ? 50 : 60;
    }

private:
    Fft fft_;
    std::vector<float> win_, frame_;
    std::vector<double> acc_;
    std::vector<cf> buf_;
    int fill_ = 0;
    long frames_ = 0;
};

/** ITU-R BS.1770 integrated loudness (48 kHz K-weighting, 400 ms blocks, 75% overlap, two gates). */
class Loudness {
public:
    explicit Loudness(int channels) : ch_(channels), k1_(channels), k2_(channels), sum_(channels, 0.0) {
        for (int c = 0; c < channels; c++) {
            k1_[c] = Biquad::raw(1.53512485958697, -2.69169618940638, 1.19839281085285, 1, -1.69065929318241,
                                 0.73248077421585);
            k2_[c] = Biquad::raw(1.0, -2.0, 1.0, 1, -1.99004745483398, 0.99007225036621);
        }
    }

    void push(const float* interleaved, int frames) {
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < ch_; c++) {
                const float v = k2_[c].run(k1_[c].run(interleaved[(size_t) i * ch_ + c]));
                sum_[c] += (double) v * v;
            }
            if (++n_ == kStep) {
                double ms = 0;
                for (int c = 0; c < ch_; c++) ms += sum_[c] / kStep;
                steps_.push_back(ms);
                std::fill(sum_.begin(), sum_.end(), 0.0);
                n_ = 0;
            }
        }
    }

    float lufs() const {
        std::vector<double> blocks;
        for (size_t i = 3; i < steps_.size(); i++)
            blocks.push_back((steps_[i] + steps_[i - 1] + steps_[i - 2] + steps_[i - 3]) / 4.0);
        auto L = [](double ms) { return -0.691 + 10.0 * std::log10(ms + 1e-20); };
        double s = 0;
        int n = 0;
        for (double b : blocks)
            if (L(b) > -70) { s += b; n++; }
        if (n == 0) return -70.f;
        const double rel = L(s / n) - 10.0;
        s = 0; n = 0;
        for (double b : blocks)
            if (L(b) > -70 && L(b) > rel) { s += b; n++; }
        return n == 0 ? -70.f : (float) L(s / n);
    }

private:
    static constexpr int kStep = kProcessRate / 10;  // 100 ms
    int ch_;
    std::vector<Biquad> k1_, k2_;
    std::vector<double> sum_;
    int n_ = 0;
    std::vector<double> steps_;
};

// ---- Plumbing -------------------------------------------------------------------------------------------

class PcmReader {
public:
    PcmReader(const std::string& path, int channels, int outChannels)
        : f_(std::fopen(path.c_str(), "rb")), ch_(channels), outCh_(outChannels) {
        if (f_) {
            std::fseek(f_, 0, SEEK_END);
            total_ = std::ftell(f_) / (2L * channels);
            std::fseek(f_, 0, SEEK_SET);
        }
    }
    ~PcmReader() { if (f_) std::fclose(f_); }
    bool ok() const { return f_ != nullptr; }
    long long totalFrames() const { return total_; }

    /** Reads up to [frames] frames, downmixed to outCh, as floats. Returns frames read. */
    int read(int frames, std::vector<float>& out) {
        raw_.resize((size_t) frames * ch_);
        const size_t got = std::fread(raw_.data(), sizeof(int16_t) * ch_, frames, f_);
        out.resize(got * outCh_);
        const float k = 1.f / 32768.f;
        for (size_t i = 0; i < got; i++) {
            const int16_t* s = &raw_[i * ch_];
            if (ch_ == outCh_) {
                for (int c = 0; c < ch_; c++) out[i * outCh_ + c] = s[c] * k;
            } else if (outCh_ == 1) {
                float m = 0;
                for (int c = 0; c < ch_; c++) m += s[c];
                out[i] = m * k / ch_;
            } else {  // > 2 channels: keep front L/R, fold the centre in
                const float centre = ch_ > 2 ? s[2] * 0.7071f : 0.f;
                out[i * 2] = (s[0] + centre) * k * 0.8f;
                out[i * 2 + 1] = (s[1] + centre) * k * 0.8f;
            }
        }
        return (int) got;
    }

private:
    FILE* f_;
    int ch_, outCh_;
    long long total_ = 0;
    std::vector<int16_t> raw_;
};

/** The per-channel chain both passes share. */
struct Channel {
    std::vector<Biquad> pre;  // de-hum notches + high-pass
    std::unique_ptr<RnnoiseStage> rnn;
    std::unique_ptr<SpectralDenoiser> spectral;
    std::vector<Biquad> eq;

    void run(float* x, int n) {
        for (auto& q : pre)
            for (int i = 0; i < n; i++) x[i] = q.run(x[i]);
        if (rnn) rnn->run(x, n);
        if (spectral) spectral->run(x, n);
        for (auto& q : eq)
            for (int i = 0; i < n; i++) x[i] = q.run(x[i]);
    }

    int latency() const {
        return (rnn ? RnnoiseStage::kLatency : 0) + (spectral ? SpectralDenoiser::kLatency : 0);
    }
};

std::vector<Biquad> preFilters(int humHz, bool highpass) {
    std::vector<Biquad> v;
    if (humHz > 0)
        for (int h = 1; h * humHz <= 1000; h++) v.push_back(Biquad::notch(h * humHz, 18.0 + 2.0 * h, kProcessRate));
    if (highpass) {  // 4th-order Butterworth at 75 Hz
        v.push_back(Biquad::highpass(75, 0.5412, kProcessRate));
        v.push_back(Biquad::highpass(75, 1.3066, kProcessRate));
    }
    return v;
}

float rnnWet(float strength) { return std::min(1.f, 0.45f + 0.55f * strength); }

struct Driver {
    std::string inPath;
    int inRate, channels, outCh;
    ProgressSink* sink;

    /**
     * Streams the whole file through [process] (per-channel, in place) and hands latency-compensated
     * interleaved output to [emit]. Returns false on I/O error, and stops early when cancelled.
     */
    template <typename Process, typename Emit>
    int run(int latency, int pFrom, int pTo, Process&& process, Emit&& emit) {
        PcmReader reader(inPath, channels, outCh);
        if (!reader.ok()) return RC_IO;
        Resampler rs(inRate, kProcessRate, outCh);
        const long long expected = (long long) std::llround((double) reader.totalFrames() * kProcessRate / inRate);
        long long toSkip = latency, emitted = 0, consumed = 0;
        std::vector<float> in, res, inter;
        std::vector<std::vector<float>> planes(outCh);

        auto handle = [&](std::vector<float>& interleaved) {
            const int frames = (int) (interleaved.size() / outCh);
            if (frames == 0) return;
            for (int c = 0; c < outCh; c++) {
                planes[c].resize(frames);
                for (int i = 0; i < frames; i++) planes[c][i] = interleaved[(size_t) i * outCh + c];
                process(c, planes[c].data(), frames);
            }
            const int start = (int) std::min<long long>(toSkip, frames);
            toSkip -= start;
            const int count = (int) std::max<long long>(0, std::min<long long>(frames - start, expected - emitted));
            if (count <= 0) return;
            inter.resize((size_t) count * outCh);
            for (int i = 0; i < count; i++)
                for (int c = 0; c < outCh; c++) inter[(size_t) i * outCh + c] = planes[c][start + i];
            emitted += count;
            emit(inter.data(), count);
        };

        while (true) {
            if (sink && sink->cancelled()) return RC_CANCELLED;
            const int got = reader.read(8192, in);
            if (got <= 0) break;
            consumed += got;
            res.clear();
            rs.push(in.data(), got, res);
            handle(res);
            if (sink && reader.totalFrames() > 0)
                sink->progress(pFrom + (int) ((pTo - pFrom) * consumed / reader.totalFrames()), 100);
        }
        res.clear();
        rs.flush(res);
        handle(res);
        // Push silence through so delayed samples come out.
        long long tail = latency + 1024;
        while (emitted < expected && tail > 0) {
            const int n = (int) std::min<long long>(4096, tail);
            res.assign((size_t) n * outCh, 0.f);
            handle(res);
            tail -= n;
        }
        // Pad if the file was shorter than its own latency (tiny clips).
        if (emitted < expected) {
            inter.assign((size_t) (expected - emitted) * outCh, 0.f);
            emit(inter.data(), (int) (expected - emitted));
        }
        return RC_OK;
    }
};

}  // namespace

AudioResult processAudioFile(const std::string& inPath, const std::string& outPath, int inRate, int channels,
                             const AudioParams& p, ProgressSink* sink) {
    AudioResult result;
    if (inRate < 4000 || inRate > 384000 || channels < 1 || channels > 16) {
        result.rc = RC_BAD_INPUT;
        return result;
    }
    const int outCh = std::min(channels, 2);
    result.outChannels = outCh;
    const float strength = std::min(1.f, std::max(0.f, p.strength));
    const bool denoise = p.mode != DENOISE_OFF && strength > 0.001f;
    const bool voice = denoise && p.mode == DENOISE_VOICE;
    Driver drv{inPath, inRate, channels, outCh, sink};

    // ---- Pass 1: analysis ----------------------------------------------------------------------------
    int humHz = p.hum == HUM_50 ? 50 : (p.hum == HUM_60 ? 60 : 0);
    std::vector<NoiseProfiler> profilers(denoise ? outCh : 0);
    std::vector<Stft> analysers(denoise ? outCh : 0);
    std::vector<std::unique_ptr<RnnoiseStage>> rnn1;
    std::vector<std::vector<Biquad>> pre1(outCh, preFilters(humHz, p.highpass));
    for (int c = 0; c < outCh && voice; c++) rnn1.emplace_back(new RnnoiseStage(rnnWet(strength)));
    HumDetector hum;
    std::vector<std::vector<float>> scratch(outCh);
    Loudness inLoud(outCh);
    const bool needPass1 = denoise || p.hum == HUM_AUTO;
    if (needPass1) {
        int rc = drv.run(0, 0, 30,
            [&](int c, float* raw, int n) {
                scratch[c].assign(raw, raw + n);  // analyse a copy; the emitted audio stays untouched
                float* x = scratch[c].data();
                if (c == 0 && p.hum == HUM_AUTO)
                    for (int i = 0; i < n; i++) hum.push(x[i]);
                for (auto& q : pre1[c])
                    for (int i = 0; i < n; i++) x[i] = q.run(x[i]);
                if (!denoise) return;
                if (voice) rnn1[c]->run(x, n);
                analysers[c].run(x, n, false, [&](cf* X) { profilers[c].add(X); });
            },
            [&](const float* inter, int n) { inLoud.push(inter, n); });
        if (rc != RC_OK) { result.rc = rc; return result; }
        result.inputLufs = inLoud.lufs();
        if (p.hum == HUM_AUTO) humHz = hum.detect(kProcessRate);
    }
    result.humHz = humHz;

    // ---- Pass 2: process into a float scratch file ---------------------------------------------------
    std::vector<Channel> chans(outCh);
    for (int c = 0; c < outCh; c++) {
        chans[c].pre = preFilters(humHz, p.highpass);
        if (voice) chans[c].rnn.reset(new RnnoiseStage(rnnWet(strength)));
        if (denoise && profilers[c].frames() > 4)
            chans[c].spectral.reset(new SpectralDenoiser(profilers[c].noisePsd(), voice ? strength * 0.5f : strength));
        if (p.clarity > 0.001f) {
            chans[c].eq.push_back(Biquad::peak(3200, 0.9, 5.0 * p.clarity, kProcessRate));
            chans[c].eq.push_back(Biquad::highShelf(9500, 3.5 * p.clarity, kProcessRate));
        }
    }
    const std::string tmpPath = outPath + ".f32";
    FILE* tmp = std::fopen(tmpPath.c_str(), "wb");
    if (!tmp) { result.rc = RC_IO; return result; }
    Loudness outLoud(outCh);
    float peak = 0.f;
    long long frames = 0;
    int rc = drv.run(chans[0].latency(), needPass1 ? 30 : 0, 90,
        [&](int c, float* x, int n) { chans[c].run(x, n); },
        [&](const float* inter, int n) {
            outLoud.push(inter, n);
            for (int i = 0; i < n * outCh; i++) peak = std::max(peak, std::fabs(inter[i]));
            std::fwrite(inter, sizeof(float) * outCh, n, tmp);
            frames += n;
        });
    std::fclose(tmp);
    if (rc != RC_OK) { std::remove(tmpPath.c_str()); result.rc = rc; return result; }

    // ---- Pass 3: gain + 16-bit -----------------------------------------------------------------------
    const float measured = outLoud.lufs();
    float gain = 1.f;
    if (p.normalize && measured > -69.f) gain = std::pow(10.f, (p.targetLufs - measured) / 20.f);
    gain = std::min(gain, 10.f);                                        // never more than +20 dB
    if (peak > 0) gain = std::min(gain, 0.891f / peak);                 // -1 dBFS sample peak ceiling
    result.outputLufs = measured > -69.f ? measured + 20.f * std::log10(gain) : measured;

    FILE* fin = std::fopen(tmpPath.c_str(), "rb");
    FILE* fout = std::fopen(outPath.c_str(), "wb");
    if (!fin || !fout) {
        if (fin) std::fclose(fin);
        if (fout) std::fclose(fout);
        std::remove(tmpPath.c_str());
        result.rc = RC_IO;
        return result;
    }
    std::vector<float> fb(8192 * outCh);
    std::vector<int16_t> sb(8192 * outCh);
    long long written = 0;
    while (true) {
        const size_t got = std::fread(fb.data(), sizeof(float) * outCh, 8192, fin);
        if (got == 0) break;
        for (size_t i = 0; i < got * outCh; i++) {
            const float v = std::max(-1.f, std::min(1.f, fb[i] * gain)) * 32767.f;
            sb[i] = (int16_t) std::lrint(v);
        }
        std::fwrite(sb.data(), sizeof(int16_t) * outCh, got, fout);
        written += got;
        if (sink && frames > 0) sink->progress(90 + (int) (10 * written / frames), 100);
    }
    std::fclose(fin);
    std::fclose(fout);
    std::remove(tmpPath.c_str());
    result.outFrames = written;
    result.rc = RC_OK;
    if (sink) sink->progress(100, 100);
    return result;
}

}  // namespace sharpr

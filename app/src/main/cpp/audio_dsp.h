// Sharpr audio clean-up: offline, file to file, three passes.
//
//   1. analyse  - noise floor per frequency bin, mains hum (50/60 Hz)
//   2. process  - resample to 48 kHz, de-hum, high-pass, RNNoise (voice) and/or spectral denoise,
//                 clarity EQ; measures loudness (ITU-R BS.1770 K-weighting with gating)
//   3. finish   - loudness normalisation with a peak ceiling, 16-bit output
//
// Input and output are raw interleaved signed 16-bit little-endian PCM files. Platform neutral.
#pragma once

#include <string>

#include "sharpr_common.h"

namespace sharpr {

enum DenoiseMode {
    DENOISE_OFF = 0,
    DENOISE_UNIVERSAL = 1,  // spectral (stationary noise: hiss, fans, traffic rumble, room tone)
    DENOISE_VOICE = 2,      // RNNoise neural suppression, then a light spectral clean-up
};

enum HumMode { HUM_OFF = 0, HUM_AUTO = 1, HUM_50 = 2, HUM_60 = 3 };

struct AudioParams {
    int mode = DENOISE_UNIVERSAL;
    float strength = 0.6f;  // 0..1
    int hum = HUM_AUTO;
    bool highpass = true;   // remove rumble below ~75 Hz
    float clarity = 0.f;    // 0..1 presence/air lift
    bool normalize = true;
    float targetLufs = -16.f;
};

struct AudioResult {
    int rc = RC_OK;
    int outRate = 48000;
    int outChannels = 0;
    long long outFrames = 0;
    float inputLufs = -70.f;
    float outputLufs = -70.f;
    int humHz = 0;  // detected and removed mains hum, 0 = none
};

constexpr int kProcessRate = 48000;

AudioResult processAudioFile(const std::string& inPath, const std::string& outPath, int inRate, int channels,
                             const AudioParams& params, ProgressSink* sink);

}  // namespace sharpr

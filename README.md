# Sharpr

**On-device AI enhancement for images, video and audio on Android.**
Upscale photos up to 4×, sharpen whole videos frame by frame, and strip noise and hum from voice and music. Everything runs on your phone's GPU (or CPU). Nothing is uploaded, ever.

[![Android](https://github.com/anshm-2166/WikiTechxHackDay-Project-Sharpr/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/anshm-2166/WikiTechxHackDay-Project-Sharpr/actions/workflows/android.yml)

*A project by The Bug Slayyers.*

<p align="center">
  <img src="docs/screenshots/dark-onboarding-1.png" width="19%" alt="Onboarding" />
  <img src="docs/screenshots/dark-studio.png" width="19%" alt="Studio" />
  <img src="docs/screenshots/dark-detail-image.png" width="19%" alt="Before and after slider" />
  <img src="docs/screenshots/dark-editor-video.png" width="19%" alt="Video editor" />
  <img src="docs/screenshots/dark-detail-audio.png" width="19%" alt="Audio A/B" />
</p>

## Why Sharpr

Apps like Remini send your photos to a server. Sharpr does all of the work on the device: no account, no upload, no subscription, no watermark.

- **Private by design.** Media are processed locally and stay in the app's own storage until you share or save them.
- **GPU first, CPU always.** Inference runs on the Vulkan GPU through [ncnn](https://github.com/Tencent/ncnn). If a driver fails, errors out or returns garbage (checked on the first tile), Sharpr finishes on the CPU and tells you. Choose Auto, GPU or CPU.
- **Images, video and audio** in one app, with one queue that keeps working in the background.

## Features

### Images
- **Six models** (see below): general, photo, anime/illustration and restoration, from 1× ("fix at the same size") up to 4×.
- **AI denoise** variants for models trained with noise removal, plus classic pre-denoise, natural blend, sharpening and film grain.
- **Quality boost (TTA):** every tile runs 8 ways (flips and transposes) and is averaged, for cleaner edges.
- **Batch:** pick up to 30 images and apply the same settings.
- **Smart guards:** resolution alone never blocks an image. Sharpr measures real sharpness on native-resolution crops and only holds back images that are both large and already crisp. The scale is lowered automatically to stay under your output-size limit.
- **Compare:** before/after slider with pinch zoom, pan and double-tap magnify.

### Video
- **Frame-by-frame upscaling up to 4K** (or 1× "fix"), with H.264 or HEVC output at three quality levels. Scales the device's encoder can't take are disabled up front.
- **Repeated-frame reuse:** frames that didn't change (held anime frames, static shots) reuse the previous result instead of running the network again. Animation, which often holds each drawing for two or three frames, benefits most.
- **Soundtrack:** copied untouched, or cleaned by the audio engine. Rotation metadata is preserved.
- **Preview mode:** process just the first 3 or 10 seconds to judge a model before a long run.
- **Compare:** play the original or the result from the same position, or freeze a frame from both into the compare slider.

### Audio
- **Voice mode:** [RNNoise](https://github.com/xiph/rnnoise), a neural noise suppressor trained on speech, followed by a light spectral clean-up.
- **Universal mode:** decision-directed Wiener filtering against a noise floor learned from the whole file, gentle enough for music.
- **De-hum:** finds 50 or 60 Hz mains hum and its harmonics (or you force one) and notches them out.
- **Rumble filter, clarity EQ** (presence and air) and **loudness normalisation** to −16 LUFS (ITU-R BS.1770 gating) with a −1 dBFS peak ceiling.
- **A/B listening:** switch between original and cleaned while playing, at the same moment, with both waveforms on screen.
- Any input Android can decode (MP3, AAC/M4A, Opus, Ogg, FLAC, WAV, AMR…), output as AAC (M4A) or WAV at 48 kHz.

### Everywhere
- **Background processing:** a sequential job queue runs in a foreground service (`mediaProcessing` on Android 15+, `dataSync` before), holds a wake lock, shows progress with ETA in a notification with a Cancel action, and notifies you when results are ready.
- **Library** of every result, filterable by type, with share, save to Pictures/Movies/Music, delete, and **Edit** (re-run from the original with new settings).
- **Share to Sharpr** from any gallery, file manager or recorder.
- **Design:** Jetpack Compose UI with light, dark and system themes, a one-tap theme toggle, AMOLED black, Material You wallpaper colours, five gradient accents, Bricolage Grotesque headlines and a choice of body typeface (Plus Jakarta Sans, Inter, Outfit, Space Grotesk). First-launch onboarding sets theme, accent and notifications and detects your GPU.

## Models

| Model in the app | Network | Best for | Scales | License |
| --- | --- | --- | --- | --- |
| **Fast** | Real-ESRGAN `realesr-animevideov3` (x2/x3/x4) | Everything, and video | 1–4× | BSD-3-Clause |
| **Photo Ultra** | Real-ESRGAN `x4plus` | Real-world photos, most detail | 1, 2, 4× | BSD-3-Clause |
| **Photo Lite** | waifu2x UpConv7 photo (+ noise level 2) | Gentle, natural 2× | 1, 2× | MIT |
| **Anime HD** | Real-CUGAN SE (conservative / denoise 3×) | Line art, anime frames | 1–4× | MIT |
| **Illustration** | Real-ESRGAN `x4plus-anime` | Drawings, digital art | 1, 2, 4× | BSD-3-Clause |
| **Restore** | waifu2x CUNet (noise 1 / noise 3) | JPEG blocks and noise at the same size | 1× | MIT |

Lower scales are produced by box-averaging the network's native output (supersampling), which gives sharper results than running a smaller network. Every model's tile geometry (border crop, alignment, residual head) was measured and is covered by tests.

## How it works

```
Image   decode (EXIF-aware) ─► OpenCV pre-denoise ─► ncnn tiles (GPU/CPU, TTA) ─► OpenCV blend / sharpen / grain ─► PNG/JPEG/WebP
Video   MediaExtractor ─► HW decoder ─► SurfaceTexture ─► GL readback ─► ncnn ─► GL ─► HW encoder (surface) ─► MediaMuxer
                                                         └─ repeated frame? reuse last result            audio: copy or clean ─┘
Audio   MediaCodec decode ─► 48 kHz resampler ─► de-hum ─► high-pass ─► RNNoise ─► spectral Wiener ─► EQ ─► loudness ─► AAC/WAV
```

Performance notes:
- **Loaded networks are cached** across jobs and video frames, so a batch or a video pays the model load and Vulkan pipeline compilation once. They are released after 45 s idle or when the system is low on memory.
- **Tile size follows the GPU's memory budget**, scaled per model (compact networks take bigger tiles). Each tile carries just enough context for its network's receptive field, so seams are invisible (tested to > 60 dB PSNR against untiled output for the compact models).
- **Precision:** Exact (fp32), Balanced (fp16 storage, the default) or Turbo (fp16 arithmetic).
- **CPU path** uses the performance cores only by default, with ncnn's NEON/x86 kernels.
- The video path never leaves the GPU for colour conversion: decode, readback and encode go through GL; only the RGBA frame visits ncnn.

## Build and run

Requirements: Android Studio (or JDK 17+), Android SDK 37, NDK 28.2.13676358, CMake 3.31.6.

```bash
scripts/setup-native.sh        # downloads the prebuilt ncnn Vulkan libraries (~60 MB); setup-native.ps1 on Windows
./gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. minSdk 26 (Android 8.0). Saving to the gallery needs Android 10+; on 8 and 9 use Share.

## Tests

| Suite | What it covers | Run |
| --- | --- | --- |
| Native core (desktop) | Every model: registration, tile seams, scales, TTA, precision, cancel. Audio: bit-exact pass-through, resampler, denoise SNR, voice timing, hum detection, loudness | see `app/src/test/native/CMakeLists.txt` |
| JVM unit tests | Model geometry and assets, guards, library JSON (incl. v1), job ETA | `./gradlew :app:testDebugUnitTest` |
| Instrumented | All six models on device, video with sound and frame reuse, preview + clean audio, audio to M4A/WAV | `scripts/ci-device-tests.sh` with a device attached |
| Screenshot tour | Every screen and editor in dark and light | part of the instrumented run |

GitHub Actions runs all of them on every push, the instrumented ones on an x86_64 emulator.

## Project layout

```
app/src/main/cpp/       native core: sr_engine (ncnn tiling), audio_dsp, jni_bridge, rnnoise/
app/src/main/java/…/
  engine/               image, video and audio pipelines, models, guards, codecs, GL
  work/                 job queue, foreground service, notifications
  data/                 settings, library, export
  ui/                   Compose theme, components and screens
app/src/test/           JVM tests + native desktop tests
app/src/androidTest/    on-device pipeline tests and the screenshot tour
docs/                   screenshots, samples, third-party licenses
scripts/                native dependency setup, device test runner
website/                showcase site
```

## Roadmap

- Google Play release
- Face restoration for portraits
- Frame interpolation (smooth 60 fps) for video
- Persisting the queue across reboots

## License and credits

Released under the MIT License (see `LICENSE`). Third-party components keep their own licenses (copies in [`docs/licenses`](docs/licenses)): ncnn (BSD-3-Clause), OpenCV (Apache-2.0), Real-ESRGAN (BSD-3-Clause), Real-CUGAN ncnn (MIT), waifu2x ncnn (MIT), RNNoise (BSD-3-Clause), Jetpack Compose and Media3 (Apache-2.0). Fonts under the SIL Open Font License: Bricolage Grotesque, Plus Jakarta Sans, Inter, Outfit, Space Grotesk, JetBrains Mono.

---

A project by **The Bug <span style="color:red">Slayyers</span>**

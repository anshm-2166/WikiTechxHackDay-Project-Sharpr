# Sharpr - desktop

On-device AI image super-resolution for Windows, built with Python + Kivy and the
[ncnn](https://github.com/Tencent/ncnn) runtime (Vulkan GPU with CPU fallback). It mirrors the
Sharpr Android app: Create, Library, Settings, plus Detail (before/after slider) and Edit screens.
Nothing leaves your computer.

## Setup

Requires Python 3.11 or 3.12 (Kivy 2.3 / ncnn wheels are available for these on Windows).

```powershell
cd desktop
py -3.12 -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python main.py
```

or simply run `.\run.ps1` (creates the venv and installs requirements on first run).

## Models

The ncnn model files are **not duplicated** here. They are read from the shared Android assets folder
`../app/src/main/assets/models/` (`realesr-animevideov3-x2/x3/x4`, `realesrgan-x4plus`,
`realesrgan-x4plus-anime`, each `.param` + `.bin`).

Override the location (first match wins):

1. `models_dir` in `%APPDATA%\Sharpr\settings.json`
2. the `SHARPR_MODELS` environment variable
3. `desktop/models/`
4. `../app/src/main/assets/models/` (default)

## Data

Settings (`settings.json`), the library index (`library.json`) and the `originals/`, `results/`,
`thumbs/` folders live in `%APPDATA%\Sharpr` (override with the `SHARPR_DATA` environment variable).

## Features

- Create: pick or drag-and-drop an image, info chips (resolution, MP, sharpness, noise), model
  (Fast / Ultra / Illustration), scale 2x/3x/4x (3x for Fast only), Denoise / Natural blend / Sharpen /
  Grain sliders, progress overlay with tile counter, engine used and Cancel.
- Guards: refuses images with longest side >= `max_input_side` (default 2048) or >= 4 MP (with an
  "Upscale anyway" override unless Strict guard is on), rejects inputs under 16 px, and clamps the scale
  to stay under `max_output_mp` (default 24 MP), telling you when it did.
- Engine: Auto (GPU, CPU fallback) / GPU / CPU. The first GPU tile is sanity-checked and the run falls
  back to CPU on any Vulkan error or implausible output. The backend actually used is reported.
- Library, Detail (draggable before/after divider, scroll-wheel zoom, drag to pan, double-click to
  reset), Edit (regenerates from the original; "Keep previous version" on by default), Settings.

## Layout and look

- Opens as a phone-style window (400x820, minimum 340x560). Below 700 dp wide it is the phone layout;
  700-900 dp centres a 480 dp column; above 900 dp it switches to the two-column desktop layout.
- Icons are the Android app's vector paths (`sharpr/icons.py`), rasterised per pixel size with Pillow and
  tinted with the theme colour. One type scale (12-34 sp) is defined in `sharpr/widgets.py`.
- Guards mirror Android: resolution alone never blocks; only large AND already crisp images are held back.
  Scales: 1x (Enhance, original size) for all models, 3x for Fast only.

## Tests

```powershell
.\.venv\Scripts\python tests\test_pipeline.py
```

Runs headless: guard rules, a Fast-model CPU upscale of a generated image (output size, tiling seams,
progress, cancel) and the library round trip. `tools/smoke_responsive.py <out-dir> [WxH ...]` drives the window at several sizes; `tools/smoke.py <out-dir>` drives the real Kivy window
through every screen and saves screenshots (developer aid). `tools/make_static_fonts.py` regenerates
the static Regular/Bold font instances from the variable fonts (needs `fonttools`).

## Notes

- Fonts: SDL cannot select weights of variable fonts, so static Regular/Bold instances are generated
  from the Android variable fonts and registered with Kivy.
- Native file dialogs (open / Save As) use `tkinter`, which ships with the python.org installer.
- Strict guard defaults to off, so the large-and-sharp block can be overridden per image.

"""Full upscale pipeline: load -> guards -> denoise -> ncnn -> blend/sharpen/grain -> encode."""
from __future__ import annotations

import threading
import time
from pathlib import Path
from dataclasses import dataclass, asdict
from typing import Callable, Optional

import cv2
import numpy as np
from PIL import Image, ImageOps

from . import engine, guards
from .settings import Settings, missing_models_message

EXTS = (".png", ".jpg", ".jpeg", ".webp", ".bmp")


class GuardError(Exception):
    def __init__(self, title, message):
        super().__init__(message)
        self.title, self.message = title, message


@dataclass
class Options:
    model: str = "fast"
    scale: int = 2
    denoise: int = 0
    blend: int = 0
    sharpen: int = 0
    grain: int = 0

    def to_dict(self):
        return asdict(self)

    @classmethod
    def from_dict(cls, d):
        return cls(**{k: d[k] for k in cls.__dataclass_fields__ if k in d})


@dataclass
class Loaded:
    bgr: np.ndarray
    alpha: Optional[np.ndarray]


@dataclass
class Result:
    image: np.ndarray            # BGR or BGRA uint8
    info: engine.EngineInfo
    options: Options             # options actually used (scale may be clamped)
    in_size: tuple
    out_size: tuple
    seconds: float
    note: str = ""


def load_image(path) -> Loaded:
    with Image.open(path) as im:
        im = ImageOps.exif_transpose(im)
        has_alpha = im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info)
        if has_alpha:
            arr = np.array(im.convert("RGBA"))
            bgr = cv2.cvtColor(arr[:, :, :3], cv2.COLOR_RGB2BGR)
            alpha = arr[:, :, 3]
            return Loaded(bgr, None if alpha.min() == 255 else alpha)
        arr = np.array(im.convert("RGB"))
    return Loaded(cv2.cvtColor(arr, cv2.COLOR_RGB2BGR), None)


def _denoise(bgr: np.ndarray, amount: int) -> np.ndarray:
    s = amount / 100.0
    if s <= 0:
        return bgr
    if bgr.shape[0] * bgr.shape[1] / 1e6 <= 2.5:
        h = 1.0 + s * 11.0
        return cv2.fastNlMeansDenoisingColored(bgr, None, h, h, 7, 21)
    return cv2.bilateralFilter(bgr, 7, 20 + s * 60, 20 + s * 60)


def _sharpen(img: np.ndarray, amount: int) -> np.ndarray:
    a = amount / 100.0 * 1.2
    if a <= 0:
        return img
    blur = cv2.GaussianBlur(img, (0, 0), 1.2)
    return cv2.addWeighted(img, 1 + a, blur, -a, 0)


def _grain(img: np.ndarray, amount: int) -> np.ndarray:
    sigma = amount / 100.0 * 10.0
    if sigma <= 0:
        return img
    rng = np.random.default_rng()
    out = img.copy()
    band = 512
    for y in range(0, img.shape[0], band):
        part = out[y:y + band].astype(np.float32)
        noise = rng.normal(0, sigma, part.shape[:2] + (1,)).astype(np.float32)
        out[y:y + band] = np.clip(part + noise, 0, 255).astype(np.uint8)
    return out


def resolve_models_dir(settings: Settings, model: str, scale: int):
    """Folder that contains the files for this model, or raise engine.ModelsMissing (friendly message)."""
    paths = settings.search_paths()
    param, binf = engine.model_files(Path("."), model, scale)
    for p in paths:
        if (p / param.name).exists() and (p / binf.name).exists():
            return p
    raise engine.ModelsMissing(missing_models_message(paths, param.stem))


def process(
    path,
    opts: Options,
    settings: Settings,
    *,
    override: bool = False,
    progress: Optional[Callable[[float, int, int, str, str], None]] = None,
    cancel: Optional[threading.Event] = None,
    loaded: Optional[Loaded] = None,
) -> Result:
    """progress(fraction, tile, tiles, backend_label, stage)."""
    cancel = cancel or threading.Event()

    def report(f, tile=0, tiles=0, label="", stage=""):
        if progress:
            progress(min(max(f, 0.0), 1.0), tile, tiles, label, stage)

    t0 = time.time()
    report(0, stage="Loading")
    ld = loaded or load_image(path)
    h, w = ld.bgr.shape[:2]

    tiny = guards.check_min(w, h)   # resolution/sharpness was vetted by the UI; only enforce the minimum
    if tiny:
        raise GuardError(tiny.title, tiny.message)
    plan = guards.plan_scale(w, h, opts.scale, opts.model, float(settings["max_output_mp"]))
    if not plan.ok:
        raise GuardError("Output would be too large", plan.message)
    used = Options(**{**opts.to_dict(), "scale": plan.scale})

    src = ld.bgr
    if opts.denoise > 0:
        report(0.01, stage="Denoising")
        src = _denoise(src, opts.denoise)
    if cancel.is_set():
        raise engine.Cancelled()

    def eng_progress(i, n, label):
        report(0.05 + 0.87 * (i / max(n, 1)), i, n, label, "Upscaling")

    rgb = cv2.cvtColor(src, cv2.COLOR_BGR2RGB)
    out_rgb, info = engine.upscale_rgb(
        rgb, used.model, used.scale,
        models_dir=resolve_models_dir(settings, used.model, used.scale),
        engine=settings["engine"],
        allow_fallback=bool(settings["cpu_fallback"]),
        threads=int(settings["threads"]),
        tile=int(settings["tile"]),
        fp16=bool(settings["fp16"]),
        progress=eng_progress, cancel=cancel,
    )
    out = cv2.cvtColor(out_rgb, cv2.COLOR_RGB2BGR)
    del out_rgb
    oh, ow = out.shape[:2]

    report(0.93, info.tiles, info.tiles, info.backend_label, "Finishing")
    if opts.blend > 0:
        b = opts.blend / 100.0
        ref = cv2.resize(src, (ow, oh), interpolation=cv2.INTER_LANCZOS4)
        out = cv2.addWeighted(out, 1 - b, ref, b, 0)
        del ref
    out = _sharpen(out, opts.sharpen)
    out = _grain(out, opts.grain)

    if ld.alpha is not None:
        a = cv2.resize(ld.alpha, (ow, oh), interpolation=cv2.INTER_LANCZOS4)
        out = np.dstack([out, a])
    report(1.0, info.tiles, info.tiles, info.backend_label, "Done")
    return Result(out, info, used, (w, h), (ow, oh), time.time() - t0, plan.message)


def encode(img: np.ndarray, fmt: str, quality: int = 92) -> tuple[bytes, str]:
    """Encode to PNG/JPEG/WebP bytes. Returns (data, extension)."""
    fmt = fmt.lower()
    if fmt in ("jpeg", "jpg"):
        if img.shape[2] == 4:  # flatten onto white
            a = img[:, :, 3:4].astype(np.float32) / 255.0
            img = (img[:, :, :3] * a + 255 * (1 - a)).astype(np.uint8)
        ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, int(quality)])
        ext = ".jpg"
    elif fmt == "webp":
        ok, buf = cv2.imencode(".webp", img, [cv2.IMWRITE_WEBP_QUALITY, int(quality)])
        ext = ".webp"
    else:
        ok, buf = cv2.imencode(".png", img, [cv2.IMWRITE_PNG_COMPRESSION, 3])
        ext = ".png"
    if not ok:
        raise IOError("Could not encode image")
    return buf.tobytes(), ext

"""ncnn tiled inference with Vulkan -> CPU fallback."""
from __future__ import annotations

import os
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

import cv2
import numpy as np

try:
    import ncnn
except ImportError:  # pragma: no cover
    ncnn = None

PREPAD = 10
MODELS = ("fast", "photo", "illustration")
MODEL_LABELS = {"fast": "Fast", "photo": "Ultra", "illustration": "Illustration"}


class EngineError(RuntimeError):
    pass


class ModelsMissing(EngineError):
    """Model files could not be found; the message lists searched folders and how to fix it."""


class Cancelled(Exception):
    pass


class BackendFailure(EngineError):
    """The selected backend produced an error / bad output."""


def native_scale(model: str, scale: int) -> int:
    return scale if (model == "fast" and scale > 1) else 4


def model_basename(model: str, scale: int) -> str:
    if model == "fast":
        return f"realesr-animevideov3-x{scale}"
    return "realesrgan-x4plus" if model == "photo" else "realesrgan-x4plus-anime"


def model_files(models_dir: Path, model: str, scale: int) -> tuple[Path, Path]:
    base = model_basename(model, native_scale(model, scale))
    return Path(models_dir) / f"{base}.param", Path(models_dir) / f"{base}.bin"


def gpu_count() -> int:
    if ncnn is None:
        return 0
    try:
        return int(ncnn.get_gpu_count())
    except Exception:
        return 0


def gpu_name() -> str:
    try:
        return str(ncnn.get_gpu_info(0).device_name())
    except Exception:
        return "GPU"


@dataclass
class EngineInfo:
    backend: str = "cpu"                 # 'gpu' or 'cpu' actually used
    backend_label: str = "CPU"
    fell_back: bool = False
    fallback_reason: str = ""
    tile: int = 0
    tiles: int = 0
    seconds: float = 0.0
    notes: list = field(default_factory=list)


def _build_net(param: Path, binf: Path, use_gpu: bool, threads: int, fp16: bool):
    if ncnn is None:
        raise EngineError("The 'ncnn' package is not installed.")
    if not param.exists() or not binf.exists():
        raise EngineError(f"Model files not found: {param.name} (looked in {param.parent})")
    net = ncnn.Net()
    net.opt.use_vulkan_compute = use_gpu
    net.opt.num_threads = threads if threads > 0 else (os.cpu_count() or 4)
    net.opt.use_fp16_packed = fp16
    net.opt.use_fp16_storage = fp16
    net.opt.use_fp16_arithmetic = fp16 and use_gpu
    try:
        if net.load_param(str(param)) != 0 or net.load_model(str(binf)) != 0:
            raise BackendFailure("failed to load model")
    except BackendFailure:
        raise
    except Exception as e:
        raise BackendFailure(f"failed to load model: {e}") from e
    return net


def _infer(net, tile_rgb: np.ndarray) -> np.ndarray:
    """tile_rgb: HxWx3 uint8 -> HxWx3 float32 in 0..255 (unclipped)."""
    arr = np.ascontiguousarray(tile_rgb.transpose(2, 0, 1), dtype=np.float32) * (1.0 / 255.0)
    try:
        ex = net.create_extractor()
        ex.input("data", ncnn.Mat(arr).clone())
        ret, out = ex.extract("output")
    except Exception as e:
        raise BackendFailure(f"inference error: {e}") from e
    if ret != 0:
        raise BackendFailure(f"extract returned {ret}")
    o = np.array(out)
    if o.ndim != 3 or o.shape[0] != 3:
        raise BackendFailure(f"unexpected output shape {o.shape}")
    return o.transpose(1, 2, 0) * 255.0


def _sane(inp: np.ndarray, out: np.ndarray) -> bool:
    if not np.all(np.isfinite(out)):
        return False
    return abs(float(out.mean()) / 255.0 - float(inp.mean()) / 255.0) <= 0.25


def upscale_rgb(
    rgb: np.ndarray,
    model: str,
    scale: int,
    *,
    models_dir: Path,
    engine: str = "auto",
    allow_fallback: bool = True,
    threads: int = 0,
    tile: int = 0,
    fp16: bool = False,
    progress: Optional[Callable[[int, int, str], None]] = None,
    cancel: Optional[threading.Event] = None,
) -> tuple[np.ndarray, EngineInfo]:
    """Upscale an RGB uint8 image. progress(tile_index, tile_count, backend_label)."""
    t0 = time.time()
    ns = native_scale(model, scale)
    param, binf = model_files(models_dir, model, scale)

    have_gpu = gpu_count() > 0
    if engine == "cpu":
        plan = ["cpu"]
    elif engine == "gpu":
        plan = ["gpu", "cpu"] if allow_fallback else ["gpu"]
    else:  # auto = GPU with CPU fallback
        plan = ["gpu", "cpu"] if have_gpu else ["cpu"]
    if "gpu" in plan and not have_gpu:
        if allow_fallback:
            plan = ["cpu"]
        else:
            raise EngineError("No Vulkan GPU available and CPU fallback is disabled.")

    info = EngineInfo()
    out = None
    for i, backend in enumerate(plan):
        use_gpu = backend == "gpu"
        label = f"GPU ({gpu_name()})" if use_gpu else "CPU"
        try:
            net = _build_net(param, binf, use_gpu, threads, fp16)
            out = _run_tiles(net, rgb, ns, tile or (200 if use_gpu else 128), progress, cancel,
                             label, check_first=use_gpu, info=info)
        except BackendFailure as e:
            if i + 1 < len(plan):
                info.fell_back = True
                info.fallback_reason = str(e)
                info.notes.append(f"{backend.upper()} failed ({e}); falling back to {plan[i + 1].upper()}")
                continue
            raise EngineError(f"Inference failed on {backend.upper()}: {e}") from e
        info.backend = backend
        info.backend_label = label
        break

    h, w = rgb.shape[:2]
    if ns != scale:
        out = cv2.resize(out, (w * scale, h * scale), interpolation=cv2.INTER_AREA)
    info.seconds = time.time() - t0
    return out, info


def _run_tiles(net, rgb, ns, tile, progress, cancel, label, check_first, info) -> np.ndarray:
    h, w = rgb.shape[:2]
    p = PREPAD
    padded = cv2.copyMakeBorder(rgb, p, p, p, p, cv2.BORDER_REPLICATE)
    out = np.zeros((h * ns, w * ns, 3), np.uint8)
    xs = list(range(0, w, tile))
    ys = list(range(0, h, tile))
    total = len(xs) * len(ys)
    info.tile, info.tiles = tile, total
    n = 0
    if progress:
        progress(0, total, label)
    for y0 in ys:
        th = min(tile, h - y0)
        for x0 in xs:
            if cancel is not None and cancel.is_set():
                raise Cancelled()
            tw = min(tile, w - x0)
            crop = padded[y0:y0 + th + 2 * p, x0:x0 + tw + 2 * p]
            res = _infer(net, crop)
            if n == 0 and check_first and not _sane(crop, res):
                raise BackendFailure("sanity check failed on first tile (bad GPU output)")
            res = res[p * ns:(p + th) * ns, p * ns:(p + tw) * ns]
            out[y0 * ns:(y0 + th) * ns, x0 * ns:(x0 + tw) * ns] = np.clip(res + 0.5, 0, 255).astype(np.uint8)
            n += 1
            if progress:
                progress(n, total, label)
    return out

"""Input analysis and upscaling guards (mirrors the Android Guards.kt / Models.kt logic)."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

import cv2
import numpy as np

MIN_SIDE = 16
HIGHRES_MP = 4.0
SOFT_BELOW = 90.0
CRISP_FROM = 350.0
SCALES = (1, 2, 3, 4)


def allowed_scales(model: str) -> tuple[int, ...]:
    """1x (Enhance) for every model; 3x only for Fast."""
    return (1, 2, 3, 4) if model == "fast" else (1, 2, 4)


@dataclass
class ImageInfo:
    width: int
    height: int
    megapixels: float
    detail: float           # 75th percentile Laplacian variance over native-resolution crops
    sharpness_label: str    # Soft / Normal / Crisp
    noise: float            # estimated sigma (0..255 scale)
    noise_label: str

    @property
    def is_crisp(self) -> bool:
        return self.detail >= CRISP_FROM

    @property
    def sharpness(self) -> float:  # backwards compatible name
        return self.detail


def _laplacian_variance(bgr: np.ndarray) -> float:
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (3, 3), 0.8)   # keep sensor noise from looking like detail
    lap = cv2.Laplacian(gray, cv2.CV_64F)
    return float(lap.std() ** 2)


def native_detail(bgr: np.ndarray) -> float:
    """Sharpness at the image's own resolution: 3x3 grid of native 256px crops, 75th percentile."""
    h, w = bgr.shape[:2]
    crop = min(256, w, h)
    scores = []
    for fy in (0.2, 0.5, 0.8):
        for fx in (0.2, 0.5, 0.8):
            x0, y0 = int((w - crop) * fx), int((h - crop) * fy)
            scores.append(_laplacian_variance(bgr[y0:y0 + crop, x0:x0 + crop]))
    scores.sort()
    return scores[min(len(scores) * 3 // 4, len(scores) - 1)]


def sharpness_label(detail: float) -> str:
    return "Soft" if detail < SOFT_BELOW else ("Normal" if detail < CRISP_FROM else "Crisp")


def analyze(bgr: np.ndarray) -> ImageInfo:
    h, w = bgr.shape[:2]
    detail = native_detail(bgr)
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    if max(h, w) > 1024:  # noise estimate on a downsized copy, as before
        f = 1024 / max(h, w)
        gray = cv2.resize(gray, None, fx=f, fy=f, interpolation=cv2.INTER_AREA)
    # Immerkaer fast noise estimate
    g = gray.astype(np.float32)
    kernel = np.array([[1, -2, 1], [-2, 4, -2], [1, -2, 1]], np.float32)
    conv = cv2.filter2D(g, -1, kernel)
    gh, gw = g.shape
    sigma = float(np.sum(np.abs(conv[1:-1, 1:-1])) * np.sqrt(0.5 * np.pi) / (6.0 * max(1, (gw - 2) * (gh - 2))))
    nlabel = "Low" if sigma < 2.5 else ("Medium" if sigma < 6 else "High")
    return ImageInfo(w, h, w * h / 1e6, detail, sharpness_label(detail), sigma, nlabel)


@dataclass
class GuardResult:
    ok: bool                 # may proceed without override
    overridable: bool        # blocked, but user may override (strict guard off)
    message: str = ""
    title: str = ""
    note: str = ""           # informational, never blocks


def check_min(width: int, height: int) -> Optional[GuardResult]:
    if min(width, height) < MIN_SIDE:
        return GuardResult(False, False, f"The image is only {width} x {height} px. At least {MIN_SIDE} px "
                           "on each side is needed.", "Image too small")
    return None


def is_high_res(width: int, height: int, max_side: int) -> bool:
    return max(width, height) >= max_side or width * height / 1e6 >= HIGHRES_MP


def check_input(width: int, height: int, max_side: int = 2048, strict: bool = False,
                detail: Optional[float] = None) -> GuardResult:
    """Resolution alone never blocks. Only large AND already crisp images are held back."""
    tiny = check_min(width, height)
    if tiny:
        return tiny
    high = is_high_res(width, height, max_side)
    crisp = detail is not None and detail >= CRISP_FROM
    if high and crisp:
        msg = (f"At {width} x {height} ({width * height / 1e6:.1f} MP) this image is detailed enough already. "
               "Upscaling it would burn time and memory for little gain.")
        if strict:
            msg += " Strict guard is on in Settings, so it cannot be overridden."
        return GuardResult(False, not strict, msg, "Already high resolution and sharp")
    note = "Large but soft. Sharpr will recover detail rather than just enlarge it." if high else ""
    return GuardResult(True, False, note=note)


@dataclass
class ScalePlan:
    scale: int
    clamped: bool
    ok: bool
    message: str = ""


def plan_scale(width: int, height: int, scale: int, model: str, max_output_mp: float = 24) -> ScalePlan:
    """Largest supported scale <= requested whose output fits the cap (1x = Enhance at original size)."""
    def mp(s):
        return width * height * s * s / 1e6

    fits = [s for s in allowed_scales(model) if s <= scale and mp(s) <= max_output_mp]
    if not fits:
        return ScalePlan(1, False, False,
                         f"Even the 1x enhance mode exceeds your {max_output_mp:g} MP output limit. "
                         "Raise it in Settings.")
    best = max(fits)
    if best == scale:
        return ScalePlan(best, False, True)
    if best == 1:
        msg = (f"Too big to enlarge within your {max_output_mp:g} MP limit, so Sharpr will enhance it "
               "at the same size.")
    else:
        msg = f"Scale lowered to {best}x to stay under your {max_output_mp:g} MP output limit."
    return ScalePlan(best, True, True, msg)

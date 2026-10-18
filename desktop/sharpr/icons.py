"""Icon set: the Android app's vector paths (24x24 viewport, 1.9 stroke, round caps/joins),
rasterised with Pillow at 8x supersampling to a white RGBA texture per pixel size, then tinted
with the theme colour via the canvas Color instruction."""
from __future__ import annotations

import math
import re
from functools import lru_cache

from kivy.graphics import Color, Rectangle
from kivy.graphics.texture import Texture
from PIL import Image, ImageDraw

STROKE = 1.9
VIEW = 24.0
SS = 8  # supersampling factor

_RR = "a{r},{r} 0 0 1 "  # helper not used directly; paths below are literal

ICON_PATHS = {
    # --- shared with Android (app/src/main/res/drawable/ic_*.xml) ---
    "back": "M19,12 L5,12 M11,6 L5,12 L11,18",
    "bolt": "M13,3 L5.5,13.5 L11.5,13.5 L10.5,21 L18.5,10 L12.5,10 Z",
    "check": "M5,12.5 L10,17.5 L19,7",
    "chip": "M8.5,6 h7 a2.5,2.5 0 0 1 2.5,2.5 v7 a2.5,2.5 0 0 1 -2.5,2.5 h-7 a2.5,2.5 0 0 1 -2.5,-2.5 v-7 "
            "a2.5,2.5 0 0 1 2.5,-2.5 z M9.5,3 L9.5,6 M14.5,3 L14.5,6 M9.5,18 L9.5,21 M14.5,18 L14.5,21 "
            "M3,9.5 L6,9.5 M3,14.5 L6,14.5 M18,9.5 L21,9.5 M18,14.5 L21,14.5",
    "close": "M6,6 L18,18 M18,6 L6,18",
    "save": "M12,4 L12,15 M7.5,10.8 L12,15.2 L16.5,10.8 M5,20 L19,20",
    "edit": "M4,20 L4.8,15.6 L16,4.4 L19.6,8 L8.4,19.2 Z M14,6.4 L17.6,10",
    "image": "M7,4.5 h10 a3.5,3.5 0 0 1 3.5,3.5 v8 a3.5,3.5 0 0 1 -3.5,3.5 h-10 a3.5,3.5 0 0 1 -3.5,-3.5 v-8 "
             "a3.5,3.5 0 0 1 3.5,-3.5 z M7.4,10 a1.6,1.6 0 1 0 3.2,0 a1.6,1.6 0 1 0 -3.2,0 "
             "M4,17 L9.5,12.8 L13,15.5 L16,12.5 L20,16.5",
    "info": "M3,12 a9,9 0 1 0 18,0 a9,9 0 1 0 -18,0 M12,11 L12,16.5 M12,7.7 L12,7.71",
    "library": "M6,4 h2.5 a2,2 0 0 1 2,2 v2.5 a2,2 0 0 1 -2,2 h-2.5 a2,2 0 0 1 -2,-2 v-2.5 a2,2 0 0 1 2,-2 z "
               "M15.5,4 h2.5 a2,2 0 0 1 2,2 v2.5 a2,2 0 0 1 -2,2 h-2.5 a2,2 0 0 1 -2,-2 v-2.5 a2,2 0 0 1 2,-2 z "
               "M6,13.5 h2.5 a2,2 0 0 1 2,2 v2.5 a2,2 0 0 1 -2,2 h-2.5 a2,2 0 0 1 -2,-2 v-2.5 a2,2 0 0 1 2,-2 z "
               "M15.5,13.5 h2.5 a2,2 0 0 1 2,2 v2.5 a2,2 0 0 1 -2,2 h-2.5 a2,2 0 0 1 -2,-2 v-2.5 a2,2 0 0 1 2,-2 z",
    "plus": "M12,5 L12,19 M5,12 L19,12",
    "refresh": "M19,12 a7,7 0 1 1 -2.05,-4.95 M19,5 L19,9.5 L14.6,9.5",
    "settings": "M4,7 L6.4,7 M11.6,7 L20,7 M4,17 L12.4,17 M17.6,17 L20,17 M6.4,7 a2.6,2.6 0 1 0 5.2,0 "
                "a2.6,2.6 0 1 0 -5.2,0 M12.4,17 a2.6,2.6 0 1 0 5.2,0 a2.6,2.6 0 1 0 -5.2,0",
    "share": "M3.5,12 a2.5,2.5 0 1 0 5,0 a2.5,2.5 0 1 0 -5,0 M15,5.8 a2.5,2.5 0 1 0 5,0 a2.5,2.5 0 1 0 -5,0 "
             "M15,18.2 a2.5,2.5 0 1 0 5,0 a2.5,2.5 0 1 0 -5,0 M8.2,10.8 L15.3,7 M8.2,13.2 L15.3,17",
    "create": "M12,3 Q12.8,11.2 21,12 Q12.8,12.8 12,21 Q11.2,12.8 3,12 Q11.2,11.2 12,3 Z",
    "swap": "M4,8 L20,8 M16,4 L20,8 L16,12 M20,16 L4,16 M8,12 L4,16 L8,20",
    "trash": "M4,7 L20,7 M10,3.5 L14,3.5 M6,7 L7,20 L17,20 L18,7 M10,11 L10,16 M14,11 L14,16",
    "wand": "M5,19 L14,10 M17,3 Q17.4,6.6 21,7 Q17.4,7.4 17,11 Q16.6,7.4 13,7 Q16.6,6.6 17,3 Z",
    # --- desktop-only, drawn in the same style ---
    "folder": "M3.5,8 a2,2 0 0 1 2,-2 h3.8 l2,2.4 h7.2 a2,2 0 0 1 2,2 v7.1 a2,2 0 0 1 -2,2 h-13 a2,2 0 0 1 -2,-2 z",
    "copy": "M9.5,8.5 h8 a2,2 0 0 1 2,2 v8 a2,2 0 0 1 -2,2 h-8 a2,2 0 0 1 -2,-2 v-8 a2,2 0 0 1 2,-2 z "
            "M6.5,15.5 a2,2 0 0 1 -2,-2 v-8 a2,2 0 0 1 2,-2 h8 a2,2 0 0 1 2,2",
    "chevrons": "M9,7 L4,12 L9,17 M15,7 L20,12 L15,17",
}

_TOKEN = re.compile(r"([MmLlHhVvQqAaZz])|(-?\d*\.?\d+(?:e-?\d+)?)")


def _arc(p0, rx, ry, phi, fa, fs, p1, steps=48):
    """SVG endpoint arc -> list of points (excluding p0)."""
    x1, y1 = p0
    x2, y2 = p1
    if rx == 0 or ry == 0 or p0 == p1:
        return [p1]
    rx, ry = abs(rx), abs(ry)
    c, s = math.cos(math.radians(phi)), math.sin(math.radians(phi))
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = c * dx + s * dy, -s * dx + c * dy
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = math.sqrt(max(num / den, 0)) * (-1 if fa == fs else 1)
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx, cy = c * cxp - s * cyp + (x1 + x2) / 2, s * cxp + c * cyp + (y1 + y2) / 2

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dt > 0:
        dt -= 2 * math.pi
    elif fs and dt < 0:
        dt += 2 * math.pi
    pts = []
    for i in range(1, steps + 1):
        t = t1 + dt * i / steps
        ex, ey = rx * math.cos(t), ry * math.sin(t)
        pts.append((c * ex - s * ey + cx, s * ex + c * ey + cy))
    pts[-1] = p1
    return pts


def parse_path(d: str):
    """Return a list of polylines (list of (x, y)), closed paths repeat their first point."""
    toks = [(m.group(1), m.group(2)) for m in _TOKEN.finditer(d)]
    i, cmd, cur, start = 0, None, (0.0, 0.0), (0.0, 0.0)
    paths, poly = [], []

    def num():
        nonlocal i
        v = float(toks[i][1])
        i += 1
        return v

    while i < len(toks):
        if toks[i][0]:
            cmd = toks[i][0]
            i += 1
            if cmd in "Zz":
                if poly:
                    poly.append(start)
                    paths.append(poly)
                poly, cur = [], start
                continue
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = cur if rel else (0.0, 0.0)
        if c == "M":
            if poly:
                paths.append(poly)
            cur = (num() + ox, num() + oy)
            start, poly = cur, [cur]
            cmd = "l" if rel else "L"
        elif c == "L":
            cur = (num() + ox, num() + oy)
            poly.append(cur)
        elif c == "H":
            cur = (num() + ox, cur[1])
            poly.append(cur)
        elif c == "V":
            cur = (cur[0], num() + oy)
            poly.append(cur)
        elif c == "Q":
            cx, cy = num() + ox, num() + oy
            ex, ey = num() + ox, num() + oy
            p0 = cur
            for k in range(1, 25):
                t = k / 24
                poly.append(((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * cx + t * t * ex,
                             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * cy + t * t * ey))
            cur = (ex, ey)
        elif c == "A":
            rx, ry, phi, fa, fs = num(), num(), num(), int(num()), int(num())
            end = (num() + ox, num() + oy)
            poly.extend(_arc(cur, rx, ry, phi, fa, fs, end))
            cur = end
        else:
            raise ValueError(f"unsupported path command {cmd}")
    if poly:
        paths.append(poly)
    return paths


def render(name: str, px: int) -> Image.Image:
    """White RGBA icon, px x px, 1.9/24 stroke with round caps and joins."""
    big = px * SS
    k = big / VIEW
    img = Image.new("L", (big, big), 0)
    dr = ImageDraw.Draw(img)
    w = STROKE * k
    r = w / 2
    for poly in parse_path(ICON_PATHS[name]):
        pts = [(x * k, y * k) for x, y in poly]
        if len(pts) > 1:
            dr.line(pts, fill=255, width=int(round(w)), joint="curve")
        for x, y in pts:  # round caps + joins
            dr.ellipse((x - r, y - r, x + r, y + r), fill=255)
    img = img.resize((px, px), Image.LANCZOS)
    out = Image.new("RGBA", (px, px), (255, 255, 255, 0))
    out.putalpha(img)
    return out


@lru_cache(maxsize=256)
def icon_texture(name: str, px: int) -> Texture:
    im = render(name, px)
    # GL texture rows run bottom-up
    data = im.transpose(Image.FLIP_TOP_BOTTOM).tobytes()
    tex = Texture.create(size=(px, px), colorfmt="rgba")
    tex.blit_buffer(data, colorfmt="rgba", bufferfmt="ubyte")
    tex.mag_filter = tex.min_filter = "linear"
    return tex


def draw_icon(name: str, cx: float, cy: float, size: float, color, lw=None):
    """Draw icon centred at (cx, cy), `size` px square, tinted `color`. Call inside a canvas context."""
    if name not in ICON_PATHS:
        return
    px = max(int(round(size)), 8)
    tex = icon_texture(name, px)
    Color(*color)
    Rectangle(texture=tex, pos=(round(cx - px / 2), round(cy - px / 2)), size=(px, px))

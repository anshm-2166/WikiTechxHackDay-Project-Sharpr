"""Design tokens + live theme switching."""
from __future__ import annotations

import sys
import weakref
from pathlib import Path

from kivy.core.text import LabelBase
from kivy.event import EventDispatcher
from kivy.metrics import dp
from kivy.properties import BooleanProperty, NumericProperty, StringProperty

FONT_DIR = Path(__file__).resolve().parent.parent / "assets" / "fonts"

FONTS = {
    "Outfit": "outfit",
    "Plus Jakarta Sans": "plus_jakarta_sans",
    "Space Grotesk": "space_grotesk",
}

# accent -> (light primary, light deep, dark primary, dark deep, gradient end)
ACCENTS = {
    "Violet": ("#7C3AED", "#5B21B6", "#A78BFA", "#7C3AED", "#C026D3"),
    "Orchid": ("#C026D3", "#86198F", "#E879F9", "#C026D3", "#F472B6"),
    "Indigo": ("#4F46E5", "#3730A3", "#818CF8", "#4F46E5", "#7C3AED"),
    "Lilac": ("#9B7BF0", "#6D4FD1", "#C4B5FD", "#8B5CF6", "#E879F9"),
}

RADIUS = 24  # dp, card corner radius


def hex_rgba(h: str, a: float = 1.0) -> list:
    h = h.lstrip("#")
    return [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)] + [a]


def mix(c1, c2, t):
    return [c1[i] * (1 - t) + c2[i] * t for i in range(4)]


def alpha(c, a):
    return [c[0], c[1], c[2], a]


def register_fonts():
    for name, stem in FONTS.items():
        LabelBase.register(
            name=name,
            fn_regular=str(FONT_DIR / f"{stem}-Regular.ttf"),
            fn_bold=str(FONT_DIR / f"{stem}-Bold.ttf"),
        )


def system_is_dark() -> bool:
    if sys.platform == "win32":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                                r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize") as k:
                return winreg.QueryValueEx(k, "AppsUseLightTheme")[0] == 0
        except OSError:
            pass
    return False


class Theme(EventDispatcher):
    """Singleton. Widgets register with `theme.watch(w)` and implement `on_theme()`."""

    version = NumericProperty(0)
    font = StringProperty("Outfit")
    dark = BooleanProperty(False)

    def __init__(self):
        super().__init__()
        self._watchers = weakref.WeakSet()
        self.colors: dict = {}
        self.mode, self.amoled, self.accent = "system", False, "Violet"
        self.apply("system", False, "Violet", "Outfit")

    def watch(self, widget):
        self._watchers.add(widget)

    def apply(self, mode: str, amoled: bool, accent: str, font: str):
        self.mode, self.amoled, self.accent = mode, amoled, accent
        dark = system_is_dark() if mode == "system" else mode == "dark"
        lp, ld, dp_, dd, end = ACCENTS.get(accent, ACCENTS["Violet"])
        if dark:
            c = {
                "bg": "#000000" if amoled else "#0E0A1A",
                "surface": "#0A0714" if amoled else "#171127",
                "surface_alt": "#1A1330" if amoled else "#211936",
                "primary": dp_, "primary_deep": dd, "on_primary": "#FFFFFF",
                "text": "#F3EEFF", "text_muted": "#A59CC4", "outline": "#2E2548",
                "grad_a": dd, "grad_b": end,
                "danger": "#F87171", "warn": "#FBBF24", "ok": "#4ADE80",
            }
        else:
            c = {
                "bg": "#F7F4FF", "surface": "#FFFFFF", "surface_alt": "#EFEAFE",
                "primary": lp, "primary_deep": ld, "on_primary": "#FFFFFF",
                "text": "#1B1030", "text_muted": "#6B6385", "outline": "#E3DCF7",
                "grad_a": lp, "grad_b": end,
                "danger": "#DC2626", "warn": "#B45309", "ok": "#15803D",
            }
        self.colors = {k: hex_rgba(v) for k, v in c.items()}
        self.dark = dark
        self.font = font if font in FONTS else "Outfit"
        self.version += 1
        for w in list(self._watchers):
            try:
                w.on_theme()
            except Exception:
                pass

    def __getitem__(self, key: str) -> list:
        return self.colors[key]

    def radius(self, scale: float = 1.0) -> float:
        return dp(RADIUS * scale)


theme = Theme()

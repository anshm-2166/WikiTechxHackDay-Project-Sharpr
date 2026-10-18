"""Persistent user settings (JSON) and per-user data directory handling."""
from __future__ import annotations

import json
import os
import threading
from pathlib import Path

APP_NAME = "Sharpr"

DEFAULTS: dict = {
    # appearance
    "theme": "system",          # system | light | dark
    "amoled": False,
    "accent": "Violet",         # Violet | Orchid | Indigo | Lilac
    "font": "Outfit",           # Outfit | Plus Jakarta Sans | Space Grotesk
    # inference
    "engine": "auto",           # auto | gpu | cpu
    "cpu_fallback": True,
    "threads": 0,               # 0 = auto
    "tile": 0,                  # 0 = auto
    "fp16": False,
    "default_model": "fast",    # fast | photo | illustration
    "default_scale": 2,
    # guards
    "max_input_side": 2048,
    "strict_guard": False,
    "max_output_mp": 24,
    # output
    "out_format": "png",        # png | jpeg | webp
    "out_quality": 92,
    # models folder override ("" = auto-detect)
    "models_dir": "",
}


def data_dir() -> Path:
    """Per-user data directory (override with SHARPR_DATA)."""
    env = os.environ.get("SHARPR_DATA")
    if env:
        p = Path(env)
    else:
        base = os.environ.get("APPDATA") or str(Path.home() / ".config")
        p = Path(base) / APP_NAME
    p.mkdir(parents=True, exist_ok=True)
    return p


def project_root() -> Path:
    return Path(__file__).resolve().parent.parent


def find_models_dir(override: str = "") -> Path:
    """Resolve the folder holding the ncnn .param/.bin files.

    Order: explicit setting, SHARPR_MODELS env, desktop/models, then the
    shared Android assets folder (../app/src/main/assets/models).
    """
    candidates = []
    if override:
        candidates.append(Path(override))
    if os.environ.get("SHARPR_MODELS"):
        candidates.append(Path(os.environ["SHARPR_MODELS"]))
    root = project_root()
    candidates.append(root / "models")
    candidates.append(root.parent / "app" / "src" / "main" / "assets" / "models")
    for c in candidates:
        if (c / "realesrgan-x4plus.param").exists() or (c / "realesr-animevideov3-x2.param").exists():
            return c
    return candidates[0]


class Settings:
    """Dict-like settings store that auto-saves on change."""

    def __init__(self, path: Path | None = None):
        self.path = path or (data_dir() / "settings.json")
        self._lock = threading.Lock()
        self.values = dict(DEFAULTS)
        self.load()

    def load(self):
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            for k, v in raw.items():
                if k in DEFAULTS:
                    self.values[k] = v
        except (OSError, ValueError):
            pass

    def save(self):
        with self._lock:
            try:
                self.path.write_text(json.dumps(self.values, indent=2), encoding="utf-8")
            except OSError:
                pass

    def __getitem__(self, key):
        return self.values[key]

    def get(self, key, default=None):
        return self.values.get(key, default)

    def set(self, key, value):
        if key in DEFAULTS and self.values.get(key) != value:
            self.values[key] = value
            self.save()

    @property
    def models_dir(self) -> Path:
        return find_models_dir(self.values.get("models_dir", ""))

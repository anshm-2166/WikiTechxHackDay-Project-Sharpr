"""Persistent user settings (JSON) and per-user data directory handling."""
from __future__ import annotations

import json
import os
import sys
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


def model_search_paths(override: str = "", env: str | None = None, root: Path | None = None,
                       exe_dir: Path | None = None) -> list[Path]:
    """Folders searched for the ncnn model files, in priority order.

    1. `models_dir` setting, 2. SHARPR_MODELS env var, 3. desktop/models, 4. the shared Android assets
    (../app/src/main/assets/models) relative to the package (never the working directory), 5. the same two
    folders next to the executable when frozen.
    """
    root = root or project_root()
    env = os.environ.get("SHARPR_MODELS", "") if env is None else env
    out: list[Path] = []
    if override:
        out.append(Path(override))
    if env:
        out.append(Path(env))
    out += [root / "models", root.parent / "app" / "src" / "main" / "assets" / "models"]
    if exe_dir is None and getattr(sys, "frozen", False):
        exe_dir = Path(sys.executable).resolve().parent
    if exe_dir is not None:
        out += [exe_dir / "models", exe_dir.parent / "app" / "src" / "main" / "assets" / "models"]
    seen, unique = set(), []
    for p in out:
        if str(p) not in seen:
            seen.add(str(p))
            unique.append(p)
    return unique


def find_models_dir(override: str = "", paths: list[Path] | None = None) -> Path:
    """First search path that contains any model file (else the first path)."""
    paths = paths if paths is not None else model_search_paths(override)
    for c in paths:
        if (c / "realesrgan-x4plus.param").exists() or (c / "realesr-animevideov3-x4.param").exists():
            return c
    return paths[0]


def missing_models_message(paths: list[Path], needed: str) -> str:
    listing = "\n".join(f"  {p}" for p in paths)
    return (f"Sharpr could not find its AI model files ({needed}.param and {needed}.bin).\n\n"
            f"Folders searched:\n{listing}\n\n"
            "How to fix: copy the models folder (the .param and .bin files from app/src/main/assets/models) "
            "into desktop/models, or set the SHARPR_MODELS environment variable, or set \"models_dir\" in "
            "settings.json to the folder that contains them.")


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

    def search_paths(self) -> list[Path]:
        return model_search_paths(self.values.get("models_dir", ""))

    @property
    def models_dir(self) -> Path:
        return find_models_dir(paths=self.search_paths())

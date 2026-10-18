"""Native OS helpers: file dialogs, reveal in Explorer, clipboard."""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path
from typing import Optional

from .pipeline import EXTS


def _tk():
    import tkinter as tk
    root = tk.Tk()
    root.withdraw()
    try:
        root.attributes("-topmost", True)
    except Exception:
        pass
    return root


def pick_image() -> Optional[str]:
    try:
        from tkinter import filedialog
        root = _tk()
        patterns = " ".join(f"*{e}" for e in EXTS)
        path = filedialog.askopenfilename(
            title="Choose an image",
            filetypes=[("Images", patterns), ("All files", "*.*")])
        root.destroy()
        return path or None
    except Exception:
        return None


def save_as(default_name: str, ext: str) -> Optional[str]:
    try:
        from tkinter import filedialog
        root = _tk()
        path = filedialog.asksaveasfilename(
            title="Save upscaled image", initialfile=default_name, defaultextension=ext,
            filetypes=[("PNG", "*.png"), ("JPEG", "*.jpg *.jpeg"), ("WebP", "*.webp")])
        root.destroy()
        return path or None
    except Exception:
        return None


def reveal(path: Path):
    path = str(path)
    if sys.platform == "win32":
        subprocess.Popen(["explorer", "/select,", os.path.normpath(path)])
    elif sys.platform == "darwin":
        subprocess.Popen(["open", "-R", path])
    else:
        subprocess.Popen(["xdg-open", os.path.dirname(path)])


def copy_text(text: str):
    from kivy.core.clipboard import Clipboard
    Clipboard.copy(text)

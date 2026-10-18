"""On-disk library: JSON index + originals/ results/ thumbs/ folders."""
from __future__ import annotations

import json
import shutil
import threading
import time
import uuid
from pathlib import Path
from typing import Optional

import cv2
import numpy as np

from . import pipeline
from .settings import data_dir

THUMB_SIDE = 420


class Library:
    def __init__(self, root: Path | None = None):
        self.root = Path(root) if root else data_dir()
        for sub in ("originals", "results", "thumbs"):
            (self.root / sub).mkdir(parents=True, exist_ok=True)
        self.index_path = self.root / "library.json"
        self._lock = threading.RLock()
        self.items: list[dict] = []
        self._load()

    # --- persistence -------------------------------------------------
    def _load(self):
        try:
            data = json.loads(self.index_path.read_text(encoding="utf-8"))
            self.items = [i for i in data.get("items", []) if isinstance(i, dict) and "id" in i]
        except (OSError, ValueError):
            self.items = []

    def _save(self):
        tmp = self.index_path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"version": 1, "items": self.items}, indent=1), encoding="utf-8")
        tmp.replace(self.index_path)

    # --- access --------------------------------------------------------
    def all(self) -> list[dict]:
        with self._lock:
            return sorted(self.items, key=lambda i: i.get("created", 0), reverse=True)

    def get(self, item_id: str) -> Optional[dict]:
        with self._lock:
            return next((i for i in self.items if i["id"] == item_id), None)

    def path(self, item: dict, kind: str) -> Path:
        folder = {"original": "originals", "result": "results", "thumb": "thumbs"}[kind]
        return self.root / folder / item[kind]

    # --- mutation ------------------------------------------------------
    def _write_thumb(self, img: np.ndarray, name: str):
        h, w = img.shape[:2]
        f = THUMB_SIDE / max(h, w)
        small = img
        if f < 1:
            small = cv2.resize(img, (max(1, int(w * f)), max(1, int(h * f))), interpolation=cv2.INTER_AREA)
        if small.shape[2] == 4:
            a = small[:, :, 3:4].astype(np.float32) / 255
            small = (small[:, :, :3] * a + 255 * (1 - a)).astype(np.uint8)
        ok, buf = cv2.imencode(".jpg", small, [cv2.IMWRITE_JPEG_QUALITY, 88])
        if ok:
            (self.root / "thumbs" / name).write_bytes(buf.tobytes())

    def save_result(self, original_path, result: "pipeline.Result", fmt: str, quality: int,
                    name: str = "", replace_id: Optional[str] = None) -> dict:
        """Add a new item (copying the original), or replace the result of `replace_id`."""
        data, ext = pipeline.encode(result.image, fmt, quality)
        with self._lock:
            old = None
            if replace_id:
                item = self.get(replace_id)
                if item is None:
                    raise KeyError(replace_id)
                old = self.path(item, "result")
            else:
                item = {"id": uuid.uuid4().hex[:12], "created": time.time()}
                src = Path(original_path)
                orig_name = f"{item['id']}{src.suffix.lower() or '.png'}"
                shutil.copyfile(src, self.root / "originals" / orig_name)
                item["original"] = orig_name
                item["name"] = name or src.stem
            item_id = item["id"]
            res_name = f"{item_id}_{int(time.time() * 1000)}{ext}"
            (self.root / "results" / res_name).write_bytes(data)
            thumb_name = f"{item_id}_{int(time.time() * 1000)}.jpg"
            self._write_thumb(result.image, thumb_name)
            if old is not None:
                old.unlink(missing_ok=True)
                if item.get("thumb"):
                    self.path(item, "thumb").unlink(missing_ok=True)
            o = result.options
            item.update({
                "result": res_name, "thumb": thumb_name, "format": ext.lstrip("."),
                "orig_w": result.in_size[0], "orig_h": result.in_size[1],
                "new_w": result.out_size[0], "new_h": result.out_size[1],
                "scale": o.scale, "model": o.model,
                "backend": result.info.backend_label, "fell_back": result.info.fell_back,
                "seconds": round(result.seconds, 2), "options": o.to_dict(), "note": result.note,
            })
            if not replace_id:
                self.items.append(item)
            self._save()
            return item

    def delete(self, item_id: str):
        with self._lock:
            item = self.get(item_id)
            if not item:
                return
            for kind in ("original", "result", "thumb"):
                if kind in item:
                    self.path(item, kind).unlink(missing_ok=True)
            self.items = [i for i in self.items if i["id"] != item_id]
            self._save()

    def clear(self):
        with self._lock:
            for sub in ("originals", "results", "thumbs"):
                shutil.rmtree(self.root / sub, ignore_errors=True)
                (self.root / sub).mkdir(parents=True, exist_ok=True)
            self.items = []
            self._save()

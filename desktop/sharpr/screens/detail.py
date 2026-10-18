"""Detail screen: before/after comparison, actions and info."""
from __future__ import annotations

import shutil
import threading
import time
from pathlib import Path

import cv2
from kivy.clock import Clock
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout

from .. import dialogs, pipeline
from ..engine import MODEL_LABELS
from ..widgets import (Btn, Card, CompareView, Header, IconBtn, KV, Section, TwoPane, VBox, prepare_display,
                       texture_from_rgba)
from .base import BaseScreen


class DetailScreen(BaseScreen):
    key = "detail"

    def __init__(self, **kw):
        super().__init__(**kw)
        self.item = None
        self._token = 0
        root = BoxLayout(orientation="vertical", spacing=dp(12))
        self.del_btn = IconBtn("trash", "danger")
        self.header = Header("", "", on_back=lambda: self.app.back(), right=[self.del_btn])
        self.del_btn.bind(on_release=lambda *_: self.confirm_delete())
        root.add_widget(self.header)
        self.compare = CompareView()
        self.side = VBox(spacing=dp(16))
        self.pane = TwoPane([self.compare], [self.side], side_w=340, main_fill=True, phone="split", bottom=8)
        root.add_widget(self.pane)
        self.add_widget(root)
        self.setup_responsive(root, top=12, bottom=12)
        self.on_mode(self.app.mode)

    def on_mode(self, mode):
        self.pane.set_mode(mode)

    # data --------------------------------------------------------------------
    def on_show(self, item_id=None, **kw):
        item = self.app.library.get(item_id) if item_id else self.item
        if not item:
            self.app.go("library")
            return
        self.item = item
        self.header.title.text = item.get("name", "Result")
        self.header.subtitle.text = (f"{item['scale']}x, {MODEL_LABELS.get(item['model'], item['model'])}, "
                                     f"{item['new_w']} x {item['new_h']}")
        self._build_side()
        self.compare.before = self.compare.after = None
        self.compare.reset_view()
        self.compare.split = .5
        self._token += 1
        threading.Thread(target=self._load, args=(self._token, item), daemon=True).start()

    def _load(self, token, item):
        lib = self.app.library
        try:
            after = pipeline.load_image(lib.path(item, "result"))
            a_img = after.bgr
            if after.alpha is not None:
                import numpy as np
                a_img = np.dstack([a_img, after.alpha])
            a_rgba = prepare_display(a_img)
            before = pipeline.load_image(lib.path(item, "original")).bgr
            ah, aw = a_rgba.shape[:2]
            b = cv2.resize(before, (aw, ah), interpolation=cv2.INTER_LINEAR)
            b_rgba = prepare_display(b, max_side=max(aw, ah))
        except Exception as err:  # noqa: BLE001
            msg = f"Could not load images: {err}"
            Clock.schedule_once(lambda dt: self.app.toast(msg, "error"), 0)
            return

        def apply(dt):
            if token != self._token:
                return
            self.compare.after = texture_from_rgba(a_rgba)
            self.compare.before = texture_from_rgba(b_rgba)
        Clock.schedule_once(apply, 0)

    # sidebar -------------------------------------------------------------------
    def _kv(self, parent, key, value):
        parent.add_widget(KV(key, value))

    def _build_side(self):
        it = self.item
        s = self.side
        s.clear_widgets()
        acts = Card(auto_height=True, padding=dp(18), spacing=dp(10))
        acts.add_widget(Section("Actions"))
        for text, icon, kind, cb in [
            ("Edit", "edit", "primary", self.edit),
            ("Save as...", "save", "tonal", self.save_as),
            ("Reveal in Explorer", "folder", "tonal", self.reveal),
            ("Copy file path", "copy", "tonal", self.copy_path),
        ]:
            b = Btn(text, icon=icon, kind=kind)
            b.bind(on_release=lambda *_, cb=cb: cb())
            acts.add_widget(b)
        s.add_widget(acts)

        info = Card(auto_height=True, padding=dp(18), spacing=dp(10))
        info.add_widget(Section("Info"))
        o = it.get("options", {})
        self._kv(info, "Original", f"{it['orig_w']} x {it['orig_h']} px")
        self._kv(info, "Upscaled", f"{it['new_w']} x {it['new_h']} px  ({it['new_w'] * it['new_h'] / 1e6:.1f} MP)")
        self._kv(info, "Model", f"{MODEL_LABELS.get(it['model'], it['model'])}, {it['scale']}x")
        eng = it.get("backend", "?") + ("  (GPU failed, fell back)" if it.get("fell_back") else "")
        self._kv(info, "Engine", eng)
        self._kv(info, "Time", f"{it.get('seconds', 0):.1f} s")
        self._kv(info, "Settings", f"Denoise {o.get('denoise', 0)}%, Natural blend {o.get('blend', 0)}%, "
                                   f"Sharpen {o.get('sharpen', 0)}%, Grain {o.get('grain', 0)}%")
        self._kv(info, "Format", it.get("format", "").upper())
        self._kv(info, "Created", time.strftime("%Y-%m-%d %H:%M", time.localtime(it.get("created", 0))))
        if it.get("note"):
            self._kv(info, "Note", it["note"])
        s.add_widget(info)

    # actions ---------------------------------------------------------------------
    def _result_path(self) -> Path:
        return self.app.library.path(self.item, "result")

    def edit(self):
        self.app.push("edit", item_id=self.item["id"])

    def save_as(self):
        src = self._result_path()
        name = f"{self.item.get('name', 'image')}_x{self.item['scale']}{src.suffix}"
        dest = dialogs.save_as(name, src.suffix)
        if not dest:
            return
        try:
            dest_p = Path(dest)
            same = dest_p.suffix.lower().replace("jpeg", "jpg") == src.suffix.lower().replace("jpeg", "jpg")
            if same:
                shutil.copyfile(src, dest_p)
            else:
                ld = pipeline.load_image(src)
                img = ld.bgr
                if ld.alpha is not None:
                    import numpy as np
                    img = np.dstack([img, ld.alpha])
                fmt = {".jpg": "jpeg", ".jpeg": "jpeg", ".webp": "webp"}.get(dest_p.suffix.lower(), "png")
                data, _ = pipeline.encode(img, fmt, int(self.app.settings["out_quality"]))
                dest_p.write_bytes(data)
            self.app.toast(f"Saved {dest_p.name}")
        except Exception as e:  # noqa: BLE001
            self.app.dialog("Could not save", str(e))

    def reveal(self):
        dialogs.reveal(self._result_path())

    def copy_path(self):
        dialogs.copy_text(str(self._result_path()))
        self.app.toast("File path copied")

    def confirm_delete(self):
        def go():
            self.app.library.delete(self.item["id"])
            self.item = None
            self.app.toast("Deleted")
            self.app.go("library")
        self.app.dialog("Delete this upscale?", "The result and its saved original will be removed.",
                        [("Cancel", "tonal", None), ("Delete", "danger", go)])

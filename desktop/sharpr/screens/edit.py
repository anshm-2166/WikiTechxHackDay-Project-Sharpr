"""Edit screen: regenerate a library item from its original with new options."""
from __future__ import annotations

from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout

from .. import pipeline
from ..engine import MODEL_LABELS
from ..widgets import Btn, Card, Header, RoundedImage, SwitchRow, TwoPane, texture_from_bgr
from .base import BaseScreen
from .options import OptionsPanel


class EditScreen(BaseScreen):
    key = "edit"

    def __init__(self, **kw):
        super().__init__(**kw)
        self.item = None
        root = BoxLayout(orientation="vertical", spacing=dp(12))
        self.header = Header("Edit", "", on_back=lambda: self.app.back())
        root.add_widget(self.header)
        self.preview = RoundedImage(mode="contain")
        self.options = OptionsPanel()
        keep = Card(auto_height=True, padding=dp(18))
        self.keep = SwitchRow("Keep previous version", "Off replaces the current result.", True)
        keep.add_widget(self.keep)
        self.go_btn = Btn("Regenerate", icon="create", height=dp(54))
        self.go_btn.bind(on_release=lambda *_: self.start())
        self.pane = TwoPane([self.preview], [self.options, keep, self.go_btn], side_w=390,
                            main_fill=True, main_h=220, bottom=16)
        root.add_widget(self.pane)
        self.add_widget(root)
        self.setup_responsive(root, top=12, bottom=0)
        self.on_mode(self.app.mode)

    def on_mode(self, mode):
        self.pane.set_mode(mode)

    def on_show(self, item_id=None, **kw):
        item = self.app.library.get(item_id)
        if not item:
            self.app.go("library")
            return
        self.item = item
        self.header.title.text = "Edit"
        self.header.subtitle.text = (f"From the original, {item['orig_w']} x {item['orig_h']} px, "
                                     f"last: {MODEL_LABELS.get(item['model'], '')} {item['scale']}x")
        self.options.set_image_size((item["orig_w"], item["orig_h"]))
        self.options.set_options(pipeline.Options.from_dict(item.get("options", {})))
        self.keep.active = True
        try:
            self.preview.texture = texture_from_bgr(pipeline.load_image(self.app.library.path(item, "original")).bgr, 2048)
        except Exception:
            self.preview.texture = None

    def start(self):
        item = self.item
        lib, s = self.app.library, self.app.settings
        opts = self.options.get_options()
        keep = self.keep.active
        original = lib.path(item, "original")

        def work(progress, cancel):
            res = pipeline.process(original, opts, s, override=True, progress=progress, cancel=cancel)
            progress(1.0, res.info.tiles, res.info.tiles, res.info.backend_label, "Saving")
            saved = lib.save_result(original, res, s["out_format"], int(s["out_quality"]), name=item.get("name", ""),
                                    replace_id=None if keep else item["id"])
            return saved, res

        def done(payload):
            saved, res = payload
            self.app.toast(res.note or f"Done in {res.seconds:.1f}s on {res.info.backend_label}")
            self.app.pop_to_detail(saved["id"])

        self.app.run_job(work, done, title="Regenerating")

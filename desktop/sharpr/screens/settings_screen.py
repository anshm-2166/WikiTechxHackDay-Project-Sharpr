"""Settings screen."""
from __future__ import annotations

import os

from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout

from .. import engine
from ..guards import SCALES
from ..settings import data_dir
from ..theme import ACCENTS, FONTS
from ..widgets import Btn, Card, Header, KV, Scroll, Section, Segmented, SliderRow, Swatches, SwitchRow, T, VBox
from .base import BaseScreen


def field(title, hint, control):
    box = VBox(spacing=dp(6))
    box.add_widget(T(title, style="body", strong=True))
    if hint:
        box.add_widget(T(hint, style="small", tone="text_muted", wrap=True))
    box.add_widget(control)
    return box


class SettingsScreen(BaseScreen):
    key = "settings"

    def __init__(self, **kw):
        super().__init__(**kw)
        self.root_box = BoxLayout(orientation="vertical", padding=[dp(24), dp(16), dp(24), 0], spacing=dp(8))
        self.root_box.add_widget(Header("Settings", "Everything is stored locally"))
        self.scroll = Scroll(pad=(0, 8, 0, 8))
        self.root_box.add_widget(self.scroll)
        self.add_widget(self.root_box)
        self.setup_responsive(self.root_box, top=16, bottom=96, wide_max=760)
        self._built = False
        self._controls = []

    def on_show(self, **kw):
        if not self._built:
            self._build()
            self._built = True
        for widget, key, prop in self._controls:   # keep controls in step with the stored values
            value = self.app.settings[key]
            if getattr(widget, prop) != value:
                setattr(widget, prop, value)

    def _card(self, title):
        c = Card(auto_height=True, padding=dp(18), spacing=dp(16))
        c.add_widget(Section(title))
        self.scroll.box.add_widget(c)
        return c

    def _bind(self, widget, key, prop="value", after=None, cast=None):
        def cb(_, v):
            self.app.settings.set(key, cast(v) if cast else v)
            if after:
                after()
        widget.bind(**{prop: cb})
        self._controls.append((widget, key, prop))

    def _build(self):
        s = self.app.settings
        app = self.app

        # appearance ---------------------------------------------------------
        c = self._card("Appearance")
        w = Segmented([("system", "System"), ("light", "Light"), ("dark", "Dark")], s["theme"])
        self._bind(w, "theme", after=app.apply_theme)
        c.add_widget(field("Theme", "", w))
        w = SwitchRow("AMOLED black", "Pure black background in dark mode.", bool(s["amoled"]))
        self._bind(w, "amoled", "active", app.apply_theme)
        c.add_widget(w)
        w = Swatches(list(ACCENTS), s["accent"])
        self._bind(w, "accent", after=app.apply_theme)
        c.add_widget(field("Accent colour", "", w))
        w = Segmented([(f, f) for f in FONTS], s["font"])
        self._bind(w, "font", after=app.apply_theme)
        c.add_widget(field("Font", "", w))

        # engine ---------------------------------------------------------------
        c = self._card("Inference engine")
        w = Segmented([("auto", "Auto"), ("gpu", "GPU"), ("cpu", "CPU")], s["engine"])
        self._bind(w, "engine")
        n = engine.gpu_count()
        hint = f"Auto uses the Vulkan GPU and falls back to CPU. Detected: {engine.gpu_name() if n else 'no Vulkan GPU'}."
        c.add_widget(field("Backend", hint, w))
        w = SwitchRow("Fall back to CPU if GPU fails", "Recommended: some drivers return broken output.", bool(s["cpu_fallback"]))
        self._bind(w, "cpu_fallback", "active")
        c.add_widget(w)
        cores = os.cpu_count() or 4
        thread_opts = [0] + [t for t in (1, 2, 4, 8, 12, 16, 24, 32) if t <= cores]
        if cores not in thread_opts:
            thread_opts.append(cores)
        w = Segmented([(t, "Auto" if t == 0 else str(t)) for t in thread_opts], s["threads"] if s["threads"] in thread_opts else 0)
        self._bind(w, "threads")
        c.add_widget(field("CPU threads", f"{cores} logical cores available.", w))
        w = Segmented([(0, "Auto"), (64, "64"), (128, "128"), (200, "200"), (256, "256"), (400, "400")], s["tile"])
        self._bind(w, "tile")
        c.add_widget(field("Tile size", "Smaller tiles use less memory. Auto: 200 on GPU, 128 on CPU.", w))
        w = SwitchRow("FP16", "Half precision. Faster on many GPUs; disable if you see artefacts.", bool(s["fp16"]))
        self._bind(w, "fp16", "active")
        c.add_widget(w)

        # defaults -----------------------------------------------------------
        c = self._card("Defaults")
        w = Segmented([(m, engine.MODEL_LABELS[m]) for m in engine.MODELS], s["default_model"])
        self._bind(w, "default_model")
        c.add_widget(field("Default model", "", w))
        w = Segmented([(sc, f"{sc}x") for sc in SCALES], int(s["default_scale"]))
        self._bind(w, "default_scale")
        c.add_widget(field("Default scale", "1x enhances at the original size.", w))

        # guards -----------------------------------------------------------------
        c = self._card("Guards")
        w = SliderRow("Count as high-res from (longest side, px)", "Large images are only held back when they are also already sharp.", " px", 512, 8192, 64)
        w.value = int(s["max_input_side"])
        self._bind(w, "max_input_side", cast=int)
        c.add_widget(w)
        w = SwitchRow("Strict guard", "Images that are large AND already sharp can't be forced through. Soft large photos are never blocked", bool(s["strict_guard"]))
        self._bind(w, "strict_guard", "active")
        c.add_widget(w)
        w = SliderRow("Max output size", "Scale is lowered automatically to stay under this.", " MP", 4, 100, 1)
        w.value = int(s["max_output_mp"])
        self._bind(w, "max_output_mp", cast=int)
        c.add_widget(w)

        # output -------------------------------------------------------------------
        c = self._card("Output")
        w = Segmented([("png", "PNG"), ("jpeg", "JPEG"), ("webp", "WebP")], s["out_format"])
        self._bind(w, "out_format")
        c.add_widget(field("Format", "", w))
        w = SliderRow("Quality", "Used for JPEG and WebP.", "", 50, 100, 1)
        w.value = int(s["out_quality"])
        self._bind(w, "out_quality", cast=int)
        c.add_widget(w)

        # storage ------------------------------------------------------------------
        c = self._card("Storage")
        c.add_widget(KV("Library", data_dir()))
        c.add_widget(KV("Models", s.models_dir))
        b = Btn("Clear library", icon="trash", kind="danger", size_hint_x=None, width=dp(200))
        b.bind(on_release=lambda *_: self._confirm_clear())
        c.add_widget(b)

    def _confirm_clear(self):
        def go():
            self.app.library.clear()
            self.app.toast("Library cleared")
        self.app.dialog("Clear the whole library?", "All saved results and originals will be deleted. This cannot be undone.",
                        [("Cancel", "tonal", None), ("Clear", "danger", go)])

"""Shared options panel (Create + Edit)."""
from __future__ import annotations

from kivy.app import App

from .. import guards
from ..engine import MODEL_LABELS, MODELS
from ..pipeline import Options
from ..widgets import Card, Section, Segmented, SliderRow, Spacer, T

MODEL_BLURB = {
    "fast": "Quickest. Great for illustrations, screenshots and clean photos. Supports 1x to 4x.",
    "photo": "Ultra detail for real photographs. Slower, 4x network (2x is averaged down).",
    "illustration": "Tuned for anime, line art and flat colour. 4x network (2x is averaged down).",
}


class OptionsPanel(Card):
    def __init__(self, **kw):
        super().__init__(auto_height=True, **kw)
        s = App.get_running_app().settings
        self._size = None
        self._cb = None

        self.add_widget(Section("Model"))
        self.model = Segmented([(m, MODEL_LABELS[m]) for m in MODELS], s["default_model"])
        self.add_widget(self.model)
        self.blurb = T(MODEL_BLURB[self.model.value], style="small", tone="text_muted", wrap=True)
        self.add_widget(self.blurb)

        self.add_widget(Spacer(4))
        self.add_widget(Section("Scale"))
        self.scale = Segmented([(1, "1x"), (2, "2x"), (3, "3x"), (4, "4x")], int(s["default_scale"]))
        self.add_widget(self.scale)
        self.scale_hint = T("", style="small", tone="text_muted", wrap=True)
        self.add_widget(self.scale_hint)

        self.add_widget(Spacer(4))
        self.add_widget(Section("Fine tuning"))
        self.denoise = SliderRow("Denoise", "Clean up noise before upscaling.", "%")
        self.blend = SliderRow("Natural blend", "Mix in the plain upscale to tame invented texture.", "%")
        self.sharpen = SliderRow("Sharpen", "Unsharp mask on the result.", "%")
        self.grain = SliderRow("Grain", "Subtle film grain for a natural finish.", "%")
        for w in (self.denoise, self.blend, self.sharpen, self.grain):
            self.add_widget(w)

        self.model.bind(value=self._model_changed)
        self.scale.bind(value=self._changed)
        self._model_changed()

    # public ------------------------------------------------------------
    def bind_change(self, cb):
        self._cb = cb

    def get_options(self) -> Options:
        return Options(self.model.value, int(self.scale.value), int(self.denoise.value),
                       int(self.blend.value), int(self.sharpen.value), int(self.grain.value))

    def set_options(self, o: Options):
        self.model.value = o.model
        self.scale.value = o.scale
        self.denoise.value, self.blend.value = o.denoise, o.blend
        self.sharpen.value, self.grain.value = o.sharpen, o.grain
        self._model_changed()

    def set_image_size(self, size):
        self._size = size
        self._update_hint()

    # internals -------------------------------------------------------------
    def _model_changed(self, *_):
        m = self.model.value
        self.blurb.text = MODEL_BLURB.get(m, "")
        self.scale.disabled_values = [] if m == "fast" else [3]
        if m != "fast" and self.scale.value == 3:
            self.scale.value = 4
        self._changed()

    def _changed(self, *_):
        self._update_hint()
        if self._cb:
            self._cb()

    def _update_hint(self):
        if not self._size:
            self.scale_hint.text = ("3x is only available with the Fast model. 1x enhances at the original size."
                                    if self.model.value != "fast" else "1x enhances at the original size.")
            self.scale_hint.tone = "text_muted"
            return
        s = App.get_running_app().settings
        w, h = self._size
        plan = guards.plan_scale(w, h, int(self.scale.value), self.model.value, float(s["max_output_mp"]))
        if not plan.ok:
            self.scale_hint.text, self.scale_hint.tone = plan.message, "danger"
        else:
            sc = plan.scale
            text = (f"Enhanced at original size: {w} x {h}" if sc == 1
                    else f"Output: {w * sc} x {h * sc} px ({w * h * sc * sc / 1e6:.1f} MP)")
            if plan.clamped:
                text = plan.message + "\n" + text
            self.scale_hint.text = text
            self.scale_hint.tone = "warn" if plan.clamped else "text_muted"

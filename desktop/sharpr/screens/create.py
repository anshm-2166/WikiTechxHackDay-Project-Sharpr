"""Create screen: pick an image, tune options, upscale."""
from __future__ import annotations

from pathlib import Path

from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout

from .. import dialogs, guards, pipeline
from ..widgets import Btn, Card, Chip, Flow, Hero, RoundedImage, Spacer, T, TwoPane, VBox, texture_from_bgr
from .base import BaseScreen
from .options import OptionsPanel


class CreateScreen(BaseScreen):
    key = "create"

    def __init__(self, **kw):
        super().__init__(**kw)
        self.path = None
        self.loaded = None
        self.info = None
        self.override = False
        self.preview_tex = None

        root = BoxLayout(orientation="vertical")
        self.hero = Hero()
        self.hero_title = T("Make every pixel count", style="display", tone="on_primary", wrap=True)
        self.hero.add_widget(self.hero_title)
        self.hero.add_widget(T("On-device AI super-resolution. Your photos never leave this computer.",
                               style="body", tone="on_primary", wrap=True))
        self.stage = VBox(spacing=dp(14))
        self.options = OptionsPanel()
        self.go_btn = Btn("Upscale", icon="create", height=dp(54))
        self.go_btn.bind(on_release=lambda *_: self.start())
        self.pane = TwoPane([self.hero, self.stage], [self.options, self.go_btn], side_w=390, bottom=110)
        root.add_widget(self.pane)
        self.add_widget(root)
        self.setup_responsive(root, top=12)
        self.options.bind_change(self._refresh_state)
        self.on_mode(self.app.mode)
        self._render()

    def on_mode(self, mode):
        self.pane.set_mode(mode)
        if hasattr(self, "preview"):
            self.preview.height = dp(360 if mode == "wide" else 260)

    # state ---------------------------------------------------------------
    def pick(self):
        p = dialogs.pick_image()
        if p:
            self.load_path(p)

    def load_path(self, path):
        try:
            ld = pipeline.load_image(path)
        except Exception as e:  # noqa: BLE001
            self.app.dialog("Could not open image", f"{Path(path).name}\n{e}")
            return
        self.path, self.loaded, self.override = path, ld, False
        self.info = guards.analyze(ld.bgr)
        try:
            self.preview_tex = texture_from_bgr(ld.bgr, 2048)
        except Exception:
            self.preview_tex = None
        self.options.set_image_size((self.info.width, self.info.height))
        self._render()

    def _guard(self):
        s = self.app.settings
        return guards.check_input(self.info.width, self.info.height, int(s["max_input_side"]),
                                  bool(s["strict_guard"]), self.info.detail)

    # ui ----------------------------------------------------------------------
    def _render(self):
        st = self.stage
        st.clear_widgets()
        if not self.path:
            card = Card(auto_height=True, padding=dp(18), spacing=dp(12))
            card.add_widget(T("Choose an image", style="h1", wrap=True))
            card.add_widget(T("PNG, JPG, WebP or BMP. You can also drop a file anywhere on this window.",
                              style="body", tone="text_muted", wrap=True))
            b = Btn("Browse files", icon="folder", size_hint_x=None, width=dp(190))
            b.bind(on_release=lambda *_: self.pick())
            card.add_widget(Spacer(4))
            card.add_widget(b)
            st.add_widget(card)
            self._refresh_state()
            return

        self.preview = RoundedImage(texture=self.preview_tex, mode="contain", size_hint_y=None,
                                    height=dp(360 if self.app.mode == "wide" else 260))
        st.add_widget(self.preview)
        row = Flow()
        i = self.info
        for c in (Chip(f"{i.width} x {i.height}", "image"), Chip(f"{i.megapixels:.2f} MP"),
                  Chip(f"Sharpness: {i.sharpness_label}"), Chip(f"Noise: {i.noise_label}")):
            row.add_widget(c)
        st.add_widget(row)
        st.add_widget(T(Path(self.path).name, style="small", tone="text_muted"))

        g = self._guard()
        if g.ok and g.note:
            st.add_widget(T(g.note, style="small", tone="text_muted", wrap=True))
        if not g.ok:
            card = Card(auto_height=True, tone="surface_alt", radius_scale=.83, padding=dp(18), spacing=dp(12))
            card.add_widget(T(g.title, style="title", tone="warn", wrap=True))
            card.add_widget(T(g.message, style="body", tone="text_muted", wrap=True))
            if g.overridable and not self.override:
                b = Btn("Upscale anyway", kind="ghost", size_hint_x=None, width=dp(190))
                b.bind(on_release=lambda *_: self._override())
                card.add_widget(b)
            elif self.override:
                card.add_widget(T("Override enabled for this image.", style="small", tone="ok", wrap=True))
            st.add_widget(card)

        other = Btn("Choose another image", kind="ghost", size_hint_x=None, width=dp(230), icon="folder")
        other.bind(on_release=lambda *_: self.pick())
        st.add_widget(other)
        self._refresh_state()

    def _override(self):
        self.override = True
        self._render()

    def _refresh_state(self, *_):
        if not hasattr(self, "go_btn"):
            return
        if not self.path:
            self.go_btn.disabled = True
            return
        g = self._guard()
        s = self.app.settings
        plan = guards.plan_scale(self.info.width, self.info.height, int(self.options.scale.value),
                                 self.options.model.value, float(s["max_output_mp"]))
        self.go_btn.disabled = not (plan.ok and (g.ok or (g.overridable and self.override)))

    # run ---------------------------------------------------------------------
    def start(self):
        if not self.path or self.go_btn.disabled:
            return
        opts = self.options.get_options()
        path, loaded, override = self.path, self.loaded, self.override
        s, lib = self.app.settings, self.app.library

        def work(progress, cancel):
            res = pipeline.process(path, opts, s, override=override, progress=progress, cancel=cancel, loaded=loaded)
            progress(1.0, res.info.tiles, res.info.tiles, res.info.backend_label, "Saving")
            return lib.save_result(path, res, s["out_format"], int(s["out_quality"])), res

        def done(payload):
            item, res = payload
            notes = []
            if res.note:
                notes.append(res.note)
            if res.info.fell_back:
                notes.append("GPU failed, used CPU instead.")
            self.app.toast(" ".join(notes) or f"Done in {res.seconds:.1f}s on {res.info.backend_label}")
            self.app.push("detail", item_id=item["id"])

        self.app.run_job(work, done)

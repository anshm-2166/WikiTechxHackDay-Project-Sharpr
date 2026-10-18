"""Library screen: grid of past results."""
from __future__ import annotations

import cv2
import numpy as np
from kivy.metrics import dp
from kivy.uix.behaviors import ButtonBehavior
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.gridlayout import GridLayout

from ..engine import MODEL_LABELS
from ..widgets import (Btn, Card, Chip, Header, IconBtn, RoundedImage, Scroll, Skin, T,
                       texture_from_bgr)
from .base import BaseScreen


class Tile(Skin, ButtonBehavior, FloatLayout):
    def __init__(self, item, lib, on_open, on_delete, **kw):
        super().__init__(**kw)
        self.item = item
        tex = None
        try:
            img = cv2.imdecode(np.fromfile(str(lib.path(item, "thumb")), "uint8"), cv2.IMREAD_COLOR)
            tex = texture_from_bgr(img, 512)
        except Exception:
            pass
        self.img = RoundedImage(texture=tex, mode="cover", radius=18, pos_hint={"x": 0, "y": 0}, size_hint=(1, 1))
        self.add_widget(self.img)
        self.badge = Chip(f"{MODEL_LABELS.get(item.get('model'), '')} {item.get('scale', '?')}x".strip(), tone="primary")
        self.badge.label.tone = "on_primary"
        self.badge.pos = (self.x + dp(12), (self.y + self.height) - dp(44))
        self.add_widget(self.badge)
        self.delete = IconBtn("trash", "danger", size=32)
        self.add_widget(self.delete)
        self.caption = Chip(f"{item.get('new_w')} x {item.get('new_h')}",
                            tone="surface")
        self.add_widget(self.caption)
        self.bind(on_release=lambda *_: on_open(item))
        self.delete.bind(on_release=lambda *_: on_delete(item))
        self.bind(pos=self._lay, size=self._lay)
        self.init_skin()
        self._lay()

    def _lay(self, *_):
        self.badge.pos = (self.x + dp(12), (self.y + self.height) - dp(44))
        self.delete.pos = ((self.x + self.width) - dp(42), (self.y + self.height) - dp(42))
        self.caption.pos = (self.x + dp(12), self.y + dp(12))

    def paint(self):
        pass

    def on_touch_down(self, touch):
        return super().on_touch_down(touch)


class LibraryScreen(BaseScreen):
    key = "library"

    def __init__(self, **kw):
        super().__init__(**kw)
        root = BoxLayout(orientation="vertical", spacing=dp(8))
        self.header = Header("Library", "")
        root.add_widget(self.header)
        self.scroll = Scroll(pad=(0, 8, 0, 8))
        root.add_widget(self.scroll)
        self.add_widget(root)
        self.setup_responsive(root, top=16, bottom=96)
        self.scroll.bind(width=lambda *_: self._relayout())
        self._items = []

    def on_show(self, **kw):
        self._items = self.app.library.all()
        n = len(self._items)
        self.header.subtitle.text = f"{n} upscale{'s' if n != 1 else ''}" if n else "Nothing here yet"
        self._build()

    def _cols(self):
        if self.app.mode == "phone":
            return 2
        return max(1, int((self.scroll.width + dp(16)) // dp(356)))

    def _relayout(self):
        if not hasattr(self, "grid") or self.grid.parent is None:
            return
        cols = self._cols()
        gap = self.grid.spacing[0]
        w = max((self.scroll.width - gap * (cols - 1)) / cols, dp(120))
        self.grid.cols = cols
        self.grid.col_default_width = w
        self.grid.row_default_height = w * .8

    def _build(self):
        box = self.scroll.box
        box.clear_widgets()
        if not self._items:
            card = Card(auto_height=True, padding=dp(32), spacing=dp(12))
            card.add_widget(T("No upscales yet", style="h1"))
            card.add_widget(T("Everything you upscale is saved here, with its original, so you can tweak and "
                              "regenerate it later.", style="body", tone="text_muted", wrap=True))
            b = Btn("Create one", icon="create", size_hint_x=None, width=dp(180))
            b.bind(on_release=lambda *_: self.app.go("create"))
            card.add_widget(b)
            box.add_widget(card)
            return
        self.grid = GridLayout(cols=self._cols(), spacing=dp(16), size_hint_y=None,
                               row_force_default=True, col_force_default=True)
        self.grid.bind(minimum_height=self.grid.setter("height"))
        for it in self._items:
            self.grid.add_widget(Tile(it, self.app.library, self._open, self._confirm_delete))
        box.add_widget(self.grid)
        self._relayout()

    def _open(self, item):
        self.app.push("detail", item_id=item["id"])

    def _confirm_delete(self, item):
        def go():
            self.app.library.delete(item["id"])
            self.app.toast("Deleted")
            self.on_show()
        self.app.dialog("Delete this upscale?", "The result and its saved original will be removed.",
                        [("Cancel", "tonal", None), ("Delete", "danger", go)])

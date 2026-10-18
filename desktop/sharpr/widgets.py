"""Small themed widget kit drawn entirely with Kivy canvas instructions."""
from __future__ import annotations

import ctypes
import math

import cv2
import numpy as np
from kivy.animation import Animation
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.graphics import Color, Ellipse, Line, Mesh, Rectangle, RoundedRectangle
from kivy.graphics.texture import Texture
from kivy.metrics import dp, sp
from kivy.properties import (BooleanProperty, ListProperty, NumericProperty, ObjectProperty,
                             StringProperty)
from kivy.uix.behaviors import ButtonBehavior
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.label import Label
from kivy.uix.layout import Layout
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget

from .icons import draw_icon  # noqa: F401
from .theme import RADIUS, alpha, mix, theme

# ----------------------------------------------------------------------------
# helpers
# ----------------------------------------------------------------------------

_GRAD_CACHE: dict = {}


def gradient_texture(a, b):
    """Diagonal (top-left -> bottom-right) gradient texture."""
    key = (tuple(a), tuple(b))
    tex = _GRAD_CACHE.get(key)
    if tex is None:
        n = 32
        u = np.linspace(0, 1, n)[None, :]
        v = np.linspace(0, 1, n)[:, None]          # row 0 = bottom
        t = ((u + (1 - v)) / 2)[..., None]
        arr = np.array(a)[None, None, :] * (1 - t) + np.array(b)[None, None, :] * t
        data = (np.clip(arr, 0, 1) * 255).astype(np.uint8)
        tex = Texture.create(size=(n, n), colorfmt="rgba")
        tex.blit_buffer(data.tobytes(), colorfmt="rgba", bufferfmt="ubyte")
        tex.mag_filter = tex.min_filter = "linear"
        tex.wrap = "clamp_to_edge"
        if len(_GRAD_CACHE) > 24:
            _GRAD_CACHE.clear()
        _GRAD_CACHE[key] = tex
    return tex


def _max_tex() -> int:
    try:
        from kivy.graphics.opengl import GL_MAX_TEXTURE_SIZE, glGetIntegerv
        buf = (ctypes.c_int * 1)()
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, buf)
        return max(2048, min(int(buf[0]), 8192))
    except Exception:
        return 4096


_MAXTEX = None


def init_gl_limits():
    """Query the GL texture size limit (call from the main thread once a window exists)."""
    global _MAXTEX
    _MAXTEX = _max_tex()


def prepare_display(img: np.ndarray, max_side: int | None = None) -> np.ndarray:
    """Return an RGBA uint8 array (bottom-up, GL order), downscaled to fit textures. Thread-safe."""
    limit = max_side or _MAXTEX or 4096
    h, w = img.shape[:2]
    if max(h, w) > limit:
        f = limit / max(h, w)
        img = cv2.resize(img, (int(w * f), int(h * f)), interpolation=cv2.INTER_AREA)
    if img.ndim == 2:
        img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)
    if img.shape[2] == 4:
        rgba = cv2.cvtColor(img, cv2.COLOR_BGRA2RGBA)
    else:
        rgba = cv2.cvtColor(img, cv2.COLOR_BGR2RGBA)
    return np.ascontiguousarray(rgba[::-1])


def texture_from_rgba(rgba: np.ndarray) -> Texture:
    h, w = rgba.shape[:2]
    tex = Texture.create(size=(w, h), colorfmt="rgba")
    tex.blit_buffer(rgba.tobytes(), colorfmt="rgba", bufferfmt="ubyte")
    tex.mag_filter = tex.min_filter = "linear"
    return tex


def texture_from_bgr(img: np.ndarray, max_side: int | None = None) -> Texture:
    return texture_from_rgba(prepare_display(img, max_side))


def accent_text():
    """Readable accent colour for text on neutral surfaces."""
    return theme["primary"] if theme.dark else theme["primary_deep"]


# ----------------------------------------------------------------------------
# icons
# ----------------------------------------------------------------------------

ICON_SIZE = dp(22)   # uniform icon size for buttons and the nav bar


# ----------------------------------------------------------------------------
# base mixin + text
# ----------------------------------------------------------------------------

class Skin:
    """Mixin: owns canvas.before, repainting on pos/size/theme change."""

    def init_skin(self):
        theme.watch(self)
        self.bind(pos=self._skin_rp, size=self._skin_rp)
        self.repaint()

    def _skin_rp(self, *_):
        self.repaint()

    def on_theme(self):
        self.repaint()

    def repaint(self):
        c = self.canvas.before
        c.clear()
        with c:
            self.paint()

    def paint(self):
        pass


# One type scale for the whole app (sp). Nothing below 12.
#   display 34 bold | h1 = title 22 bold | title = heading 17 bold | body 15 | small = muted 14
#   label 13 bold | tiny = caption 12 | chip 12.5 bold | section 12 bold uppercase (see Section)
SIZES = {"display": 34, "h1": 22, "title": 17, "body": 15, "small": 14, "label": 13, "tiny": 12, "chip": 12.5}
BOLD_STYLES = {"display", "h1", "title", "label", "chip"}


class T(Label):
    """Themed text. style: display/h1/title/body/small/tiny. tone: theme colour key."""

    style = StringProperty("body")
    tone = StringProperty("text")
    wrap = BooleanProperty(False)
    fit = BooleanProperty(False)       # width follows text
    strong = BooleanProperty(False)

    def __init__(self, text="", style="body", tone="text", wrap=False, fit=False, strong=False, **kw):
        kw.setdefault("halign", "left")
        kw.setdefault("valign", "middle")
        kw.setdefault("size_hint_y", None)
        super().__init__(text=text, **kw)
        self.style, self.tone, self.wrap, self.fit, self.strong = style, tone, wrap, fit, strong
        self._fixed_h = "height" in kw
        if fit:
            self.size_hint_x = None
        self.shorten = not wrap and not fit
        self.shorten_from = "right"
        theme.watch(self)
        self.bind(style=self._restyle, tone=self._restyle, strong=self._restyle,
                  width=self._relayout, texture_size=self._relayout, wrap=self._relayout, fit=self._relayout)
        self._restyle()

    def _restyle(self, *_):
        self.font_size = sp(SIZES.get(self.style, 15))
        self.bold = self.strong or self.style in BOLD_STYLES
        self.font_name = theme.font
        c = theme[self.tone] if self.tone in theme.colors else theme["text"]
        self.color = c
        if not self.wrap and not self._fixed_h:
            self.height = self.font_size * 1.6
        self._relayout()

    def on_theme(self):
        self._restyle()

    def _relayout(self, *_):
        if self.wrap:
            self.text_size = (max(self.width, 1), None)
            self.height = max(self.texture_size[1], self.font_size * 1.4)
        else:
            self.text_size = (None if self.fit else max(self.width, 1), self.height)
        if self.fit:
            self.width = self.texture_size[0]


class Spacer(Widget):
    def __init__(self, h=12, **kw):
        super().__init__(size_hint_y=None, height=dp(h), **kw)


class HSpacer(Widget):
    pass


class VBox(BoxLayout):
    """Vertical box that sizes itself to its children."""

    def __init__(self, **kw):
        kw.setdefault("orientation", "vertical")
        kw.setdefault("spacing", dp(8))
        kw.setdefault("size_hint_y", None)
        super().__init__(**kw)
        self.bind(minimum_height=self.setter("height"))


class Header(BoxLayout):
    """Screen header: optional back button, title/subtitle, right-side widgets."""

    def __init__(self, title, subtitle="", on_back=None, right=(), **kw):
        kw.setdefault("size_hint_y", None)
        kw.setdefault("height", dp(64))
        kw.setdefault("spacing", dp(12))
        super().__init__(**kw)
        if on_back:
            b = IconBtn("back", "tonal")
            b.pos_hint = {"center_y": .5}
            b.bind(on_release=lambda *_: on_back())
            self.add_widget(b)
        col = BoxLayout(orientation="vertical")
        self.title = T(title, style="h1", height=dp(36))
        col.add_widget(self.title)
        self.subtitle = T(subtitle, style="small", tone="text_muted", height=dp(22))
        col.add_widget(self.subtitle)
        self.add_widget(col)
        for w in right:
            self.add_widget(w)


# ----------------------------------------------------------------------------
# containers
# ----------------------------------------------------------------------------

class Card(Skin, BoxLayout):
    """Rounded surface. tone: theme colour key; auto_height sizes to content."""

    tone = StringProperty("surface")
    radius_scale = NumericProperty(1.0)
    outlined = BooleanProperty(True)
    auto_height = BooleanProperty(False)

    def __init__(self, **kw):
        kw.setdefault("orientation", "vertical")
        kw.setdefault("padding", dp(18))
        kw.setdefault("spacing", dp(12))
        super().__init__(**kw)
        self.bind(tone=self._skin_rp, radius_scale=self._skin_rp, outlined=self._skin_rp)
        self.bind(auto_height=self._auto)
        self._auto()
        self.init_skin()

    def _auto(self, *_):
        if self.auto_height:
            self.size_hint_y = None
            self.bind(minimum_height=self._set_h)
            self._set_h()

    def _set_h(self, *_):
        self.height = self.minimum_height

    def paint(self):
        rad = dp(RADIUS) * self.radius_scale
        Color(*theme[self.tone])
        RoundedRectangle(pos=self.pos, size=self.size, radius=[rad])
        if self.outlined:
            Color(*theme["outline"])
            Line(rounded_rectangle=(self.x, self.y, self.width, self.height, rad), width=1)


class Hero(Skin, BoxLayout):
    """Violet -> fuchsia gradient card."""

    def __init__(self, **kw):
        kw.setdefault("orientation", "vertical")
        kw.setdefault("padding", dp(24))
        kw.setdefault("spacing", dp(6))
        kw.setdefault("size_hint_y", None)
        super().__init__(**kw)
        self.bind(minimum_height=self.setter("height"))
        self.init_skin()

    def paint(self):
        Color(1, 1, 1, 1)
        RoundedRectangle(pos=self.pos, size=self.size, radius=[dp(RADIUS + 4)],
                         texture=gradient_texture(theme["grad_a"], theme["grad_b"]))


class Scroll(ScrollView):
    """ScrollView with a themed bar and a vertical `box` child."""

    def __init__(self, max_content=0, pad=(0, 0, 0, 0), **kw):
        kw.setdefault("bar_width", dp(6))
        kw.setdefault("scroll_type", ["bars", "content"])
        kw.setdefault("do_scroll_x", False)
        kw.setdefault("scroll_wheel_distance", dp(60))
        super().__init__(**kw)
        self.max_content = dp(max_content)
        self.pad = [dp(v) for v in pad]
        self.box = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(16))
        self.box.bind(minimum_height=self.box.setter("height"))
        self.add_widget(self.box)
        self.bind(width=self._pad)
        self._pad()
        theme.watch(self)
        self.on_theme()

    def _pad(self, *_):
        l, t, r, b = self.pad
        if self.max_content:
            extra = max((self.width - self.max_content) / 2, 0)
            l, r = l + extra, r + extra
        self.box.padding = [l, t, r, b]

    def on_theme(self):
        self.bar_color = alpha(theme["primary"], .55)
        self.bar_inactive_color = alpha(theme["primary"], .22)


def corner_masks(x, y, w, h, r, color, steps=8):
    """Paint the area outside rounded corners of a rect with `color` (hides square image corners)."""
    r = min(r, w / 2, h / 2)
    if r < 1:
        return
    Color(*color)
    # (apex, arc centre, start angle) for TL, TR, BR, BL
    corners = [((x, y + h), (x + r, y + h - r), 180), ((x + w, y + h), (x + w - r, y + h - r), 90),
               ((x + w, y), (x + w - r, y + r), 0), ((x, y), (x + r, y + r), 270)]
    for apex, c, a0 in corners:
        verts = [apex[0], apex[1], 0, 0]
        for i in range(steps + 1):
            ang = math.radians(a0 - 90 * i / steps)
            verts += [c[0] + r * math.cos(ang), c[1] + r * math.sin(ang), 0, 0]
        Mesh(vertices=verts, indices=list(range(steps + 2)), mode="triangle_fan")


class RoundedImage(Skin, Widget):
    """Image with rounded corners. mode: cover | contain. `backdrop` is the colour behind it."""

    texture = ObjectProperty(None, allownone=True)
    mode = StringProperty("cover")
    radius = NumericProperty(RADIUS)
    backdrop = StringProperty("bg")

    def __init__(self, **kw):
        super().__init__(**kw)
        self.bind(texture=self._skin_rp, mode=self._skin_rp, radius=self._skin_rp)
        self.init_skin()

    def paint(self):
        rad = dp(self.radius)
        contain = self.mode == "contain"
        Color(*theme["surface_alt"])
        RoundedRectangle(pos=self.pos, size=self.size, radius=[rad])
        t = self.texture
        if not t or self.width < 2 or self.height < 2:
            return
        tw, th = t.size
        Color(1, 1, 1, 1)
        if contain:
            f = min(self.width / tw, self.height / th)
            w, h = tw * f, th * f
            x, y = (self.x + self.width / 2) - w / 2, (self.y + self.height / 2) - h / 2
            Rectangle(pos=(x, y), size=(w, h), texture=t)
            corner_masks(x, y, w, h, rad, theme["surface_alt"])
        else:
            f = max(self.width / tw, self.height / th)
            vw, vh = self.width / (tw * f), self.height / (th * f)
            u0, v0 = (1 - vw) / 2, (1 - vh) / 2
            Rectangle(pos=self.pos, size=self.size, texture=t,
                      tex_coords=(u0, v0, u0 + vw, v0, u0 + vw, v0 + vh, u0, v0 + vh))
            corner_masks(self.x, self.y, self.width, self.height, rad, theme[self.backdrop])


# ----------------------------------------------------------------------------
# buttons
# ----------------------------------------------------------------------------

class Btn(Skin, ButtonBehavior, FloatLayout):
    """Pill button. kind: primary | tonal | ghost | danger."""

    text = StringProperty("")
    icon = StringProperty("")
    kind = StringProperty("primary")

    def __init__(self, text="", icon="", kind="primary", **kw):
        kw.setdefault("size_hint_y", None)
        kw.setdefault("height", dp(48))
        super().__init__(**kw)
        self.text, self.icon, self.kind = text, icon, kind
        self.label = T(text, style="body", strong=True, fit=True, halign="center")
        self.add_widget(self.label)
        self.bind(text=self._on_text, icon=self._skin_rp, kind=self._skin_rp, state=self._skin_rp,
                  disabled=self._on_disabled)
        self.label.bind(texture_size=self._skin_rp)
        if "size_hint_x" not in kw and "width" not in kw:
            pass
        self._on_text()
        self.init_skin()

    def _on_text(self, *_):
        self.label.text = self.text
        self._skin_rp()

    def _on_disabled(self, *_):
        self.opacity = .45 if self.disabled else 1
        self._skin_rp()

    def _colors(self):
        k = self.kind
        if k == "primary":
            return None, theme["on_primary"]
        if k == "tonal":
            return theme["surface_alt"], accent_text()
        if k == "danger":
            return alpha(theme["danger"], .14), theme["danger"]
        return None, theme["text"]  # ghost

    def paint(self):
        bg, fg = self._colors()
        rad = self.height / 2
        if self.kind == "primary":
            Color(1, 1, 1, 1)
            RoundedRectangle(pos=self.pos, size=self.size, radius=[rad],
                             texture=gradient_texture(theme["grad_a"], theme["grad_b"]))
        elif bg:
            Color(*bg)
            RoundedRectangle(pos=self.pos, size=self.size, radius=[rad])
        if self.kind == "ghost":
            Color(*theme["outline"])
            Line(rounded_rectangle=(self.x, self.y, self.width, self.height, rad), width=1.2)
        if self.state == "down":
            Color(0, 0, 0, .14) if not theme.dark else Color(1, 1, 1, .12)
            RoundedRectangle(pos=self.pos, size=self.size, radius=[rad])
        # content: icon + label
        isz = ICON_SIZE
        has_icon, has_text = bool(self.icon), bool(self.text)
        self.label.color = fg
        tw = self.label.texture_size[0] if has_text else 0
        total = (isz if has_icon else 0) + (dp(8) if has_icon and has_text else 0) + tw
        x = (self.x + self.width / 2) - total / 2
        if has_icon:
            draw_icon(self.icon, x + isz / 2, (self.y + self.height / 2), isz, fg)
            x += isz + (dp(8) if has_text else 0)
        self.label.opacity = 1 if has_text else 0
        self.label.pos = (round(x), round(self.y + self.height / 2 - self.label.height / 2))


class IconBtn(Btn):
    def __init__(self, icon="", kind="tonal", size=44, **kw):
        kw.update(size_hint=(None, None), size=(dp(size), dp(size)))
        super().__init__("", icon, kind, **kw)


# ----------------------------------------------------------------------------
# inputs
# ----------------------------------------------------------------------------

class Segmented(Skin, BoxLayout):
    """Pill-shaped segmented control. options: [(value, label), ...]."""

    options = ListProperty()
    value = ObjectProperty(None, allownone=True)
    disabled_values = ListProperty()
    ind = NumericProperty(0.0)

    def __init__(self, options=None, value=None, **kw):
        kw.setdefault("size_hint_y", None)
        kw.setdefault("height", dp(46))
        kw.setdefault("padding", dp(4))
        super().__init__(**kw)
        self._labels = []
        self.bind(options=self._build, value=self._on_value, disabled_values=self._recolor,
                  ind=self._skin_rp, width=self._fit)
        if options:
            self.options = options
        if value is not None:
            self.value = value
        self.init_skin()

    def _index(self):
        for i, (v, _) in enumerate(self.options):
            if v == self.value:
                return i
        return 0

    def _build(self, *_):
        self.clear_widgets()
        self._labels = []
        for v, text in self.options:
            lab = T(text, style="label", halign="center", size_hint_y=1)
            self._labels.append(lab)
            self.add_widget(lab)
        self.ind = self._index()
        self._recolor()
        self._skin_rp()

    def _on_value(self, *_):
        Animation.cancel_all(self, "ind")
        Animation(ind=self._index(), d=.16, t="out_quad").start(self)
        self._recolor()

    def _recolor(self, *_):
        for i, ((v, _), lab) in enumerate(zip(self.options, self._labels)):
            lab.tone = "on_primary" if v == self.value else "text_muted"
            lab.opacity = .35 if v in self.disabled_values else 1
        self._fit()

    def _fit(self, *_):
        """Shrink labels that would not fit their cell."""
        n = max(len(self.options), 1)
        avail = (self.width - 2 * self.padding[0]) / n - dp(10)
        if avail <= 0:
            return
        base = sp(SIZES["label"])
        from kivy.core.text import Label as CoreLabel
        meter = CoreLabel(font_name=theme.font, font_size=base, bold=True)
        for lab in self._labels:
            w = meter.get_extents(lab.text)[0]
            lab.font_size = max(base * avail / w, sp(12)) if w > avail else base

    def on_theme(self):
        self._recolor()
        self.repaint()

    def paint(self):
        rad = self.height / 2
        Color(*theme["surface_alt"])
        RoundedRectangle(pos=self.pos, size=self.size, radius=[rad])
        n = max(len(self.options), 1)
        pad = self.padding[0]
        cw = (self.width - 2 * pad) / n
        Color(1, 1, 1, 1)
        RoundedRectangle(pos=(self.x + pad + self.ind * cw, self.y + pad), size=(cw, self.height - 2 * pad),
                         radius=[(self.height - 2 * pad) / 2],
                         texture=gradient_texture(theme["grad_a"], theme["grad_b"]))

    def on_touch_down(self, touch):
        if not self.collide_point(*touch.pos) or self.disabled:
            return super().on_touch_down(touch)
        n = max(len(self.options), 1)
        pad = self.padding[0]
        i = int((touch.x - self.x - pad) / max((self.width - 2 * pad) / n, 1))
        i = min(max(i, 0), n - 1)
        v = self.options[i][0]
        if v not in self.disabled_values:
            self.value = v
        return True


class Slide(Skin, Widget):
    """Horizontal slider with a pill track and round thumb."""

    value = NumericProperty(0)
    min = NumericProperty(0)
    max = NumericProperty(100)
    step = NumericProperty(1)

    def __init__(self, **kw):
        kw.setdefault("size_hint_y", None)
        kw.setdefault("height", dp(30))
        super().__init__(**kw)
        self.bind(value=self._skin_rp, min=self._skin_rp, max=self._skin_rp)
        self.init_skin()

    def _frac(self):
        return (self.value - self.min) / max(self.max - self.min, 1e-9)

    def paint(self):
        th = dp(6)
        r = dp(11)
        x0, x1 = self.x + r, (self.x + self.width) - r
        cy = (self.y + self.height / 2)
        Color(*theme["surface_alt"])
        RoundedRectangle(pos=(x0 - r / 2, cy - th / 2), size=(x1 - x0 + r, th), radius=[th / 2])
        xv = x0 + (x1 - x0) * self._frac()
        Color(1, 1, 1, 1)
        RoundedRectangle(pos=(x0 - r / 2, cy - th / 2), size=(max(xv - x0 + r / 2, th), th), radius=[th / 2],
                         texture=gradient_texture(theme["grad_a"], theme["grad_b"]))
        Color(0, 0, 0, .12)
        Ellipse(pos=(xv - r, cy - r - dp(1.5)), size=(2 * r, 2 * r))
        Color(1, 1, 1, 1)
        Ellipse(pos=(xv - r, cy - r), size=(2 * r, 2 * r))
        Color(*theme["primary"])
        Line(circle=(xv, cy, r - dp(1)), width=dp(2))

    def _set_from_x(self, x):
        r = dp(11)
        f = min(max((x - self.x - r) / max(self.width - 2 * r, 1), 0), 1)
        v = self.min + f * (self.max - self.min)
        if self.step:
            v = round(v / self.step) * self.step
        self.value = v

    def on_touch_down(self, touch):
        if self.disabled or not self.collide_point(*touch.pos):
            return super().on_touch_down(touch)
        touch.grab(self)
        self._set_from_x(touch.x)
        return True

    def on_touch_move(self, touch):
        if touch.grab_current is self:
            self._set_from_x(touch.x)
            return True
        return super().on_touch_move(touch)

    def on_touch_up(self, touch):
        if touch.grab_current is self:
            touch.ungrab(self)
            return True
        return super().on_touch_up(touch)


class SliderRow(VBox):
    """Title + value readout + slider (+ wrapped hint)."""

    value = NumericProperty(0)

    def __init__(self, title, hint="", suffix="", lo=0, hi=100, step=1, **kw):
        kw.setdefault("spacing", dp(2))
        super().__init__(**kw)
        self.suffix = suffix
        head = BoxLayout(size_hint_y=None, height=dp(24))
        head.add_widget(T(title, style="body", strong=True))
        self.readout = T("0", style="small", tone="text_muted", halign="right", fit=True)
        head.add_widget(self.readout)
        self.add_widget(head)
        self.slider = Slide(min=lo, max=hi, step=step)
        self.add_widget(self.slider)
        if hint:
            self.add_widget(T(hint, style="tiny", tone="text_muted", wrap=True))
        self.slider.bind(value=self._from_slider)
        self.bind(value=self._to_slider)

    def _from_slider(self, _, v):
        self.value = v
        self.readout.text = f"{int(v)}{self.suffix}"

    def _to_slider(self, _, v):
        self.slider.value = v
        self.readout.text = f"{int(v)}{self.suffix}"


class PillSwitch(Skin, ButtonBehavior, Widget):
    active = BooleanProperty(False)
    kx = NumericProperty(0.0)

    def __init__(self, active=False, **kw):
        kw.setdefault("size_hint", (None, None))
        kw.setdefault("size", (dp(52), dp(30)))
        super().__init__(**kw)
        self.active = active
        self.kx = 1.0 if active else 0.0
        self.bind(active=self._anim, kx=self._skin_rp)
        self.init_skin()

    def _anim(self, *_):
        Animation(kx=1.0 if self.active else 0.0, d=.14, t="out_quad").start(self)

    def on_release(self):
        self.active = not self.active

    def paint(self):
        r = self.height / 2
        Color(*mix(theme["outline"], theme["primary"], self.kx))
        RoundedRectangle(pos=self.pos, size=self.size, radius=[r])
        kr = r - dp(4)
        cx = self.x + r + (self.width - 2 * r) * self.kx
        Color(1, 1, 1, 1)
        Ellipse(pos=(cx - kr, (self.y + self.height / 2) - kr), size=(2 * kr, 2 * kr))


class SwitchRow(BoxLayout):
    active = BooleanProperty(False)

    def __init__(self, title, subtitle="", active=False, **kw):
        kw.setdefault("size_hint_y", None)
        kw.setdefault("spacing", dp(8))
        super().__init__(**kw)
        col = VBox(spacing=dp(2))
        col.add_widget(T(title, style="body", strong=True, height=dp(24)))
        if subtitle:
            col.add_widget(T(subtitle, style="tiny", tone="text_muted", wrap=True))
        self.add_widget(col)
        self.sw = PillSwitch(active)
        holder = FloatLayout(size_hint_x=None, width=dp(56))
        self.sw.pos_hint = {"right": 1, "center_y": .5}
        holder.add_widget(self.sw)
        self.add_widget(holder)
        col.bind(height=lambda *_: setattr(self, "height", max(dp(40), col.height)))
        self.height = dp(40)
        self.active = active
        self.sw.bind(active=lambda _, v: setattr(self, "active", v))
        self.bind(active=lambda _, v: setattr(self.sw, "active", v))


class Chip(Skin, BoxLayout):
    """Pill that hugs its text (12dp horizontal / 6dp vertical padding)."""

    text = StringProperty("")
    icon = StringProperty("")
    tone = StringProperty("surface_alt")

    def __init__(self, text="", icon="", tone="surface_alt", **kw):
        kw.setdefault("size_hint", (None, None))
        super().__init__(**kw)
        self.label = T(text, style="chip", fit=True, tone="text", halign="center")
        self.add_widget(self.label)
        self.text, self.icon, self.tone = text, icon, tone
        self.bind(text=self._sync, icon=self._sync, tone=self._skin_rp)
        self.label.bind(texture_size=self._sync)
        self._sync()
        self.init_skin()

    def _sync(self, *_):
        self.label.text = self.text
        lead = dp(12) + (dp(22) if self.icon else 0)
        self.padding = [lead, dp(6), dp(12), dp(6)]
        self.width = lead + dp(12) + self.label.texture_size[0]
        self.height = max(dp(28), self.label.height + dp(12))
        self._skin_rp()

    def paint(self):
        Color(*theme[self.tone])
        RoundedRectangle(pos=self.pos, size=self.size, radius=[self.height / 2])
        if self.icon:
            draw_icon(self.icon, self.x + dp(12) + dp(8), self.y + self.height / 2, dp(16), accent_text())


_SECTION_CACHE: dict = {}


def _section_texture(text: str, font_path: str, px: int):
    key = (text, font_path, px)
    hit = _SECTION_CACHE.get(key)
    if hit is None:
        from PIL import Image, ImageDraw, ImageFont
        font = ImageFont.truetype(font_path, px)
        gap = 0.1 * px                                   # 0.1em letter-spacing
        width = int(math.ceil(sum(font.getlength(c) for c in text) + gap * (len(text) - 1))) + 2
        asc, desc = font.getmetrics()
        im = Image.new("L", (width, asc + desc), 0)
        d = ImageDraw.Draw(im)
        x = 0.0
        for c in text:
            d.text((x, 0), c, font=font, fill=255)
            x += font.getlength(c) + gap
        rgba = Image.new("RGBA", im.size, (255, 255, 255, 0))
        rgba.putalpha(im)
        tex = Texture.create(size=im.size, colorfmt="rgba")
        tex.blit_buffer(rgba.transpose(Image.FLIP_TOP_BOTTOM).tobytes(), colorfmt="rgba", bufferfmt="ubyte")
        tex.mag_filter = tex.min_filter = "linear"
        hit = (tex, im.size[0], im.size[1])
        if len(_SECTION_CACHE) > 200:
            _SECTION_CACHE.clear()
        _SECTION_CACHE[key] = hit
    return hit


class Section(Widget):
    """Section label: 12 bold, UPPERCASE, 0.1em letter-spacing, accent colour."""

    def __init__(self, text, **kw):
        kw.setdefault("size_hint", (None, None))
        super().__init__(**kw)
        self.text = text.upper()
        theme.watch(self)
        self.bind(pos=self._paint, size=self._paint)
        self._refresh()

    def _refresh(self):
        from .theme import FONT_DIR, FONTS
        path = str(FONT_DIR / f"{FONTS[theme.font]}-Bold.ttf")
        self._tex, w, h = _section_texture(self.text, path, max(int(round(sp(12))), 12))
        self.size = (w, h + dp(8))
        self._paint()

    def on_theme(self):
        self._refresh()

    def _paint(self, *_):
        self.canvas.clear()
        if not getattr(self, "_tex", None):
            return
        with self.canvas:
            Color(*accent_text())
            w, h = self._tex.size
            Rectangle(texture=self._tex, pos=(round(self.x), round(self.y + (self.height - h) / 2)), size=(w, h))


class KV(BoxLayout):
    """Aligned two-column row: fixed-width muted key, wrapping value."""

    def __init__(self, key, value, key_w=92, **kw):
        kw.setdefault("size_hint_y", None)
        kw.setdefault("spacing", dp(12))
        super().__init__(**kw)
        self.add_widget(T(key, style="small", tone="text_muted", size_hint=(None, 1), width=dp(key_w), valign="top"))
        self.v = T(str(value), style="small", wrap=True)
        self.add_widget(self.v)
        self.v.bind(height=self._h)
        self._h()

    def _h(self, *_):
        self.height = self.v.height


class Swatches(Skin, Widget):
    """Row of accent colour circles."""

    options = ListProperty()
    value = StringProperty("")

    def __init__(self, options, value, **kw):
        kw.setdefault("size_hint_y", None)
        kw.setdefault("height", dp(48))
        super().__init__(**kw)
        self.options = options
        self.value = value
        self.bind(value=self._skin_rp)
        self.init_skin()

    def _cells(self):
        d = dp(40)
        return [(self.x + i * (d + dp(14)), (self.y + self.height / 2) - d / 2, d) for i in range(len(self.options))]

    def paint(self):
        from .theme import ACCENTS, hex_rgba
        for (x, y, d), name in zip(self._cells(), self.options):
            a = ACCENTS[name]
            Color(1, 1, 1, 1)
            Ellipse(pos=(x, y), size=(d, d), texture=gradient_texture(hex_rgba(a[0] if not theme.dark else a[2]), hex_rgba(a[4])))
            if name == self.value:
                Color(*theme["text"])
                Line(circle=(x + d / 2, y + d / 2, d / 2 + dp(4)), width=dp(2))
                draw_icon("check", x + d / 2, y + d / 2, dp(22), (1, 1, 1, 1))

    def on_touch_down(self, touch):
        if not self.collide_point(*touch.pos):
            return super().on_touch_down(touch)
        for (x, y, d), name in zip(self._cells(), self.options):
            if x <= touch.x <= x + d:
                self.value = name
                return True
        return True


class Bar(Skin, Widget):
    value = NumericProperty(0.0)

    def __init__(self, **kw):
        kw.setdefault("size_hint_y", None)
        kw.setdefault("height", dp(10))
        super().__init__(**kw)
        self.bind(value=self._skin_rp)
        self.init_skin()

    def paint(self):
        r = self.height / 2
        Color(*theme["surface_alt"])
        RoundedRectangle(pos=self.pos, size=self.size, radius=[r])
        w = max(self.width * min(max(self.value, 0), 1), self.height)
        Color(1, 1, 1, 1)
        RoundedRectangle(pos=self.pos, size=(w, self.height), radius=[r],
                         texture=gradient_texture(theme["grad_a"], theme["grad_b"]))


# ----------------------------------------------------------------------------
# navigation bar
# ----------------------------------------------------------------------------

class NavBar(Skin, FloatLayout):
    """Floating pill navigation bar."""

    current = StringProperty("create")
    ind = NumericProperty(0.0)

    ITEM_W = 124

    def __init__(self, items, on_select=None, **kw):
        kw.setdefault("size_hint", (None, None))
        kw.setdefault("size", (dp(self.ITEM_W * len(items) + 16), dp(64)))
        super().__init__(**kw)
        self.items = items          # [(key, label, icon)]
        self.on_select = on_select
        self.labels = []
        for key, label, icon in items:
            lab = T(label, style="label", fit=True, halign="center")
            self.labels.append(lab)
            self.add_widget(lab)
        self.bind(current=self._cur, ind=self._skin_rp)
        self.ind = self._idx()
        self.init_skin()

    def _idx(self):
        for i, (k, *_r) in enumerate(self.items):
            if k == self.current:
                return i
        return 0

    def _cur(self, *_):
        Animation.cancel_all(self, "ind")
        Animation(ind=self._idx(), d=.2, t="out_cubic").start(self)

    def _cell(self, i):
        w = (self.width - dp(16)) / len(self.items)
        return self.x + dp(8) + i * w, w

    def paint(self):
        r = self.height / 2
        for k in range(3):  # soft shadow
            Color(0, 0, 0, .05 if not theme.dark else .12)
            RoundedRectangle(pos=(self.x - k * 2, self.y - dp(3) - k * 2), size=(self.width + k * 4, self.height + k * 4),
                             radius=[r + k * 2])
        Color(*theme["surface"])
        RoundedRectangle(pos=self.pos, size=self.size, radius=[r])
        Color(*theme["outline"])
        Line(rounded_rectangle=(self.x, self.y, self.width, self.height, r), width=1)
        x0, w = self._cell(0)
        ih = self.height - dp(16)
        Color(1, 1, 1, 1)
        RoundedRectangle(pos=(x0 + self.ind * w, self.y + dp(8)), size=(w, ih), radius=[ih / 2],
                         texture=gradient_texture(theme["grad_a"], theme["grad_b"]))
        sel = self._idx()
        isz = ICON_SIZE
        for i, ((key, label, icon), lab) in enumerate(zip(self.items, self.labels)):
            cx0, cw = self._cell(i)
            active = i == sel
            col = theme["on_primary"] if active else theme["text_muted"]
            lab.tone = "on_primary" if active else "text_muted"
            total = isz + dp(8) + lab.texture_size[0]
            sx = cx0 + cw / 2 - total / 2
            draw_icon(icon, sx + isz / 2, (self.y + self.height / 2), isz, col)
            lab.pos = (round(sx + isz + dp(8)), round(self.y + self.height / 2 - lab.height / 2))

    def on_touch_down(self, touch):
        if not self.collide_point(*touch.pos):
            return super().on_touch_down(touch)
        x0, w = self._cell(0)
        i = int((touch.x - x0) // w)
        if 0 <= i < len(self.items):
            key = self.items[i][0]
            if self.on_select:
                self.on_select(key)
        return True


# ----------------------------------------------------------------------------
# overlays: modal, dialog, toast, progress
# ----------------------------------------------------------------------------

class Modal(FloatLayout):
    """Full-window dim layer that swallows touches. Add `self.card` content."""

    def __init__(self, width=440, **kw):
        super().__init__(**kw)
        self.maxw = dp(width)
        self.card = Card(auto_height=True, size_hint=(None, None), width=min(self.maxw, Window.width - dp(32)),
                         pos_hint={"center_x": .5, "center_y": .5}, padding=dp(24), spacing=dp(14))
        self.card.bind(height=lambda *_: None)
        self.add_widget(self.card)
        self.opacity = 0
        self.cancellable = True

    def on_size(self, *_):
        self.card.width = max(min(self.maxw, self.width - dp(32)), dp(240))
        self._dim()

    def _dim(self):
        self.canvas.before.clear()
        with self.canvas.before:
            Color(0, 0, 0, .55 if theme.dark else .38)
            Rectangle(pos=self.pos, size=self.size)

    def on_pos(self, *_):
        self._dim()

    def show(self, host):
        host.add_widget(self)
        self._dim()
        Animation(opacity=1, d=.15).start(self)
        return self

    def dismiss(self, *_):
        a = Animation(opacity=0, d=.12)
        a.bind(on_complete=lambda *_: self.parent and self.parent.remove_widget(self))
        a.start(self)

    def on_touch_down(self, touch):
        super().on_touch_down(touch)
        if self.cancellable and not self.card.collide_point(*touch.pos):
            self.dismiss()
        return True

    def on_touch_move(self, touch):
        super().on_touch_move(touch)
        return True

    def on_touch_up(self, touch):
        super().on_touch_up(touch)
        return True


class Dialog(Modal):
    def __init__(self, title, message, buttons, **kw):
        """buttons: [(label, kind, callback_or_None)]. The dialog dismisses before the callback runs."""
        super().__init__(**kw)
        self.card.add_widget(T(title, style="title", wrap=True))
        if message:
            self.card.add_widget(T(message, style="body", tone="text_muted", wrap=True))
        row = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(10))
        row.add_widget(HSpacer())
        for label, kind, cb in buttons:
            b = Btn(label, kind=kind, size_hint_x=None, width=dp(max(96, 24 + 10 * len(label))))
            b.bind(on_release=lambda _, cb=cb: self._press(cb))
            row.add_widget(b)
        self.card.add_widget(Spacer(2))
        self.card.add_widget(row)

    def _press(self, cb):
        self.dismiss()
        if cb:
            Clock.schedule_once(lambda dt: cb(), .02)


class ProgressOverlay(Modal):
    def __init__(self, on_cancel, **kw):
        super().__init__(**kw)
        self.cancellable = False
        self.title = T("Upscaling", style="title", tone="text")
        self.percent = T("0%", style="display", tone="text")
        self.bar = Bar()
        self.stage = T("Starting", style="body", tone="text_muted")
        self.tiles = T("", style="small", tone="text_muted")
        self.engine = T("", style="small", tone="text_muted")
        for w in (self.title, self.percent, self.bar, self.stage, self.tiles, self.engine):
            self.card.add_widget(w)
        self.card.add_widget(Spacer(4))
        self.cancel_btn = Btn("Cancel", kind="ghost")
        self.cancel_btn.bind(on_release=lambda *_: self._cancel(on_cancel))
        self.card.add_widget(self.cancel_btn)

    def _cancel(self, cb):
        self.cancel_btn.disabled = True
        self.stage.text = "Cancelling..."
        cb()

    def update(self, frac, tile, tiles, engine, stage):
        self.percent.text = f"{int(frac * 100)}%"
        self.bar.value = frac
        self.stage.text = stage or ""
        self.tiles.text = f"Tile {tile} / {tiles}" if tiles else ""
        self.engine.text = f"Engine: {engine}" if engine else ""


class Toast(Skin, BoxLayout):
    def __init__(self, text, kind="info", **kw):
        kw.setdefault("size_hint", (None, None))
        kw.setdefault("padding", [dp(18), dp(10)])
        super().__init__(**kw)
        self.kind = kind
        self.label = T(text, style="body", strong=True, wrap=True, tone="on_primary", halign="center")
        self.add_widget(self.label)
        probe = Label(text=text, font_name=theme.font, font_size=sp(15), bold=True)
        probe.texture_update()
        self.width = min(probe.texture_size[0] + dp(40), max(Window.width - dp(32), dp(200)))
        self.label.bind(height=self._fit)
        self._fit()
        self.init_skin()

    def _fit(self, *_):
        self.height = max(dp(46), self.label.height + dp(20))

    def paint(self):
        Color(*(theme["danger"] if self.kind == "error" else theme["primary_deep"]))
        RoundedRectangle(pos=self.pos, size=self.size, radius=[min(self.height / 2, dp(24))])

    def on_theme(self):
        self.repaint()


class Flow(Layout):
    """Left-to-right layout that wraps children onto new rows (children need a fixed size)."""

    spacing = NumericProperty(dp(8))

    def __init__(self, **kw):
        kw.setdefault("size_hint_y", None)
        super().__init__(**kw)
        self.bind(spacing=self._trigger_layout, pos=self._trigger_layout, size=self._trigger_layout)

    def add_widget(self, widget, *a, **k):
        super().add_widget(widget, *a, **k)
        widget.bind(size=self._trigger_layout)

    def do_layout(self, *_):
        gap, rows, row, rw, rh = self.spacing, [], [], 0, 0
        for c in reversed(self.children):
            if row and rw + gap + c.width > self.width:
                rows.append((row, rh))
                row, rw, rh = [], 0, 0
            rw += (gap if row else 0) + c.width
            row.append(c)
            rh = max(rh, c.height)
        if row:
            rows.append((row, rh))
        total = sum(h for _, h in rows) + gap * max(len(rows) - 1, 0)
        if abs(self.height - total) > .5:
            self.height = total
        y = self.y + total
        for row, rh in rows:
            y -= rh
            x = self.x
            for c in row:
                c.pos = (x, y + (rh - c.height) / 2)
                x += c.width + gap
            y -= gap


class TwoPane(BoxLayout):
    """Responsive body. wide: main | side columns. phone/column: one stacked column
    (phone='stack') or main above a short scrolling side panel (phone='split')."""

    def __init__(self, main, side, side_w=390, main_fill=False, phone="stack", main_h=240, bottom=110, **kw):
        super().__init__(**kw)
        self.main, self.side = list(main), list(side)
        self.side_w, self.main_fill, self.phone = side_w, main_fill, phone
        self.main_h, self.bottom = main_h, bottom
        self.mode = None
        self._split = None
        self.bind(height=self._split_h)

    def _split_h(self, *_):
        if self._split is not None:
            self._split.height = min(max(self.height * .4, dp(190)), dp(340))

    def _fill(self, scroll, widgets):
        for w in widgets:
            scroll.box.add_widget(w)
        return scroll

    def set_mode(self, mode):
        if mode == self.mode:
            return
        self.mode = mode
        for w in self.main + self.side:
            if w.parent:
                w.parent.remove_widget(w)
        self.clear_widgets()
        self._split = None
        if mode == "wide":
            self.orientation, self.spacing = "horizontal", dp(20)
            if self.main_fill:
                for w in self.main:
                    w.size_hint = (1, 1)
                    self.add_widget(w)
            else:
                self.add_widget(self._fill(Scroll(pad=(0, 0, 12, self.bottom)), self.main))
            self.add_widget(self._fill(Scroll(size_hint_x=None, width=dp(self.side_w),
                                              pad=(0, 0, 12, self.bottom)), self.side))
        elif self.phone == "split":
            self.orientation, self.spacing = "vertical", dp(12)
            for w in self.main:
                w.size_hint = (1, 1)
                self.add_widget(w)
            self._split = self._fill(Scroll(size_hint_y=None, pad=(0, 0, 0, 8)), self.side)
            self.add_widget(self._split)
            self._split_h()
        else:
            self.orientation = "vertical"
            sc = Scroll(pad=(0, 0, 0, self.bottom))
            if self.main_fill:
                for w in self.main:
                    w.size_hint, w.height = (1, None), dp(self.main_h)
            self._fill(sc, self.main + self.side)
            self.add_widget(sc)


# ----------------------------------------------------------------------------
# before / after comparison
# ----------------------------------------------------------------------------

class CompareView(Skin, FloatLayout):
    """Before/after slider with scroll-wheel zoom and drag-pan."""

    split = NumericProperty(.5)
    zoom = NumericProperty(1.0)
    pan_x = NumericProperty(0.0)
    pan_y = NumericProperty(0.0)
    before = ObjectProperty(None, allownone=True)
    after = ObjectProperty(None, allownone=True)

    def __init__(self, **kw):
        super().__init__(**kw)
        self._mode = None
        self.lbl_before = T("Before", style="label", fit=True, tone="on_primary")
        self.lbl_after = T("After", style="label", fit=True, tone="on_primary")
        self.lbl_zoom = T("", style="tiny", strong=True, fit=True, tone="on_primary")
        for w in (self.lbl_before, self.lbl_after, self.lbl_zoom):
            self.add_widget(w)
        self.bind(split=self._skin_rp, zoom=self._zoom_changed, pan_x=self._skin_rp, pan_y=self._skin_rp,
                  before=self._skin_rp, after=self._skin_rp, size=lambda *_: self._clamp_pan())
        self.init_skin()

    def _zoom_changed(self, *_):
        self.lbl_zoom.text = f"{self.zoom:.1f}x"
        self.lbl_zoom.texture_update()
        self._skin_rp()

    # geometry ------------------------------------------------------------
    def _rect(self):
        t = self.after
        if not t:
            return None
        tw, th = t.size
        f = min((self.width - dp(16)) / tw, (self.height - dp(16)) / th) * self.zoom
        w, h = tw * f, th * f
        return (self.x + self.width / 2) + self.pan_x - w / 2, (self.y + self.height / 2) + self.pan_y - h / 2, w, h

    def _clamp_pan(self):
        r = self._rect()
        if not r:
            return
        _, _, w, h = r
        mx = max((w - self.width) / 2, 0)
        my = max((h - self.height) / 2, 0)
        self.pan_x = min(max(self.pan_x, -mx), mx)
        self.pan_y = min(max(self.pan_y, -my), my)

    def _blit(self, tex, rect, clip):
        ix, iy, iw, ih = rect
        x0, x1 = max(ix, clip[0]), min(ix + iw, clip[2])
        y0, y1 = max(iy, clip[1]), min(iy + ih, clip[3])
        if x1 <= x0 or y1 <= y0:
            return
        u0, u1 = (x0 - ix) / iw, (x1 - ix) / iw
        v0, v1 = (y0 - iy) / ih, (y1 - iy) / ih
        Color(1, 1, 1, 1)
        Rectangle(texture=tex, pos=(x0, y0), size=(x1 - x0, y1 - y0),
                  tex_coords=(u0, v0, u1, v0, u1, v1, u0, v1))

    def paint(self):
        Color(*theme["surface_alt"])
        RoundedRectangle(pos=self.pos, size=self.size, radius=[dp(RADIUS)])
        r = self._rect()
        if not r:
            return
        clip = (self.x, self.y, (self.x + self.width), (self.y + self.height))
        dx = self.x + self.split * self.width
        if self.after:
            self._blit(self.after, r, clip)
        if self.before:
            self._blit(self.before, r, (self.x, self.y, dx, (self.y + self.height)))
        # divider + handle
        Color(1, 1, 1, .95)
        Line(points=[dx, self.y, dx, (self.y + self.height)], width=dp(1.6))
        hr = dp(20)
        Color(0, 0, 0, .18)
        Ellipse(pos=(dx - hr, (self.y + self.height / 2) - hr - dp(1.5)), size=(2 * hr, 2 * hr))
        Color(1, 1, 1, 1)
        Ellipse(pos=(dx - hr, (self.y + self.height / 2) - hr), size=(2 * hr, 2 * hr))
        draw_icon("chevrons", dx, self.y + self.height / 2, dp(24), theme["primary"])
        # labels
        pad, ph = dp(14), dp(30)
        for lab, x in ((self.lbl_before, self.x + pad), (self.lbl_after, (self.x + self.width) - pad - lab_w(self.lbl_after))):
            w = lab_w(lab)
            Color(0, 0, 0, .5)
            RoundedRectangle(pos=(x, (self.y + self.height) - pad - ph), size=(w, ph), radius=[ph / 2])
            lab.color = (1, 1, 1, 1)
            lab.pos = (x + dp(14), (self.y + self.height) - pad - ph + (ph - lab.height) / 2)
        self.lbl_before.opacity = 1 if dx - self.x > lab_w(self.lbl_before) + 2 * pad else 0
        self.lbl_after.opacity = 1 if (self.x + self.width) - dx > lab_w(self.lbl_after) + 2 * pad else 0
        # zoom pill
        if self.zoom > 1.01:
            w = lab_w(self.lbl_zoom, 10)
            Color(0, 0, 0, .5)
            RoundedRectangle(pos=((self.x + self.width) - pad - w, self.y + pad), size=(w, dp(24)), radius=[dp(12)])
            self.lbl_zoom.color = (1, 1, 1, 1)
            self.lbl_zoom.pos = ((self.x + self.width) - pad - w + dp(10), self.y + pad + (dp(24) - self.lbl_zoom.height) / 2)
            self.lbl_zoom.opacity = 1
        else:
            self.lbl_zoom.opacity = 0

    def reset_view(self):
        self.zoom, self.pan_x, self.pan_y = 1.0, 0.0, 0.0

    # interaction ---------------------------------------------------------
    def on_touch_down(self, touch):
        if not self.collide_point(*touch.pos):
            return super().on_touch_down(touch)
        if touch.is_mouse_scrolling:
            f = 1.18 if touch.button == "scrollup" else 1 / 1.18
            self._zoom_at(touch.pos, f)
            return True
        if touch.is_double_tap:
            self.reset_view()
            return True
        dx = self.x + self.split * self.width
        self._mode = "divider" if (abs(touch.x - dx) < dp(30) or self.zoom <= 1.001) else "pan"
        touch.grab(self)
        if self._mode == "divider":
            self._set_split(touch.x)
        return True

    def _zoom_at(self, pos, f):
        nz = min(max(self.zoom * f, 1.0), 24.0)
        f = nz / self.zoom
        qx = pos[0] - (self.x + self.width / 2)
        qy = pos[1] - (self.y + self.height / 2)
        self.pan_x = qx - (qx - self.pan_x) * f
        self.pan_y = qy - (qy - self.pan_y) * f
        self.zoom = nz
        self._clamp_pan()

    def _set_split(self, x):
        self.split = min(max((x - self.x) / max(self.width, 1), .0), 1.0)

    def on_touch_move(self, touch):
        if touch.grab_current is self:
            if self._mode == "divider":
                self._set_split(touch.x)
            else:
                self.pan_x += touch.dx
                self.pan_y += touch.dy
                self._clamp_pan()
            return True
        return super().on_touch_move(touch)

    def on_touch_up(self, touch):
        if touch.grab_current is self:
            touch.ungrab(self)
            return True
        return super().on_touch_up(touch)


def lab_w(lab, extra=14):
    return lab.texture_size[0] + dp(extra) * 2

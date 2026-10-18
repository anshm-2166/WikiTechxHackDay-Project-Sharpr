"""Application shell: window, theme wiring, navigation, background job runner."""
from __future__ import annotations

import threading
import traceback

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.metrics import dp
from kivy.properties import StringProperty
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.screenmanager import FadeTransition, ScreenManager

from . import engine, pipeline
from .library import Library
from .settings import Settings
from .theme import register_fonts, theme
from .widgets import Dialog, init_gl_limits, NavBar, ProgressOverlay, Toast

TABS = [("create", "Create", "create"), ("library", "Library", "library"), ("settings", "Settings", "settings")]


class Root(FloatLayout):
    pass


PHONE_MAX = 700   # dp: below this the phone layout is used
WIDE_MIN = 900    # dp: from here on the two-column desktop layout is used
DEFAULT_SIZE = (400, 820)
MIN_SIZE = (340, 560)


def screen_height() -> int:
    try:
        import ctypes
        return int(ctypes.windll.user32.GetSystemMetrics(1))
    except Exception:
        return 1080


class SharprApp(App):
    title = "Sharpr"
    mode = StringProperty("phone")   # phone | column | wide

    def build(self):
        register_fonts()
        self.settings = Settings()
        self.library = Library()
        self.stack: list[tuple[str, dict]] = []
        self._cancel = threading.Event()
        self._progress_ui = None
        self.apply_theme()

        try:
            Window.minimum_width = MIN_SIZE[0]
            Window.minimum_height = MIN_SIZE[1]
        except Exception:
            pass
        h = min(DEFAULT_SIZE[1], screen_height() * .88 / dp(1))
        Window.size = (DEFAULT_SIZE[0], h)  # setter takes density-independent units
        self._update_mode(Window.size)

        from .screens.create import CreateScreen
        from .screens.detail import DetailScreen
        from .screens.edit import EditScreen
        from .screens.library_screen import LibraryScreen
        from .screens.settings_screen import SettingsScreen

        self.root = Root()
        self.sm = ScreenManager(transition=FadeTransition(duration=.12))
        self.screens = {}
        for cls in (CreateScreen, LibraryScreen, SettingsScreen, DetailScreen, EditScreen):
            s = cls(name=cls.key)
            self.screens[cls.key] = s
            self.sm.add_widget(s)
        self.root.add_widget(self.sm)

        self.nav = NavBar(TABS, on_select=self.go)
        self.nav.pos_hint = {"center_x": .5}
        self.nav.y = dp(18)
        self.root.add_widget(self.nav)
        self.overlay = FloatLayout()
        self.overlay.size_hint = (1, 1)
        self.root.add_widget(self.overlay)
        self.toasts = FloatLayout(size_hint=(1, 1))
        self.root.add_widget(self.toasts)

        Window.bind(on_drop_file=self._on_drop, size=lambda *_: Clock.schedule_once(lambda dt: self._update_mode(Window.size), 0))
        self._update_mode(Window.size)
        init_gl_limits()
        self.go("create")
        return self.root

    # responsive -----------------------------------------------------------
    def _update_mode(self, size):
        w = size[0] / dp(1)
        self.mode = "phone" if w < PHONE_MAX else ("column" if w < WIDE_MIN else "wide")
        nav = getattr(self, "nav", None)
        if nav is not None:
            nav.width = min(dp(388), size[0] - dp(24))

    # theme ---------------------------------------------------------------
    def apply_theme(self):
        s = self.settings
        theme.apply(s["theme"], bool(s["amoled"]), s["accent"], s["font"])
        Window.clearcolor = theme["bg"]

    # navigation ------------------------------------------------------------
    def _show(self, name, kw, direction_hint=None):
        scr = self.screens[name]
        self.sm.current = name
        self.nav.opacity = 1 if name in ("create", "library", "settings") else 0
        self.nav.disabled = self.nav.opacity == 0
        if name in ("create", "library", "settings"):
            self.nav.current = name
        if hasattr(scr, "on_show"):
            scr.on_show(**kw)

    def go(self, name, **kw):
        """Switch to a top-level tab (clears the push stack)."""
        self.stack = [(name, kw)]
        self._show(name, kw)

    def push(self, name, replace=False, **kw):
        if replace and self.stack:
            self.stack[-1] = (name, kw)
        else:
            self.stack.append((name, kw))
        self._show(name, kw)

    def back(self):
        if len(self.stack) > 1:
            self.stack.pop()
        name, kw = self.stack[-1]
        self._show(name, kw)

    def pop_to_detail(self, item_id):
        """Close the edit screen and show the (new or updated) item's detail."""
        if self.stack and self.stack[-1][0] == "edit":
            self.stack.pop()
        if self.stack and self.stack[-1][0] == "detail":
            self.stack.pop()
        self.push("detail", item_id=item_id)

    def _on_drop(self, window, filename, *args):
        path = filename.decode("utf-8", "ignore") if isinstance(filename, bytes) else str(filename)
        if path.lower().endswith(pipeline.EXTS):
            self.go("create")
            self.screens["create"].load_path(path)
        else:
            self.toast("Unsupported file type", "error")

    # feedback ------------------------------------------------------------
    def toast(self, text, kind="info", seconds=3.2):
        t = Toast(text, kind)
        t.pos_hint = {"center_x": .5}
        t.y = dp(100) if self.nav.opacity else dp(28)
        self.toasts.add_widget(t)
        t.opacity = 0
        from kivy.animation import Animation
        Animation(opacity=1, d=.15).start(t)

        def kill(dt):
            a = Animation(opacity=0, d=.25)
            a.bind(on_complete=lambda *_: t.parent and t.parent.remove_widget(t))
            a.start(t)
        Clock.schedule_once(kill, seconds)

    def dialog(self, title, message, buttons=None):
        buttons = buttons or [("OK", "primary", None)]
        return Dialog(title, message, buttons).show(self.overlay)

    # background jobs -------------------------------------------------------
    def run_job(self, work, on_success, title="Upscaling"):
        """Run work(progress, cancel) in a worker thread with a progress overlay.

        progress(frac, tile, tiles, engine_label, stage) may be called from the worker.
        """
        self._cancel = threading.Event()
        ui = ProgressOverlay(on_cancel=self._cancel.set)
        ui.title.text = title
        ui.show(self.overlay)
        self._progress_ui = ui

        def progress(frac, tile, tiles, label, stage):
            Clock.schedule_once(lambda dt: ui.update(frac, tile, tiles, label, stage), 0)

        def runner():
            try:
                result = work(progress, self._cancel)
            except engine.Cancelled:
                Clock.schedule_once(lambda dt: self._job_finished(ui, "cancel"), 0)
            except pipeline.GuardError as e:
                Clock.schedule_once(lambda dt, e=e: self._job_finished(ui, "guard", e), 0)
            except Exception as e:  # noqa: BLE001
                traceback.print_exc()
                Clock.schedule_once(lambda dt, e=e: self._job_finished(ui, "error", e), 0)
            else:
                Clock.schedule_once(lambda dt: self._job_finished(ui, "ok", result, on_success), 0)

        threading.Thread(target=runner, daemon=True, name="sharpr-job").start()

    def _job_finished(self, ui, status, payload=None, on_success=None):
        ui.dismiss()
        if status == "ok":
            on_success(payload)
        elif status == "cancel":
            self.toast("Upscale cancelled")
        elif status == "guard":
            self.dialog(payload.title, payload.message)
        else:
            self.dialog("Something went wrong", f"{payload}")

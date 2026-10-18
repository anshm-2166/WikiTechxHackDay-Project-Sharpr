"""Application shell: window, theme wiring, navigation, background job runner."""
from __future__ import annotations

import os
import threading

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.metrics import dp
from kivy.properties import StringProperty
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.screenmanager import FadeTransition, ScreenManager

from . import pipeline
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
    def run_job(self, job, on_success, title="Upscaling"):
        """Run `job` in a separate worker process (ncnn holds the GIL, which would freeze the UI in a
        thread) and show a progress overlay. on_success(item, summary) runs on the UI thread."""
        import json
        import queue
        import subprocess
        import sys

        from .settings import project_root

        ui = ProgressOverlay(on_cancel=lambda: self._cancel_job())
        ui.title.text = title
        ui.show(self.overlay)
        self._progress_ui = ui
        env = dict(os.environ, PYTHONPATH=str(project_root()), PYTHONIOENCODING="utf-8")
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        self._proc = subprocess.Popen(
            [sys.executable, "-m", "sharpr.worker", json.dumps(job)], cwd=str(project_root()), env=env,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
            encoding="utf-8", creationflags=flags)
        events: "queue.Queue[dict]" = queue.Queue()
        proc = self._proc

        def reader():
            for line in proc.stdout:
                if line.startswith("@@"):
                    try:
                        events.put(json.loads(line[2:]))
                    except ValueError:
                        pass
            events.put({"k": "eof"})

        threading.Thread(target=reader, daemon=True, name="sharpr-job-reader").start()
        finished = {"done": False}

        def poll(dt):
            while not events.empty():
                ev = events.get()
                k = ev["k"]
                if k == "progress":
                    ui.update(ev["frac"], ev["tile"], ev["tiles"], ev["label"], ev["stage"])
                    continue
                finished["done"] = True
                self._job_finished(ui, k, ev, on_success)
                return False
            return True

        Clock.schedule_interval(poll, 0.05)

    def _cancel_job(self):
        try:
            self._proc.stdin.write("cancel\n")
            self._proc.stdin.flush()
        except Exception:  # noqa: BLE001
            pass

    def _job_finished(self, ui, kind, ev, on_success):
        ui.dismiss()
        try:
            self._proc.wait(timeout=5)
        except Exception:  # noqa: BLE001
            self._proc.kill()
        if kind == "done":
            self.library.reload()
            on_success(ev["item"], ev)
        elif kind == "cancel":
            self.toast("Upscale cancelled")
        elif kind == "guard":
            self.dialog(ev["title"], ev["message"])
        elif kind == "error":
            self.dialog(ev.get("title", "Something went wrong"), ev.get("message", "Unknown error"))
        else:
            self.dialog("Something went wrong", "The upscale process ended unexpectedly.")

    def on_stop(self):
        proc = getattr(self, "_proc", None)
        if proc is not None and proc.poll() is None:
            proc.kill()

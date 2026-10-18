"""Capture real-window screenshots for the website (dev tool): python tools/make_screenshots.py"""
import json, os, shutil, sys, tempfile, time
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
SAMPLES = ROOT.parent / "website" / "assets" / "samples"
OUT = ROOT.parent / "website" / "assets" / "desktop"
OUT.mkdir(parents=True, exist_ok=True)
data = Path(tempfile.mkdtemp(prefix="sharpr_shots_"))
os.environ["SHARPR_DATA"] = str(data)
os.environ["KIVY_NO_ARGS"] = "1"
import cv2
import numpy as np
from kivy.config import Config
Config.set("input", "mouse", "mouse,disable_multitouch")
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.graphics import Color, Rectangle
import sharpr.screens.settings_screen as ss
from sharpr import engine, pipeline
from sharpr.app import SharprApp
from sharpr.theme import theme
from sharpr.widgets import Scroll

# neutral paths in the Storage card
_KV = ss.KV
ss.KV = lambda k, v, **kw: _KV(k, {"Library": "Sharpr/data", "Models": "Sharpr/models"}.get(k, v), **kw)

meta = {e["file"]: e for e in json.loads((SAMPLES / "samples.json").read_text())["files"]}
SRC = SAMPLES / "original.jpg"

# a large, sharp, generated image that trips the guard (never used as a sample)
GUARD_IMG = data / "guard-test.png"
yy, xx = np.mgrid[0:2000, 0:3000]
g = np.stack([xx * 255 // 3000, yy * 255 // 2000, (xx + yy) * 255 // 5000], -1).astype(np.uint8)
g = np.where(((xx // 3 + yy // 3) % 2 == 0)[..., None], g, 255 - g).astype(np.uint8)
for r in range(100, 900, 60):
    cv2.circle(g, (1500, 1000), r, (20, 20, 20), 2)
cv2.imwrite(str(GUARD_IMG), g)

SEEDED = []
app = SharprApp()
steps = []
def step(fn, delay=0.7): steps.append((fn, delay))
def run_next(dt):
    if not steps:
        app.stop(); return
    fn, delay = steps.pop(0)
    try: fn()
    except Exception:
        import traceback; traceback.print_exc()
    Clock.schedule_once(run_next, delay)

def shot(name):
    app.toasts.clear_widgets()
    r = app.root
    r.canvas.before.clear()
    with r.canvas.before:
        Color(*theme["bg"]); Rectangle(pos=r.pos, size=r.size)
    r.export_to_png(str(OUT / f"{name}.png"))
    print("shot", name)

def size(w, h): setattr(Window, "size", (w, h))
cr = lambda: app.screens["create"]

def seed_library():
    ld = pipeline.load_image(SRC)
    h, w = ld.bgr.shape[:2]
    for fn, model in [("fast-4x.png", "fast"), ("photo-4x.png", "photo"), ("art-4x.png", "illustration")]:
        img = cv2.imread(str(SAMPLES / fn))
        m = meta[fn]
        info = engine.EngineInfo(backend="gpu", backend_label=m["backend"], tiles=6)
        res = pipeline.Result(img, info, pipeline.Options(model, 4), (w, h), (img.shape[1], img.shape[0]), m["seconds"])
        SEEDED.append(app.library.save_result(SRC, res, "png", 92, name="the-bug-slayyers")["id"])
def item(model):
    return next(i for i in app.library.all() if i["model"] == model)

# --- scrolling helpers: never leave a row cut by the viewport edge --------------------------------
def scrolls(screen):
    return [w for w in screen.walk(restrict=True) if isinstance(w, Scroll) and w.parent is not None]

def _rows(sc):
    out = []
    for w in sc.box.children:
        kids = list(w.children) if type(w).__name__ in ("Card", "VBox", "Hero") and w.children else [w]
        for k in kids:
            out.extend(list(k.children) if type(k).__name__ == "VBox" and k.children else [k])
    return out

def _conflict(sc):
    bottom = sc.to_window(sc.x, sc.y)[1]
    top = bottom + sc.height
    for r in _rows(sc):
        y0 = r.to_window(r.x, r.y)[1]
        y1 = y0 + r.height
        if r.height > 1 and ((y0 < bottom - 1 < y1 - 1) or (y0 + 1 < top + 1 < y1)):
            if y0 < bottom - 1 and y1 > bottom + 1: return True
            if y0 < top - 1 and y1 > top + 1 and sc.scroll_y < 0.999: return True
    return False

def snap(sc):
    total = sc.box.height - sc.height
    if total <= 0: return
    base = sc.scroll_y
    sc.update_from_scroll()
    if not _conflict(sc): return
    for d in range(2, 400, 2):
        for sign in (1, -1):
            sc.scroll_y = min(max(base + sign * d / total, 0), 1)
            sc.update_from_scroll()
            if not _conflict(sc): return
    sc.scroll_y = base; sc.update_from_scroll()

def snap_all(screen):
    for sc in scrolls(screen): snap(sc)

def scroll_to_section(screen, text):
    for sc in scrolls(screen):
        for card in sc.box.children:
            for ch in card.children:
                if type(ch).__name__ == "Section" and ch.text == text:
                    sc.scroll_to(card, padding=0, animate=False)
                    sc.update_from_scroll()
                    return

def scroll_to_widget(screen, widget):
    """Scroll so that `widget` sits at the top of its viewport."""
    for sc in scrolls(screen):
        if widget in list(sc.walk()):
            total = sc.box.height - sc.height
            sc.update_from_scroll()
            rel = (sc.to_window(sc.x, sc.y)[1] + sc.height) - (widget.to_window(widget.x, widget.y)[1] + widget.height)
            sc.scroll_y = min(max(sc.scroll_y - (rel - dp_(8)) / max(total, 1), 0), 1)
            sc.update_from_scroll()
            return
def dp_(v):
    from kivy.metrics import dp
    return dp(v)

def theme_set(t):
    app.settings.set("theme", t); app.apply_theme()

def wait_progress(timeout=170):
    t0 = time.time()
    def poll():
        ui = app._progress_ui
        v = ui.bar.value if ui is not None else 0
        if v < 0.42 and time.time() - t0 < timeout:
            steps.insert(0, (poll, 1.0))
    return poll

def reset_options():
    cr().options.model.value = "fast"; cr().options.scale.value = 4

def phone_create(t):
    step(lambda: theme_set(t), 0.8)
    step(lambda: size(400, 820), 1.8)
    step(lambda: app.go("create"), 0.8)
    step(lambda: (cr().load_path(str(SRC)), reset_options()), 1.0)
    step(lambda: (scroll_to_widget(cr(), cr().preview), snap_all(cr())), 0.6)
    step(lambda: shot(f"phone-create-image-{t}"), 0.5)

def run_theme(t):
    if os.environ.get("ONLY") == "phone-create":
        return phone_create(t)
    step(lambda: theme_set(t), 0.8)
    step(lambda: size(1280, 800), 1.5)
    step(lambda: app.go("create"), 0.8)
    step(lambda: (setattr(cr(), "path", None), setattr(cr().options, "_size", None), cr().options._update_hint(),
                  cr()._render(), reset_options()), 0.8)
    step(lambda: shot(f"create-empty-{t}"), 0.5)
    step(lambda: (cr().load_path(str(SRC)), reset_options()), 1.0)
    step(lambda: snap_all(cr()), 0.4)
    step(lambda: shot(f"create-image-{t}"), 0.5)
    # guard banner
    step(lambda: cr().load_path(str(GUARD_IMG)), 1.5)
    step(lambda: (setattr(scrolls(cr())[0], "scroll_y", 0), snap(scrolls(cr())[0])), 0.5)
    step(lambda: shot(f"guard-banner-{t}"), 0.5)
    step(lambda: (cr().load_path(str(SRC)), reset_options()), 1.0)
    # real job, captured at roughly 40-60 percent
    def go_job():
        app._progress_ui = None
        app.settings.set("engine", "cpu")
        cr().options.model.value = "photo"; cr().options.scale.value = 4; cr().start()
    step(go_job, 3.0)
    step(wait_progress(), 0.3)
    step(lambda: shot(f"progress-{t}"), 0.5)
    step(lambda: (app._cancel_job(), app.settings.set("engine", "auto")), 6.0)
    step(lambda: (app.toasts.clear_widgets(), [app.library.delete(i["id"]) for i in app.library.all()
                                              if i["id"] not in SEEDED], reset_options()), 0.5)
    # detail
    step(lambda: app.push("detail", item_id=item("photo")["id"]), 3.5)
    step(lambda: (setattr(app.screens["detail"].compare, "split", .5), app.toasts.clear_widgets()), 0.5)
    step(lambda: shot(f"detail-compare-{t}"), 0.5)
    step(lambda: app.screens["detail"].compare._zoom_at((app.root.width * 0.37, app.root.height * 0.55), 3.2), 0.8)
    step(lambda: shot(f"detail-zoom-{t}"), 0.5)
    step(lambda: app.screens["detail"].compare.reset_view(), 0.5)
    step(lambda: [setattr(sc, "scroll_y", 0) for sc in scrolls(app.screens["detail"])], 0.6)
    step(lambda: shot(f"detail-info-{t}"), 0.5)
    step(lambda: app.push("edit", item_id=item("photo")["id"]), 1.5)
    step(lambda: snap_all(app.screens["edit"]), 0.4)
    step(lambda: shot(f"edit-{t}"), 0.5)
    step(lambda: app.go("library"), 1.5)
    step(lambda: shot(f"library-{t}"), 0.5)
    step(lambda: app.go("settings"), 1.2)
    step(lambda: (setattr(scrolls(app.screens["settings"])[0], "scroll_y", 1), snap_all(app.screens["settings"])), 0.5)
    step(lambda: shot(f"settings-{t}"), 0.5)
    step(lambda: scroll_to_section(app.screens["settings"], "INFERENCE ENGINE"), 0.6)
    step(lambda: shot(f"settings-engine-{t}"), 0.5)
    step(lambda: scroll_to_section(app.screens["settings"], "GUARDS"), 0.6)
    step(lambda: shot(f"settings-guards-{t}"), 0.5)
    # phone mode
    step(lambda: size(400, 820), 1.8)
    step(lambda: app.go("create"), 0.8)
    step(lambda: (cr().load_path(str(SRC)), reset_options()), 1.0)
    step(lambda: (scroll_to_widget(cr(), cr().preview), snap_all(cr())), 0.6)
    step(lambda: shot(f"phone-create-image-{t}"), 0.5)
    step(lambda: app.push("detail", item_id=item("photo")["id"]), 3.5)
    step(lambda: (app.toasts.clear_widgets(), setattr(app.screens["detail"].compare, "split", .5)), 0.5)
    step(lambda: shot(f"phone-detail-compare-{t}"), 0.5)
    step(lambda: app.go("library"), 1.5)
    step(lambda: shot(f"phone-library-{t}"), 0.5)
    step(lambda: app.go("settings"), 1.2)
    step(lambda: (setattr(scrolls(app.screens["settings"])[0], "scroll_y", 1), snap_all(app.screens["settings"])), 0.5)
    step(lambda: shot(f"phone-settings-{t}"), 0.5)

step(lambda: None, 2.5)
step(seed_library, 1.0)
run_theme("light")
run_theme("dark")
Clock.schedule_once(run_next, 3.0)
app.run()
shutil.rmtree(data, ignore_errors=True)
print("DONE")

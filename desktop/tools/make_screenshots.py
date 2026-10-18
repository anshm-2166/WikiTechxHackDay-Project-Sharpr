"""Capture real-window screenshots for the website (dev tool): python tools/make_screenshots.py"""
import json, os, shutil, sys, tempfile
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
from kivy.config import Config
Config.set("input", "mouse", "mouse,disable_multitouch")
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.graphics import Color, Rectangle
import sharpr.screens.settings_screen as ss
from sharpr import engine, pipeline
from sharpr.app import SharprApp
from sharpr.theme import theme

# neutral paths in the Storage card
_KV = ss.KV
ss.KV = lambda k, v, **kw: _KV(k, {"Library": "Sharpr/data", "Models": "Sharpr/models"}.get(k, v), **kw)

meta = {e["file"]: e for e in json.loads((SAMPLES / "samples.json").read_text())["files"]}
SRC = SAMPLES / "original.jpg"
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
        app.library.save_result(SRC, res, "png", 92, name="the-bug-slayyers")
def item(model):
    return next(i for i in app.library.all() if i["model"] == model)

def scroll_side(y):
    pane = app.screens["detail"].pane
    for c in pane.children:
        if hasattr(c, "scroll_y") and c.size_hint_x is None: c.scroll_y = y

def guards_card():
    sc = app.screens["settings"].scroll
    for card in sc.box.children:
        for ch in card.children:
            if type(ch).__name__ == "Section" and ch.text == "GUARDS":
                sc.scroll_to(card, padding=0, animate=False); return

def theme_set(t):
    app.settings.set("theme", t); app.apply_theme()

def run_theme(t):
    def wide():
        size(1280, 800)
    step(lambda: theme_set(t), 0.8)
    step(wide, 1.2)
    step(lambda: app.go("create"), 0.8)
    step(lambda: (setattr(cr(), "path", None), setattr(cr().options, "_size", None), cr().options._update_hint(), cr()._render()), 0.8)
    step(lambda: shot(f"create-empty-{t}"), 0.5)
    def pick():
        cr().load_path(str(SRC)); cr().options.model.value = "fast"; cr().options.scale.value = 4
    step(pick, 1.0)
    step(lambda: shot(f"create-image-{t}"), 0.5)
    # real job, captured mid-way
    def go_job():
        cr().options.model.value = "photo"; cr().options.scale.value = 4; cr().start()
    step(go_job, 25.0)
    step(lambda: shot(f"progress-{t}"), 0.5)
    step(lambda: app._cancel.set(), 5.0)
    step(lambda: cr().options.model.value.__class__ and setattr(cr().options.model, "value", "fast"), 0.3)
    # detail
    step(lambda: app.push("detail", item_id=item("photo")["id"]), 3.0)
    step(lambda: shot(f"detail-compare-{t}"), 0.5)
    step(lambda: (app.screens["detail"].compare._zoom_at((app.root.width * 0.37, app.root.height * 0.55), 3.2)), 0.8)
    step(lambda: shot(f"detail-zoom-{t}"), 0.5)
    step(lambda: app.screens["detail"].compare.reset_view(), 0.5)
    step(lambda: scroll_side(0), 0.6)
    step(lambda: shot(f"detail-info-{t}"), 0.5)
    step(lambda: app.push("edit", item_id=item("photo")["id"]), 1.2)
    step(lambda: shot(f"edit-{t}"), 0.5)
    step(lambda: app.go("library"), 1.2)
    step(lambda: shot(f"library-{t}"), 0.5)
    step(lambda: app.go("settings"), 1.0)
    step(lambda: shot(f"settings-{t}"), 0.5)
    step(guards_card, 0.8)
    step(lambda: shot(f"settings-guards-{t}"), 0.5)
    # phone mode
    step(lambda: size(400, 820), 1.5)
    step(lambda: app.go("create"), 0.8)
    step(lambda: cr().load_path(str(SRC)), 1.0)
    step(lambda: shot(f"phone-create-image-{t}"), 0.5)
    step(lambda: app.push("detail", item_id=item("photo")["id"]), 3.0)
    step(lambda: shot(f"phone-detail-compare-{t}"), 0.5)
    step(lambda: app.go("library"), 1.2)
    step(lambda: shot(f"phone-library-{t}"), 0.5)
    step(lambda: app.go("settings"), 1.0)
    step(lambda: setattr(app.screens["settings"].scroll, "scroll_y", 1), 0.4)
    step(lambda: shot(f"phone-settings-{t}"), 0.5)

step(lambda: None, 2.5)
step(seed_library, 1.0)
run_theme("light")
run_theme("dark")
Clock.schedule_once(run_next, 3.0)
app.run()
shutil.rmtree(data, ignore_errors=True)
print("DONE")

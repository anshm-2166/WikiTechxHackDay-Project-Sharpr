"""Drive the real window at several sizes and export a screenshot of each screen (dev tool)."""
import os, sys, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(tempfile.mkdtemp())
out.mkdir(parents=True, exist_ok=True)
os.environ["SHARPR_DATA"] = str(out / "data")
os.environ["KIVY_NO_ARGS"] = "1"
import cv2, numpy as np
from kivy.config import Config
Config.set("input", "mouse", "mouse,disable_multitouch")
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.graphics import Color, Rectangle
from kivy.metrics import dp
from sharpr.app import SharprApp
from sharpr.theme import theme
from sharpr.widgets import ProgressOverlay

img = out / "sample.png"
yy, xx = np.mgrid[0:240, 0:320]
a = np.stack([xx * 255 // 320, yy * 255 // 240, (xx + yy) * 255 // 560], -1).astype(np.uint8)
cv2.circle(a, (160, 120), 60, (40, 60, 230), -1)
cv2.putText(a, "SHARPR", (70, 130), cv2.FONT_HERSHEY_DUPLEX, 1.6, (255, 255, 255), 3)
cv2.imwrite(str(img), a)

sharp = out / "sharp.png"; cv2.imwrite(str(sharp), np.random.default_rng(3).integers(0, 256, (400, 2400, 3), dtype=np.uint8))
SIZES = [(400, 820), (340, 560), (800, 900), (1280, 800)]
if len(sys.argv) > 2:
    SIZES = [tuple(int(v) for v in s.split("x")) for s in sys.argv[2:]]
app = SharprApp()
steps = []
def step(fn, delay=0.6): steps.append((fn, delay))
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
    r.export_to_png(str(out / f"{name}.png"))
cr = lambda: app.screens["create"]

def make_item():
    app.settings.set("engine", "cpu")
    cr().load_path(str(img)); cr().start()
step(lambda: (app.settings.set("theme", os.environ.get("THEME", "light")), app.apply_theme()), 1.0)
step(lambda: None, 1.5)
step(make_item, 8.0)
for (w, h) in SIZES:
    tag = f"{w}x{h}"
    step(lambda w=w, h=h: setattr(Window, "size", (w, h)), 1.0)
    step(lambda: print("MODE", Window.size, app.mode), 0.1)
    step(lambda: app.go("create"), 0.8)
    step(lambda: cr().load_path(str(img)), 0.8)
    step(lambda tag=tag: shot(f"{tag}_1_create"), 0.5)
    step(lambda: cr().load_path(str(sharp)), 0.8)
    step(lambda tag=tag: shot(f"{tag}_9_guard"), 0.5)
    step(lambda: cr().load_path(str(img)), 0.5)
    step(lambda: app.go("library"), 0.8)
    step(lambda tag=tag: shot(f"{tag}_2_library"), 0.5)
    step(lambda: app.push("detail", item_id=app.library.all()[0]["id"]), 1.5)
    step(lambda tag=tag: shot(f"{tag}_3_detail"), 0.5)
    step(lambda: app.push("edit", item_id=app.library.all()[0]["id"]), 0.8)
    step(lambda tag=tag: shot(f"{tag}_4_edit"), 0.5)
    step(lambda: app.go("settings"), 0.8)
    step(lambda tag=tag: shot(f"{tag}_5_settings"), 0.5)
    step(lambda: app.go("create"), 0.5)
    def ov():
        o = ProgressOverlay(on_cancel=lambda: None); o.show(app.overlay); o.update(.4, 33, 80, "GPU (Intel(R) UHD Graphics)", "Upscaling")
        app._tmp = o
    step(ov, 0.6)
    step(lambda tag=tag: shot(f"{tag}_6_progress"), 0.3)
    step(lambda: app._tmp.dismiss(), 0.5)
    step(lambda: app.dialog("Already high resolution", "This image is already high resolution - longest side 2400 px (limit 2048 px). Upscaling it would add little detail.", [("Cancel", "tonal", None), ("Continue", "primary", None)]), 0.6)
    step(lambda tag=tag: shot(f"{tag}_7_dialog"), 0.3)
    step(lambda: [w for w in app.overlay.children][0].dismiss(), 0.5)
    step(lambda: app.toast("Scale lowered from 4x to 3x to keep the result under 24 MP (20.7 MP)."), 0.5)
    step(lambda tag=tag: shot(f"{tag}_8_toast"), 0.3)
Clock.schedule_once(run_next, 3.0)
app.run()
print("DONE", out)

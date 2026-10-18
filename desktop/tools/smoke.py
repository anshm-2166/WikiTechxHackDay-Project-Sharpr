"""Drives the real Kivy app through its screens and saves screenshots (dev tool)."""
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
from sharpr.app import SharprApp

img = out / "sample.png"
yy, xx = np.mgrid[0:240, 0:320]
a = np.stack([xx * 255 // 320, yy * 255 // 240, (xx + yy) * 255 // 560], -1).astype(np.uint8)
cv2.circle(a, (160, 120), 60, (40, 60, 230), -1); cv2.putText(a, "SHARPR", (70, 130), cv2.FONT_HERSHEY_DUPLEX, 1.6, (255, 255, 255), 3)
cv2.imwrite(str(img), a)

app = SharprApp()
steps = []
def shot(name):
    from kivy.core.window import Window
    Window.screenshot(name=str(out / f"{name}.png"))
    os.replace(out / f"{name}0001.png", out / f"{name}.png") if (out / f"{name}0001.png").exists() else None
def step(fn, delay=0.8):
    steps.append((fn, delay))
def run_next(dt):
    if not steps:
        app.stop(); return
    fn, delay = steps.pop(0)
    try: fn()
    except Exception:
        import traceback; traceback.print_exc()
    Clock.schedule_once(run_next, delay)

cr = lambda: app.screens["create"]
step(lambda: shot("1_create_empty"))
step(lambda: cr().load_path(str(img)))
step(lambda: shot("2_create_loaded"))
step(lambda: setattr(cr().options.sharpen, "value", 40))
step(lambda: cr().start(), 0.5)
def wait_done():
    if app.sm.current != "detail":
        steps.insert(0, (wait_done, 1.0))
step(wait_done, 3.0)
step(lambda: shot("3_progress") if app.sm.current != "detail" else None, 0.1)
step(wait_done, 2.0)
step(lambda: shot("4_detail"), 2.0)
step(lambda: setattr(app.screens["detail"].compare, "split", 0.35))
step(lambda: app.screens["detail"].compare._zoom_at((600, 400), 2.5))
step(lambda: shot("5_detail_zoom"))
step(lambda: app.push("edit", item_id=app.library.all()[0]["id"]))
step(lambda: shot("6_edit"))
step(lambda: app.go("library"))
step(lambda: shot("7_library"), 1.2)
step(lambda: app.go("settings"))
step(lambda: shot("8_settings"))
def dark():
    app.settings.set("theme", "dark"); app.apply_theme()
step(dark)
step(lambda: shot("9_settings_dark"))
step(lambda: app.go("library"))
step(lambda: shot("10_library_dark"), 1.0)
step(lambda: app.go("create"))
step(lambda: shot("11_create_dark"))

# progress overlay + cancel (slow CPU model)
big = out / "wide.png"; cv2.imwrite(str(big), np.tile(a, (1, 8, 1))[:240, :2400])   # 2400 px wide -> guard
tiny = out / "tiny.png"; cv2.imwrite(str(tiny), a[:10, :10])
step(lambda: app.go("create"))
step(lambda: cr().load_path(str(big)))
step(lambda: shot("12_guard_blocked"))
step(lambda: cr()._override())
step(lambda: shot("13_guard_override"))
step(lambda: cr().load_path(str(tiny)))
step(lambda: shot("14_tiny"))
def slow():
    app.settings.set("engine", "cpu"); app.settings.set("tile", 32)
    cr().load_path(str(img)); cr().options.model.value = "photo"; cr().options.scale.value = 4
    cr().start()
step(slow, 2.5)
step(lambda: None, 1.0)
step(lambda: app.root.export_to_png(str(out / "15_progress.png")), 1.0)
step(lambda: app.screens and app._cancel_job(), 2.0)
step(lambda: shot("16_after_cancel"))
Clock.schedule_once(run_next, 3.0)
app.run()
print("SMOKE DONE", out)

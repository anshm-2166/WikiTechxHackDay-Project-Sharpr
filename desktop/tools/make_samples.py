"""Generate website sample images from the real engine (dev tool): python tools/make_samples.py"""
import json, sys, time
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
import cv2, numpy as np
from sharpr import pipeline
from sharpr.settings import Settings

OUT = ROOT.parent / "website" / "assets" / "samples"
src = OUT / "original.jpg"
s = Settings(ROOT / "tools" / "_samples_settings.json")
s.values.update(engine="auto", cpu_fallback=True, tile=0, fp16=False)
ld = pipeline.load_image(src)
h, w = ld.bgr.shape[:2]
entries = []

def save(name, img, model, scale, backend, seconds):
    cv2.imwrite(str(OUT / name), img)
    entries.append({"file": name, "model": model, "scale": scale, "width": img.shape[1], "height": img.shape[0],
                    "backend": backend, "seconds": round(seconds, 2)})
    print(name, img.shape[1], img.shape[0], backend, round(seconds, 2))

outs = {}
for name, model, scale in [("fast-4x.png", "fast", 4), ("photo-4x.png", "photo", 4),
                           ("art-4x.png", "illustration", 4), ("fast-2x.png", "fast", 2)]:
    res = pipeline.process(src, pipeline.Options(model, scale), s, loaded=ld)
    label = {"fast": "Fast", "photo": "Photo Ultra", "illustration": "Illustration"}[model]
    save(name, res.image, label, scale, res.info.backend_label, res.seconds)
    outs[name] = res.image
t = time.time(); bic = cv2.resize(ld.bgr, (w * 4, h * 4), interpolation=cv2.INTER_CUBIC)
save("bicubic-4x.png", bic, "Bicubic (cv2.INTER_CUBIC)", 4, "CPU", time.time() - t)
t = time.time(); nn = cv2.resize(ld.bgr, (w * 4, h * 4), interpolation=cv2.INTER_NEAREST)
save("nearest-4x.png", nn, "Nearest neighbour", 4, "CPU", time.time() - t)

# detail crops: region in original coordinates (x, y, w, h); 4x outputs cropped natively, original enlarged nearest
regions = {"bug": (283, 108, 112, 62), "slayyers": (128, 164, 284, 56)}
variants = {"original": nn, "bicubic": bic, "fast": outs["fast-4x.png"], "photo": outs["photo-4x.png"], "art": outs["art-4x.png"]}
for rname, (x, y, cw, ch) in regions.items():
    for vname, img in variants.items():
        crop = img[y * 4:(y + ch) * 4, x * 4:(x + cw) * 4]
        fn = f"crop-{rname}-{vname}.png"
        cv2.imwrite(str(OUT / fn), crop)
        entries.append({"file": fn, "model": vname, "crop_of_original_px": [x, y, cw, ch], "zoom": 4,
                        "width": crop.shape[1], "height": crop.shape[0]})
(OUT / "samples.json").write_text(json.dumps({"source": "original.jpg", "source_size": [w, h],
                                              "note": "Default enhance settings (no denoise/blend/sharpen/grain).",
                                              "files": entries}, indent=2), encoding="utf-8")
(ROOT / "tools" / "_samples_settings.json").unlink(missing_ok=True)

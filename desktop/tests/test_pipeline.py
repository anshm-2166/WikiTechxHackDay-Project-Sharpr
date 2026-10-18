"""Headless pipeline test: python tests/test_pipeline.py  (or pytest)."""
import sys
import tempfile
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cv2
import numpy as np

from sharpr import engine, guards, pipeline
from sharpr.library import Library
from sharpr.settings import Settings


def make_image(path, w=96, h=64):
    y, x = np.mgrid[0:h, 0:w]
    img = np.stack([x * 255 // w, y * 255 // h, (x + y) * 255 // (w + h)], -1).astype(np.uint8)
    cv2.circle(img, (w // 2, h // 2), min(h, w) // 4, (30, 60, 220), -1)
    cv2.imwrite(str(path), img)


def blurry(w=2400, h=1800):
    rng = np.random.default_rng(1)
    n = rng.integers(0, 256, (h, w, 3), dtype=np.uint8)
    return cv2.GaussianBlur(n, (0, 0), 6)


def sharp(w=2400, h=1800):
    rng = np.random.default_rng(2)
    return rng.integers(0, 256, (h, w, 3), dtype=np.uint8)   # maximal high-frequency content


def test_analysis_and_guard_logic():
    soft, hard = guards.analyze(blurry()), guards.analyze(sharp())
    assert soft.detail < guards.SOFT_BELOW and soft.sharpness_label == "Soft", soft.detail
    assert hard.detail >= guards.CRISP_FROM and hard.sharpness_label == "Crisp", hard.detail
    # large + soft: never blocked, informational note only
    g = guards.check_input(2400, 1800, 2048, strict=True, detail=soft.detail)
    assert g.ok and "Large but soft" in g.note
    # large + crisp: blocked, override allowed unless strict
    g = guards.check_input(2400, 1800, 2048, strict=False, detail=hard.detail)
    assert not g.ok and g.overridable and g.title == "Already high resolution and sharp"
    assert not guards.check_input(2400, 1800, 2048, strict=True, detail=hard.detail).overridable
    # small + crisp is fine; resolution alone never blocks
    assert guards.check_input(800, 600, 2048, detail=hard.detail).ok
    assert guards.check_input(2400, 1800, 2048, detail=None).ok
    g = guards.check_input(8, 500)
    assert not g.ok and not g.overridable                                   # tiny


def test_scale_plan():
    assert guards.allowed_scales("fast") == (1, 2, 3, 4) and guards.allowed_scales("photo") == (1, 2, 4)
    p = guards.plan_scale(3000, 2000, 4, "fast", 24)       # 4x = 96 MP, 2x = 24 MP -> 2x
    assert p.ok and p.scale == 2 and p.clamped
    p = guards.plan_scale(4000, 3000, 4, "photo", 24)      # 12 MP: even 2x = 48 MP -> 1x
    assert p.ok and p.scale == 1 and "enhance it at the same size" in p.message
    assert not guards.plan_scale(6000, 5000, 1, "fast", 24).ok   # 30 MP even at 1x


def test_pipeline_fast_cpu():
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        src = td / "in.png"
        make_image(src)
        s = Settings(td / "settings.json")
        s.values.update(engine="cpu", tile=32)
        prog = []
        res = pipeline.process(src, pipeline.Options("fast", 2, denoise=10, blend=20, sharpen=30, grain=10), s,
                               progress=lambda *a: prog.append(a))
        assert res.image.shape[:2] == (128, 192), res.image.shape
        assert res.info.backend == "cpu"
        assert prog and prog[-1][0] == 1.0 and res.info.tiles == 6
        res3 = pipeline.process(src, pipeline.Options("fast", 3), s)
        assert res3.image.shape[:2] == (192, 288)
        res1 = pipeline.process(src, pipeline.Options("fast", 1), s)      # Enhance: original size
        assert res1.image.shape[:2] == (64, 96), res1.image.shape
        s.values["tile"] = 512
        res_one = pipeline.process(src, pipeline.Options("fast", 2), s)
        s.values["tile"] = 32
        res_tiled = pipeline.process(src, pipeline.Options("fast", 2), s)
        diff = np.abs(res_one.image.astype(int) - res_tiled.image.astype(int)).mean()
        assert diff < 2.0, diff
        lib = Library(td / "lib")
        item = lib.save_result(src, res, "png", 90)
        assert lib.path(item, "result").exists() and lib.path(item, "thumb").exists()
        assert Library(td / "lib").get(item["id"])["new_w"] == 192
        lib.delete(item["id"])
        assert not lib.all()
        print("pipeline ok:", res.info.backend_label, f"{res.seconds:.2f}s", "seam diff", round(float(diff), 3))


def test_cap_falls_to_1x():
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        src = td / "big.png"
        cv2.imwrite(str(src), np.full((3000, 4000, 3), 120, np.uint8))     # 12 MP, flat -> fast to run
        s = Settings(td / "s.json")
        s.values.update(engine="cpu", tile=400, max_output_mp=24)
        res = pipeline.process(src, pipeline.Options("fast", 4), s)
        assert res.options.scale == 1 and res.image.shape[:2] == (3000, 4000) and res.note


def test_pipeline_does_not_block_resolution_but_blocks_tiny():
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        s = Settings(td / "s.json")
        s.values.update(engine="cpu", strict_guard=True, tile=400)
        wide = td / "wide.png"
        cv2.imwrite(str(wide), np.full((40, 2100, 3), 90, np.uint8))       # high-res by side, not re-blocked
        res = pipeline.process(wide, pipeline.Options("fast", 1), s)
        assert res.image.shape[:2] == (40, 2100)
        tiny = td / "tiny.png"
        make_image(tiny, 10, 10)
        try:
            pipeline.process(tiny, pipeline.Options("fast", 2), s, override=True)
        except pipeline.GuardError:
            pass
        else:
            raise AssertionError("tiny must never be processed")


def test_cancel():
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        src = td / "in.png"
        make_image(src)
        s = Settings(td / "s.json")
        s.values.update(engine="cpu", tile=16)
        ev = threading.Event()
        ev.set()
        try:
            pipeline.process(src, pipeline.Options("fast", 2), s, cancel=ev)
        except engine.Cancelled:
            return
        raise AssertionError("expected Cancelled")


def test_no_em_dash_or_mojibake():
    """UI text must not contain U+2014 (em dash) or mojibake in any source file."""
    root = Path(__file__).resolve().parent.parent / "sharpr"
    for f in root.rglob("*.py"):
        text = f.read_text(encoding="utf-8")
        assert "—" not in text, f"em dash in {f}"
        assert "â€" not in text and "Ã" not in text, f"mojibake in {f}"


def test_model_discovery_order():
    import os
    from sharpr import settings as st
    root, exe = Path("/r"), Path("/x/bin")
    got = st.model_search_paths("/o", env="/e", root=root, exe_dir=exe)
    assert got == [Path("/o"), Path("/e"), root / "models", root.parent / "app/src/main/assets/models",
                   exe / "models", exe.parent / "app/src/main/assets/models"], got
    assert st.model_search_paths("", env="", root=root, exe_dir=None)[0] == root / "models"
    # package-relative, independent of the working directory
    here = os.getcwd()
    try:
        os.chdir(tempfile.gettempdir())
        paths = st.model_search_paths("", env="")
        assert paths[0] == st.project_root() / "models" and paths[0].is_absolute()
        assert (st.project_root().parent / "app/src/main/assets/models") in paths
    finally:
        os.chdir(here)
    with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
        (Path(b) / "realesrgan-x4plus.param").write_text("x")
        assert st.find_models_dir(paths=[Path(a), Path(b)]) == Path(b)     # first dir that has models
        assert st.find_models_dir(paths=[Path(a)]) == Path(a)


def test_missing_models_message():
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        src = td / "in.png"
        make_image(src)
        s = Settings(td / "s.json")
        s.values.update(engine="cpu")
        s.search_paths = lambda: [td / "nowhere"]
        try:
            pipeline.process(src, pipeline.Options("fast", 2), s)
        except engine.ModelsMissing as e:
            msg = str(e)
            assert "nowhere" in msg and "Folders searched" in msg and "How to fix" in msg
            assert "realesr-animevideov3-x2" in msg and "SHARPR_MODELS" in msg
        else:
            raise AssertionError("expected ModelsMissing")


def test_gpu_probe():
    print("vulkan gpus:", engine.gpu_count())


if __name__ == "__main__":
    test_analysis_and_guard_logic(); test_scale_plan(); test_pipeline_does_not_block_resolution_but_blocks_tiny()
    test_cancel(); test_pipeline_fast_cpu(); test_cap_falls_to_1x(); test_no_em_dash_or_mojibake(); test_model_discovery_order(); test_missing_models_message(); test_gpu_probe()
    print("ALL OK")

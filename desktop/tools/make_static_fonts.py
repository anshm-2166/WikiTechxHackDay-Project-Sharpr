"""Generate static Regular/Medium/Bold instances from the variable fonts (needs fonttools)."""
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

FONTS = Path(__file__).resolve().parent.parent / "assets" / "fonts"
NAMES = {"outfit": "Outfit", "plus_jakarta_sans": "PlusJakartaSans", "space_grotesk": "SpaceGrotesk"}
WEIGHTS = {"Regular": 400, "Bold": 700}

for stem in NAMES:
    for label, wght in WEIGHTS.items():
        f = TTFont(FONTS / f"{stem}.ttf")
        axes = {a.axisTag: a for a in f["fvar"].axes}
        limits = {"wght": wght}
        for tag, a in axes.items():
            if tag != "wght":
                limits[tag] = a.defaultValue
        inst = instantiateVariableFont(f, limits)
        out = FONTS / f"{stem}-{label}.ttf"
        inst.save(out)
        print("wrote", out.name)

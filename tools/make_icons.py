"""Generate the app icon set from the source character art.

The source art is a dark-background chibi illustration; the background is removed
with a flood fill seeded from the four corners and the character is re-composed on
a white (rounded square / circle) background for the launcher icons, plus a
transparent cut-out used as the adaptive-icon foreground and the in-app logo.
"""
import os
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# source artwork: --src <file>, $DSMON_ART, or art/character.png in the repo root
SRC = (sys.argv[sys.argv.index("--src") + 1] if "--src" in sys.argv
       else os.environ.get("DSMON_ART", os.path.join(ROOT, "art", "character.png")))
RES = os.path.join(ROOT, "app", "src", "main", "res")
TOOLS = os.path.join(ROOT, "tools")

THRESH = 40
HALO_DILATE = 7          # odd kernel size: grows the removed background by ~3px
FEATHER = 1.4
SS = 4                   # supersampling factor for smooth downscales
BOTTOM_KEEP = 0.94       # drop the dark fade/gradient at the very bottom of the source art

WHITE = (255, 255, 255, 255)


def load_cutout() -> Image.Image:
    """Return the character as RGBA on a transparent background (bottom gradient cropped)."""
    if not os.path.exists(SRC):
        raise SystemExit("source art not found: %s\npass --src <file> or set DSMON_ART" % SRC)
    src = Image.open(SRC).convert("RGB")
    w, h = src.size
    src = src.crop((0, 0, w, int(h * BOTTOM_KEEP)))
    w, h = src.size
    base = np.asarray(src).astype(np.int16)

    filled = src.copy()
    for seed in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        ImageDraw.floodfill(filled, seed, (255, 255, 255), thresh=THRESH)
    # The flood fill paints exact white, so a per-pixel diff isolates the removed area.
    mask = (np.abs(np.asarray(filled).astype(np.int16) - base).max(axis=2) > 8)
    m = Image.fromarray((mask * 255).astype(np.uint8), "L")
    m = m.filter(ImageFilter.MaxFilter(HALO_DILATE)).filter(ImageFilter.GaussianBlur(FEATHER))

    alpha = Image.fromarray((255 - np.asarray(m)).astype(np.uint8), "L")
    cut = src.convert("RGBA")
    cut.putalpha(alpha)
    return cut


def fit_height(cut: Image.Image, canvas: int, height_ratio: float = 0.97,
               bottom_inset: float = 0.0, anchor: str = "bottom") -> Image.Image:
    """Fit the art by *height* so the whole character stays visible.

    A negative ``bottom_inset`` pushes the art past the bottom edge, so the icon is cut through the
    solid part of the hair: no white strip and no pale feathered edge at the bottom.
    """
    scale = (canvas * height_ratio) / cut.height
    art = cut.resize((max(1, int(cut.width * scale)), max(1, int(cut.height * scale))), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    if anchor == "bottom":
        top = int(canvas * (1.0 - bottom_inset)) - art.height
    else:
        top = (canvas - art.height) // 2
    out.paste(art, ((canvas - art.width) // 2, max(0, top)), art)
    return out


def fit_safe(cut: Image.Image, canvas: int, safe: float = 72.0 / 108.0,
             bottom_at: float = 90.0 / 108.0) -> Image.Image:
    """Adaptive-icon foreground: keep the art inside the launcher's safe zone.

    Android draws adaptive icons by cropping to the central 72/108 of the canvas, so art that fills
    the whole canvas gets zoomed and cut on the device. Here the character is sized to the safe zone
    and sits at the bottom of it.
    """
    art_h = canvas * safe
    scale = art_h / cut.height
    art = cut.resize((max(1, int(cut.width * scale)), max(1, int(art_h))), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    top = int(canvas * bottom_at) - art.height
    out.paste(art, ((canvas - art.width) // 2, max(0, top)), art)
    return out


def fit_bleed(cut: Image.Image, canvas: int, width_ratio: float = 1.0,
              anchor: str = "bottom") -> Image.Image:
    """Scale by *width* so the art nearly fills the icon, then sit it on the bottom edge.

    The source art is already cropped at the shoulders, so a width-filling, bottom-anchored
    composition has no white strip underneath and no big white margin around it.
    """
    scale = (canvas * width_ratio) / cut.width
    art = cut.resize((max(1, int(cut.width * scale)), max(1, int(cut.height * scale))), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    top = canvas - art.height if anchor == "bottom" else (canvas - art.height) // 2
    out.paste(art, ((canvas - art.width) // 2, max(0, top)), art)
    return out


def trim(cut: Image.Image) -> Image.Image:
    bbox = cut.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
    return cut.crop(bbox)


def fit(cut: Image.Image, canvas: int, fill: float, anchor: str = "center") -> Image.Image:
    """Place the art on a transparent square canvas.

    ``anchor="bottom"`` drops the art to the lower edge (the source art is already cropped at the
    shoulders, so a bottom-anchored character has no dead white strip under it).
    """
    side = max(cut.size)
    target = int(canvas * fill)
    scale = target / side
    art = cut.resize((max(1, int(cut.width * scale)), max(1, int(cut.height * scale))), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    top = canvas - art.height if anchor == "bottom" else (canvas - art.height) // 2
    out.paste(art, ((canvas - art.width) // 2, max(0, top)), art)
    return out


def rounded_mask(canvas: int, radius_ratio: float) -> Image.Image:
    big = canvas * SS
    m = Image.new("L", (big, big), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * radius_ratio), fill=255)
    return m.resize((canvas, canvas), Image.LANCZOS)


def circle_mask(canvas: int) -> Image.Image:
    big = canvas * SS
    m = Image.new("L", (big, big), 0)
    ImageDraw.Draw(m).ellipse([0, 0, big - 1, big - 1], fill=255)
    return m.resize((canvas, canvas), Image.LANCZOS)


def compose(art: Image.Image, mask: Image.Image) -> Image.Image:
    plate = Image.new("RGBA", mask.size, WHITE)
    out = Image.new("RGBA", mask.size, (0, 0, 0, 0))
    out.paste(plate, (0, 0), mask)
    out.alpha_composite(art)
    # keep the icon silhouette exactly (square/circle) by re-applying the mask
    out.putalpha(Image.composite(out.getchannel("A"), Image.new("L", mask.size, 0), mask))
    return out


def save(img: Image.Image, *parts: str) -> None:
    path = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "WEBP", quality=92, method=6)
    print("  ", os.path.relpath(path, ROOT), img.size, str(round(os.path.getsize(path) / 1024)) + "KB")


def purge_pngs() -> None:
    for root, _dirs, files in os.walk(RES):
        for name in files:
            if name.endswith(".png") and ("ic_launcher" in name or name.startswith("logo_")):
                os.remove(os.path.join(root, name))


def main() -> None:
    cut = trim(load_cutout())
    print("character bbox:", cut.size)

    # launcher icons: white plate, whole character visible, cut just below the bottom edge
    dens = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, size in dens.items():
        art = fit_height(cut, size, 0.97, -0.025, "bottom")
        save(compose(art, rounded_mask(size, 0.22)), f"mipmap-{name}", "ic_launcher.webp")
        save(compose(fit_height(cut, size, 0.90, -0.025, "bottom"), circle_mask(size)),
             f"mipmap-{name}", "ic_launcher_round.webp")

    # adaptive icon foreground: art inside the 72/108 safe zone (launchers crop the outer ring)
    for name, size in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
        save(fit_safe(cut, size), f"drawable-{name}", "ic_launcher_foreground.webp")

    # in-app logo (white rounded card) and a transparent mark for headers
    logo = compose(fit_height(cut, 512, 0.97, -0.025, "bottom"), rounded_mask(512, 0.22))
    save(logo, "drawable-nodpi", "logo_mark.webp")
    save(fit(cut, 256, 0.92), "drawable-nodpi", "logo_transparent.webp")

    purge_pngs()

    # preview sheet — loaded back from the generated assets so it can never drift.
    # The third tile emulates what a launcher shows: adaptive foreground cropped to the central
    # 72/108 safe zone and masked to a squircle.
    sheet = Image.new("RGB", (900, 300), (18, 18, 20))
    square = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.webp")).convert("RGBA")
    circle = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_round.webp")).convert("RGBA")

    fg = Image.open(os.path.join(RES, "drawable-xxxhdpi", "ic_launcher_foreground.webp")).convert("RGBA")
    inset = int(fg.width * 18 / 108)
    core = fg.crop((inset, inset, fg.width - inset, fg.height - inset))
    adaptive = Image.new("RGBA", (192, 192), (255, 255, 255, 255))
    adaptive.alpha_composite(core.resize((192, 192), Image.LANCZOS))
    adaptive.putalpha(rounded_mask(192, 0.26))

    tiles = [square, circle, adaptive]
    for i, t in enumerate(tiles):
        t = t.resize((192, 192), Image.LANCZOS)
        sheet.paste(t.convert("RGB"), (20 + i * 220, 50), t)
    sheet.save(os.path.join(TOOLS, "preview-icon-final.png"))
    print("preview ->", os.path.join(TOOLS, "preview-icon-final.png"))


if __name__ == "__main__":
    main()

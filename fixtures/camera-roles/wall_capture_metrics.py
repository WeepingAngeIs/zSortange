"""Compare actual camera monitor and player framebuffers from disposable hosted clients."""
import json
import os
import statistics
from pathlib import Path

from PIL import Image, ImageDraw


if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("Use the hosted camera fixture.")

SIDE = 256
MASK = (22, 23, 34)
OUT = Path("comparison")
OUT.mkdir(exist_ok=True)


def load(path):
    with Image.open(path) as picture:
        return picture.convert("RGB")


def shot(folder, name):
    return folder / f"{name}-CamOwner.png"


def sidecar(path, suffix):
    return path.with_name(path.name + suffix)


def straighten(path):
    # Four measured screen corners in the real player's framebuffer.
    x0, y0, x3, y3, x2, y2, x1, y1 = json.loads(sidecar(path, ".json").read_text())
    ax, ay = x1 - x2, y1 - y2
    bx, by = x3 - x2, y3 - y2
    cx, cy = x0 - x1 + x2 - x3, y0 - y1 + y2 - y3
    det = ax * by - ay * bx
    g, h = (cx * by - cy * bx) / det, (ax * cy - ay * cx) / det
    transform = ((x1 * (1 + g) - x0) / SIDE, (x3 * (1 + h) - x0) / SIDE, x0,
                 (y1 * (1 + g) - y0) / SIDE, (y3 * (1 + h) - y0) / SIDE, y0, g / SIDE, h / SIDE)
    return load(path).transform((SIDE, SIDE), Image.Transform.PERSPECTIVE, transform, Image.Resampling.BILINEAR)


def region(where):
    x0, y0, x1, y1 = (round(v * SIDE) for v in where)
    return [(x, y) for y in range(y0, y1) for x in range(x0, x1)
            if abs(x - SIDE // 2) > 4 or abs(y - SIDE // 2) > 4]


REGIONS = {
    "whole": region((.025, .025, .975, .975)),
    "roof-and-eaves": region((.25, .015, .75, .15)),
    "lit-cherry-facade": region((.29, .33, .71, .48)),
    "pavement-3-to-8-blocks": region((.42, .595, .58, .75)),
    "pavement-7-to-20-blocks-through-alley": region((.365, .538, .385, .604)),
    "gold-to-lapis-sign": region((.453, .454, .547, .511)),
}


def luma(rgb):
    return .2126 * rgb[0] + .7152 * rgb[1] + .0722 * rgb[2]


def average_color(pixels):
    return [round(statistics.mean(channel), 2) for channel in zip(*pixels)]


def measure(photo, native, points):
    a = [photo.getpixel(p) for p in points]
    b = [native.getpixel(p) for p in points]
    return {
        "samplePixels": len(a),
        "meanRGB": average_color(a),
        "stockMeanRGB": average_color(b),
        "meanLuma": round(statistics.mean(map(luma, a)), 2),
        "stockMeanLuma": round(statistics.mean(map(luma, b)), 2),
        "meanAbsRGBError": round(statistics.mean(abs(i-j) for p,q in zip(a,b) for i,j in zip(p,q)), 2),
    }


def save_grid(name, rows):
    cols = len(rows[0])
    canvas = Image.new("RGB", (SIDE*cols, (SIDE+32)*len(rows)), (21, 22, 29))
    draw = ImageDraw.Draw(canvas)
    for y, row in enumerate(rows):
        for x, (title, picture) in enumerate(row):
            at = (x*SIDE, y*(SIDE+32))
            draw.text((at[0]+5, at[1]+8), title, fill=(247, 240, 252))
            canvas.paste(picture.resize((SIDE,SIDE), Image.Resampling.LANCZOS), (at[0], at[1]+32))
    canvas.save(OUT / name)



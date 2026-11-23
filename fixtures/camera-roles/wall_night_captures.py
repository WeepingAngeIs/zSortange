"""Score actual same-wall monitor and stock camera screenshots from the hosted indoor observer."""
import json
import os
import statistics
from pathlib import Path

from PIL import Image, ImageDraw


if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("Use the hosted camera fixture.")

SIDE = 256
MASK = (22, 23, 34)
VERSIONS = {"installed": "sharpcam.3 (installed)", "inherited": "pocketdefault.1", "candidate": "wallnight.1"}
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


scenes, results, failures = {}, {}, []
for trial, version in VERSIONS.items():
    folder = Path("observations") / f"wall-night-{trial}-evidence"
    if not (folder / "PASS.txt").is_file():
        raise AssertionError(f"Missing completed client record for {version}")
    report = results[trial] = {}
    scenes[trial] = {}
    for state in ("gold", "blue"):
        path = shot(folder, "wall-" + state)
        panel = straighten(path)
        stock = load(shot(folder, "stock-" + state)).resize((SIDE,SIDE), Image.Resampling.BOX)
        texture = load(sidecar(path, ".texture.png"))
        exact = texture.resize((SIDE,SIDE), Image.Resampling.NEAREST)
        receipt = json.loads(sidecar(path, ".scene.json").read_text())
        for kind, image in (("monitor", panel), ("stock", stock), ("texture", exact)):
            scenes[trial][state, kind] = image
        receipt["sensorCoveragePercent"] = round(100*receipt["visibleRays"]/receipt["rays"], 2)
        receipt["exactNeutralMaskPercent"] = round(100*sum(p == MASK for p in texture.getdata())/(texture.width*texture.height), 2)
        receipt["regions"] = {}
        for label, area in REGIONS.items():
            unmasked = [p for p in area if exact.getpixel(p) != MASK]
            result = {"monitor": measure(panel, stock, area), "texture": measure(exact, stock, area),
                      "unmaskedPercent": round(100*len(unmasked)/len(area), 2)}
            if unmasked:
                result["textureOnVisiblePixels"] = measure(exact,stock,unmasked)
            receipt["regions"][label] = result
        report[state] = receipt
    # Explicitly distinguish scene change on the actual monitor from a changed control texture.
    for kind in ("stock","monitor"):
        gold = scenes[trial]["gold",kind]
        blue = scenes[trial]["blue",kind]
        marker = REGIONS["gold-to-lapis-sign"]
        g = average_color([gold.getpixel(p) for p in marker])
        b = average_color([blue.getpixel(p) for p in marker])
        shift = round((b[2]-b[0])-(g[2]-g[0]), 2)
        report[kind + "SignBlueMinusRedShift"] = shift
        if trial == "candidate" and shift < 8:
            failures.append(f"{version} {kind}: changed outdoor sign was not plainly separable, shift {shift}")
    masked_path = shot(folder,"private-sign")
    masked_texture = load(sidecar(masked_path, ".texture.png")).resize((SIDE,SIDE), Image.Resampling.NEAREST)
    sign = REGIONS["gold-to-lapis-sign"]
    masked_fraction = sum(masked_texture.getpixel(p) == MASK for p in sign)/len(sign)
    report["maskedSignPercent"] = round(100*masked_fraction, 2)
    if masked_fraction < .98:
        failures.append(f"{version}: only {masked_fraction:.2%} of the protected sign was masked")
    scenes[trial]["protected sign","monitor"] = straighten(masked_path)
    redacted = straighten(shot(folder,"private-screen"))
    restored = straighten(shot(folder,"wall-restored"))
    scenes[trial]["private","monitor"] = redacted
    scenes[trial]["restored","monitor"] = restored

for state in ("gold","blue"):
    rows = []
    for trial, version in VERSIONS.items():
        rows.append([(version + " / stock lens", scenes[trial][state,"stock"]),
                     (version + " / in-game monitor", scenes[trial][state,"monitor"]),
                     (version + " / monitor texture", scenes[trial][state,"texture"])])
    save_grid("night-" + state + ".png", rows)
save_grid("claim-revocation.png", [[(state,scenes["candidate"][state,"monitor"]) for state in ("blue","protected sign","private","restored")]])
for label, area in REGIONS.items():
    for state in ("gold", "blue"):
        common = [p for p in area if all(scenes[t][state,"texture"].getpixel(p) != MASK for t in VERSIONS)]
        for trial in VERSIONS:
            if common:
                results[trial][state]["regions"][label]["matchedVisiblePixels"] = measure(
                    scenes[trial][state,"texture"], scenes[trial][state,"stock"], common)
(OUT/"measurements.json").write_text(json.dumps(results, indent=2))
print(json.dumps({trial: {key: results[trial]["gold"][key] for key in ("sensorCoveragePercent","exactNeutralMaskPercent")}
                  for trial in VERSIONS}, indent=2))
if failures:
    raise AssertionError(failures)

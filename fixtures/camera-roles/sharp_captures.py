"""Compare pixels from the presented monitor against the previous mod and the native camera reference."""
import json
import math
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageStat

base = Path("build/role-check")
out = Path("upload/captures")
out.mkdir(parents=True, exist_ok=True)
old, new, solo = "sharp-old", "sharp-new", "sharp-solo"


def original(trial, shot, role="CamOwner"):
    with Image.open(base / trial / "control" / f"{shot}-{role}.png") as image:
        return image.convert("RGB")


def panel(trial, shot, role="CamOwner"):
    source = base / trial / "control" / f"{shot}-{role}.png.json"
    quad = json.loads(source.read_text())
    width = math.hypot(quad[6] - quad[0], quad[7] - quad[1])
    height = math.hypot(quad[2] - quad[0], quad[3] - quad[1])
    # Common sample density below the projected panel width, without synthesizing detail.
    side = 62 if shot == "small-day" else 192
    assert min(width, height) >= side * .88, (trial, shot, quad)
    with original(trial, shot, role) as source:
        return source.transform((side, side), Image.Transform.QUAD, quad, Image.Resampling.NEAREST)


def interior(trial, shot):
    image = panel(trial, shot)
    margin = max(2, int(image.width * .08))
    return image.crop((margin, margin, image.width-margin, image.height-margin))


def detail(image):
    w, h = image.size
    pixels = image.load()
    # Omit the room player's center reticle.
    return sum(max(abs(a-b) for a, b in zip(pixels[x, y], pixels[x+1, y])) >= 6
               for y in range(2, h-2) for x in range(2, w-3)
               if abs(x-w//2) > 8 or abs(y-h//2) > 8)


def difference(a, b, limit=13):
    return sum(max(pixel) > limit for pixel in ImageChops.difference(a, b).getdata())


def collect(trial):
    root = base / trial / "control"
    for file in root.glob("*.png*"):
        (out / (trial + "-" + file.name)).write_bytes(file.read_bytes())
    acknowledgements = [f"{receipt.stem}  {receipt.read_text().strip()}"
                        for receipt in sorted(root.glob("*.ok"))]
    (out / f"{trial}-observations.txt").write_text("\n".join(acknowledgements) + "\n")
    print(trial, (root / "PASS.txt").read_text())


def contact(name, columns, rows):
    width = max(image.width for row in rows for image in row) + 8
    height = max(image.height for row in rows for image in row) + 10
    canvas = Image.new("RGB", (width * len(columns), height * len(rows) + 25), "#191a21")
    draw = ImageDraw.Draw(canvas)
    for x, title in enumerate(columns):
        draw.text((x*width+4, 6), title, fill="white")
    for y, row in enumerate(rows):
        for x, image in enumerate(row):
            canvas.paste(image, (x*width+4, y*height+25))
    canvas.save(out / name)


for trial in (old, new, solo):
    collect(trial)
a, b = interior(old, "large-day"), interior(new, "large-day")
data = {
    "old_detail_edges": detail(a),
    "new_detail_edges": detail(b),
    "small_old_detail_edges": detail(interior(old, "small-day")),
    "small_new_detail_edges": detail(interior(new, "small-day")),
    "old_same_map_color_edit": difference(a, interior(old, "large-brick")),
    "new_same_map_color_edit": difference(b, interior(new, "large-brick")),
    "masked_source_cell": difference(interior(new, "source-cell-clear"), interior(new, "source-cell-masked")),
    "cow_enters": difference(interior(new, "large-cow"), interior(new, "large-brick")),
    "visible_player_enters": difference(interior(new, "large-player"), interior(new, "large-cow")),
    "player_hidden": difference(interior(new, "large-player"), interior(new, "large-invisible")),
    "source_rain": difference(interior(new, "large-player"), interior(new, "large-rain"), 7),
    "source_night": difference(interior(new, "large-rain"), interior(new, "large-night")),
    "solo_detail_edges": detail(interior(solo, "large-day")),
    "solo_unloaded": difference(interior(solo, "source-unloaded"), interior(solo, "restored-after-claim")),
}
# Actual terrain textures should yield detail that the old single color per sample cannot show.
assert data["new_detail_edges"] > data["old_detail_edges"] * 1.4 + 80, data
assert data["small_new_detail_edges"] > data["small_old_detail_edges"] + 22, data
assert data["new_same_map_color_edit"] > data["old_same_map_color_edit"] + 90, data
for key in ("masked_source_cell", "cow_enters", "visible_player_enters", "player_hidden",
            "source_rain", "source_night", "solo_detail_edges", "solo_unloaded"):
    assert data[key] > 30, (key, data)
contact("comparison.png", ["Installed monitor", "New monitor"], [
    [panel(old, "large-brick"), panel(new, "large-brick")],
    [panel(old, "small-day"), panel(new, "small-day")],
])
rows = []
references = {}
displayed = {}
for condition, shot in (("day", "large-player"), ("rain", "large-rain"), ("night", "large-night")):
    name = "reference-" + condition
    base_path = base / new / "control"
    reference = original(new, name, "RoomBuilder")
    with Image.open(base_path/(name+"-outside-before.png")) as before, Image.open(base_path/(name+"-outside-after.png")) as after:
        assert before.size == after.size and difference(before.convert("RGB"), after.convert("RGB"), 2) < before.width * before.height * .01, "Reference changed outside player's view"
    assert len(reference.getcolors(reference.width * reference.height)) > 20, name
    references[condition] = reference.resize((192, 192), Image.Resampling.BOX)
    displayed[condition] = panel(new, shot)
    rows.append([panel(old, shot), displayed[condition], references[condition]])
    # Quantify the difference to stock rendering without calling the two renderers equivalent.
    data["stock_vs_screen_mean_rgb_error_" + condition] = ImageStat.Stat(
        ImageChops.difference(references[condition], displayed[condition])).mean
data["stock_reference_day_to_night"] = difference(references["day"], references["night"])
data["stock_reference_clear_to_rain"] = difference(references["day"], references["rain"], 7)
assert data["stock_reference_day_to_night"] > 400 and data["stock_reference_clear_to_rain"] > 100, data
contact("source-comparison.png", ["Installed monitor", "New monitor", "Stock renderer at lens"], rows)
hardware, fallback = panel(new, "same-scene-world-renderer"), panel(new, "same-scene-software")
contact("same-observation.png", ["World block renderer", "Software fallback"], [[hardware, fallback]])
data["same_observation_renderer_difference"] = difference(hardware, fallback, 3)
(out / "measurements.json").write_text(json.dumps(data, indent=2))
print("PASS matched monitor texture, subjects, weather/light, stock camera comparisons and integrated server", data)

"""Draw two orthographic shoulder close-ups directly from the Cherry backpack in each packaged JAR."""
import argparse
import io
import json
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageDraw, ImageFont


MODEL = "assets/anyastorage/models/item/backpack/cherry.json"
SCALE = 40  # pixels per Minecraft model unit
SIZE = (9 * SCALE, 5 * SCALE)  # Z 3..12, Y 11..16
PAPER, INK, MUTE, BACKDROP, HATCH = "#f6f1ec", "#272129", "#594f5a", "#312c37", (255, 201, 66)


def side_faces(jar, side):
    model = json.loads(jar.read(MODEL))
    textures = {}
    result = []
    for index, element in enumerate(model["elements"]):
        face = element["faces"].get(side)
        if not face:
            continue
        if "rotation" in element or face.get("rotation", 0):
            raise ValueError("This orthographic study expects axis-aligned, unrotated side faces")
        key = face["texture"].removeprefix("#")
        if key not in textures:
            namespace, path = model["textures"][key].split(":")
            with Image.open(io.BytesIO(jar.read(f"assets/{namespace}/textures/{path}.png"))) as source:
                textures[key] = source.convert("RGBA")
        (x0, y0, z0), (x1, y1, z1) = element["from"], element["to"]
        depth = -x0 if side == "west" else x1
        result.append((depth, index, y0, y1, z0, z1, face["uv"], textures[key]))
    return result


def shoulder(jar, side):
    faces = side_faces(jar, side)
    tile = Image.new("RGB", SIZE, BACKDROP)
    pixels = tile.load()
    tied = 0
    for py in range(tile.height):
        y = 16 - (py + .5) / SCALE
        for px in range(tile.width):
            z = 3 + (px + .5) / SCALE if side == "west" else 12 - (px + .5) / SCALE
            foremost = float("-inf")
            layer = set()
            for depth, index, y0, y1, z0, z1, uv, texture in faces:
                if depth < foremost or not (y0 <= y < y1 and z0 <= z < z1):
                    continue
                across = (z - z0 if side == "west" else z1 - z) / (z1 - z0)
                u = uv[0] + (uv[2] - uv[0]) * across
                v = uv[1] + (uv[3] - uv[1]) * (y1 - y) / (y1 - y0)
                tx = max(0, min(texture.width - 1, int(u * texture.width / 16)))
                ty = max(0, min(texture.height - 1, int(v * texture.height / 16)))
                color = texture.getpixel((tx, ty))
                if color[3] == 0:
                    continue
                if depth > foremost:
                    foremost = depth
                    layer.clear()
                layer.add(index)
                pixels[px, py] = color[:3]
            # The generator's first two cuboids are the body and flap. Keep trim in front of them opaque.
            if 0 in layer and 1 in layer:
                tied += 1
                if (px + py) % 12 < 4:
                    pixels[px, py] = HATCH
    return tile, tied / SCALE ** 2


def font(size, bold=False):
    names = ("DejaVuSans-Bold.ttf", "LiberationSans-Bold.ttf") if bold else ("DejaVuSans.ttf", "LiberationSans-Regular.ttf")
    for name in names:
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    raise RuntimeError("Install DejaVu Sans or Liberation Sans for the figure labels")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "candidate", "output"):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    canvas = Image.new("RGB", (894, 666), PAPER)
    draw = ImageDraw.Draw(canvas)
    draw.text((24, 16), "Placed backpack shoulder seams", fill=INK, font=font(30, True))
    draw.text((25, 58), "Cherry skin  /  anyastorage:item/backpack/cherry", fill=MUTE, font=font(17))
    for column, (label, path) in enumerate((("Before", args.base), ("After", args.candidate))):
        x = 114 + 390 * column
        draw.text((x, 99), label, fill=INK, font=font(21, True))
        with ZipFile(path) as jar:
            for row, side in enumerate(("west", "east")):
                y = 137 + 243 * row
                if column == 0:
                    draw.text((24, y + 80), side.title(), fill=INK, font=font(19, True))
                    draw.text((25, y + 107), "side", fill=MUTE, font=font(15))
                tile, overlap = shoulder(jar, side)
                if (column == 0 and overlap <= 0) or (column == 1 and overlap != 0):
                    raise AssertionError(f"{label} {side} shoulder: unexpected visible overlap {overlap}")
                print(f"Cherry {side} shoulder, {label.lower()}: {overlap:g} square model units at equal depth.")
                canvas.paste(tile, (x, y))
                draw.text((x, y + SIZE[1] + 5), f"Visible overlap   {overlap:g} square model units",
                          fill=MUTE, font=font(15))
    draw.rectangle((25, 620, 45, 638), fill=HATCH, outline=INK)
    draw.text((54, 614), "Gold lines mark body and flap faces at the same visible depth.", fill=INK, font=font(17))
    draw.text((25, 643), "Orthographic depth illustration from the packaged meshes and textures.", fill=MUTE, font=font(14))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(args.output, format="PNG")


if __name__ == "__main__":
    main()

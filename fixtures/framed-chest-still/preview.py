"""Offline two-angle artwork comparison. Requires Git, Pillow 10.1+, and a local JDK 21.

Run with --output DIRECTORY and optionally --jdk JDK_HOME. Only one invented
six-chest arrangement is drawn. The angled view projects the same flat face.
"""

import argparse
import io
import math
from pathlib import Path
import subprocess
import tempfile

from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BASELINE = "784cb15e90fd9802b26c5efaea568864420f04f3"
ART = Path("src/main/resources/assets/anyastorage/textures/entity")
GLINT_MS = 600
GLINT_POSITIONS = ((120, 8), (120, 240))


def sample_commands(jdk):
    def tool(name):
        return str(jdk / "bin" / name) if jdk else name

    projection = ROOT / "src/main/java/dev/anya/storage/pocket/ChestLayoutProjection.java"
    with tempfile.TemporaryDirectory() as classes:
        subprocess.run([tool("javac"), "--release", "21", "-d", classes,
                        projection, HERE / "FrameSample.java"], check=True)
        result = subprocess.run([tool("java"), "-cp", classes, "FrameSample"],
                                check=True, capture_output=True, text=True)
    size, *placements = [tuple(map(int, line.split())) for line in result.stdout.splitlines()]
    if [bit for bit, _, _ in placements] != [0, 1, 2, 3, 4, 7]:
        raise ValueError("Expected the four originals and the first and fourth optional chest")
    return size, placements


def compose(atlas, dimensions, placements):
    side, atlas_width, sx, sy, width, height = dimensions
    if atlas.size != (atlas_width, side):
        raise ValueError(f"Unexpected atlas dimensions {atlas.size}")
    face = atlas.crop((0, 0, side, side))
    stamp = atlas.crop((sx, sy, sx + width, sy + height))
    for _, left, top in placements:
        face.alpha_composite(stamp, dest=(left, top))
    return face


def with_glints(face, glints, phase):
    tile = glints.crop((0, 8 * phase, 16, 8 * (phase + 1)))
    image = face.copy()
    for position in GLINT_POSITIONS:
        image.alpha_composite(tile, dest=position)
    return image


def project(face, yaw):
    # Pinhole camera 3.5 face widths away. A head-on square spans 168 screen
    # pixels. Inverting the projection maps each screen pixel to its source UV.
    view = (202, 194)
    supersample = 3
    width, height = (length * supersample for length in view)
    cx, cy = width / 2, height / 2
    angle = math.radians(yaw)
    cos = math.cos(angle)
    depth = math.sin(angle) / 3.5
    denominator = 168 * supersample * cos + cx * depth
    side = face.width
    coeffs = (
        side * (1 - depth / 2) / denominator, 0, side * (.5 - cx / denominator),
        -side * depth / (2 * denominator), side * cos / denominator,
        side * (.5 - cos * cy / denominator), -depth / denominator, 0,
    )
    # Nearest-neighbour source sampling preserves texels. Antialias only the
    # reduction to final screen size, with premultiplied alpha at the edges.
    plane = face.convert("RGBa").transform((width, height), Image.Transform.PERSPECTIVE,
                                           coeffs, Image.Resampling.NEAREST)
    return plane.resize(view, Image.Resampling.LANCZOS).convert("RGBA")


def sheet(before, after):
    canvas = Image.new("RGB", (540, 516), "#f2efea")
    draw = ImageDraw.Draw(canvas)
    title = ImageFont.load_default(size=26)
    label = ImageFont.load_default(size=17)
    body = ImageFont.load_default(size=14)
    ink = "#2d2934"
    draw.text((18, 12), "Pocket entrance", font=title, fill=ink)
    draw.text((19, 46), "Same six closed chests  /  Offline texture preview", font=body, fill="#534d59")
    for x, caption in ((116, "Front"), (326, "38° side view")):
        draw.text((x+101, 70), caption, font=label, fill=ink, anchor="mt")
    for y, name, face in ((94, "Previous", before), (302, "New frame", after)):
        draw.text((18, y+97), name, font=label, fill=ink, anchor="lm")
        for x, angle in ((116, 0), (326, 38)):
            plane = project(face, angle)
            # Each camera sample uses the same neutral backdrop beneath transparent texels.
            draw.rectangle((x, y, x+201, y+193), fill="#57545e")
            canvas.paste(plane, (x, y), plane)
    return canvas


def main(jdk, output):
    dimensions, placements = sample_commands(jdk)
    baseline = subprocess.run(["git", "show", f"{BASELINE}:{(ART / 'entrance_chest_layout.png').as_posix()}"],
                              check=True, cwd=ROOT, capture_output=True).stdout
    with Image.open(io.BytesIO(baseline)) as asset:
        before = compose(asset.convert("RGBA"), dimensions, placements)
    with Image.open(ROOT / ART / "framed_chest_still.png") as asset:
        framed = compose(asset.convert("RGBA"), dimensions, placements)
    with Image.open(ROOT / ART / "framed_still_glints.png") as asset:
        glints = asset.convert("RGBA")
    if glints.size != (16, 64):
        raise ValueError("The glint strip must contain eight 16 by 8 tiles")
    frames = [with_glints(framed, glints, phase) for phase in range(8)]
    output.mkdir(parents=True, exist_ok=True)
    sheet(before, frames[2]).save(output / "framed-chest-comparison.png")
    # Show the small frame glints at native resolution and the authored cadence.
    loop = []
    for frame in frames:
        matte = Image.new("RGB", frame.size, "#57545e")
        matte.paste(frame, mask=frame.getchannel("A"))
        loop.append(matte)
    # Use a single palette so changing a glint cannot shift colors elsewhere.
    colors = Image.new("RGB", (framed.width, framed.height * len(loop)))
    for index, matte in enumerate(loop):
        colors.paste(matte, (0, index * framed.height))
    palette = colors.quantize(colors=256, dither=Image.Dither.NONE)
    loop = [matte.quantize(palette=palette, dither=Image.Dither.NONE) for matte in loop]
    loop[0].save(output / "framed-chest-glints.gif", save_all=True, append_images=loop[1:],
                 duration=GLINT_MS, loop=0, disposal=1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, help="JDK 21 home (default: java and javac on PATH)")
    args = parser.parse_args()
    main(args.jdk, args.output)

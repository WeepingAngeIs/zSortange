"""Compare the candidate's production stamp commands against eight known room positions.

Requires Python 3, Pillow 10+, and JDK 21. Pass --output DIR and optionally --jdk DIR.
Inputs are the candidate's source, its packaged textures, and fabricated masks only.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from PIL import Image, ImageChops, ImageDraw, ImageFont


FIXTURE = Path(__file__).resolve().parent
MAIN = FIXTURE.parents[1] / "src/main"
PROJECTION = MAIN / "java/dev/anya/storage/pocket/ChestLayoutProjection.java"
TEXTURES = MAIN / "resources/assets/anyastorage/textures/entity"
ATLAS = TEXTURES / "entrance_chest_layout.png"
STUDY = TEXTURES / "starter_room_study.png"
# Registry order from PocketService.chestPositions then PocketRoomLayout.pads.
# Values are relative X and Z in blocks at Y=65.
PADS = ((6, 6), (10, 6), (14, 6), (18, 6), (6, 14), (10, 14), (22, 14), (26, 14))


def production_frames(jdk):
    def executable(name):
        return str(jdk / "bin" / name) if jdk else name

    with tempfile.TemporaryDirectory() as classes:
        subprocess.run([executable("javac"), "--release", "21", "-d", classes,
                        PROJECTION, PROJECTION.with_name("RoomBounds.java"),
                        FIXTURE / "ChestLayoutProbe.java"], check=True)
        probe = subprocess.run([executable("java"), "-cp", classes, "ChestLayoutProbe"],
                               check=True, capture_output=True, text=True)
    dimensions, *rows = probe.stdout.splitlines()
    frames = {}
    for row in rows:
        mask, eligible, *placements = row.split()
        frames[int(mask)] = eligible == "true", [tuple(map(int, p.split(","))) for p in placements]
    return tuple(map(int, dimensions.split())), frames


def expected_position(bit, width, height):
    x, z = PADS[bit]
    return 56 + 6 * x - 2 * z - width // 2, 96 + x + 3 * z - height + 1


def digest(data):
    return hashlib.sha256(data).hexdigest()


def comparison_sheet(images, path):
    sheet = Image.new("RGB", (612, 746), "#f4f2ed")
    draw = ImageDraw.Draw(sheet)
    title, heading, body = [ImageFont.load_default(size=size) for size in (28, 19, 14)]
    draw.text((24, 16), "Anya Storage Plus", font=title, fill="#211e29")
    draw.text((24, 55), "Chest layout comparison  /  Offline, with sample layouts", font=body, fill="#47414e")
    panels = [
        (-1, "Public illustration", "The fallback face"),
        (15, "Four original chests", "All optional pads empty"),
        (31, "First optional pad filled", "Room position X 6, Z 14"),
        (143, "Fourth optional pad filled", "Room position X 26, Z 14"),
    ]
    for index, (mask, label, detail) in enumerate(panels):
        left, top = 24 + index % 2 * 304, 90 + index // 2 * 327
        draw.text((left, top), label, fill="#211e29", font=heading)
        draw.text((left, top + 26), detail, fill="#47414e", font=body)
        face = images[mask].resize((256, 256), Image.Resampling.NEAREST)
        x, y = left, top + 50
        draw.rectangle((x-1, y-1, x+256, y+256), fill="#201d28", outline="#756b7e")
        sheet.paste(face, (x, y), face)
    sheet.save(path)


def compare(jdk, output):
    (size, atlas_width, sx, sy, width, height), frames = production_frames(jdk)
    with Image.open(ATLAS) as asset:
        atlas = asset.convert("RGBA")
    with Image.open(STUDY) as asset:
        public = asset.convert("RGBA")
    assert atlas.size == (atlas_width, size) and atlas_width >= sx + width and size >= sy + height
    base = atlas.crop((0, 0, size, size))
    stamp = atlas.crop((sx, sy, sx + width, sy + height))
    assert set(stamp.getchannel("A").getdata()) == {0, 255}, "Expected transparent cutout and opaque chest pixels"
    assert base.tobytes() != public.resize(base.size, Image.Resampling.NEAREST).tobytes()

    images = {}
    invalid_count = 0
    assert set(frames) == set(range(-1, 257)) | {0x10f}
    for mask, (eligible, placements) in frames.items():
        expected_valid = 0 <= mask <= 255 and mask % 16 == 15
        assert eligible == expected_valid, f"Eligibility for mask {mask}"
        if not eligible:
            assert not placements, f"Stamp emitted for fallback mask {mask}"
            invalid_count += 1
            if mask == -1:
                images[mask] = public.copy()
            continue
        bits = [bit for bit in range(len(PADS)) if mask & (1 << bit)]
        expected = [(bit, *expected_position(bit, width, height)) for bit in bits]
        assert placements == expected, f"Wrong chest, floor position or draw order for mask {mask}"
        face = base.copy()
        for bit, left, top in placements:
            assert 0 <= left <= size - width and 0 <= top <= size - height, f"Pad {bit} clipped"
            face.alpha_composite(stamp, dest=(left, top))
        images[mask] = face

    # Independently check every possible single optional change: all four pads
    # both absent and present, in every context of the other three optional pads.
    delta_bounds = {}
    changed_pairs = 0
    for mask in range(15, 256, 16):
        for bit in range(4, 8):
            if mask & (1 << bit):
                continue
            left, top = expected_position(bit, width, height)
            previous, current = images[mask], images[mask | (1 << bit)]
            # Paint only the expected rectangle on the previous face. Every other
            # pixel, including transparent edges, must remain byte-for-byte equal.
            expected = previous.copy()
            expected.paste(stamp, (left, top), stamp.getchannel("A"))
            assert current.tobytes() == expected.tobytes(), f"Unexpected pixel change at pad {bit}"
            bounds = ImageChops.difference(current, previous).getbbox(alpha_only=False)
            assert bounds and left <= bounds[0] < bounds[2] <= left + width
            assert top <= bounds[1] < bounds[3] <= top + height
            delta_bounds.setdefault(bit, bounds)
            assert delta_bounds[bit] == bounds, f"Another pad hides the silhouette at pad {bit}"
            changed_pairs += 1
    assert changed_pairs == 32 and len(set(delta_bounds.values())) == 4
    assert len({digest(images[mask].tobytes()) for mask in range(15, 256, 16)}) == 16

    output.mkdir(parents=True, exist_ok=True)
    png = output / "chest-layout-comparison.png"
    comparison_sheet(images, png)
    receipt = {
        "scope": "Offline face-texture composition. Fabricated masks only. No Minecraft, GPU, or server permissions exercised.",
        "valid_layouts": 16, "ineligible_masks_emitting_zero_stamps": invalid_count,
        "single_optional_pad_changes_checked": changed_pairs,
        "changed_pixel_boxes_left_top_right_bottom_exclusive": delta_bounds,
        "source_sha256": {p.name: digest(p.read_bytes()) for p in (PROJECTION, ATLAS, STUDY)},
        "public_artwork_rgba_sha256": digest(images[-1].tobytes()),
        "comparison_png_sha256": digest(png.read_bytes()),
    }
    receipt_path = output / "chest-layout-comparison.json"
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(f"Passed 16 layouts and 32 single-pad pixel comparisons. Results in {output}")


if __name__ == "__main__":
    if not __debug__:
        raise SystemExit("Run without -O to enable assertions.")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, help="JDK home (default: java and javac on PATH)")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    compare(args.jdk, args.output)

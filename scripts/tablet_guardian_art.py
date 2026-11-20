"""Build the tablet's blue double-blink atlas and idle glance from the supplied Figura avatar."""

import argparse
import hashlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from cupid_face_render import render_faces


SOURCE_SHA256 = "bbb6c314b54dc1b2d50b52ce62113fe22814563c28dc30724ef1d5f0db02540b"
FRAME = (240, 144)
GUI = Path(__file__).resolve().parents[1] / "src/main/resources/assets/anyastorage/textures/gui"


def glass(luminance, head):
    # Screen exposure and blue phosphor are identical for every pose.
    width, height = FRAME
    rim = Image.new("RGB", FRAME, (3, 7, 18))
    pen = ImageDraw.Draw(rim)
    pen.rounded_rectangle((1, 1, 238, 142), 10, fill=(11, 23, 42), outline=(44, 69, 103))
    pen.rounded_rectangle((4, 4, 235, 139), 8, fill=(3, 9, 22), outline=(25, 44, 70))
    pen.line((16, 2, 157, 2), fill=(55, 79, 110))
    inner = (width - 16, height - 16)
    y, x = np.indices((inner[1], inner[0]))
    fx = (x + 0.5 - inner[0] / 2) / (inner[0] / 2)
    fy = (y + 0.5 - inner[1] / 2) / (inner[1] / 2)
    tone = np.interp(luminance, [43, 65, 96, 130, 161, 192, 230],
                     [0, 16, 66, 138, 204, 239, 255])
    surface = Image.fromarray(np.uint8(np.where(head, tone, 0))).resize(inner, Image.Resampling.LANCZOS)
    detail = np.array(surface, dtype=float)
    glow = np.array(surface.filter(ImageFilter.GaussianBlur(1.25)), dtype=float)
    vignette = np.clip(1.08 - 0.23 * fx**2 - 0.17 * fy**2, 0.59, 1)
    # Fade the bottom of the image into the glass above the silver rail.
    foot = np.clip((inner[1] - 1 - y) / 9, 0, 1)
    foot = foot * foot * (3 - 2 * foot)
    intensity = (0.90 * detail + 0.10 * glow) * vignette * foot + 12 * (1 - 0.48 * fx**2 - 0.45 * fy**2)
    intensity *= np.where(y % 3 == 2, 0.89, 1.0)
    values = [0, 16, 48, 98, 155, 209, 255]
    ramp = np.array([(1, 5, 14), (5, 16, 37), (10, 38, 77), (19, 73, 135),
                     (49, 125, 201), (108, 183, 245), (193, 227, 255)])
    colors = np.stack([np.interp(intensity, values, ramp[:, c]) for c in range(3)], axis=-1)
    clip = Image.new("L", inner)
    ImageDraw.Draw(clip).rounded_rectangle((0, 0, inner[0]-1, inner[1]-1), 6, fill=255)
    rim.paste(Image.fromarray(colors.astype(np.uint8)), (8, 8), clip)
    return rim


def phosphor_input(portrait):
    """Convert the pink hair and peach skin to distinct intensities on the single-color display."""
    rgb = np.asarray(portrait.convert("RGB"), dtype=float)
    red, green, blue = np.moveaxis(rgb, -1, 0)
    pink = np.maximum(0, np.minimum(red - green, blue - green))
    brightness = np.interp(np.asarray(portrait.convert("L")), [0, 50, 100, 160, 210, 240, 255],
                           [0, 47, 88, 126, 175, 218, 245])
    return brightness - .90 * pink


def build(source, output):
    if hashlib.sha256(source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Expected the supplied Anya Cupid - twin tails.zip")
    portraits = render_faces(source)
    original = portraits["OPEN"].convert("RGBA")
    base = np.asarray(original)
    head = base[:, :, 3] > 32
    opening = glass(phosphor_input(original), head)
    stages = {"OPEN": opening}
    for state in ("HALF", "CLOSED", "GAZE"):
        portrait = portraits[state].convert("RGBA")
        pixels = np.asarray(portrait)
        if pixels.shape != base.shape or not np.array_equal(pixels[:, :, 3], base[:, :, 3]):
            raise ValueError(f"The avatar silhouette moved in {state}")
        moved = np.any(pixels[:, :, :3] != base[:, :, :3], axis=2) & head
        if not moved.any():
            raise ValueError(f"No eye motion in {state}")
        # Localize the bloom and resampling to changed eye texels; leave fringe, cheeks and glass fixed.
        localized = Image.new("L", FRAME)
        localized.paste(Image.fromarray(moved.astype(np.uint8) * 255).resize(
            (224, 128), Image.Resampling.BOX), (8, 8))
        lit = glass(phosphor_input(portrait), head)
        stages[state] = Image.composite(lit, opening, localized)
    if len({im.tobytes() for im in stages.values()}) != 4:
        raise ValueError("The open eyes, half blink, closed eyes and idle glance must all be distinct")
    atlas = Image.new("RGB", (FRAME[0], FRAME[1] * 3))
    for row, name in enumerate(("OPEN", "HALF", "CLOSED")):
        atlas.paste(stages[name], (0, row * FRAME[1]))
    output.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(output, optimize=True)
    stages["GAZE"].save(output.with_name("tablet_gaze_left.png"), optimize=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Anya Cupid - twin tails.zip")
    parser.add_argument("--output", type=Path, default=GUI / "tablet_guardian.png",
                        help="Path to the atlas. The matching idle glance is written beside it.")
    args = parser.parse_args()
    build(args.source, args.output)

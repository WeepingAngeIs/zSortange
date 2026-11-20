"""Generate the silver shell and export the matching checked-in idle portrait."""
import argparse
import math
from pathlib import Path

from PIL import Image, ImageDraw


GUI = Path(__file__).resolve().parents[1] / "src/main/resources/assets/anyastorage/textures/gui"
SHELL = (432, 304)
APERTURE = (16, 20, 416, 288)
DARK = (8, 16, 26)


def petal(draw, center, angle, length, width, fill):
    # Faceted petals: silver on the upper-left plane, blued steel in the crease.
    def point(across, along):
        x = across * math.cos(angle) + along * math.sin(angle)
        y = across * math.sin(angle) - along * math.cos(angle)
        return round(center[0] + x), round(center[1] + y)

    shape = [point(0, 0), point(-width * .53, length * .32), point(-width, length * .70),
             point(-width * .55, length), point(width * .28, length * .95),
             point(width * .82, length * .80), point(width * .72, length * .42)]
    draw.polygon(shape, fill=fill, outline="#51647a")
    draw.line(shape[1:5], fill="#e2ebf2")
    draw.line([point(-width * .35, length * .78), point(0, length * .46), point(0, length * .22)],
              fill="#aab9c8")


def silver_flower(draw):
    # The blossom occupies the top rail; the leaf on the right turns down beside the glass.
    for offset, color in [(1, "#68798b"), (0, "#dbe4eb")]:
        draw.line([(359, 13 + offset), (373, 13 + offset), (383, 9 + offset),
                   (392, 8 + offset), (405, 12 + offset), (415, 15 + offset),
                   (423, 24 + offset), (423, 35 + offset)], fill=color)
    leaves = [
        [(381, 10), (374, 6), (361, 6), (368, 10), (377, 11)],
        [(389, 9), (383, 11), (375, 15), (384, 15), (391, 11)],
        [(397, 10), (391, 5), (384, 4), (389, 9)],
        [(418, 18), (422, 13), (426, 12), (424, 18), (420, 21)],
        [(423, 25), (426, 19), (428, 17), (427, 25), (424, 30)],
    ]
    for leaf in leaves:
        draw.polygon(leaf, fill="#9aa9bb", outline="#677a91")
        draw.line([leaf[0], leaf[2]], fill="#e9eef3")
    cx, cy = 408, 10
    for i in range(6):
        petal(draw, (cx, cy), math.radians(20 + 60*i), 8.8, 4.6, "#bac6d1")
    for i in range(5):
        petal(draw, (cx, cy), math.radians(-8 + 72*i), 5.2, 2.6, "#dce5ed")
    draw.point((cx, cy + 1), fill="#64758c")
    draw.point((cx, cy), fill="#f5f8fa")


def shell():
    image = Image.new("RGBA", SHELL)
    draw = ImageDraw.Draw(image)
    # Multiple stepped metal surfaces keep the profile legible when the tablet is held small.
    for inset, radius, color in [(0, 14, "#243142"), (1, 13, "#738297"), (2, 12, "#c8d2db"),
                                  (3, 11, "#8697ab"), (5, 10, "#586d83"), (6, 9, "#a9b7c6")]:
        draw.rounded_rectangle((inset, inset, 431-inset, 303-inset), radius, fill=color)
    surface = Image.new("RGBA", SHELL)
    pixels = surface.load()
    for y in range(SHELL[1]):
        for x in range(SHELL[0]):
            # Two broad polished bands and a subtle directional grain; no warm-metal pigments.
            shine = 14 * math.cos((x + 1.7*y) * .022) + 7 * math.sin((x-.4*y) * .047)
            grain = ((y * 23 + x // 18 * 3) % 9 - 4) * .5
            value = round(165 + shine + grain - y * .045)
            pixels[x, y] = (value - 9, value + 2, value + 14, 255)
    mask = Image.new("L", SHELL)
    ImageDraw.Draw(mask).rounded_rectangle((6, 6, 425, 296), radius=9, fill=255)
    image.paste(surface, mask=mask)
    draw.line([(3, 61), (3, 15), (8, 7), (15, 3), (302, 3)], fill="#f0f4f6")
    draw.line([(429, 52), (429, 289), (423, 297), (416, 300), (108, 300)], fill="#41566c")
    draw.line([(26, 8), (184, 8), (195, 11), (331, 11), (339, 8), (353, 8)], fill="#899aad")
    draw.line([(26, 9), (184, 9), (195, 12), (331, 12), (339, 9), (353, 9)], fill="#d1dbe2")
    draw.rounded_rectangle((11, 15, 420, 292), radius=7, fill="#6d8093", outline="#dce4ea")
    draw.rounded_rectangle((13, 17, 418, 290), radius=5, fill="#263748", outline="#253546")
    draw.line([(16, 289), (415, 289)], fill="#758696")
    draw.rectangle((16, 20, 415, 287), fill=DARK)
    for x, y in [(9, 10), (10, 293), (421, 294)]:
        draw.line((x-1, y+1, x+1, y+1), fill="#d7e1e8")
        draw.line((x-1, y, x+1, y), fill="#6f8194")
    draw.line([(175, 296), (181, 294), (251, 294), (257, 296)], fill="#6a7a8e")
    draw.line([(177, 297), (182, 295), (250, 295), (255, 297)], fill="#cad4dd")
    silver_flower(draw)
    assert image.crop(APERTURE).getextrema() == tuple((v, v) for v in (*DARK, 255))
    return image


def main(output):
    # The idle gaze is rendered from the avatar's moving irises alongside the blink atlas.
    gaze = GUI / "tablet_gaze_left.png"
    with Image.open(gaze) as image:
        if image.size != (240, 144):
            raise ValueError("Expected the tablet's idle portrait")
    output.mkdir(parents=True, exist_ok=True)
    shell().save(output / "tablet_device.png", optimize=True)
    destination = output / gaze.name
    if gaze.resolve() != destination.resolve():
        destination.write_bytes(gaze.read_bytes())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=GUI)
    main(parser.parse_args().output)

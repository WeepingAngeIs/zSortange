"""Bake the physical Cupid Tablet's atlas sprites from its approved screen artwork."""
from pathlib import Path

from PIL import Image


TEXTURES = Path(__file__).resolve().parents[1] / "src/main/resources/assets/anyastorage/textures"
FRONT_SIZE = (256, 176)


def portrait_on_shell(shell, sheet, row):
    # Match TabletDevice.draw's 224 x 128 crop and its placement in the 400 x 268 aperture.
    top = 8 + row * 144
    portrait = sheet.crop((8, top, 232, top + 128)).resize((400, 229), Image.Resampling.NEAREST)
    frame = shell.copy()
    frame.alpha_composite(portrait, (16, 40))
    return frame


def generate():
    gui = TEXTURES / "gui"
    with Image.open(gui / "tablet_device.png") as image:
        shell = image.convert("RGBA")
    with Image.open(gui / "tablet_guardian.png") as image:
        eyes = image.convert("RGBA")
    with Image.open(gui / "tablet_gaze_left.png") as image:
        glance = image.convert("RGBA")
    if (shell.size, eyes.size, glance.size) != ((432, 304), (240, 432), (240, 144)):
        raise ValueError("Unexpected tablet artwork dimensions")

    frames = [shell, *(portrait_on_shell(shell, eyes, row) for row in range(3)),
              portrait_on_shell(shell, glance, 0)]
    width, height = FRONT_SIZE
    strip = Image.new("RGBA", (width, height * len(frames)))
    for index, frame in enumerate(frames):
        strip.paste(frame.resize(FRONT_SIZE, Image.Resampling.NEAREST), (0, index * height))

    # Use the actual silver rail for the back and edges. The back has no screen.
    metal = Image.new("RGBA", (128, 96))
    metal.paste(shell.crop((32, 0, 160, 6)), (0, 0))
    metal.paste(shell.crop((32, 293, 160, 297)).resize((128, 84), Image.Resampling.NEAREST), (0, 6))
    metal.paste(shell.crop((32, 298, 160, 304)), (0, 90))
    item = TEXTURES / "item"
    item.mkdir(parents=True, exist_ok=True)
    strip.save(item / "cupid_tablet_face.png", optimize=True)
    for index, name in enumerate(("dark", "open", "squint", "shut", "glance")):
        frame = strip.crop((0, index * height, width, (index + 1) * height))
        frame.save(item / f"cupid_tablet_face_{name}.png", optimize=True)
    metal.save(item / "cupid_tablet_case.png", optimize=True)


if __name__ == "__main__":
    generate()

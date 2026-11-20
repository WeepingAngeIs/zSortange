"""Repack the approved nine-beat Twin Tails portraits onto the two existing physical tablet cases."""
import json
from pathlib import Path

from PIL import Image


ASSETS = Path(__file__).resolve().parents[1] / "src/main/resources/assets/anyastorage"
LEGACY = ("squint", "shut", "glance", "open")


def expressions():
    for row in range(7):
        for column in range(9):
            yield row, column, f"expression_{row}_{column}"


def write_model(folder, name, model):
    (folder / (name + ".json")).write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")


def write_tablet_overrides(folder, item):
    # In 1.21.1 the last matching JSON override wins. Preserve the original high thresholds.
    states = [(name, (1 + row * 9 + column) / 256) for row, column, name in expressions()]
    states.extend((name, index / 4) for index, name in enumerate(LEGACY, 1))
    write_model(folder, item, {"parent": f"anyastorage:item/{item}_shell", "overrides": [
        {"predicate": {"anyastorage:cupid_face": value}, "model": f"anyastorage:item/{item}_{name}"}
        for name, value in states]})


def generate():
    textures = ASSETS / "textures"
    models = ASSETS / "models/item"
    with Image.open(textures / "gui/tablet_twin_tails.png") as image:
        sheet = image.convert("RGBA")
    if sheet.size != (9 * 224, 7 * 128):
        raise ValueError("Unexpected Twin Tails head sheet size")
    for item, shell_name in (("cupid_tablet", "tablet_device"), ("creative_tablet", "creative_tablet_device")):
        with Image.open(textures / f"gui/{shell_name}.png") as image:
            shell = image.convert("RGBA")
        for row, column, face in expressions():
            x, y = column * 224, row * 128
            portrait = sheet.crop((x, y, x + 224, y + 128))
            # Match the baked legacy glass exactly, including its nearest-neighbour sampling.
            front = shell.copy()
            front.alpha_composite(portrait.resize((400, 229), Image.Resampling.NEAREST), (16, 40))
            sprite = f"{item}_face_{face}"
            front.resize((256, 176), Image.Resampling.NEAREST).save(textures / f"item/{sprite}.png", optimize=True)
            write_model(models, f"{item}_{face}", {"parent": f"anyastorage:item/{item}_shell",
                        "textures": {"face": f"anyastorage:item/{sprite}"}})
        write_tablet_overrides(models, item)


if __name__ == "__main__":
    generate()

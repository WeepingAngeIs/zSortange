"""Tint the existing physical tablet's engraved silver rails. Aperture and Cupid pixels are copied verbatim."""
import json
from pathlib import Path

from PIL import Image, ImageDraw


ASSETS = Path(__file__).resolve().parents[1] / "src/main/resources/assets/anyastorage"


def generate():
    gui = ASSETS / "textures/gui"
    items = ASSETS / "textures/item"
    with Image.open(gui / "tablet_device.png") as image:
        shell = image.convert("RGBA")
    mask = Image.new("L", shell.size, 255)
    ImageDraw.Draw(mask).rectangle((16, 20, 415, 287), fill=0)
    # Keep the engraving, silhouettes, transparent corners and relative highlights of the silver.
    luminance = shell.convert("L")
    rose = Image.merge("RGBA", (
        luminance.point(lambda y: min(255, round(.90 * y + 62))),
        luminance.point(lambda y: round(.85 * y + 7)),
        luminance.point(lambda y: min(255, round(.86 * y + 32))),
        shell.getchannel("A"),
    ))
    creative = Image.composite(rose, shell, mask)
    creative.save(gui / "creative_tablet_device.png", optimize=True)
    for face in ("dark", "squint", "shut", "glance", "open"):
        with Image.open(items / f"cupid_tablet_face_{face}.png") as image:
            original = image.convert("RGBA")
        target = original.size
        rim = creative.resize(target, Image.Resampling.NEAREST)
        item_mask = mask.resize(target, Image.Resampling.NEAREST)
        Image.composite(rim, original, item_mask).save(items / f"creative_tablet_face_{face}.png", optimize=True)

    models = ASSETS / "models/item"
    def write(name, model):
        (models / (name + ".json")).write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")

    shell_id = "anyastorage:item/creative_tablet_shell"
    write("creative_tablet_shell", {"parent": "anyastorage:item/cupid_tablet_shell",
                                   "textures": {"face": "anyastorage:item/creative_tablet_face_dark"}})
    variants = []
    for index, face in enumerate(("squint", "shut", "glance", "open"), 1):
        name = "creative_tablet_" + face
        write(name, {"parent": shell_id, "textures": {"face": "anyastorage:item/creative_tablet_face_" + face}})
        variants.append({"predicate": {"anyastorage:cupid_face": index / 4}, "model": "anyastorage:item/" + name})
    write("creative_tablet", {"parent": shell_id, "overrides": variants})


if __name__ == "__main__":
    generate()

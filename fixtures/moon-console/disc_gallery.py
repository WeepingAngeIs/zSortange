"""Verify and label the nine actual item sprites extracted from the installable JAR."""
from io import BytesIO
import json
from pathlib import Path
import sys
from zipfile import ZipFile

from PIL import Image, ImageDraw, ImageFont

IDS = ["pickup_upgrade", "feeding_upgrade", "refill_upgrade", "crafting_upgrade", "smelting_upgrade",
       "compacting_upgrade", "resupply_upgrade", "storage_core", "dragon_victory_token"]


def gallery(archive, target):
    page = Image.new("RGB", (624, 384), "#222430")
    draw = ImageDraw.Draw(page)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 15)
    seen = set()
    with ZipFile(archive) as jar:
        root = "assets/anyastorage/"
        lang = json.loads(jar.read(root + "lang/en_us.json"))
        for index, key in enumerate(IDS):
            model = json.loads(jar.read(root + "models/item/" + key + ".json"))
            assert model["textures"]["layer0"] == "anyastorage:item/" + key
            icon = Image.open(BytesIO(jar.read(root + "textures/item/" + key + ".png"))).convert("RGBA")
            assert icon.size == (32,32)
            native = icon.resize((16,16), Image.Resampling.NEAREST)
            assert native.tobytes() not in seen
            seen.add(native.tobytes())
            alpha = icon.getchannel("A")
            assert all(alpha.getpixel(point) == 0 for point in [(4,4),(27,4),(4,27),(27,27)])
            if key.endswith("_upgrade"):
                for point in [(16,3),(3,15),(15,28),(28,16)]:
                    assert alpha.getpixel(point) == 255, key
            x, y = (index % 3)*208, (index // 3)*128
            large = icon.resize((82,82), Image.Resampling.NEAREST)
            page.paste(large, (x+62,y+8), large)
            page.paste(native,(x+153,y+69),native)
            label = lang["item.anyastorage." + key]
            draw.text((x+104,y+96), label, fill="#eee9e0", font=font, anchor="mt")
    target.parent.mkdir(parents=True,exist_ok=True)
    page.save(target)
    print("PASS nine distinct packaged items at actual hotbar size, transparent edges and readable names.")


if __name__ == "__main__":
    gallery(sys.argv[1],Path(sys.argv[2]))

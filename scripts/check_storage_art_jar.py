"""Check the actual candidate archive and the order of its custom control sprites."""

import ast
import json
from pathlib import Path
import re
import struct
import sys
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
ASSETS = "assets/anyastorage/"


def check_jar(path):
    declarations = (ROOT / "src/main/java/dev/anya/storage/ui/ControlGlyph.java").read_text()
    enum = declarations.split("public enum ControlGlyph {", 1)[1].split(";", 1)[0]
    glyphs = re.findall(r"\b[A-Z][A-Z_0-9]*\b", enum)
    catalog = next(node.value.value for node in ast.parse((ROOT / "scripts/control_atlas.py").read_text()).body
                   if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "CATALOG" for t in node.targets))
    assert glyphs == [line.split()[0] for line in catalog.strip().splitlines()], "Atlas order differs from menu controls"
    assert len(glyphs) == len(set(glyphs)) <= 256
    with ZipFile(path) as jar:
        for resource, size in [("textures/gui/control_atlas.png", (256, 256)),
                               ("textures/block/chest_shell.png", (128, 128)),
                               ("textures/entity/starter_room_study.png", (128, 128)),
                               ("textures/entity/pocket_pad_plan.png", (512, 256))]:
            png = jar.read(ASSETS + resource)
            assert png.startswith(b"\x89PNG\r\n\x1a\n") and struct.unpack(">II", png[16:24]) == size, resource
        assert jar.read(ASSETS + "textures/gui/control_atlas.png") == (
            ROOT / "src/main/resources" / ASSETS / "textures/gui/control_atlas.png").read_bytes()
        for material in ("starter_room_study.png", "pocket_pad_plan.png"):
            entry = ASSETS + "textures/entity/" + material
            assert jar.read(entry) == (ROOT / "src/main/resources" / entry).read_bytes(), material
        for part in ("chest", "chest_base", "chest_lid"):
            model = json.loads(jar.read(ASSETS + f"models/block/{part}.json"))
            assert model["loader"] == "neoforge:obj"
            assert model["model"] == "anyastorage:models/block/chest_shell.obj"
        assert jar.read(ASSETS + "models/block/chest_shell.obj")
        assert jar.read(ASSETS + "models/block/chest_shell.mtl")
        lang = json.loads(jar.read(ASSETS + "lang/en_us.json"))
        sounds = json.loads(jar.read(ASSETS + "sounds.json"))
        for event in ("open", "close"):
            entry = sounds["chest_" + event]
            assert lang[entry["subtitle"]] and entry["sounds"] == ["anyastorage:chest/" + event]
            audio = jar.read(ASSETS + f"sounds/chest/{event}.ogg")
            assert audio.startswith(b"OggS") and b"\x01vorbis" in audio
        assert not any(name.startswith("dev/anya/storage/acceptance/") for name in jar.namelist())
        for name in jar.namelist():
            if name.startswith(("data/anyastorage/recipe/", ASSETS)) and name.endswith(".json"):
                assert b"minecraft:green_dye" not in jar.read(name), name
        oak = json.loads(jar.read("data/anyastorage/recipe/skin_oak.json"))
        assert "minecraft:oak_sapling" in str(oak)
    print(f"Verified {len(glyphs)} control sprite mappings, chest resources, two sounds, window art and Oak patch in {path.name}")


if __name__ == "__main__":
    check_jar(Path(sys.argv[1]))

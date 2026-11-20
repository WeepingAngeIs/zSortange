"""Build the two placeable furniture models using vanilla wood, metal, and terracotta textures."""
import json
from pathlib import Path


def generate(root: Path):
    def write(path, value):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")

    def piece(lo, hi, material):
        faces = {face: {"texture": "#" + material} for face in ("north", "south", "east", "west", "up", "down")}
        return {"from": lo, "to": hi, "faces": faces}

    textures = {key: "minecraft:block/" + value for key, value in {
        "particle": "stripped_dark_oak_log", "wood": "dark_oak_planks", "back": "cherry_planks",
        "trim": "stripped_cherry_log", "paper": "birch_planks", "metal": "copper_block",
        "slot": "black_terracotta", "felt": "pink_terracotta",
    }.items()}
    shelf = [
        piece([0, 0, 1], [16, 3, 15], "wood"), piece([0, 3, 2], [16, 4, 14], "trim"),
        piece([2, 4, 3], [14, 4.35, 12], "felt"), piece([1, 4, 12], [15, 12, 14], "back"),
        # Posts meet the deck at Y=4 and the underside of the crown at Y=12.
        piece([0, 4, 12], [1, 12, 15], "wood"), piece([15, 4, 12], [16, 12, 15], "wood"),
        piece([0, 12, 12], [16, 13, 15], "trim"), piece([2, .65, .5], [14, 2.9, 1], "paper"),
        piece([1, .8, .45], [2, 2.75, 1], "metal"), piece([14, .8, .45], [15, 2.75, 1], "metal"),
    ]
    hatch = [
        piece([1, 0, 1], [15, 2, 15], "wood"), piece([2, 2, 2], [14, 14, 14], "back"),
        piece([1, 13, 1], [15, 15, 15], "wood"), piece([2, 2, 1], [3, 13, 2], "trim"),
        piece([13, 2, 1], [14, 13, 2], "trim"), piece([3, 8, 1.25], [13, 11, 2.1], "metal"),
        piece([4, 9, 1], [12, 10, 1.4], "slot"), piece([4, 3, 1.35], [12, 7.5, 2.1], "wood"),
        piece([6.5, 6, 1], [9.5, 7, 1.4], "metal"),
    ]
    for name, elements, pattern, key in [
        ("keepsake_shelf", shelf, ["S S", "PCP"],
         {"S": {"item": "minecraft:stick"}, "P": {"tag": "minecraft:planks"}, "C": {"item": "minecraft:copper_ingot"}}),
        ("delivery_hatch", hatch, [" P ", "CBC", " P "],
         {"P": {"tag": "minecraft:planks"}, "C": {"item": "minecraft:copper_ingot"}, "B": {"item": "minecraft:barrel"}}),
    ]:
        base = f"anyastorage:block/{name}"
        write(f"assets/anyastorage/models/block/{name}.json",
              {"parent": "minecraft:block/block", "textures": textures, "elements": elements})
        write(f"assets/anyastorage/models/item/{name}.json", {"parent": base})
        write(f"assets/anyastorage/blockstates/{name}.json", {"variants": {
            "facing=" + face: {"model": base, "y": index * 90}
            for index, face in enumerate(("north", "east", "south", "west"))}})
        write(f"data/anyastorage/recipe/{name}.json", {"type": "minecraft:crafting_shaped", "category": "misc",
              "pattern": pattern, "key": key, "result": {"id": "anyastorage:" + name, "count": 1}})
        write(f"data/anyastorage/loot_table/blocks/{name}.json", {"type": "minecraft:block",
              "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "anyastorage:" + name}]}]})
    lang_path = "assets/anyastorage/lang/en_us.json"
    strings = json.loads((root / lang_path).read_text(encoding="utf-8"))
    strings.update({"block.anyastorage.keepsake_shelf": "Keepsake Shelf", "block.anyastorage.delivery_hatch": "Delivery Hatch"})
    write(lang_path, strings)
    write("data/carryon/tags/block/block_blacklist.json",
          {"replace":False, "values":["anyastorage:keepsake_shelf","anyastorage:delivery_hatch"]})

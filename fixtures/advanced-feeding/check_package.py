"""Check the playable feeding.1 JAR against the exact hosted cookers.1 package."""
from collections import Counter
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import sys
import tomllib
from zipfile import ZipFile

from PIL import Image


BASE_SHA = "a96760a7eec3570fbb0a267bf56dfd58ba4498acfa6cc7aba810c30cb48b1093"
VERSION = "0.3.0-beta.7-"
MOD = "anyastorage"
DISC = "advanced_feeding_upgrade"
ASSETS = f"assets/{MOD}/"
MODEL = ASSETS + f"models/item/{DISC}.json"
ICONS = ASSETS + "textures/item/"
RECIPE = f"data/{MOD}/recipe/{DISC}.json"
LANG = ASSETS + "lang/en_us.json"
METADATA = "META-INF/neoforge.mods.toml"
CLASSES = "dev/anya/storage/"
EDITED_CLASSES = {"AdvancedFeeding", "Config", "Upgrades", "StoreMenu", "guide/GuideContent"}


def members(jar):
    names = [info.filename for info in jar.infolist() if not info.is_dir()]
    assert len(names) == len(set(names)), "Duplicate JAR member"
    return set(names)


def read_json(jar, path):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            assert key not in value, f"Duplicate key {key} in {path}"
            value[key] = item
        return value

    return json.loads(jar.read(path), object_pairs_hook=unique)


def editable_class(path):
    if not path.startswith(CLASSES) or not path.endswith(".class"):
        return False
    family = path[len(CLASSES):-len(".class")].split("$", 1)[0]
    return family in EDITED_CLASSES


def check_baseline(old, new):
    before, after = members(old), members(new)
    assert new.testzip() is None, "Candidate JAR failed integrity check"
    added_art = {MODEL, RECIPE, ICONS + DISC + ".png"}
    added_files = {p for p in after - before if not editable_class(p)}
    assert added_files == added_art, sorted(added_files ^ added_art)
    assert not (before - after), f"Missing packaged files: {sorted(before - after)}"
    assert CLASSES + "AdvancedFeeding.class" in after
    for path in sorted(before - {LANG, METADATA}):
        if editable_class(path) or old.read(path) == new.read(path):
            continue
        assert path.endswith((".json", ".mcmeta")) and read_json(old, path) == read_json(new, path), path

    base_meta = tomllib.loads(old.read(METADATA).decode())
    new_meta = tomllib.loads(new.read(METADATA).decode())
    assert base_meta["mods"][0]["version"] == VERSION + "cookers.1"
    assert new_meta["mods"][0]["version"] == VERSION + "feeding.1"
    base_meta["mods"][0]["version"] = new_meta["mods"][0]["version"]
    assert base_meta == new_meta, "Loader metadata or dependencies changed"
    print("PASS package: verified cookers.1 recipes, art, skins, unrelated classes and loader dependencies preserved.")


def pixels(jar, file):
    with Image.open(BytesIO(jar.read(ICONS + file + ".png"))) as icon:
        assert icon.format == "PNG" and icon.size == (32, 32), file
        rgba = icon.convert("RGBA")
        assert rgba.getchannel("A").getextrema() == (0, 255), file
        return list(rgba.getdata())


def check_disc(old, new):
    model = read_json(new, MODEL)
    assert model == {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{DISC}"}}
    recipe = read_json(new, RECIPE)
    assert recipe["type"] == "minecraft:crafting_shapeless"
    assert recipe["neoforge:conditions"] == [{"type": f"{MOD}:recipe_group", "group": "upgrade.feeding"}]
    ingredients = recipe["ingredients"]
    assert all(set(ingredient) == {"item"} for ingredient in ingredients)
    assert Counter(ingredient["item"] for ingredient in ingredients) == Counter(
        [f"{MOD}:feeding_upgrade", "minecraft:clock", "minecraft:bowl"])
    assert recipe["result"] == {"id": f"{MOD}:{DISC}", "count": 1}

    basic, advanced = pixels(old, "feeding_upgrade"), pixels(new, DISC)
    difference = sum(a != b and bool(a[3] or b[3]) for a, b in zip(basic, advanced))
    assert difference >= 32, f"Only {difference} visible pixels differ between the basic and advanced discs"
    previous = read_json(old, LANG)
    current = read_json(new, LANG)
    assert all(current.get(key) == value for key, value in previous.items()), "Existing item text changed"
    label = f"item.{MOD}.{DISC}"
    prefix = f"tooltip.{MOD}.{DISC}."
    added_keys = current.keys() - previous.keys()
    expected = {prefix + str(i) for i in range(1, len(added_keys))} | {label}
    assert added_keys == expected and len(expected) >= 3, sorted(added_keys ^ expected)
    assert all(isinstance(current[key], str) and current[key].strip() for key in expected)
    print(f"PASS Advanced Feeding: basic disc, clock and bowl recipe, feeding gate, distinct icon ({difference} pixels) and numbered tooltip.")


def main(playable, baseline):
    assert sha256(baseline.read_bytes()).hexdigest() == BASE_SHA, "Unexpected cookers.1 baseline"
    with ZipFile(baseline) as old, ZipFile(playable) as new:
        check_baseline(old, new)
        check_disc(old, new)


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))

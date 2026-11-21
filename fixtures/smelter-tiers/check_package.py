"""Check the two new discs and compare all preceding gameplay data with the verified modules.1 JAR."""
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import sys
import tomllib
from zipfile import ZipFile

from PIL import Image

BASE_SHA = "26e3113fa2135c6331451d4d3933a04038c56993de5f286a7d9f55d7cf6bc8d7"
MOD = "anyastorage"
LANG = f"assets/{MOD}/lang/en_us.json"
META = "META-INF/neoforge.mods.toml"
MODELS = f"assets/{MOD}/models/item/"
ICONS = f"assets/{MOD}/textures/item/"
RECIPES = f"data/{MOD}/recipe/"
MANUAL, AUTO = "manual_smelting_upgrade", "auto_smelting_upgrade"


def entries(jar):
    names = [entry.filename for entry in jar.infolist() if not entry.is_dir()]
    assert len(names) == len(set(names)), "Duplicate packaged entry"
    return set(names)


def read_json(jar, name):
    return json.loads(jar.read(name))


def pixels(jar, name):
    with Image.open(io.BytesIO(jar.read(ICONS + name + ".png"))) as icon:
        assert icon.size == (32, 32), name
        rgba = icon.convert("RGBA")
        assert rgba.getchannel("A").getextrema() == (0, 255), name
        return list(rgba.getdata())


def verify(playable, baseline):
    assert hashlib.sha256(baseline.read_bytes()).hexdigest() == BASE_SHA, "Unexpected base version"
    with ZipFile(baseline) as old, ZipFile(playable) as new:
        before, after = entries(old), entries(new)
        assert new.testzip() is None
        old_data = {p for p in before if p.startswith(("assets/", "data/"))}
        new_data = {p for p in after if p.startswith(("assets/", "data/"))}
        added = {folder + name + suffix for name in (MANUAL, AUTO)
                 for folder, suffix in ((MODELS, ".json"), (ICONS, ".png"), (RECIPES, ".json"))}
        assert new_data == old_data | added, (new_data ^ (old_data | added))
        for path in old_data - {LANG}:
            if old.read(path) != new.read(path):
                assert path.endswith((".json", ".mcmeta")) and read_json(old, path) == read_json(new, path), path
        old_lang = read_json(old, LANG)
        lang = read_json(new, LANG)
        assert all(lang.get(key) == value for key, value in old_lang.items())
        for name, lines in ((MANUAL, 2), (AUTO, 3)):
            assert lang[f"item.{MOD}.{name}"]
            for i in range(1, lines + 1):
                assert lang[f"tooltip.{MOD}.{name}.{i}"].strip()
            model = read_json(new, MODELS + name + ".json")
            assert model == {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{name}"}}
            data = read_json(new, RECIPES + name + ".json")
            assert data["neoforge:conditions"] == [{"type": f"{MOD}:recipe_group", "group": "upgrade.smelting"}]
            assert data["result"] == {"id": f"{MOD}:{name}", "count": 1}
        regular = read_json(new, RECIPES + MANUAL + ".json")
        assert regular["type"] == "minecraft:crafting_shaped" and regular["pattern"] == ["CFC", " T "]
        assert regular["key"] == {letter: {"item": "minecraft:" + item} for letter, item in
                                  (("C", "copper_ingot"), ("F", "furnace"), ("T", "redstone_torch"))}
        auto = read_json(new, RECIPES + AUTO + ".json")
        assert auto["type"] == "minecraft:crafting_shapeless"
        assert Counter(x["item"] for x in auto["ingredients"]) == Counter(
            [f"{MOD}:{MANUAL}", "minecraft:clock", "minecraft:powered_rail"])
        icons = [pixels(new, name) for name in ("smelting_upgrade", MANUAL, AUTO)]
        for idx, first in enumerate(icons):
            for other in icons[idx+1:]:
                assert sum(p != q and bool(p[3] or q[3]) for p, q in zip(first, other)) >= 32
        base_meta = tomllib.loads(old.read(META).decode())
        meta = tomllib.loads(new.read(META).decode())
        assert base_meta["mods"][0]["version"].endswith("-modules.1")
        assert meta["mods"][0]["version"] == "0.3.0-beta.7-smelter.1"
        base_meta["mods"][0]["version"] = meta["mods"][0]["version"]
        assert base_meta == meta
        assert not any("smelterreview" in n.lower() or "SmelterGameTests" in n or "SmelterRestart" in n for n in after)
        classes = {p for p in before if p.endswith(".class")}
        changed = {"Config", "Upgrades", "StoreMenu", "guide/GuideContent"}
        for path in classes:
            family = path.removeprefix("dev/anya/storage/").removesuffix(".class").split("$")[0]
            if family not in changed:
                assert old.read(path) == new.read(path), path
        print("PASS package: prior resources, legacy recipe and independent classes (including chest/EMI) match verified modules.1.")
        print("PASS smelter discs: two gated recipes, exact ingredients, ordinary hover keys and three distinct furnace-family icons.")


if __name__ == "__main__":
    verify(Path(sys.argv[1]), Path(sys.argv[2]))

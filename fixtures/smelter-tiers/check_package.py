"""Compare the two specialist families with the verified smelter.1 JAR."""
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import sys
import tomllib
from zipfile import ZipFile

from PIL import Image

BASE_SHA = "0363dedd6e6bb7fbc7c6db0f6c000cd5680a5724a101bb8b94173f4fe5a7b8f1"
MOD = "anyastorage"
LANG = f"assets/{MOD}/lang/en_us.json"
META = "META-INF/neoforge.mods.toml"
MODELS = f"assets/{MOD}/models/item/"
ICONS = f"assets/{MOD}/textures/item/"
RECIPES = f"data/{MOD}/recipe/"
FAMILIES = {"smoking": ("smoker", "copper_grate"), "blasting": ("blast_furnace", "obsidian")}
SPECIALISTS = [f"{tier}_{family}_upgrade" for family in FAMILIES for tier in ("manual", "auto")]


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
        added = {folder + name + suffix for name in SPECIALISTS
                 for folder, suffix in ((MODELS, ".json"), (ICONS, ".png"), (RECIPES, ".json"))}
        assert new_data == old_data | added, (new_data ^ (old_data | added))
        for path in old_data - {LANG}:
            if old.read(path) != new.read(path):
                assert path.endswith((".json", ".mcmeta")) and read_json(old, path) == read_json(new, path), path
        old_lang = read_json(old, LANG)
        lang = read_json(new, LANG)
        assert all(lang.get(key) == value for key, value in old_lang.items())
        for name in SPECIALISTS:
            tier, family, _ = name.split("_")
            lines = 2 if tier == "manual" else 3
            assert lang[f"item.{MOD}.{name}"]
            for i in range(1, lines + 1):
                assert lang[f"tooltip.{MOD}.{name}.{i}"].strip()
            model = read_json(new, MODELS + name + ".json")
            assert model == {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{name}"}}
            data = read_json(new, RECIPES + name + ".json")
            assert data["neoforge:conditions"] == [{"type": f"{MOD}:recipe_group", "group": "upgrade." + family}]
            assert data["result"] == {"id": f"{MOD}:{name}", "count": 1}
        for family, (station, part) in FAMILIES.items():
            manual = f"manual_{family}_upgrade"
            regular = read_json(new, RECIPES + manual + ".json")
            assert regular["type"] == "minecraft:crafting_shaped" and regular["pattern"] == ["CFC", " T "]
            assert regular["key"] == {letter: {"item": "minecraft:" + item} for letter, item in
                                      (("C", "copper_ingot"), ("F", station), ("T", "redstone_torch"))}
            auto = read_json(new, RECIPES + f"auto_{family}_upgrade.json")
            assert auto["type"] == "minecraft:crafting_shapeless"
            assert Counter(x["item"] for x in auto["ingredients"]) == Counter(
                [f"{MOD}:{manual}", "minecraft:clock", "minecraft:" + part])
        icons = [pixels(new, name) for name in ("smelting_upgrade", "manual_smelting_upgrade", "auto_smelting_upgrade", *SPECIALISTS)]
        for idx, first in enumerate(icons):
            for other in icons[idx+1:]:
                assert sum(p != q and bool(p[3] or q[3]) for p, q in zip(first, other)) >= 32
        base_meta = tomllib.loads(old.read(META).decode())
        meta = tomllib.loads(new.read(META).decode())
        assert base_meta["mods"][0]["version"] == "0.3.0-beta.7-smelter.1"
        assert meta["mods"][0]["version"] == "0.3.0-beta.7-cookers.1"
        base_meta["mods"][0]["version"] = meta["mods"][0]["version"]
        assert base_meta == meta
        assert not any("smelterreview" in n.lower() or "SmelterGameTests" in n or "SmelterRestart" in n for n in after)
        classes = {p for p in before if p.endswith(".class")}
        changed = {"Config", "ServerSettings", "BackpackSmelter", "Upgrades", "StoreMenu", "guide/GuideContent"}
        for path in classes:
            family = path.removeprefix("dev/anya/storage/").removesuffix(".class").split("$")[0]
            if family not in changed:
                assert old.read(path) == new.read(path), path
        print("PASS package: prior resources, legacy recipe and independent classes (including chest/EMI) match verified smelter.1.")
        print("PASS specialist discs: four gated recipes, exact ingredients and ordinary hover keys. Each of the seven cooker icons is visually distinct.")


if __name__ == "__main__":
    verify(Path(sys.argv[1]), Path(sys.argv[2]))

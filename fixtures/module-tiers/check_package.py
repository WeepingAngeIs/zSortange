"""Compare the hosted playable with itemhelp.1 and verify the two module additions."""
from collections import Counter
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import re
import sys
import tomllib
from zipfile import ZipFile

from PIL import Image

BASELINE = "4d125e5fc32fca35c511f6a65f4b8de8e5ff2a043e550423c9717d8352335567"
RECIPE = "data/anyastorage/recipe/"
MODEL = "assets/anyastorage/models/item/"
ICON = "assets/anyastorage/textures/item/"
LANG = "assets/anyastorage/lang/en_us.json"
META = "META-INF/neoforge.mods.toml"
PARTS = {
    "pickup": ["amethyst_shard", "name_tag", "copper_ingot"],
    "compacting": ["piston", "comparator", "amethyst_shard"],
}


def members(jar):
    names = [entry.filename for entry in jar.infolist() if not entry.is_dir()]
    assert len(names) == len(set(names)), "Duplicate files in archive"
    return set(names)


def registered_hover_ids(language):
    return {m[1] for key in language if (m := re.fullmatch(r"tooltip\.anyastorage\.([a-z0-9_]+)\.1", key))}


def pixels(jar, item):
    with Image.open(BytesIO(jar.read(ICON + item + ".png"))) as picture:
        assert picture.size == (32, 32), item
        return tuple(picture.convert("RGBA").getdata())


def check(candidate_file, baseline_file):
    assert sha256(baseline_file.read_bytes()).hexdigest() == BASELINE
    with ZipFile(baseline_file) as base, ZipFile(candidate_file) as jar:
        assert jar.testzip() is None
        old = members(base)
        new = members(jar)
        assert not any("ModuleTierGameTests" in p or "anyamoduletest" in p for p in new)
        before = json.loads(base.read(LANG))
        after = json.loads(jar.read(LANG))
        old_ids = registered_hover_ids(before)
        new_ids = registered_hover_ids(after)
        assert len(old_ids) == 34
        additions = {"advanced_" + family + "_upgrade" for family in PARTS}
        assert new_ids == old_ids | additions and len(new_ids) == 36
        assert all(after.get(k) == v for k, v in before.items()), "Previous item text changed"

        resource = lambda p: p.startswith(("assets/", "data/"))
        expected = {prefix + item + extension for item in additions for prefix, extension
                    in ((RECIPE, ".json"), (MODEL, ".json"), (ICON, ".png"))}
        assert {p for p in new - old if resource(p)} == expected, "Unexpected added gameplay data/art"
        assert not any(resource(p) for p in old - new), "Previous resources missing"
        for p in sorted(old & new):
            if resource(p) and p != LANG and base.read(p) != jar.read(p):
                # Generated JSON key order can vary between processes without changing data.
                assert p.endswith((".json", ".mcmeta")) and json.loads(base.read(p)) == json.loads(jar.read(p)), p
        meta = tomllib.loads(jar.read(META).decode())
        baseline_meta = tomllib.loads(base.read(META).decode())
        assert baseline_meta["mods"][0]["version"] == "0.3.0-beta.7-itemhelp.1"
        assert meta["mods"][0]["version"] == "0.3.0-beta.7-modules.1"
        baseline_meta["mods"][0]["version"] = meta["mods"][0]["version"]
        assert meta == baseline_meta
        seen = [pixels(jar, item) for item in sorted(old_ids) if item.endswith("_upgrade")]
        for family, parts in PARTS.items():
            item = "advanced_" + family + "_upgrade"
            recipe = json.loads(jar.read(RECIPE + item + ".json"))
            assert recipe["type"] == "minecraft:crafting_shapeless"
            assert recipe["neoforge:conditions"] == [{"type": "anyastorage:recipe_group", "group": "upgrade." + family}]
            assert recipe["result"] == {"id": "anyastorage:" + item, "count": 1}
            assert all(set(ingredient) == {"item"} for ingredient in recipe["ingredients"])
            assert Counter(i["item"] for i in recipe["ingredients"]) == Counter(
                ["anyastorage:" + family + "_upgrade"] + ["minecraft:" + part for part in parts])
            model = json.loads(jar.read(MODEL + item + ".json"))
            assert model == {"parent": "minecraft:item/generated", "textures": {"layer0": "anyastorage:item/" + item}}
            icon = pixels(jar, item)
            assert any(pixel[3] for pixel in icon) and any(not pixel[3] for pixel in icon)
            for previous in seen:
                visibly_changed = sum(a != b and (a[3] or b[3]) != 0 for a, b in zip(previous, icon))
                assert visibly_changed >= 32, f"{item} icon has only {visibly_changed} differing visible pixels"
            seen.append(icon)
            prefix = f"tooltip.anyastorage.{item}."
            assert {key[len(prefix):] for key in after if key.startswith(prefix)} == {"1", "2"}
            assert all(isinstance(after[prefix + n], str) and after[prefix + n].strip() for n in ("1", "2"))
            assert after["item.anyastorage." + item].strip()

    print("PASS package: verified itemhelp.1 baseline, 34 preserved hover entries + 2 new items, original recipes, skins and gameplay resources.")
    print("PASS modules: both shapeless crafting recipes consume their precise basic disc and three parts; each has its family gate, own model and distinct visible 32x32 icon.")


if __name__ == "__main__":
    check(Path(sys.argv[1]), Path(sys.argv[2]))

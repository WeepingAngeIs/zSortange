"""Compare the two cat backpack trims with the exact packaged feeding.1 models."""

import argparse
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path
import tomllib
from zipfile import ZipFile


BASE_SHA = "660a4a16762d7715830e03643cb851caec65b9ebec115e462b55b949c1ef884c"
MODEL = "assets/anyastorage/models/item/backpack/"
CAT_SKINS = ("bow_cat", "midnight_cat")
METADATA = "META-INF/neoforge.mods.toml"
STEP = 1 / 16  # Model units. The block is sixteen model units wide.


def members(archive):
    files = [entry.filename for entry in archive.infolist() if not entry.is_dir()]
    assert len(files) == len(set(files)), "Duplicate JAR entry"
    return set(files)


def bounds(elements):
    return tuple(tuple(fn(e[key][axis] for e in elements) for axis in range(3))
                 for fn, key in ((min, "from"), (max, "to")))


def check_model(name, old, new):
    fixed = deepcopy(old)
    base = old["elements"]
    result = new["elements"]
    flap = base[1]
    assert "up" not in base[0]["faces"] and base[0]["to"][1] == flap["from"][1] == 12.5, name
    assert flap["to"] == [13, 14.5, 10.5] and flap["from"] == [3, 12.5, 4.6], name
    for index, x, sign in ((12, 3, -1), (15, 10, 1)):
        trim = base[index]
        assert trim["from"] == [x, 14, 9] and trim["to"] == [x + 3, 14.8, 10.5], name
        assert trim["faces"]["south"]["texture"] == "#trim" and flap["faces"]["south"]["texture"] == "#top", name
        # Both exposed faces used to share the flap's outward planes over a nonempty area.
        outer = "from" if sign == -1 else "to"
        assert trim["to"][2] == flap["to"][2] and trim[outer][0] == flap[outer][0], name
        assert min(trim["to"][1], flap["to"][1]) > max(trim["from"][1], flap["from"][1]), name
        for corner in ("from", "to"):
            fixed["elements"][index][corner][0] += sign * STEP
            fixed["elements"][index][corner][2] += STEP

        moved = result[index]
        assert moved["to"][2] - flap["to"][2] == STEP, f"{name} {index}: forward clearance"
        assert sign * (moved[outer][0] - flap[outer][0]) == STEP, f"{name} {index}: outer clearance"
        # The whole middle tier still sits on the translated base, and the base still seats in the flap.
        middle = result[index + 1]
        assert moved["to"][1] == middle["from"][1], f"{name} {index}: detached middle tier"
        for axis in (0, 2):
            assert moved["from"][axis] < middle["from"][axis] < middle["to"][axis] < moved["to"][axis], name
        for axis in range(3):
            assert max(moved["from"][axis], flap["from"][axis]) < min(moved["to"][axis], flap["to"][axis]), name
        # A second cuboid could also have shared either face. Check actual two-dimensional extents.
        for direction, axis, corner in (("south", 2, "to"), ("west" if sign < 0 else "east", 0, outer)):
            for j, other in enumerate(result):
                if j == index or direction not in other["faces"] or moved[corner][axis] != other[corner][axis]:
                    continue
                overlaps = all(max(moved["from"][a], other["from"][a]) < min(moved["to"][a], other["to"][a])
                               for a in range(3) if a != axis)
                assert not overlaps, f"{name} {index}: coplanar {direction} face with {j}"

    assert fixed == new, f"{name}: unintended geometry, UV, texture or display transform change"
    assert bounds(base) == bounds(result), f"{name}: overall bounds changed"
    print(f"PASS {name}: both trim fronts and outer sides stand 1/16 model unit proud. "
          "Both buckle stacks remain seated. Dimensions, UVs, shoulder seam and overall bounds match.")


def check_archives(previous, candidate):
    assert sha256(previous.read_bytes()).hexdigest() == BASE_SHA, "Expected the verified feeding.1 JAR"
    with ZipFile(previous) as old, ZipFile(candidate) as new:
        paths = members(old)
        assert paths == members(new), "Packaged resource or class roster changed"
        models = {path for path in paths if path.startswith(MODEL) and path.endswith(".json")}
        assert len(models) == 22, "Expected 22 distinct backpack models"
        cat_models = {MODEL + skin + ".json" for skin in CAT_SKINS}
        assert cat_models <= models
        for path in sorted(paths - cat_models - {METADATA}):
            before, after = old.read(path), new.read(path)
            if before != after:
                # Several generated JSON resources use unordered keys. The parsed payload must match.
                assert path.endswith((".json", ".mcmeta")) and json.loads(before) == json.loads(after), path
        old_meta = tomllib.loads(old.read(METADATA).decode())
        new_meta = tomllib.loads(new.read(METADATA).decode())
        old_mod, new_mod = old_meta["mods"][0], new_meta["mods"][0]
        assert old_mod["modId"] == "anyastorage" and old_mod["version"] == "0.3.0-beta.7-feeding.1"
        assert old_mod["version"] != new_mod["version"] and candidate.name == f"anya-storage-plus-{new_mod['version']}.jar"
        old_mod["version"] = new_mod["version"]
        assert old_meta == new_meta, "Loader metadata changed beyond the candidate version"
        for skin in CAT_SKINS:
            path = MODEL + skin + ".json"
            check_model(skin, json.loads(old.read(path)), json.loads(new.read(path)))
        # The placed renderer draws the skin selected by the backpack item. Its block has only a particle shell.
        assert "dev/anya/storage/appearance/PlacedBackpackRenderer.class" in paths
        shell = json.loads(new.read("assets/anyastorage/models/block/placed_backpack.json"))
        assert shell["parent"] == "minecraft:block/block" and "elements" not in shell
        registry = json.loads(new.read("assets/anyastorage/models/item/backpack.json"))
        registered = {rule["model"] for rule in registry["overrides"]}
        assert all(f"anyastorage:item/backpack/{skin}" in registered for skin in CAT_SKINS)
        print("PASS package: 20 other backpack models, all textures, item overrides, block shell, "
              "renderers, gameplay classes and remaining resource data match feeding.1.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    check_archives(args.baseline, args.candidate)

"""Compare packaged backpack geometry and UVs with the roomeditor.1 JAR."""

import argparse
from copy import deepcopy
import json
from zipfile import ZipFile


MODEL = "assets/anyastorage/models/item/backpack"


def frontmost(elements, side, y, z):
    hits = {}
    for index, element in enumerate(elements):
        low, high = element["from"], element["to"]
        if side in element["faces"] and low[1] < y < high[1] and low[2] < z < high[2]:
            depth = low[0] if side == "west" else high[0]
            hits.setdefault(depth, []).append(index)
    depth = min(hits) if side == "west" else max(hits)
    return depth, hits[depth]


def check_skin(name, current, previous):
    path = f"{MODEL}/{name}.json"
    old = json.loads(previous.read(path))
    new = json.loads(current.read(path))
    expected = deepcopy(old)
    body, flap = expected["elements"][:2]
    assert body["from"] == [3, 2, 5] and body["to"] == [13, 14, 10], name
    seam = flap["from"][1]
    assert seam == 12.5 and body["to"][1] <= flap["to"][1], name
    for axis in (0, 2):
        assert flap["from"][axis] <= body["from"][axis] < body["to"][axis] <= flap["to"][axis], name

    # The unchanged flap encloses the entire slice. Keep the stitched pattern at its old height.
    base, top = body["from"][1], body["to"][1]
    body["to"][1] = seam
    del body["faces"]["up"]
    for side in ("north", "south", "west", "east"):
        uv = body["faces"][side]["uv"]
        uv[1] += (uv[3] - uv[1]) * (top - seam) / (top - base)
    assert new == expected, f"{name}: geometry, face UVs, textures or display transforms differ"
    for resource in set(old["textures"].values()):
        namespace, texture = resource.split(":")
        path = f"assets/{namespace}/textures/{texture}.png"
        assert current.read(path) == previous.read(path), path

    # Probe the unobstructed shoulder away from straps, ears and leaf ornaments.
    for side, depth in (("west", 3), ("east", 13)):
        for z in (6.1, 7.0, 7.7):
            for y, before, after in ((12.25, [0], [0]), (12.6, [0, 1], [1]),
                                     (13.2, [0, 1], [1]), (13.9, [0, 1], [1]), (14.25, [1], [1])):
                assert frontmost(old["elements"], side, y, z) == (depth, before), (name, side, y, z, "baseline")
                assert frontmost(new["elements"], side, y, z) == (depth, after), (name, side, y, z, "candidate")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate")
    parser.add_argument("baseline", help="previous roomeditor.1 playable JAR")
    args = parser.parse_args()
    with ZipFile(args.candidate) as current, ZipFile(args.baseline) as previous:
        registry = MODEL + ".json"
        base_registry = json.loads(previous.read(registry))
        assert json.loads(current.read(registry)) == base_registry, "backpack skin selection changed"
        skins = {item["model"].removeprefix("anyastorage:item/backpack/") for item in base_registry["overrides"]}
        assert len(skins) == 22 and "plain" in skins, "expected all 22 baseline skins"
        for name in sorted(skins):
            check_skin(name, current, previous)
    print("22 packaged backpack skins: both shoulder seams have one outward flap face, with original silhouette, UVs and skins.")


if __name__ == "__main__":
    main()

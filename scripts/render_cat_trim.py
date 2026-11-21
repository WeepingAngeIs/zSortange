"""Ray-sample the packaged Bow Cat model from the front and both oblique directions on the hosted runner."""

import argparse
from collections import Counter
from io import BytesIO
import json
from math import ceil, cos, floor, radians, sin
import os
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageDraw

from backpack_shoulder_figure import font


MODEL = "assets/anyastorage/models/item/backpack/bow_cat.json"
FLAP = 1
TRIMS = (12, 15)
WIDTH, HEIGHT, SCALE = 370, 170, 26
FOCUS = (8, 14.65, 9.75)
EPSILON = 1e-7
# Plane axis, sign, UV axes and directions, and diffuse brightness for the visible faces.
SIDES = {"south": (2, 1, 0, 1, 1, -1, 1.0), "up": (1, 1, 0, 1, 2, 1, 1.0),
         "west": (0, -1, 2, 1, 1, -1, .80), "east": (0, 1, 2, -1, 1, -1, .80)}


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def load_model(path):
    with ZipFile(path) as jar:
        model = json.loads(jar.read(MODEL))
        textures = {}
        for key, resource in model["textures"].items():
            namespace, location = resource.split(":")
            file = f"assets/{namespace}/textures/{location}.png"
            with Image.open(BytesIO(jar.read(file))) as source:
                textures["#" + key] = source.convert("RGBA")
    return model["elements"], textures


def render(model, yaw, elevation):
    elements, textures = model
    angle, tilt = radians(yaw), radians(elevation)
    right = (cos(angle), 0, -sin(angle))
    up = (-sin(angle)*sin(tilt), cos(tilt), -cos(angle)*sin(tilt))
    toward = (sin(angle)*cos(tilt), sin(tilt), cos(angle)*cos(tilt))

    def project(point):
        delta = [v-f for v, f in zip(point, FOCUS)]
        return WIDTH/2 + SCALE*dot(delta, right), HEIGHT/2 - SCALE*dot(delta, up)

    # Every pixel receives its own orthographic ray. Retain the foremost candidates
    # as well as the flap depth for checking clearance behind the winning trim.
    origins = [tuple(FOCUS[i] + (x+.5-WIDTH/2)*right[i]/SCALE + (HEIGHT/2-y-.5)*up[i]/SCALE
                     for i in range(3)) for y in range(HEIGHT) for x in range(WIDTH)]
    depth = [float("-inf")] * len(origins)
    winners = [None] * len(origins)
    tied = [{} for _ in origins]
    flap = {side: {} for side in SIDES}
    tile = Image.new("RGB", (WIDTH, HEIGHT), "#30303c")
    pixels = tile.load()
    for index, element in enumerate(elements):
        assert "rotation" not in element
        low, high = element["from"], element["to"]
        for side, (axis, sign, u_axis, u_sign, v_axis, v_sign, shade) in SIDES.items():
            face = element["faces"].get(side)
            if not face or sign*toward[axis] < EPSILON:
                continue
            assert face.get("rotation", 0) == 0
            plane = high[axis] if sign > 0 else low[axis]
            corners = []
            for u_edge in (low, high):
                for v_edge in (low, high):
                    point = list(low)
                    point[axis], point[u_axis], point[v_axis] = plane, u_edge[u_axis], v_edge[v_axis]
                    corners.append(project(point))
            xs, ys = zip(*corners)
            uv, texture = face["uv"], textures[face["texture"]]
            for y in range(max(0, floor(min(ys))), min(HEIGHT, ceil(max(ys)))):
                for x in range(max(0, floor(min(xs))), min(WIDTH, ceil(max(xs)))):
                    p = y*WIDTH + x
                    origin = origins[p]
                    d = (plane-origin[axis])/toward[axis]
                    if d < depth[p]-EPSILON and index != FLAP:
                        continue
                    u = (origin[u_axis]+d*toward[u_axis]-low[u_axis])/(high[u_axis]-low[u_axis])
                    v = (origin[v_axis]+d*toward[v_axis]-low[v_axis])/(high[v_axis]-low[v_axis])
                    if not (0 <= u < 1 and 0 <= v < 1):
                        continue
                    tu = uv[0] + (uv[2]-uv[0])*(u if u_sign > 0 else 1-u)
                    tv = uv[1] + (uv[3]-uv[1])*(v if v_sign > 0 else 1-v)
                    texel = texture.getpixel((max(0, min(texture.width-1, int(tu*texture.width/16))),
                                              max(0, min(texture.height-1, int(tv*texture.height/16)))))
                    if not texel[3]:
                        continue
                    if index == FLAP:
                        flap[side][p] = d
                    if d < depth[p]-EPSILON:
                        continue
                    if d > depth[p]+EPSILON:
                        tied[p].clear()
                    tied[p].setdefault(side, set()).add(index)
                    depth[p] = d
                    winners[p] = (index, side)
                    pixels[x, y] = tuple(round(c*shade) for c in texel[:3])
    conflicts, relieved = Counter(), Counter()
    for p, winner in enumerate(winners):
        if winner is None:
            continue
        for side, indices in tied[p].items():
            if FLAP in indices:
                for trim in set(TRIMS).intersection(indices):
                    conflicts[trim, side] += 1
                    x, y = p % WIDTH, p // WIDTH
                    if (x+y) % 8 < 3:
                        pixels[x, y] = (255, 183, 60)
        index, side = winner
        if index in TRIMS and p in flap[side]:
            # Measure outward normal separation where an exposed trim really covers its former neighbor.
            gap = (depth[p]-flap[side][p])*abs(toward[SIDES[side][0]])
            if gap > EPSILON:
                relieved[index, side] += 1
    return tile, conflicts, relieved


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("Run this packaged-model check on a GitHub Actions runner.")
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("baseline", "candidate", "output"):
        parser.add_argument(arg, type=Path)
    args = parser.parse_args()
    baseline, candidate = load_model(args.baseline), load_model(args.candidate)
    views = ((0, 14, "Front"), (0, 30, "Looking down at the front"),
             (-32, 14, "Left oblique"), (32, 14, "Right oblique"))
    canvas = Image.new("RGB", (2*WIDTH+68, 108+len(views)*(HEIGHT+40)), "#f6f2ee")
    pen = ImageDraw.Draw(canvas)
    pen.text((20, 10), "Bow Cat backpack shoulder trim", fill="#332838", font=font(23, True))
    pen.text((20, 42), "Amber stripes mark exposed trim and flap faces at equal depth.",
             font=font(16), fill="#524557")
    for col, label in enumerate(("Before", "After")):
        pen.text((22+col*(WIDTH+24), 72), label, font=font(17, True), fill="#332838")
    for row, (yaw, elevation, label) in enumerate(views):
        before, ties, _ = render(baseline, yaw, elevation)
        after, remaining, separated = render(candidate, yaw, elevation)
        targets = [(trim, "south") for trim in TRIMS]
        if yaw:
            targets.append((12, "west") if yaw < 0 else (15, "east"))
        assert not remaining, (label, "visible candidate coplanarity", remaining)
        for target in targets:
            assert ties[target] > 5, (label, target, "baseline overlap missing", ties)
            assert separated[target] > 5, (label, target, "visible relief missing", separated)
        print(f"{label}, {elevation} degrees above: {sum(ties.values())} tied trim/flap samples before, "
              f"0 after. Separated frontmost samples: " +
              ", ".join(f"{t[0]} {t[1]} {separated[t]}" for t in targets))
        y = 102 + row*(HEIGHT+40)
        pen.text((22, y), label, font=font(15), fill="#524557")
        canvas.paste(before, (22, y+24))
        canvas.paste(after, (22+WIDTH+24, y+24))
    canvas.save(args.output)


if __name__ == "__main__":
    main()

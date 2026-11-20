"""Check packaged materials against five real framebuffers on the small hosted art set."""
import argparse
import hashlib
import io
import json
import math
from collections import Counter
from pathlib import Path
from statistics import correlation, mean, StatisticsError
from zipfile import ZipFile

from PIL import Image, ImageChops, ImageFilter


STATES = ("closed", "open", "veil", "window", "members")
# Independent visual features in the 128px public starter-room texture.
LANDMARKS = {"ringed moon": (55, 18, 98, 43), "foreground chest": (18, 67, 59, 101),
             "receding chests": (54, 54, 112, 87)}


def missing_texture(image, gui):
    # The training toast has dark purple lettering; missing GUI sprites use bright magenta.
    # Placed world models can be dimly shaded. Require a patch, not an isolated subpixel.
    floor = 125 if gui else 32
    tiles = Counter()
    for i, (r, g, b) in enumerate(image.getdata()):
        lesser = min(r, b)
        if lesser >= floor and 5 * g < lesser and max(r, b) <= 3 * lesser:
            x, y = i % image.width, i // image.width
            tiles[x // 8, y // 8] += 1
    return max(tiles.values(), default=0)


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def cross(a, b):
    return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])


def project(uv, camera, size, surface_offset):
    # The fixed test set photographs the south face. Sample UV texel centres using the
    # actual camera pose rather than checking a handful of memorized framebuffer pixels.
    x, y, z = camera["entrance"]
    vertex = (x + uv[0], y + 1 - uv[1], z + 1 + surface_offset)
    relative = tuple(a-b for a, b in zip(vertex, camera["eye"]))
    depth = dot(camera["aim"], relative)
    assert depth > 0
    width, height = size
    scale = height / (2 * depth * math.tan(math.radians(camera["fov"]/2)))
    right = cross(camera["aim"], camera["up"])
    return (round(width/2 + dot(relative, right)*scale - .5),
            round(height/2 - dot(relative, camera["up"])*scale - .5))


def signature(frame, texture, camera, region, stride, offset):
    template = texture.convert("RGB").filter(ImageFilter.GaussianBlur(.6 if stride > 1 else .12))
    observed = frame.filter(ImageFilter.GaussianBlur(.75))
    ax, ay, bx, by = region
    source, sample = [], []
    for v in range(ay, by, stride):
        for u in range(ax, bx, stride):
            uv = ((u+.5)/texture.width, (v+.5)/texture.height)
            source.append(template.getpixel((u, v)))
            sample.append(observed.getpixel(project(uv, camera, frame.size, offset)))
    # Per-channel fit tests art placement, while observed/source gains test readable color.
    pairs = list(zip(zip(*source), zip(*sample)))
    try:
        shape = min(correlation(a, b) for a, b in pairs)
    except StatisticsError:
        shape = 0
    gains = [mean(b)/mean(a) for a, b in pairs]
    return {"correlation": round(shape, 4), "channel_gains": [round(g, 4) for g in gains]}


def check(evidence, jar):
    declared = json.loads((evidence/"runtime-inputs.json").read_text())
    with jar.open("rb") as source:
        # System Python on hosted Ubuntu may precede hashlib.file_digest.
        sha = hashlib.sha256(source.read()).hexdigest()
    assert declared.get(jar.name) == sha, "Template JAR differs from the runtime input"
    images = {}
    for state in STATES:
        path = evidence/"run-packaged-client-Alpha"/f"art-{state}.png"
        with Image.open(path) as image:
            assert image.format == "PNG" and image.width >= 640 and image.height >= 400, path
            images[state] = image.convert("RGB")
    assert len({i.tobytes() for i in images.values()}) == len(images), "Duplicate captures"
    patches = {s: missing_texture(im, s == "members") for s, im in images.items()}
    w, h = images["closed"].size
    roi = (w*40//100, h*38//100, w*60//100, h*63//100)
    delta = ImageChops.difference(images["closed"].crop(roi), images["open"].crop(roi))
    changed = sum(max(pixel) >= 16 for pixel in delta.getdata())
    with ZipFile(jar) as archive:
        def asset(path):
            with Image.open(io.BytesIO(archive.read("assets/anyastorage/textures/"+path))) as im:
                return im.convert("RGB")
        study = asset("entity/starter_room_study.png")
        veil = asset("block/home_void_cube.png")
    assert study.size == (128, 128) and veil.size == (16, 64), "Unexpected portal material dimensions"
    def camera(state):
        return json.loads((evidence/"control"/f"art-{state}-camera.json").read_text())
    near, far = camera("window"), camera("veil")
    marks = {name: signature(images["window"], study, near, area, 2, .001) for name, area in LANDMARKS.items()}
    veil_scores = [signature(images["veil"], veil.crop((0, frame*16, 16, frame*16+16)), far,
                             (1, 1, 15, 15), 1, 0)["correlation"] for frame in range(4)]
    report = {"frames": list(images), "size": [w,h], "chest_region": roi, "changed_chest_pixels": changed,
              "max_magenta_pixels_per_8px_tile": patches, "window_landmarks": marks,
              "fallback_frame_correlations": veil_scores, "runtime_jar_sha256": sha}
    (evidence/"art-frame-check.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))
    assert max(patches.values()) < 12, f"Missing-texture color patch: {patches}"
    assert changed >= 50, f"Chest pose barely changed: {changed} pixels"
    assert max(veil_scores) > .80, "Abstract fallback does not match its packaged animation"
    for label, result in marks.items():
        assert result["correlation"] >= .80, f"Focused window lost the {label} pattern"
        gains = result["channel_gains"]
        assert min(gains) >= .52 and min(gains)/max(gains) >= .73, f"Window illustration is too dim or tinted: {label}"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("jar", type=Path)
    args = parser.parse_args()
    check(args.evidence, args.jar)

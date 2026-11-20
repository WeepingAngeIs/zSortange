"""Offline pose simulation using the production JOML transforms and the cached vanilla player skin."""

import argparse
from dataclasses import dataclass
import io
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
SRC = HERE.parents[1] / "src/main/java/dev/anya/storage/appearance"
PREVIOUS = runpy.run_path(str(HERE.parent / "tablet-lifecycle/device_preview.py"))
LOGICAL = (480, 312)
SIZE = (720, 468)
LABEL_HEIGHT = 35
SCALE = SIZE[0] / LOGICAL[0]


@dataclass
class Pose:
    time: int
    face: str
    progress: float
    dim: float
    device: np.ndarray
    arm: np.ndarray


def read_poses(jdk, joml, side, slim):
    with tempfile.TemporaryDirectory(prefix="tablet-hand-pose-") as scratch:
        subprocess.run([jdk / "bin/javac", "--release", "21", "-proc:none", "-d", scratch,
                        "-cp", joml, SRC / "TabletHandlingPose.java", SRC / "TabletLift.java", SRC / "GuardianBlink.java",
                        HERE / "PreviewPose.java"], check=True, timeout=30)
        result = subprocess.run([jdk / "bin/java", "-cp", os.pathsep.join([scratch, str(joml)]),
                                 "PreviewPose", *map(str, LOGICAL), side, str(slim).lower()],
                                capture_output=True, text=True, check=True, timeout=10)
    poses = []
    for line in result.stdout.splitlines():
        ms, face, progress, dim, *values = line.split()
        device, arm = np.array(values, dtype=float).reshape(2, 4, 4).transpose(0, 2, 1)
        poses.append(Pose(int(ms), face, float(progress), float(dim), device, arm))
    if poses[0].face != "WAIT" or poses[-1].face != "DONE":
        raise ValueError("Unexpected client presentation sequence")
    return poses


def project(matrix, local):
    local = np.asarray(local)
    points = np.c_[local, np.ones(len(local))] @ matrix.T
    xyz = points[:, :3] / points[:, 3, None]
    xyz[:, :2] *= SCALE
    return xyz


def quad(canvas, depth, xyz, texture, uv, light=1.0):
    """Draw one textured plane using GUI +Y-down coordinates and the layer's actual depth."""
    height, width = canvas.shape[:2]
    low = np.maximum(np.floor(xyz[:, :2].min(0)).astype(int), 0)
    high = np.minimum(np.ceil(xyz[:, :2].max(0)).astype(int), (width, height))
    if np.any(high <= low):
        return
    x0, y0 = low
    x1, y1 = high
    basis = np.column_stack([xyz[1, :2] - xyz[0, :2], xyz[3, :2] - xyz[0, :2]])
    if abs(np.linalg.det(basis)) < 0.001:
        return
    yy, xx = np.mgrid[y0:y1, x0:x1]
    xy = np.stack([xx + .5 - xyz[0, 0], yy + .5 - xyz[0, 1]], axis=-1)
    st = xy @ np.linalg.inv(basis).T
    covered = np.all((st >= 0) & (st <= 1), axis=2)
    uv = np.asarray(uv)
    coords = uv[0] + st @ np.vstack([uv[1] - uv[0], uv[3] - uv[0]])
    texels = np.clip(np.floor(coords).astype(int), (0, 0), (texture.shape[1]-1, texture.shape[0]-1))
    pixel = texture[texels[:, :, 1], texels[:, :, 0]]
    z = xyz[0, 2] + st @ (xyz[[1, 3], 2] - xyz[0, 2])
    previous = depth[y0:y1, x0:x1]
    visible = covered & (z >= previous) & (pixel[:, :, 3] > 0)
    dest = canvas[y0:y1, x0:x1]
    alpha = pixel[visible, 3:4] / 255
    dest[visible] = np.clip(dest[visible] * (1-alpha) + pixel[visible, :3] * alpha * light, 0, 255)
    previous[visible] = z[visible]


def arm_layer(canvas, depth, matrix, skin, right, slim, sleeve):
    # Vanilla wide/slim PlayerModel + posed shoulder. Texel offsets and face winding follow ModelPart.Cube.
    wide = 3 if slim else 4
    # setupAnim normalizes the shoulder pivot to Y=2 on both skins.
    pivot = np.array([-5 if right else 5, 2., 0])
    lo = np.array([-2 if right and slim else -3 if right else -1, -2., -2.])
    hi = lo + (wide, 12, 4)
    if sleeve:
        lo -= .25
        hi += .25
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    vertices = np.array([(x0,y0,z0),(x1,y0,z0),(x1,y1,z0),(x0,y1,z0),
                         (x0,y0,z1),(x1,y0,z1),(x1,y1,z1),(x0,y1,z1)])
    # Neutral PlayerRenderer.renderHand calls setupAnim at age zero; it clears xRot and retains this bob.
    bob = .1 if right else -.1
    cs, sn = np.cos(bob), np.sin(bob)
    vertices[:, :2] = vertices[:, :2] @ np.array([[cs, sn], [-sn, cs]])
    world = project(matrix, (vertices + pivot) / 16)
    if right:
        u,v = (40,32) if sleeve else (40,16)
    else:
        u,v = (48,48) if sleeve else (32,48)
    faces = [
        ([5,4,0,1], u+4,v,u+4+wide,v+4),
        ([2,3,7,6], u+4+wide,v+4,u+4+wide*2,v),
        ([0,4,7,3], u,v+4,u+4,v+16),
        ([1,0,3,2], u+4,v+4,u+4+wide,v+16),
        ([5,1,2,6], u+4+wide,v+4,u+8+wide,v+16),
        ([4,5,6,7], u+8+wide,v+4,u+8+wide*2,v+16),
    ]
    for indices, a,b,c,d in faces:
        points = world[indices]
        normal = np.cross(points[1] - points[0], points[3] - points[0])
        normal /= max(1e-8, np.linalg.norm(normal))
        light = .70 + .28 * abs(normal @ np.array([-.28,-.55,.786]))
        quad(canvas, depth, points, skin, [(c,b),(a,b),(a,d),(c,d)], light)


def compose(scene, surfaces, skin, pose, right, slim):
    # Native rendering uses its own light/material pipeline. This CPU fixture uses a fixed soft light.
    background = Image.blend(scene, Image.new("RGB", scene.size, (12,16,23)), round(153*pose.dim)/255)
    canvas = np.array(background.resize(SIZE, Image.Resampling.BILINEAR))
    depth = np.full(canvas.shape[:2], -np.inf)
    arm_layer(canvas, depth, pose.arm, skin, right, slim, False)
    arm_layer(canvas, depth, pose.arm, skin, right, slim, True)
    # The client draws its hardware after the native arm and draws the menu above both.
    depth.fill(-np.inf)
    texture = surfaces[pose.face]
    h,w = texture.shape[:2]
    uv = [(0,0),(w,0),(w,h),(0,h)]
    plane = np.array([(0,0,0),(w,0,0),(w,h,0),(0,h,0)], dtype=float)
    if pose.progress < 1:
        for z in (-6,-3):
            quad(canvas, depth, project(pose.device, plane + (0,0,z)), surfaces["WAIT"], uv)
    quad(canvas, depth, project(pose.device, plane), texture, uv)
    return Image.fromarray(canvas)


def font(size):
    for filename in ("C:/Windows/Fonts/segoeui.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(filename, size)
        except OSError:
            continue
    return ImageFont.load_default()


def labelled(scene, stage, side, model):
    frame = Image.new("RGB", (SIZE[0], SIZE[1]+LABEL_HEIGHT), (15,23,33))
    frame.paste(scene, (0,LABEL_HEIGHT))
    pen = ImageDraw.Draw(frame)
    pen.text((12, LABEL_HEIGHT//2), stage, anchor="lm", font=font(16), fill=(233,239,245))
    pen.text((SIZE[0]-12, LABEL_HEIGHT//2), f"Offline simulation  /  {model} Steve  /  {side} hand", anchor="rm",
             font=font(14), fill=(177,195,210))
    return frame


def export(options):
    scene, menu = PREVIOUS["read_screenshot"](options.screenshot)
    surfaces = {k: np.asarray(v) for k,v in PREVIOUS["display_surfaces"](menu).items()}
    model = "slim" if options.slim else "wide"
    with zipfile.ZipFile(options.resources) as resources:
        with resources.open(f"assets/minecraft/textures/entity/player/{model}/steve.png") as image:
            skin = np.array(Image.open(io.BytesIO(image.read())).convert("RGBA"))
    poses = read_poses(options.jdk.resolve(), options.joml.resolve(), options.side, options.slim)
    right = options.side == "right"
    stamps = [(poses[0], "Resting grip", 950)]
    for index, pose in enumerate(poses):
        duration = poses[index+1].time-pose.time if index+1 < len(poses) else 1900
        stage = "Lifting the tablet" if pose.progress < 1 else "Guardian opening" if pose.face != "DONE" else "Storage ready"
        if len(stamps) > 1:
            prev, label, old_duration = stamps[-1]
            if (pose.face == prev.face and stage == label and prev.progress == pose.progress == 1
                    and pose.dim == prev.dim):
                stamps[-1] = (prev, label, old_duration+duration)
                continue
        stamps.append((pose, stage, duration))
    frames = [labelled(compose(scene, surfaces, skin, pose, right, options.slim), stage, options.side, model)
              for pose, stage, _ in stamps]
    palette = Image.new("RGB", (SIZE[0], sum(f.height for f in frames[::3])))
    for i,frame in enumerate(frames[::3]):
        palette.paste(frame, (0,i*frame.height))
    palette = palette.quantize(colors=256)
    indexed, durations = [], []
    for frame, (_, _, duration) in zip(frames, stamps):
        converted = frame.quantize(palette=palette, dither=Image.Dither.NONE)
        if indexed and indexed[-1].tobytes() == converted.tobytes():
            durations[-1] += duration
        else:
            indexed.append(converted)
            durations.append(duration)
    options.output.parent.mkdir(parents=True, exist_ok=True)
    indexed[0].save(options.output, save_all=True, append_images=indexed[1:], duration=durations,
                    loop=0, disposal=1, optimize=False)
    with Image.open(options.output) as gif:
        assert gif.n_frames == len(indexed) and gif.info["loop"] == 0
        for i, (frame, ms) in enumerate(zip(indexed,durations)):
            gif.seek(i)
            assert gif.info["duration"] == ms
            assert gif.convert("RGB").tobytes() == frame.convert("RGB").tobytes()
    print(f"Verified {len(indexed)} exported frames, {sum(durations)} ms, {options.output.stat().st_size} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--joml", type=Path, required=True)
    parser.add_argument("--resources", type=Path, required=True)
    parser.add_argument("--screenshot", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--side", choices=["left", "right"], default="right")
    parser.add_argument("--slim", action="store_true")
    export(parser.parse_args())

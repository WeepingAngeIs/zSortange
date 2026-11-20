"""Compose an offline preview using the packaged twin-tail artwork, hand poses and expression clocks."""
import argparse
from dataclasses import replace
from functools import lru_cache
import io
import os
from pathlib import Path
import runpy
import subprocess
from tempfile import TemporaryDirectory
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont


HERE = Path(__file__).resolve().parent
HAND = HERE.parent / "tablet-handling"
DRAW = runpy.run_path(str(HAND / "preview.py"))
ROOT_TEXTURE = "assets/anyastorage/textures/gui/"
ITEM_TEXTURE = "assets/anyastorage/textures/item/"
OPENINGS = [
    ("A shy blush", 6), ("The sideways smile", 4), ("Looking up with a wink", 8),
    ("The smug blink", 2), ("Looks out, turns away", 5), ("The giggle", 4),
    ("Peeks up, tongue out", 4), ("The classic double blink", 2),
]


def timelines(options):
    with TemporaryDirectory() as build:
        deps = os.pathsep.join(str(p.resolve()) for p in (options.jar, options.joml))
        subprocess.run([options.jdk / "bin/javac", "--release", "21", "-proc:none", "-cp", deps, "-d", build,
                        HAND / "PreviewPose.java", HERE / "PortraitTimeline.java"], check=True)
        java = [options.jdk / "bin/java", "-cp", os.pathsep.join([build, deps])]
        hand_rows = subprocess.check_output(java + ["PreviewPose", "480", "312", "right", "false"], text=True)
        face_rows = subprocess.check_output(java + ["dev.anya.storage.appearance.PortraitTimeline"], text=True)
    poses = {}
    for line in hand_rows.splitlines():
        ms, face, progress, dim, *elements = line.split()
        device, arm = np.array(elements, float).reshape(2,4,4).transpose(0,2,1)
        poses[int(ms)] = DRAW["Pose"](int(ms), face, float(progress), float(dim), device, arm)
    clocks = {}
    for line in face_rows.splitlines():
        visit, ms, face = line.split()
        clocks.setdefault(visit, []).append((int(ms), face))
    assert poses[1110].face == "DONE"
    return poses, clocks


def read_art(jar, capture, clocks, creative):
    scene, inventory = DRAW["PREVIOUS"]["read_screenshot"](capture)
    tablet = "creative_tablet" if creative else "cupid_tablet"
    case = "creative_tablet_device.png" if creative else "tablet_device.png"
    with zipfile.ZipFile(jar) as z:
        def image(path):
            with Image.open(io.BytesIO(z.read(path))) as im:
                return im.convert("RGBA")
        shell, sheet, classic = (image(ROOT_TEXTURE + name) for name in
                                 (case, "tablet_twin_tails.png", "tablet_guardian.png"))
        surfaces = {"WAIT": np.array(shell)}
        # Held glass and the hotbar use the same baked sprite. Sample the actual item texture.
        physical = {face for times in clocks.values() for _, face in times if face.startswith("item_")}
        for face in physical:
            sprite = image(ITEM_TEXTURE + tablet + "_face_" + face.removeprefix("item_") + ".png")
            surfaces[face] = np.array(sprite.resize(shell.size, Image.Resampling.NEAREST))
    ready = shell.copy()
    ready.paste(inventory, (25,20))
    surfaces["DONE"] = np.array(ready)
    width, height = 224, 128
    assert sheet.size == (9*width, 7*height)
    face_height = round(400 * height / width)
    assert classic.size == (240, 432)
    eyelids = (0, 1, 2, 1, 0, 1, 2, 1, 0)
    for row in range(8):
        for col in range(9):
            atlas, x, y = (classic, 8, 8 + 144 * eyelids[col]) if row == 7 else (sheet, col*width, row*height)
            tile = atlas.crop((x, y, x + width, y + height))
            frame = shell.copy()
            frame.paste(tile.resize((400,face_height),Image.Resampling.NEAREST),
                        (16,round(20+(268-face_height)/2)))
            surfaces[f"gui_{row}_{col}"] = np.array(frame)
    return scene, surfaces


def sample(poses, clocks, visit):
    idle = not visit.startswith("open")
    times = clocks[visit]
    start = poses[0]
    yield start, 240
    for i,(ms,face) in enumerate(times):
        pose = start if idle else poses[ms]
        duration = times[i+1][0] - ms if i+1 < len(times) else (320 if idle else 560)
        yield replace(pose, face=face), duration


@lru_cache()
def font(name, size):
    for candidate in (name, "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(candidate,size)
        except OSError:
            continue
    return ImageFont.load_default()


def decorate(img, caption, idle):
    if idle:
        img=img.crop((315,207,715,467))
    canvas=Image.new("RGB",(620,461),"#101821")
    canvas.paste(img.resize((620,403),Image.Resampling.LANCZOS),(0,41))
    d=ImageDraw.Draw(canvas)
    d.text((13,20),caption,font=font("segoeuib.ttf",17),fill="#edf2f8",anchor="lm")
    d.text((606,20),"Offline tablet preview",font=font("segoeui.ttf",14),fill="#a8c3d6",anchor="rm")
    d.line((0,40,620,40),fill="#4e667b")
    note="Select after one second to finish the visit" if idle else "Opening and inventory reveal at normal speed"
    d.text((12,451),note,font=font("segoeui.ttf",12),fill="#bfcedb",anchor="lm")
    return canvas


def gallery(surfaces, path):
    out = Image.new("RGB", (768, 1152), "#101821")
    pen = ImageDraw.Draw(out)
    for i, (label, col) in enumerate(OPENINGS):
        x, y = 12 + (i % 2) * 378, 7 + (i // 2) * 287
        pen.text((x + 4, y), label, font=font("segoeuib.ttf", 17), fill="#edf2f8")
        case = Image.fromarray(surfaces[f"gui_{i}_{col}"]).resize((364, 256), Image.Resampling.LANCZOS)
        out.paste(case, (x, y + 26), case.getchannel("A"))
    out.save(path, optimize=True)


def export(options):
    poses, clocks=timelines(options)
    scene, surfaces=read_art(options.jar, options.capture, clocks, options.creative)
    with zipfile.ZipFile(options.resources) as z:
        with Image.open(io.BytesIO(z.read("assets/minecraft/textures/entity/player/wide/steve.png"))) as im:
            skin=np.array(im.convert("RGBA"))
    sections = [("squint", "Caught squinting"), ("open1", OPENINGS[1][0]),
                ("idle5", "A quick giggle, then she hides"), ("open2", OPENINGS[2][0]),
                ("open7", OPENINGS[7][0])]
    frames=[]; durations=[]; picks=[]
    for key,title in sections:
        last=None; first=len(frames)
        for pose,ms in sample(poses,clocks,key):
            idle=not key.startswith("open")
            identity=(pose.face,pose.dim,pose.device.tobytes(),pose.arm.tobytes())
            if identity==last:
                durations[-1]+=ms
                continue
            scene_frame=DRAW["compose"](scene,surfaces,skin,pose,True,False)
            frames.append(decorate(scene_frame,title,idle))
            durations.append(ms)
            last=identity
        picks.extend(range(first,len(frames),3))
        picks.append(len(frames)-1)
    swatches=Image.new("RGB",(310,231*len(picks)))
    for i,index in enumerate(picks):
        swatches.paste(frames[index].resize((310,231)),(0,231*i))
    palette=swatches.quantize(colors=256,method=Image.Quantize.MEDIANCUT)
    indexed=[im.quantize(palette=palette,dither=Image.Dither.NONE) for im in frames]
    options.output.parent.mkdir(parents=True,exist_ok=True)
    indexed[0].save(options.output,save_all=True,append_images=indexed[1:],duration=durations,
                    loop=0,disposal=1,optimize=False)
    with Image.open(options.output) as gif:
        elapsed=0; expected=0
        for i in range(gif.n_frames):
            gif.seek(i); decoded=gif.convert("RGB").tobytes()
            end=elapsed+gif.info["duration"]
            while elapsed<end:
                assert indexed[expected].convert("RGB").tobytes()==decoded
                elapsed+=durations[expected]
                expected+=1
            assert elapsed==end
        assert expected==len(indexed) and elapsed==sum(durations) and gif.info["loop"]==0
        print(f"Verified {gif.n_frames} frames over {elapsed} ms.")
    if options.gallery:
        options.gallery.parent.mkdir(parents=True, exist_ok=True)
        gallery(surfaces, options.gallery)


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    for argument in ("jdk","joml","jar","resources","capture","output"):
        parser.add_argument("--"+argument,required=True,type=Path)
    parser.add_argument("--gallery",type=Path)
    parser.add_argument("--creative", action="store_true", help="Use the Creative Tablet case and baked face sprites")
    export(parser.parse_args())

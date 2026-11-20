"""Show example visits and openings from a packaged tablet with the production pose and expression clocks."""
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
        visit, ms, row, col = line.split()
        clocks.setdefault(visit, []).append((int(ms), int(row), int(col)))
    assert poses[1110].face == "DONE"
    return poses, clocks


def read_art(jar, capture):
    scene, inventory = DRAW["PREVIOUS"]["read_screenshot"](capture)
    with zipfile.ZipFile(jar) as z:
        def image(name):
            return Image.open(io.BytesIO(z.read(ROOT_TEXTURE + name))).convert("RGBA")
        shell, sheet, tumble = (image(name) for name in ("tablet_device.png", "tablet_expressions.png", "tablet_tumble.png"))
    surfaces = {"WAIT": np.array(shell)}
    ready = shell.copy()
    ready.paste(inventory, (25,20))
    surfaces["DONE"] = np.array(ready)
    width, height = 224, 128
    assert sheet.size == (9*width, 7*height)
    face_height = round(400 * height / width)
    assert tumble.size == (9*width, height)
    for row in range(8):
        atlas, y = (tumble, 0) if row == 7 else (sheet, row * height)
        for col in range(9):
            tile = atlas.crop((col*width, y, (col+1)*width, y+height))
            frame = shell.copy()
            frame.paste(tile.resize((400,face_height),Image.Resampling.NEAREST),
                        (16,round(20+(268-face_height)/2)))
            surfaces[(row,col)] = np.array(frame)
    return scene, surfaces


def sample(poses, clocks, visit):
    idle = visit.startswith("idle")
    times = clocks[visit]
    start = poses[0]
    yield start, 240
    for i,(ms,row,col) in enumerate(times):
        pose = start if idle else poses[ms]
        face = (row,col) if row >= 0 else ("DONE" if pose.face == "DONE" else "WAIT")
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
    note="Held tablet detail  ·  quiet wait skipped" if idle else "Opening and inventory reveal at normal speed"
    d.text((12,451),note,font=font("segoeui.ttf",12),fill="#bfcedb",anchor="lm")
    return canvas


def gallery(surfaces, path):
    choices = [("Waving hello", (1, 3)), ("On tiptoe", (6, 4)),
               ("Upside down", (7, 1)), ("Finding her feet", (7, 5))]
    out = Image.new("RGB", (768, 586), "#101821")
    pen = ImageDraw.Draw(out)
    for i, (label, cell) in enumerate(choices):
        x, y = 12 + (i % 2) * 378, 7 + (i // 2) * 287
        pen.text((x + 4, y), label, font=font("segoeuib.ttf", 17), fill="#edf2f8")
        case = Image.fromarray(surfaces[cell]).resize((364, 256), Image.Resampling.LANCZOS)
        out.paste(case, (x, y + 26), case.getchannel("A"))
    out.save(path, optimize=True)


def export(options):
    poses, clocks=timelines(options)
    scene, surfaces=read_art(options.jar, options.capture)
    with zipfile.ZipFile(options.resources) as z:
        with Image.open(io.BytesIO(z.read("assets/minecraft/textures/entity/player/wide/steve.png"))) as im:
            skin=np.array(im.convert("RGBA"))
    sections = [("idle3","An upside-down visit"), ("open1","A wave on opening"),
                ("idle2","Up on tiptoe"), ("open2","A wink on opening")]
    frames=[]; durations=[]; picks=[]
    for key,title in sections:
        last=None; first=len(frames)
        for pose,ms in sample(poses,clocks,key):
            idle=key.startswith("idle")
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
    export(parser.parse_args())

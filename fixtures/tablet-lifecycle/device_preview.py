"""Compose the held, open and idle tablet against a crop of the user's existing storage screenshot.

This offline preview uses a resting right hand, zero swing/equip progress, five storage rows
and a 190 ms menu-sync wait while the tablet rises. Only the separate idle waiting time is
compressed (55–110 seconds in the candidate). The rendered blink comes from the production clock.
"""
import argparse
import hashlib
from pathlib import Path
import runpy

from PIL import Image


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
GUI = REPO / "src/main/resources/assets/anyastorage/textures/gui"
ORIGINAL = runpy.run_path(str(HERE.parent / "tablet-intro/preview.py"))
LOGICAL = (480, 312)
OUTPUT_SIZE = (600, 390)
SHELL_SIZE = (432, 304)
WORLD_CROP = (0, 140, 480, 452)
RAISE_MS = 190
IDLE_MS = 500
RESAMPLE = Image.Resampling.NEAREST


def read_screenshot(path):
    if hashlib.sha256(path.read_bytes()).hexdigest() != ORIGINAL["SCREEN_SHA"]:
        raise ValueError("Use the earlier storage screenshot matched by the intro fixture")
    with Image.open(path) as image:
        # Empty background context and the real menu are separate, non-overlapping source regions.
        scene = image.convert("RGB").crop(WORLD_CROP)
        menu = image.convert("RGB").crop(ORIGINAL["UI_BOUNDS"]).resize((382, 268), Image.Resampling.LANCZOS)
    return scene, menu


def display_surfaces(menu):
    shell = Image.open(GUI / "tablet_device.png").convert("RGBA")
    if shell.size != SHELL_SIZE:
        raise ValueError("Expected the native 432 x 304 device shell")
    surfaces = {"WAIT": shell}
    with Image.open(GUI / "tablet_guardian.png") as atlas, Image.open(GUI / "tablet_gaze_left.png") as gaze:
        for row, label in enumerate(("OPEN", "HALF", "CLOSED", "GAZE")):
            source, y = (gaze, 0) if label == "GAZE" else (atlas, 144 * row)
            glass = source.crop((8, y+8, 232, y+136)).convert("RGBA")
            face_height = round(400 * glass.height / glass.width)
            lit = shell.copy()
            lit.paste(glass.resize((400, face_height), RESAMPLE), (16, round(20+(268-face_height)/2)))
            surfaces[label] = lit
    storage = shell.copy()
    storage.paste(menu, (16 + (400 - 382)//2, 20))
    surfaces["DONE"] = storage
    return surfaces


def bounds():
    width, height = LOGICAL
    w, h = SHELL_SIZE
    scale = min(width * .29 / w, height * .34 / h)
    held = (width - width*.045 - w*scale, height*.91 - h*scale, scale)
    scale = min(1, (width - 8) / w, (height - 8) / h)
    opened = ((width - w*scale)/2, (height - h*scale)/2, scale)
    return held, opened


def compose(scene, device, eased):
    held, opened = bounds()
    x, y, scale = (a + (b-a)*eased for a, b in zip(held, opened))
    tinted = Image.blend(scene, Image.new("RGB", scene.size, (12, 16, 23)), .6*eased)
    canvas = tinted.resize(OUTPUT_SIZE, Image.Resampling.BILINEAR).convert("RGBA")
    display_scale = OUTPUT_SIZE[0] / LOGICAL[0]
    size = tuple(round(n*scale*display_scale) for n in SHELL_SIZE)
    canvas.alpha_composite(device.resize(size, RESAMPLE), (round(x*display_scale), round(y*display_scale)))
    return canvas.convert("RGB")


def timeline(jdk):
    frames = [("WAIT", 0, 650)]
    for ms in range(0, RAISE_MS, 10):
        t = ms / RAISE_MS
        frames.append(("WAIT", t * t * (3 - 2*t), 10))
    clock = ORIGINAL["read_timeline"](jdk)
    for (start, state), (end, _) in zip(clock, clock[1:]):
        frames.append((state, 1, end-start))
    frames.extend([("DONE", 1, 1750), ("WAIT", 0, 1050), ("GAZE", 0, IDLE_MS), ("WAIT", 0, 800)])
    return frames


def export(screenshot, jdk, output):
    scene, menu = read_screenshot(screenshot)
    surfaces = display_surfaces(menu)
    sequence = timeline(jdk)
    # One palette avoids flicker in the static world, in the silver bevel and between eye states.
    samples = [compose(scene, surfaces[name], amount) for name, amount in
               (("WAIT", 0), ("WAIT", 1), ("OPEN", 1), ("CLOSED", 1), ("DONE", 1), ("GAZE", 0))]
    contact = Image.new("RGB", (OUTPUT_SIZE[0], OUTPUT_SIZE[1] * len(samples)))
    for index, image in enumerate(samples):
        contact.paste(image, (0, image.height * index))
    palette = contact.quantize(colors=256)

    frames, durations = [], []
    for name, amount, ms in sequence:
        image = compose(scene, surfaces[name], amount).quantize(palette=palette, dither=Image.Dither.NONE)
        if frames and image.tobytes() == frames[-1].tobytes():
            durations[-1] += ms
        else:
            frames.append(image)
            durations.append(ms)
    output.parent.mkdir(parents=True, exist_ok=True)
    frames[0].save(output, save_all=True, append_images=frames[1:], duration=durations, loop=0,
                   optimize=False, disposal=1)
    with Image.open(output) as gif:
        assert gif.n_frames == len(frames) and gif.info["loop"] == 0
        for index, frame in enumerate(frames):
            gif.seek(index)
            assert gif.info["duration"] == durations[index]
            assert gif.convert("RGB").tobytes() == frame.convert("RGB").tobytes()
    print(f"Verified {len(frames)} decoded frames, {sum(durations)} ms, {output.stat().st_size} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--screenshot", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(args.screenshot, args.jdk.resolve(), args.output)

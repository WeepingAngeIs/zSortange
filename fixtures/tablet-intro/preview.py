"""Preview the source-derived portrait on its actual timing, then hold a supplied storage screenshot.

This offline fixture needs Pillow, Java 21 and the original November 19 pocket storage screenshot.
It neither starts Minecraft nor opens a save. The last frame is a crop of the earlier screenshot.
"""

import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

from PIL import Image, ImageOps


HERE = Path(__file__).resolve().parent
SRC = HERE.parents[1] / "src/main"
ATLAS = SRC / "resources/assets/anyastorage/textures/gui/tablet_guardian.png"
BLINK = SRC / "java/dev/anya/storage/appearance/GuardianBlink.java"
SCREEN_SHA = "aff8e8c9075ace455f49019c80c927a9af86f1302e686f47adbafca49b2e6fe1"
UI_BOUNDS = (483, 216, 1629, 1020)
SIZE = (576, 352)
BACKGROUND = (6, 15, 27)


def read_timeline(jdk):
    with tempfile.TemporaryDirectory(prefix="guardian-clock-") as temporary:
        subprocess.run([jdk / "bin/javac", "--release", "21", "-d", temporary,
                        BLINK, HERE / "PreviewTiming.java"], check=True, timeout=30)
        result = subprocess.run([jdk / "bin/java", "-cp", temporary, "PreviewTiming"],
                                check=True, capture_output=True, text=True, timeout=10)
    events = []
    for line in result.stdout.splitlines():
        elapsed, frame = line.split()
        events.append((int(elapsed), frame))
    if events[0] != (0, "OPEN") or events[-1][1] != "DONE":
        raise ValueError("Unexpected client presentation states")
    return events


def center(image):
    frame = Image.new("RGB", SIZE, BACKGROUND)
    offset = ((frame.width - image.width) // 2, (frame.height - image.height) // 2)
    if image.mode == "RGBA":
        frame.paste(image, offset, image.getchannel("A"))
    else:
        frame.paste(image, offset)
    return frame


def read_frames(screenshot):
    if hashlib.sha256(screenshot.read_bytes()).hexdigest() != SCREEN_SHA:
        raise ValueError("Expected the original pocket storage screenshot for this exact crop")
    with Image.open(screenshot) as source:
        cropped = source.convert("RGB").crop(UI_BOUNDS)
    frames = {"DONE": center(ImageOps.contain(cropped, SIZE, Image.Resampling.LANCZOS))}
    with Image.open(ATLAS) as atlas:
        if atlas.size != (240, 432):
            raise ValueError("Expected three 240 by 144 source rows")
        for row, name in enumerate(("OPEN", "HALF", "CLOSED")):
            sprite = atlas.convert("RGBA").crop((0, row * 144, 240, (row + 1) * 144))
            # A 360 x 220 logical screen at 1.6x gives a 480 x 288 tablet in the output.
            frames[name] = center(sprite.resize((480, 288), Image.Resampling.NEAREST))
    # A single palette keeps unchanged pixels stable across the entire animation.
    sheet = Image.new("RGB", (SIZE[0], SIZE[1] * len(frames)))
    for index, frame in enumerate(frames.values()):
        sheet.paste(frame, (0, index * SIZE[1]))
    palette = sheet.quantize(colors=256)
    return {name: frame.quantize(palette=palette, dither=Image.Dither.NONE)
            for name, frame in frames.items()}


def export(jdk, screenshot, output):
    events = read_timeline(jdk)
    frames = read_frames(screenshot)
    sequence = [frames[name] for _, name in events]
    durations = [after[0] - before[0] for before, after in zip(events, events[1:])] + [2000]
    output.parent.mkdir(parents=True, exist_ok=True)
    sequence[0].save(output, save_all=True, append_images=sequence[1:], duration=durations,
                     loop=0, disposal=1, optimize=False)
    with Image.open(output) as gif:
        if gif.n_frames != len(sequence) or gif.info.get("loop") != 0:
            raise ValueError("GIF encoding changed the frame count or loop")
        for index, (source, duration) in enumerate(zip(sequence, durations)):
            gif.seek(index)
            if gif.info.get("duration") != duration or gif.convert("RGB").tobytes() != source.convert("RGB").tobytes():
                raise ValueError(f"GIF frame {index} changed its pixels or duration")
    print("Verified decoded GIF:", ", ".join(f"{name} {ms}ms" for (_, name), ms in zip(events, durations)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--screenshot", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(args.jdk.resolve(), args.screenshot, args.output)

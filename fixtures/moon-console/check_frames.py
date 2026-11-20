"""Compare the actual camera output with colors in the candidate's authored blue displays."""
from io import BytesIO
from pathlib import Path
import sys
from zipfile import ZipFile

from PIL import Image

def main(jar_path, folder):
    inks = set()
    with ZipFile(jar_path) as jar:
        for image in ("gui/tablet_guardian", "gui/tablet_gaze_left", "gui/tablet_expressions",
                      "gui/tablet_tumble", "item/cupid_tablet_face"):
            with Image.open(BytesIO(jar.read("assets/anyastorage/textures/" + image + ".png"))) as source:
                colors = source.convert("RGB").getcolors(source.width * source.height)
                inks.update((r, g, b) for _, (r, g, b) in colors
                            if r < 146 and b > 83 and b - r > 54 and g - r > 22 and b - g > 18)
    def count(name):
        with Image.open(folder / (name + ".png")) as shot:
            require_size = (960, 640)
            if shot.size != require_size:
                raise ValueError(f"Unexpected framebuffer size {shot.size}")
            colors = shot.convert("RGB").getcolors(shot.width * shot.height)
            return sum(n for n, color in colors if color in inks)

    control = count("display-closed")
    results = {name: count(name) for name in ("held-cupid", "lift-0", "lift-1", "lift-2", "lift-3", "lift-4")}
    print("Control scene Cupid ink pixels", control)
    for name, pixels in results.items():
        print(name, pixels)
    if any(pixels <= control + 400 for pixels in results.values()):
        raise RuntimeError("Cupid's blue display was lost in a sampled opening frame")

if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))

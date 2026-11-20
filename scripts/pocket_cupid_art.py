"""Lift six full-body poses from the shipped Cupid artwork, removing the blue glass."""
from pathlib import Path
from PIL import Image

textures = Path(__file__).resolve().parents[1] / "src/main/resources/assets/anyastorage/textures"
source = Image.open(textures / "gui/tablet_expressions.png").convert("RGB")
atlas = Image.new("RGBA", (384, 256))
for row, body in enumerate((4, 6)):
    for column, beat in enumerate((2, 4, 6)):
        x = beat * 224 + 44
        y = body * 128
        tile = source.crop((x, y, x + 128, y + 128))
        pixels = []
        for r, g, b in tile.getdata():
            # The brightest empty scanline is 37 blue. Preserve the authored banding in the subject.
            alpha = min(235, max(0, (b-38)*9))
            pixels.append((min(255, r+14), min(255, g+32), min(255, b+39), alpha) if alpha else (0,0,0,0))
        cutout = Image.new("RGBA", tile.size)
        cutout.putdata(pixels)
        atlas.paste(cutout, (column*128, row*128))
output = textures / "entity/pocket_cupid.png"
output.parent.mkdir(parents=True, exist_ok=True)
atlas.save(output, optimize=True)

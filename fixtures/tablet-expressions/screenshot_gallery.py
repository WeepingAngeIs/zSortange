"""Contact sheet of the actual hosted client's tablet glass, ordered by authored sequence."""
from pathlib import Path
import sys
from PIL import Image, ImageDraw, ImageFont

shots, output = map(Path, sys.argv[1:])
labels = ["Shy blush", "Sideways smile", "Upturned wink", "Smug blink",
          "Look out, turn away", "Lean in and giggle", "Peek up, tongue out", "Classic double blink"]
tile_w, tile_h = 270, 164
canvas = Image.new("RGB", (tile_w * 4, (tile_h+34)*2), "#18202b")
draw = ImageDraw.Draw(canvas)
font = ImageFont.truetype("DejaVuSans.ttf", 16)
samples = set()
for row, label in enumerate(labels):
    matches = list(shots.glob(f"expression-{row}-*.png"))
    assert len(matches) == 1, (row, matches)
    with Image.open(matches[0]) as frame:
        assert frame.size == (960, 640), frame.size
        # Within the centered raised screen. Keep the character at Minecraft's rendering scale.
        glass = frame.convert("RGB").crop((125, 82, 835, 510))
        blue = sum(1 for r,g,b in glass.resize((71,43)).getdata() if b>r+40 and g>r+15)
        assert blue > 160, (label, blue)
        pixels = glass.resize((54,32),Image.Resampling.NEAREST).tobytes()
        assert pixels not in samples, "Duplicate actual screen for "+label
        samples.add(pixels)
        x,y = row%4*tile_w,row//4*(tile_h+34)
        canvas.paste(glass.resize((tile_w,tile_h), Image.Resampling.LANCZOS),(x,y))
        draw.text((x+8,y+tile_h+7),label,font=font,fill="#f4eaf5")
canvas.save(output)

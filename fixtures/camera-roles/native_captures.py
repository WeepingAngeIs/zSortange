"""Compare the live hosted source framebuffer with the perspective-corrected indoor display."""
import json
import os
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageStat

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("Hosted fixture only")
trial = Path("observations/observed-new")
out = Path("comparison")


def photo(file):
    with Image.open(file) as im:
        return im.convert("RGB")


def displayed(file, size):
    x0,y0,x3,y3,x2,y2,x1,y1 = json.loads(Path(str(file) + ".json").read_text())
    dx1,dx2,dx3 = x1-x2,x3-x2,x0-x1+x2-x3
    dy1,dy2,dy3 = y1-y2,y3-y2,y0-y1+y2-y3
    det = dx1*dy2-dx2*dy1
    assert abs(det)>10
    g,h = (dx3*dy2-dx2*dy3)/det, (dx1*dy3-dx3*dy1)/det
    mapping = ((x1*(1+g)-x0)/size,(x3*(1+h)-x0)/size,x0,
               (y1*(1+g)-y0)/size,(y3*(1+h)-y0)/size,y0,g/size,h/size)
    return photo(file).transform((size,size),Image.Transform.PERSPECTIVE,mapping,Image.Resampling.BILINEAR)


size = 192
frames = []
full = []
stats = {"renderer":json.loads((trial/"native-render-stats.json").read_text())}
for state in ("vista","copper","blue"):
    source = photo(trial/f"native-{state}-outdoor-lens.png").resize((size,size),Image.Resampling.BOX)
    wall = trial/f"native-{state}-skygrid-monitor.png"
    panel = displayed(wall,size)
    frames.append((source,panel))
    full.append(photo(wall))
    roi = (9,9,183,183)
    expected = source.filter(ImageFilter.GaussianBlur(1.4)).crop(roi)
    actual = panel.filter(ImageFilter.GaussianBlur(1.4)).crop(roi)
    error = sum(ImageStat.Stat(ImageChops.difference(expected,actual)).mean)/3
    assert error < 19,(state,error)
    stats[state] = {"meanRgbError": round(error,2),
                    "receipt":(trial/f"native-presented-{state}").read_text()}
    assert int(stats[state]["receipt"].split(";")[1].split("=")[1]) >= 2

# The server changed a real orange marker to blue at the same place; verify the same moving
# color boundary on the lens and monitor. A frozen screenshot cannot pass this check.
for kind,idx in (("outdoor",0),("wall",1)):
    a,b = frames[1][idx],frames[2][idx]
    n = 0
    # The 11x9 outdoor board is centered at +25 horizontally and +5 vertically
    # on the near face 92.265625 blocks along the lens axis (95.72 radial).
    for y in range(83,101):
        for x in range(112,132):
            r,g,blue = a.getpixel((x,y))
            rr,gg,bb = b.getpixel((x,y))
            n += r>1.45*blue and g>.15*r and bb>1.25*rr and gg>1.25*rr and abs(r-rr)+abs(blue-bb)>60
    assert n>=35,(kind,n)
    stats[kind+"ChangedMarkerPixels"] = n
assert list(Path("observations/observed-solo").glob("solo-stock-source-unavailable-*.png"))
board=Image.new("RGB",(1120,843),"#191c24");draw=ImageDraw.Draw(board)
for x,text in ((12,"Stock lens at outdoor camera"),(294,"Actual wall in Skygrid"),(702,"Same wall, cropped to screen"),(924,"Marker")):
    draw.text((x,9),text,fill="white")
for row,(source,panel) in enumerate(frames):
    y=35+row*268
    board.paste(source.resize((256,256),Image.Resampling.NEAREST),(12,y))
    board.paste(full[row].resize((384,256),Image.Resampling.LANCZOS),(294,y))
    board.paste(panel.resize((208,208),Image.Resampling.LANCZOS),(702,y+16))
    draw.text((924,y+95),("Open vista","Orange marker","Light-blue marker")[row], fill="white")
    draw.text((924,y+115),("24 to 112 blocks","95.7 blocks","95.7 blocks")[row],fill="#bed3e2")
board.save(out/"live-native-wall.png")
(out/"native-checks.json").write_text(json.dumps(stats,indent=2)+"\n")
print(json.dumps(stats,indent=2))

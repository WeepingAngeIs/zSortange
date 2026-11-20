"""Verify game captures against the empty room and the transparent art inside the candidate JAR."""
import io
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from PIL import Image

jar, directory = Path(sys.argv[1]), Path(sys.argv[2])
with tempfile.TemporaryDirectory() as classes:
    check = Path(__file__).with_name("RoomVisitCheck.java")
    subprocess.run(["javac","-proc:none","-cp",str(jar),"-d",classes,str(check)], check=True)
    subprocess.run(["java","-cp",os.pathsep.join((str(jar),classes)),"dev.anya.storage.pocket.RoomVisitCheck"], check=True)
with zipfile.ZipFile(jar) as archive:
    filename = "assets/anyastorage/textures/entity/pocket_cupid.png"
    atlas = Image.open(io.BytesIO(archive.read(filename))).convert("RGBA")
assert atlas.size == (384, 256)
for y in (0,128):
    for x in (0,128,256):
        opacity = atlas.getchannel("A").crop((x,y,x+128,y+128))
        left, top, right, bottom = opacity.getbbox()
        assert left>4 and right<125 and bottom-top>=103, (left,top,right,bottom)
        visible = sum(count for value,count in enumerate(opacity.histogram()) if value>60)
        assert 2600<visible<9500, visible

def capture(name):
    with Image.open(directory / (name + ".png")) as frame:
        return frame.convert("RGB")

base = capture("room-before")
w,h = base.size
roi = (w*15//100,h*28//100,w*85//100,h*73//100)
def samples(name):
    frame = capture(name)
    assert frame.size == base.size
    seen = set()
    for y in range(roi[1],roi[3],2):
        for x in range(roi[0],roi[2],2):
            r,g,b = frame.getpixel((x,y))
            _,g0,b0 = base.getpixel((x,y))
            if b>90 and b-r>30 and g-r>18 and b-b0>24 and g-g0>18:
                seen.add((x,y))
    return seen

shown = samples("room-cupid")
leaving = samples("room-departing")
after = samples("room-after")
hidden = samples("room-models-off")
assert len(shown)>=60 and len(leaving)>=30, (len(shown),len(leaving))
assert len(after)<=max(10,len(shown)//15), (len(shown),len(after))
assert len(hidden)<=max(14,len(shown)//15), (len(shown),len(hidden))
# Use robust extents to exclude isolated distant noise.
hist_x = sorted(x for x,_ in shown)
hist_y = sorted(y for _,y in shown)
n = len(shown)
dx = hist_x[n*97//100] - hist_x[n*3//100]
dy = hist_y[n*97//100] - hist_y[n*3//100]
assert dy>=15 and dx>=6 and max(dx,dy) < h*.45, (dx,dy)
departing_x = sorted(x for x,_ in leaving)
assert departing_x[len(leaving)//2] > hist_x[n//2]+4, "Expected movement along the chest row"
print("Pocket visitor camera: full-body art, natural appearance, movement and clear departure.",
      "Peak, departing and after:", len(shown),len(leaving),len(after))

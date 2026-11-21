"""Check packaged items and real client frames from the disposable camera scenario."""
import json
import sys
from pathlib import Path
from zipfile import ZipFile
from PIL import Image, ImageChops

root = Path("build/role-check/studio/control")
def image(name):
    with Image.open(root / (name+"-CamOwner.png")) as frame:
        return frame.convert("RGB")
def crop(name):
    frame=image(name)
    w,h=frame.size
    return frame.crop((w//2-w//5,h//2-h//5,w//2+w//5,h//2+h//5))

before=crop("ceiling-without-flyover")
fly=crop("ceiling-player-overhead")
after=crop("ceiling-after-flyover")
def changed(a,b,threshold=12):
    delta=ImageChops.difference(a,b)
    return sum(max(pixel)>threshold for pixel in delta.getdata())
evidence={"flyover_vs_clear":changed(fly,before),"flyover_vs_departed":changed(fly,after),
          "roof_vs_midnight":changed(crop("ceiling-source-roof"),crop("ceiling-night"))}
assert min(evidence.values())>8,evidence
owner=crop("nine-block-screen")
assert len(owner.getcolors(owner.width*owner.height))>3,"Missing outdoor image"
with ZipFile(sys.argv[1]) as jar:
    def read(path):
        return json.loads(jar.read(path+".json"))
    for name,base in (("outdoor_camera",None),("sky_camera","outdoor_camera"),("wall_monitor",None),
                      ("wide_wall_monitor","wall_monitor"),("cinema_wall_monitor","wide_wall_monitor")):
        recipe=read("data/anyastorage/recipe/"+name)
        assert recipe["result"]=={"id":"anyastorage:"+name,"count":1}
        if base:
            assert {"item":"anyastorage:"+base} in recipe["key"].values()
        if name.endswith("wall_monitor"):
            panel=read("assets/anyastorage/models/block/"+name)["elements"]
            assert len(panel)==1 and panel[0]["to"][2]-panel[0]["from"][2]==1
    rails=read("assets/anyastorage/models/block/cctv_mount")["elements"]
    assert [r["name"] for r in rails]==["wall plate","left rail","right rail","pivot"]
    assert rails[1]["to"][0]<rails[2]["from"][0]
(root/"pixel-samples.json").write_text(json.dumps(evidence,indent=2))
print("PASS two-client flyover appears and leaves on the real rendered ceiling, separate roof and night images, tier crafting chain, thin screen and separated camera rails.",evidence)

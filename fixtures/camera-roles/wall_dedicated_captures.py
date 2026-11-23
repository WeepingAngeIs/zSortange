"""Compare the frozen production monitor in one integrated world and one two-client dedicated world."""
import json
import os
from pathlib import Path
from PIL import Image
from wall_capture_metrics import OUT, SIDE, MASK, REGIONS, load, shot, sidecar, straighten, measure, average_color, save_grid

solo = Path("solo")
pair = Path("build/role-check") / os.environ["TRIAL"] / "control"
assert (pair/"PASS.txt").is_file() and (solo/"PASS.txt").is_file()
results = {"revision": "d3b25e71ab98e851777d6a7a00fbd91dc9ebc53b", "fixtureRun": os.environ["GITHUB_RUN_ID"]}
for phase, prefix in (("day","day-"),("night","")):
    path = shot(pair,prefix+"wall-blue")
    panel = straighten(path)
    one = straighten(shot(solo,prefix+"wall-blue"))
    normal = load(shot(pair,phase+"-player-view"))
    x = (normal.width-normal.height)//2
    normal = normal.crop((x,0,x+normal.height,normal.height)).resize((SIDE,SIDE), Image.Resampling.LANCZOS)
    tex = load(sidecar(path,".texture.png"))
    previous = load(sidecar(shot(solo,prefix+"wall-blue"),".texture.png"))
    points = REGIONS["whole"]
    mask = 100*sum(px == MASK for px in tex.getdata())/(tex.width*tex.height)
    result = results[phase] = {"maskPercent":mask, "panelVsSolo":measure(panel,one,points),
                               "panelVsFirstPerson":measure(panel,normal,points),
                               "gpu":json.loads(sidecar(path,".gpu.json").read_text())}
    assert mask < 6 and result["gpu"]["visibleExactGpuColors"] > 110000
    assert result["panelVsSolo"]["meanAbsRGBError"] < 10
    save_grid("dedicated-"+phase+".png",[[("Solo indoor monitor",one),("Dedicated indoor monitor",panel),
              ("Dedicated first-person lens",normal)],[("Solo monitor texture",previous),
              ("Dedicated monitor texture",tex),("Dedicated stock lens",load(shot(pair,prefix+"stock-blue")))]])
points = REGIONS["gold-to-lapis-sign"]
remote = {}
for state in ("blue","gold"):
    path = shot(pair,"skygrid-"+state)
    receipt = json.loads(sidecar(path,".scene.json").read_text())
    assert receipt["viewerDimension"] == "anyastorage:pockets" and not receipt["localLighting"]
    monitor = straighten(path)
    tex = load(sidecar(path,".texture.png")).resize((SIDE,SIDE),Image.Resampling.NEAREST)
    remote[state] = (monitor, tex)
    results["skygrid-"+state] = {"serverScene":receipt,"signOnMonitor":average_color([monitor.getpixel(p) for p in points]),
                                 "signOnTexture":average_color([tex.getpixel(p) for p in points])}
for field in ("signOnMonitor","signOnTexture"):
    b=results["skygrid-blue"][field]
    g=results["skygrid-gold"][field]
    assert (b[2]-b[0]) - (g[2]-g[0]) > 8, "The remote marker did not visibly change"
guest = pair/"other-player-cannot-watch-same-wall-screen-RoomBuilder.png"
unloaded = shot(pair,"skygrid-source-unloaded")
save_grid("skygrid-and-privacy.png",[[("Skygrid / blue outside",remote["blue"][0]),
          ("Skygrid / gold outside",remote["gold"][0])],[("Other player's private-screen refusal",straighten(guest)),
          ("Keeper left / source unloaded",straighten(unloaded))]])
(OUT/"dedicated-measurements.json").write_text(json.dumps(results,indent=2))
print(json.dumps({p:results[p]["maskPercent"] for p in ("day","night")}))

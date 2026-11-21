"""Check the actual camera pixels on the main framebuffer, excluding the crosshair."""
from pathlib import Path
import json
import statistics
import zipfile
import sys
from PIL import Image

root = Path("build/role-check/candidate/control")
def patch(step,role="CamOwner"):
    with Image.open(root / f"{step}-{role}.png") as image:
        image=image.convert("RGB")
        x,y=image.width//2,image.height//2
        return tuple(int(statistics.median(image.getpixel((x+dx,y+dy))[i]
                                           for dx in range(6,11) for dy in range(6,11))) for i in range(3))

steps=["wall-north-red-owner-and-private-guests","wall-north-lime-same-aim","wall-east-blue-after-player-swivel",
       "skyglass-noon-and-guest-privacy","skyglass-physical-roof","skyglass-midnight-and-guest-privacy"]
evidence={step:{role:patch(step,role) for role in ("CamOwner","RoomBuilder","RoomVisitor")} for step in steps}
def difference(a,b):
    return max(abs(x-y) for x,y in zip(a,b))
for i in range(3):
    colors=evidence[steps[i]]
    owner=colors["CamOwner"]
    assert owner[i]>min(owner)+15, (steps[i],owner)
    for role in ("RoomBuilder","RoomVisitor"):
        assert difference(colors[role],owner)>18, (steps[i],role,colors)
for a,b in [(3,4),(4,5),(3,5)]:
    assert difference(evidence[steps[a]]["CamOwner"],evidence[steps[b]]["CamOwner"])>9, evidence
(root/"pixel-samples.json").write_text(json.dumps(evidence,indent=2))
# Assert the baked resource uses a fixed bracket and the right lens for every saved state.
with zipfile.ZipFile(sys.argv[1]) as jar:
    base="assets/anyastorage/"
    def asset(name):return json.loads(jar.read(base+name+".json"))
    parts=asset("blockstates/outdoor_camera")["multipart"]
    head=asset("models/block/cctv_head")["elements"]
    mount=asset("models/block/cctv_mount")["elements"]
    assert [b["name"] for b in mount]==["wall plate","arm","pivot"]
    lens=next(b for b in head if b["name"]=="front glass")
    assert lens["from"][2]==0 and lens["faces"]["north"]["texture"]=="#lens"
    for i,face in enumerate(("north","east","south","west","up","down")):
        for pan,turn in enumerate((0,1,-1)):
            active=[p["apply"] for p in parts if p["when"]["facing"]==face and p["when"].get("pan",str(pan))==str(pan)]
            assert [a["model"] for a in active]==["anyastorage:block/cctv_mount","anyastorage:block/cctv_head"]
            if i<4:
                assert active[0]["y"]==90*i and active[1]["y"]==90*((i+turn)%4)
            else:
                assert all(a["x"]==(270 if face=="up" else 90) for a in active)
print("PASS six model rotations, swivel with stationary mount, owner framebuffer changed with fixed scene replacement, camera turn, source roof and night. Both other players kept private screens.")


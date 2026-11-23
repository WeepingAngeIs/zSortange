"""Create original block models and disposable game settings on the public runner only."""
import base64
import json
import os
import struct
import sys
import uuid
import zlib
from pathlib import Path

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("This setup generates the public synthetic trial on GitHub only.")
root = Path("build") / ("back-fit-" + sys.argv[1])
game = root / "game"
(game / "config").mkdir(parents=True, exist_ok=True)
(root / "hosted-check.marker").write_text("synthetic\n")
(game / "config/figura.json").write_text(json.dumps({"CONFIG_VERSION":1, "allow_networking":False,
                                                      "update_channel":0, "server_ip":"127.0.0.1:9"}))
(game / "options.txt").write_text("onboardAccessibility:false\ntutorialStep:none\nenableVsync:false\n"
                                  "renderDistance:4\nsimulationDistance:3\nmaxFps:35\n")
def png(rgb):
    def chunk(kind, data):
        return struct.pack(">I",len(data))+kind+data+struct.pack(">I",zlib.crc32(kind+data))
    pixels=(b"\0"+bytes(rgb)*8)*8
    return b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",8,8,8,2,0,0,0))+chunk(b"IDAT",zlib.compress(pixels))+chunk(b"IEND",b"")

def model(name, size, anchored=True):
    directory=root/name
    directory.mkdir()
    def uid(label): return str(uuid.uuid5(uuid.NAMESPACE_DNS,"synthetic.fit."+name+"."+label))
    def vec(coords): return [v*size for v in coords]
    def cube(label, lower, upper):
        return {"name":label,"type":"cube","uuid":uid(label),"from":vec(lower),"to":vec(upper),
                "origin":vec([0,24,0]),"rotation":[0,0,0],"visibility":True,"export":True,
                "faces":{s:{"uv":[0,0,8,8],"texture":0} for s in ("north","south","east","west","up","down")}}
    torso=cube("TorsoMesh",[-4,12,-2],[4,24,2])
    head=cube("SkullMesh",[-3,24,-3],[3,30,3])
    def group(label, kids):
        return {"name":label,"uuid":uid(label),"origin":vec([0,24,0]),"rotation":[0,0,0],
                "visibility":True,"export":True,"children":kids}
    if anchored:
        groups=[group("Body",[torso["uuid"]]),group("Head",[head["uuid"]])]
    else:
        groups=[group("Decoration",[torso["uuid"], head["uuid"]])]
    texture=png((40,226,195) if size>.9 else (218,92,235))
    desc={"meta":{"model_format":"free","format_version":"4.10","box_uv":False},"name":"model",
          "resolution":{"width":8,"height":8},"elements":[torso,head],"outliner":groups,"animations":[],
          "textures":[{"name":"fixture.png","id":"0","uuid":uid("texture"),"width":8,"height":8,
                       "uv_width":8,"uv_height":8,"source":"data:image/png;base64,"+base64.b64encode(texture).decode()}]}
    (directory/"avatar.json").write_text(json.dumps({"name":name,"authors":["Synthetic fixture"]}))
    (directory/"model.bbmodel").write_text(json.dumps(desc))
    if anchored:
        (directory/"script.lua").write_text("""
vanilla_model.PLAYER:setVisible(false)
function events.tick()
  local v=player:getVelocity()
  local active=v.x*v.x+v.z*v.z>0.0001
  local pulse=active and math.sin(world.getTime() * 0.43) or 0
  models.model.Body:setPos(0, 2.5*pulse, 0)
  models.model.Body:setRot(0, 11*pulse, 0)
end
""")
model("avatar-a",1)
model("avatar-b",.62)
model("avatar-none",1,False)

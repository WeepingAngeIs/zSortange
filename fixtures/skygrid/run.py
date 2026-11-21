"""Exercise a room exit and color reload on the hosted guest and dedicated server."""
import os
from pathlib import Path
import subprocess
import time

if os.getenv("GITHUB_ACTIONS") != "true":
    raise SystemExit("Hosted fixture only")
root = Path("build/skygrid")
server, client = root / "Server", root / "Client"
(server / "eula.txt").write_text("eula=true\n")
(server / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=25574\nonline-mode=false\nlevel-type=minecraft:flat\nview-distance=3\nsimulation-distance=3\n")
(client / "options.txt").write_text("onboardAccessibility:false\ntutorialStep:none\nenableVsync:false\nrenderDistance:4\n")
# Synthetic fixture image, created only in this runner's disposable world.
import json, hashlib, uuid, struct, zlib
owner_bytes=bytearray(hashlib.md5(b"OfflinePlayer:ImageGuest").digest())
owner_bytes[6]=(owner_bytes[6]&15)|48
owner_bytes[8]=(owner_bytes[8]&63)|128
owner=str(uuid.UUID(bytes=bytes(owner_bytes)))
def chunk(k,data):
    return struct.pack(">I",len(data))+k+data+struct.pack(">I",zlib.crc32(k+data)&0xffffffff)
png=bytes.fromhex("89504e470d0a1a0a")+chunk(b"IHDR",struct.pack(">IIBBBBB",32,32,8,2,0,0,0))+chunk(b"IDAT",zlib.compress((bytes([0])+bytes([180,123,144])*32)*32))+chunk(b"IEND",b"")
folder=server/"world/anya_storage/interiors"
folder.mkdir(parents=True,exist_ok=True)
image_id="b9ca394f-66cb-4bb3-831b-b8dd34648fa0"
(folder/(image_id+".png")).write_bytes(png)
(folder/"catalog.json").write_text(json.dumps({"entries":[{"id":image_id,"owner":owner,"bytes":len(png),"width":32,"height":32,"viewers":[],"approved":False}]}))
def command(folder):
    return ["bash", (folder / "launch.txt").read_text().strip()]
with (server / "console.log").open("w") as log:
    proc = subprocess.Popen(command(server), cwd=server, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
    try:
        for tick in range(180):
            if proc.poll() is not None:
                raise RuntimeError("Dedicated server stopped during startup")
            if "For help, type" in (server/"console.log").read_text(errors="replace"):
                break
            time.sleep(1)
        else:
            raise TimeoutError("Server startup")
        with (client / "console.log").open("w") as client_log:
            subprocess.run(["xvfb-run","-a",*command(client)],cwd=client,stdout=client_log,stderr=subprocess.STDOUT,timeout=210,check=True)
        assert (client / "CHECKS.txt").is_file() and (server / "entry.txt").is_file()
    finally:
        if proc.poll() is None:
            proc.communicate("stop\n",timeout=45)
with (server / "restart.log").open("w") as log:
    subprocess.run(command(server),cwd=server,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=150)
print((client/"CHECKS.txt").read_text()+(server/"CHECKS.txt").read_text())

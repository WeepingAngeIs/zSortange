"""Exercise one room placement and extended visit on the hosted guest and dedicated server."""
import os
from pathlib import Path
import subprocess
import time

if os.getenv("GITHUB_ACTIONS") != "true":
    raise SystemExit("Hosted fixture only")
root = Path("build/room-chests")
server, client = root / "Server", root / "Client"
(server / "eula.txt").write_text("eula=true\n")
(server / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=25574\nonline-mode=false\nlevel-type=minecraft:flat\nview-distance=3\nsimulation-distance=3\n")
(client / "options.txt").write_text("onboardAccessibility:false\ntutorialStep:none\nenableVsync:false\nrenderDistance:4\n")
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

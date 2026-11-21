"""Exercise normal player packets on a loopback-only disposable hosted server."""
import os
import subprocess
import sys
import time
from pathlib import Path

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("Hosted fixture only")
root = Path("build/role-check") / sys.argv[1]
control = root / "control"
control.mkdir(parents=True, exist_ok=True)
server = root / "Server"
(server / "eula.txt").write_text("eula=true\n")
(server / "server.properties").write_text(
    "server-ip=127.0.0.1\nserver-port=25574\nonline-mode=false\ngamemode=survival\n"
    "spawn-protection=0\nlevel-type=minecraft:flat\nview-distance=4\nsimulation-distance=3\n")
roles = ["CamOwner","RoomBuilder"]
if sys.argv[1] != "baseline":
    roles.append("RoomVisitor")

processes = {}
logs = []
def start(name, display):
    folder = root / name
    console = (folder / "console.log").open("w")
    logs.append(console)
    command = ["bash", (folder / "launch.txt").read_text().strip()]
    if display:
        command = ["xvfb-run", "-a"] + command
        (folder / "options.txt").write_text(
            "onboardAccessibility:false\ntutorialStep:none\nenableVsync:false\n"
            "renderDistance:4\nsimulationDistance:3\nmaxFps:35\n")
    processes[name] = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE,
                                        stdout=console, stderr=subprocess.STDOUT, text=True)

try:
    start("Server", False)
    for _ in range(180):
        if processes["Server"].poll() is not None:
            raise RuntimeError("Server startup failed")
        if "For help, type" in (server / "console.log").read_text(errors="replace"):
            break
        time.sleep(1)
    else:
        raise TimeoutError("Server startup")
    for role in roles:
        start(role, True)
        time.sleep(1)
    deadline = time.monotonic()+380
    while not (control / "PASS.txt").exists():
        failures = list(control.glob("FAIL-*"))
        if failures:
            raise RuntimeError(failures[0].read_text())
        for name,proc in processes.items():
            if proc.poll() is not None:
                raise RuntimeError(name+" stopped during fixture")
        if time.monotonic()>deadline:
            raise TimeoutError("Last step: "+(control/"step.txt").read_text())
        time.sleep(.5)
    print((control / "PASS.txt").read_text())
finally:
    (control / "quit").touch()
    if processes["Server"].poll() is None:
        processes["Server"].stdin.write("stop\n")
        processes["Server"].stdin.flush()
    for proc in processes.values():
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            proc.terminate()
    for log in logs:
        log.close()


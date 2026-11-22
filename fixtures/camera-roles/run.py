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
solo = sys.argv[1].endswith('-solo')
sharp = sys.argv[1].startswith('sharp-')
horizon = sys.argv[1].startswith('sharp-horizon-')
view_distance = 10 if horizon else 4
server.mkdir(exist_ok=True)
(server / "eula.txt").write_text("eula=true\n")
(server / "server.properties").write_text(
    "server-ip=127.0.0.1\nserver-port=25574\nonline-mode=false\ngamemode=survival\n"
    f"spawn-protection=0\nlevel-type=minecraft:flat\nview-distance={view_distance}\nsimulation-distance=3\n")
roles = ["CamOwner"] if solo else ["CamOwner","RoomBuilder"]
if sys.argv[1] == "candidate":
    roles.append("RoomVisitor")

processes = {}
logs = []
def start(name, display):
    folder = root / name
    console = (folder / ("restart.log" if (control / "restart").exists() else "console.log")).open("w")
    logs.append(console)
    command = ["bash", (folder / "launch.txt").read_text().strip()]
    env = os.environ.copy()
    if display:
        # Concurrent clients must not extract and load the same native library.
        tmp = (folder / "native-tmp").resolve()
        native = (folder / "lwjgl").resolve()
        tmp.mkdir()
        native.mkdir()
        env["JAVA_TOOL_OPTIONS"] = f"-Djava.io.tmpdir={tmp} -Dorg.lwjgl.system.SharedLibraryExtractPath={native}"
        command = ["xvfb-run", "-a"] + command
        (folder / "options.txt").write_text(
            "onboardAccessibility:false\ntutorialStep:none\nenableVsync:false\n"
            f"renderDistance:{view_distance}\nsimulationDistance:3\nmaxFps:35\n")
    processes[name] = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE,
                                        stdout=console, stderr=subprocess.STDOUT, text=True, env=env)

try:
    if not solo:
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
    # The sharp trial exercises actual GPU draws on the software-hosted runners; its
    # last step also waits for a genuine vanilla chunk eviction, not a simulated signal.
    budget = 1100 if horizon else 720 if sharp else 630 if sys.argv[1]=="studio" else 380
    deadline = time.monotonic()+budget
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
    if "Server" in processes and processes["Server"].poll() is None:
        processes["Server"].stdin.write("stop\n")
        processes["Server"].stdin.flush()
    for proc in processes.values():
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            proc.terminate()
    for log in logs:
        log.close()


if sys.argv[1] == "studio":
    (control / "restart").touch()
    start("Server",False)
    try:
        result=processes["Server"].wait(timeout=120)
        if result or not (control/"restored").exists():
            raise RuntimeError("Dedicated restart failed: "+(server/"restart.log").read_text(errors="replace")[-2500:])
        print((control/"restored").read_text())
    finally:
        if "Server" in processes and processes["Server"].poll() is None:
            processes["Server"].terminate()
        for log in logs:
            log.close()

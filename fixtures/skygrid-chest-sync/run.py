"""Three boots of one synthetic world. All runtime material stays on the public hosted runner."""
import os
import shutil
import subprocess
import time
from contextlib import ExitStack
from pathlib import Path

if os.getenv("GITHUB_ACTIONS") != "true":
    raise SystemExit("GitHub-hosted fixture only")

root = Path("build/chest-sync").resolve()
legacy, current, client = root/"Legacy/Server", root/"Candidate/Server", root/"Candidate/Client"

def launch(folder):
    return ["bash", (folder/"launch.txt").read_text().strip()]

def run_phase(folder, name, with_client=False):
    (folder/"phase.txt").write_text(name)
    (folder/"eula.txt").write_text("eula=true\n")
    (folder/"server.properties").write_text("server-ip=127.0.0.1\nserver-port=25577\nonline-mode=false\nspawn-protection=0\nlevel-type=minecraft:flat\ngamemode=survival\ndifficulty=peaceful\nview-distance=3\nsimulation-distance=3\n")
    with ExitStack() as files:
        log = folder/(name+".log")
        proc = subprocess.Popen(launch(folder), cwd=folder, stdin=subprocess.PIPE, stdout=files.enter_context(log.open("w")), stderr=subprocess.STDOUT, text=True)
        guests = []
        try:
            deadline = time.monotonic()+250
            while time.monotonic()<deadline:
                if (folder/"FAILED").exists():
                    raise RuntimeError((folder/"FAILED").read_text())
                if (folder/(name+".pass")).exists():
                    break
                if proc.poll() is not None or any(g.poll() is not None for g in guests):
                    raise RuntimeError(f"{name} process exited before its checks completed")
                if with_client and not guests and "For help, type" in log.read_text(errors="replace"):
                    for player_dir in (client,client.with_name("Builder")):
                        (player_dir/"options.txt").write_text("onboardAccessibility:false\ntutorialStep:none\nenableVsync:false\nrenderDistance:3\n")
                        guests.append(subprocess.Popen(["xvfb-run","-a",*launch(player_dir)],cwd=player_dir,stdout=files.enter_context((player_dir/"client.log").open("w")),stderr=subprocess.STDOUT))
                time.sleep(.3)
            else:
                raise TimeoutError(name)
            print((folder/(name+".pass")).read_text().strip(), flush=True)
        finally:
            if proc.poll() is None:
                proc.communicate("stop\n",timeout=55)
            for guest in guests:
                if guest.poll() is None:
                    guest.terminate()
                    guest.wait(timeout=20)
        if proc.returncode:
            raise RuntimeError(f"{name}: server returned {proc.returncode}")

if os.getenv("SKYGRID_CHEST_LAYOUT") == "true":
    run_phase(legacy,"layout-baseline")
    run_phase(current,"layout-play",True)
    run_phase(current,"layout-restart")
else:
    run_phase(legacy,"legacy")
    shutil.copytree(legacy/"world",current/"world")
    shutil.copyfile(legacy/"ids.txt",current/"ids.txt")
    run_phase(current,"upgrade",True)
    run_phase(current,"reload")

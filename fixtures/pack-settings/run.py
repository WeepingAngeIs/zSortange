"""Boot the same isolated server twice and retain only the public check results."""
import os
from pathlib import Path
import subprocess
import time

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("GitHub-hosted fixture only")
server=Path("build/pack-settings/Server").resolve()
(server/"server.properties").write_text("server-ip=127.0.0.1\nserver-port=25579\nonline-mode=false\nlevel-type=minecraft:flat\nview-distance=2\nsimulation-distance=2\n")
(server/"eula.txt").write_text("eula=true\n")
for phase in ("first","restart"):
    (server/"phase.txt").write_text(phase)
    with (server/(phase+".log")).open("w") as output:
        process=subprocess.Popen(["bash",(server/"launch.txt").read_text().strip()],cwd=server,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True)
        try:
            deadline=time.monotonic()+120
            while time.monotonic()<deadline:
                if (server/"FAILED").exists():
                    raise RuntimeError((server/"FAILED").read_text())
                if (server/(phase+".pass")).exists():
                    break
                if process.poll() is not None:
                    raise RuntimeError(f"{phase} stopped before its checks finished")
                time.sleep(.4)
            else:
                raise TimeoutError(phase)
        finally:
            if process.poll() is None:
                process.communicate("stop\n",timeout=40)
        if process.returncode:
            raise RuntimeError(f"{phase} shutdown returned {process.returncode}")
        print((server/(phase+".pass")).read_text().strip(),flush=True)

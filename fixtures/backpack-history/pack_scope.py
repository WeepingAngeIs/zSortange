"""Keep the two full-pack JVMs away from the hosted worker's own memory allowance."""
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def main():
    assert os.environ["GITHUB_ACTIONS"] == "true" and os.environ["RUNNER_ENVIRONMENT"] == "github-hosted"
    assert os.environ["GITHUB_JOB"] == "pack-custody" and os.geteuid() != 0
    script = Path(sys.argv[1]).resolve(strict=True)
    assert script.parent == Path(__file__).resolve().parent
    assert script.name in ("stock_admission.py","stock_recovery.py")
    cgroups = Path("/sys/fs/cgroup")
    assert "memory" in (cgroups/"cgroup.controllers").read_text().split()
    run, attempt = (int(os.environ[k]) for k in ("GITHUB_RUN_ID","GITHUB_RUN_ATTEMPT"))
    scope = f"storage-pack-{run}-{attempt}-{script.stem.replace('_','-')}-{os.getpid()}.scope"
    group = cgroups / "system.slice" / scope
    high = 25*(1024**3)//2
    maximum = 13*1024**3
    pid = os.fork()
    if pid == 0:
        os.kill(os.getpid(), signal.SIGSTOP)
        os.execv(sys.executable,[sys.executable,"-u",str(script),*sys.argv[2:]])
    try:
        _, state = os.waitpid(pid, os.WUNTRACED)
        assert os.WIFSTOPPED(state)
        # Systemd attaches an existing unprivileged process. No game or its arguments
        # are passed to the privileged process, and the Actions worker stays outside.
        subprocess.run(["sudo","-n","busctl","--system","--quiet","call","org.freedesktop.systemd1",
            "/org/freedesktop/systemd1","org.freedesktop.systemd1.Manager","StartTransientUnit",
            "ssa(sv)a(sa(sv))",scope,"fail","6","Slice","s","system.slice","MemoryAccounting","b","true",
            "MemoryHigh","t",str(high),"MemoryMax","t",str(maximum),"MemorySwapMax","t",str(2**64-1),
            "PIDs","au","1",str(pid),"0"],check=True)
        for _ in range(100):
            if f"0::/system.slice/{scope}" in Path(f"/proc/{pid}/cgroup").read_text().splitlines():break
            time.sleep(.1)
        else:
            raise RuntimeError("The worker process did not enter its RAM-limited scope")
        assert (group/"memory.high").read_text().strip()==str(high)
        assert (group/"memory.max").read_text().strip()==str(maximum)
        assert (group/"memory.swap.max").read_text().strip()=="max"
        os.kill(pid,signal.SIGCONT)
        while True:
            child,status = os.waitpid(pid,os.WNOHANG)
            if child:
                pid=None
                raise SystemExit(os.waitstatus_to_exitcode(status))
            spare=(group/"memory.swap.current").read_text().strip()
            resident=(group/"memory.current").read_text().strip()
            print(f"Pack process group: RAM={resident} swap={spare}",flush=True)
            time.sleep(20)
    finally:
        if pid is not None:
            try: os.kill(pid,signal.SIGKILL)
            except ProcessLookupError: pass
        subprocess.run(["sudo","-n","systemctl","stop","--no-block",scope],check=False)


if __name__=="__main__":
    main()

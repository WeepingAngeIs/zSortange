"""Open the same disposable world with two frozen published test artifacts."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys

OLD = "1cd9fa2c91060f24246075eeba6c1ac01801eef492f1572b8d1d518285571c15"
NEW = "afd62612c2f09c6bf156831b0e798da58ef0fd1632297dc936ea69e11b66e91e"

def main():
    assert os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted"
    old, target = [Path(arg).resolve(strict=True) for arg in sys.argv[1:3]]
    frozen = NEW if len(sys.argv) == 3 else sys.argv[3]
    assert len(frozen)==64 and [hashlib.sha256(p.read_bytes()).hexdigest() for p in (old, target)] == [OLD, frozen]
    root = Path(__file__).resolve().parents[2]
    base = root / "build/release-check"
    game = base / "game"
    game.mkdir(parents=True)
    (game / "eula.txt").write_text("eula=true\n")
    (game / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\n"
        "level-name=synthetic-upgrade\nlevel-type=minecraft:flat\nspawn-protection=0\n"
        "view-distance=3\nsimulation-distance=3\ngenerate-structures=false\n")
    subprocess.run(["gradle","--no-daemon","--max-workers=2","-I","fixtures/release/fixture.gradle",
                    f"-PreleaseLegacy={old}",f"-PreleaseTarget={target}","prepareUpgradeProbe"],check=True,cwd=root)
    legacy, current = (base / "launch.txt").read_text().splitlines()
    receipts = []
    pids = set()
    for phase, script, sha in (("seed",legacy,OLD),("upgrade",current,frozen),("restart",current,frozen)):
        log = base / (phase + ".log")
        with log.open("x") as writer:
            run = subprocess.run(["bash",script],cwd=game,env=dict(os.environ,STORAGE_UPGRADE_PHASE=phase,
                STORAGE_UPGRADE_SHA=sha),stdout=writer,stderr=subprocess.STDOUT,timeout=300)
        receipt = game / (phase + ".pass")
        if run.returncode or not receipt.is_file() or "All dimensions are saved" not in log.read_text(errors="replace"):
            print(log.read_text(errors="replace")[-18000:])
            raise SystemExit(phase + " did not finish")
        pid, desc = receipt.read_text().splitlines()
        assert pid not in pids and desc.startswith(phase + " " + sha)
        pids.add(pid)
        receipts.append(desc)
    (base/"UPGRADE.txt").write_text("\n".join(receipts)+"\n")
    print("\n".join(receipts))

if __name__ == "__main__":
    main()

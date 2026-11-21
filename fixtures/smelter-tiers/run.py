"""Run two normal servers against the same frozen playable and the same hosted test world."""
from hashlib import sha256
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("This fixture requires a disposable GitHub Actions runner.")
    root = Path(__file__).resolve().parents[2]
    playable = Path(sys.argv[1]).resolve(strict=True)
    digest = sha256(playable.read_bytes()).hexdigest()
    evidence = root / "build/smelter-tier"
    game = evidence / "game"
    game.mkdir(parents=True, exist_ok=True)
    if any(game.iterdir()):
        raise SystemExit("The seed server requires an empty disposable game directory.")
    (game / "eula.txt").write_text("eula=true\n")
    (game / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-name=smelter-world\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nlevel-seed=1704\n"
        "view-distance=3\nsimulation-distance=3\nspawn-protection=0\n")
    subprocess.run(["gradle", "--no-daemon", "--console=plain", "--max-workers=2",
                    "-I", "fixtures/smelter-tiers/fixture.gradle",
                    f"-PsmelterPlayable={playable}", "prepareSmelterReview"], cwd=root, check=True)
    launch = (evidence / "launch.txt").read_text().strip()
    receipts = []
    pids = set()
    for phase in ("seed", "verify"):
        with (evidence / f"{phase}.console.log").open("w") as console:
            result = subprocess.run(["bash", launch], cwd=game, timeout=360,
                                    env=dict(os.environ, ANYA_SMELTER_PHASE=phase),
                                    stdout=console, stderr=subprocess.STDOUT)
        log = game / "logs/latest.log"
        if log.is_file():
            shutil.copyfile(log, evidence / f"{phase}.log")
        text = (evidence / f"{phase}.console.log").read_text(errors="replace")
        if result.returncode or not (game / f"{phase}.pass").is_file():
            print("\n".join(text.splitlines()[-120:]), flush=True)
            raise SystemExit(f"{phase} did not complete, process exit {result.returncode}")
        receipt = (game / f"{phase}.pass").read_text().splitlines()
        assert receipt[0] == f"{phase} {digest}", receipt
        assert receipt[1].startswith("JVM ") and int(receipt[1][4:]) > 0, receipt
        pids.add(receipt[1])
        assert (game / "smelter-world/level.dat").is_file(), "World save did not finish"
        receipts.extend(receipt)
    assert len(pids) == 2, "Expected two different server processes"
    summary = "\n".join(receipts) + "\n"
    (evidence / "CHECKS.txt").write_text(summary)
    print(summary, end="")


if __name__ == "__main__":
    main()

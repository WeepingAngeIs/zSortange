"""Exercise the exact uploaded playable in successive hosted server processes."""
from hashlib import sha256
import os
from pathlib import Path
import shutil
import subprocess
import sys
import region_rollback


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("The history fixture requires a disposable GitHub Actions runner.")
    root = Path(__file__).resolve().parents[2]
    jar = Path(sys.argv[1]).resolve(strict=True)
    digest = sha256(jar.read_bytes()).hexdigest()
    base = root / "build/bag-history"
    game = base / "game"
    game.mkdir(parents=True)
    (game / "eula.txt").write_text("eula=true\n")
    (game / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-name=history-world\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nlevel-seed=59482\n"
        "view-distance=3\nsimulation-distance=3\nspawn-protection=0\n")
    subprocess.run(["gradle", "--no-daemon", "--max-workers=2", "--console=plain",
                    "-I", "fixtures/backpack-history/fixture.gradle",
                    f"-PbagPlayable={jar}", "prepareBagHistoryReview"], cwd=root, check=True)
    script = (base / "launch.txt").read_text().strip()
    receipts = []
    pids = set()
    for phase in ("seed", "queue", "deliver", "replay", "receipts", "source-loss", "source-replay"):
        path = base / (phase + ".console.log")
        with path.open("x") as output:
            proc = subprocess.run(["bash", script], cwd=game, env=dict(os.environ, ANYA_BAG_PHASE=phase),
                                  timeout=300, stdout=output, stderr=subprocess.STDOUT)
        if (game / "logs/latest.log").is_file():
            shutil.copyfile(game / "logs/latest.log", base / (phase + ".server.log"))
        console = path.read_text(errors="replace")
        marker = game / (phase + ".pass")
        if proc.returncode or not marker.is_file() or "All dimensions are saved" not in console:
            print("\n".join(console.splitlines()[-110:]), flush=True)
            raise SystemExit(f"{phase} did not finish a clean save and exit (exit {proc.returncode})")
        lines = marker.read_text().splitlines()
        assert lines[0] == phase + " " + digest and lines[1].startswith("JVM "), lines[:2]
        assert lines[1] not in pids and int(lines[1][4:]) > 0
        pids.add(lines[1])
        assert (game / "history-world/level.dat").is_file()
        receipts.extend(lines)
        if phase == "receipts":
            receipts.append("SAVED original entity chunk SHA-256 " + region_rollback.capture(game))
        if phase == "source-loss":
            receipts.append(region_rollback.reinsert(game))
    summary = "\n".join(receipts) + "\n"
    (base / "CHECKS.txt").write_text(summary)
    print(summary)


if __name__ == "__main__":
    main()

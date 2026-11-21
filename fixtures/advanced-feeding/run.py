"""Start one disposable GitHub-hosted feeding test server with or without the pinned provider."""
import argparse
from hashlib import sha256
import os
from pathlib import Path
import shutil
import subprocess


ONION = "11605cfec2ac2e6385fc119d01bb6633b8a9ce8fdf0614cd272254630d56c0a7"
CORE = "39506208ad0c7efb2c9ac8128766425faebf50c408ec869d5f5043c0742c231b"


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("This fixture requires a disposable GitHub Actions runner.")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("playable", type=Path)
    parser.add_argument("--provider", type=Path)
    parser.add_argument("--core", type=Path)
    args = parser.parse_args()
    if bool(args.provider) != bool(args.core):
        parser.error("Supply both the pinned Onion JAR and its CreativeCore dependency.")
    root = Path(__file__).resolve().parents[2]
    playable = args.playable.resolve(strict=True)
    digest = sha256(playable.read_bytes()).hexdigest()
    variant = "provider" if args.provider else "plain"
    out = root / "build" / "advanced-feeding" / variant
    game = out / "game"
    game.mkdir(parents=True, exist_ok=True)
    if any(game.iterdir()):
        raise SystemExit("Required a new disposable server directory.")
    (game / "eula.txt").write_text("eula=true\n")
    (game / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-name=feeding-test\n"
        "level-type=minecraft:flat\nlevel-seed=93643\ngenerate-structures=false\ndifficulty=normal\n"
        "view-distance=3\nsimulation-distance=3\nspawn-protection=0\n")
    command = ["gradle", "--no-daemon", "--console=plain", "--max-workers=2", "-I",
               "fixtures/advanced-feeding/fixture.gradle", f"-PfeedingPlayable={playable}"]
    if args.provider:
        for prop, path, expected in [("feedingProvider", args.provider, ONION), ("feedingCore", args.core, CORE)]:
            source = path.resolve(strict=True)
            if sha256(source.read_bytes()).hexdigest() != expected:
                raise SystemExit(f"Unexpected SHA-256 for {source.name}")
            command.append(f"-P{prop}={source}")
    subprocess.run([*command, "prepareFeedingReview"], cwd=root, check=True)
    with (out / "console.log").open("w") as log:
        result = subprocess.run(["bash", (out / "launch.txt").read_text().strip()], cwd=game, timeout=360,
                                stdout=log, stderr=subprocess.STDOUT)
    if (game / "logs/latest.log").is_file():
        shutil.copyfile(game / "logs/latest.log", out / "server.log")
    receipt = game / "feeding-checks.txt"
    if result.returncode or not receipt.is_file():
        print("\n".join((out / "console.log").read_text(errors="replace").splitlines()[-120:]))
        raise SystemExit(f"Server {variant} did not complete, process exit {result.returncode}")
    text = receipt.read_text()
    assert text.splitlines()[:2] == [f"Playable SHA-256 {digest}", f"Runtime {variant}"]
    if args.provider:
        assert ONION in text and CORE in text
    (out / "CHECKS.txt").write_text(text)
    print(text, end="")


if __name__ == "__main__":
    main()

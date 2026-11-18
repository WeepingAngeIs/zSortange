"""Two clean dedicated processes, one tiny throwaway world, no client or profile."""

from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request


INSTALLER = "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar"
INSTALLER_SHA = "d88b448eab73cd65bdf1720844a4828262de30a15fc71bd04dd81acc61c5399a"
INTEGRATIONS = {
    "open-parties-and-claims-neoforge-1.21.1-0.33.0.jar": "77eadbbb7cbd7298d9a99070ebde552b765dd91bdad5054c9f845a17d2931765",
    "carryon-neoforge-1.21.1-2.2.6.13.jar": "d4f9576b6593db9486a89d35c1d84c31ee89141d99dbae2aa470350ab53c4e09",
}


def digest(path):
    return sha256(path.read_bytes()).hexdigest()


def run():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        raise SystemExit("Run this fixture only on a standard GitHub-hosted runner.")
    root = Path(__file__).resolve().parents[1]
    server = root / "build/ordinary-storage/server"
    server.mkdir(parents=True, exist_ok=False)
    evidence = root / "export/ordinary-storage"
    evidence.mkdir(parents=True, exist_ok=False)
    mods = server / "mods"
    mods.mkdir()
    jar, = [p for p in (root / "build/libs").glob("anya-storage-plus-*.jar") if not p.name.endswith("-sources.jar")]
    staged = root / "build/acceptance"
    fixture, = staged.glob("anya-storage-acceptance-fixture-*.jar")
    # Gradle module filenames can include a repeated Minecraft version. Identify by the exact bytes.
    dependencies = {digest(p): p for p in (staged / "dependencies").glob("*.jar")}
    for name, expected in INTEGRATIONS.items():
        shutil.copyfile(dependencies[expected], mods / name)
    for source in (jar, fixture):
        shutil.copyfile(source, mods / source.name)
    (evidence / "inputs.json").write_text(json.dumps({p.name: digest(p) for p in sorted(mods.iterdir())}, indent=2))
    installer = server.parent / "installer.jar"
    urllib.request.urlretrieve(INSTALLER, installer)
    if digest(installer) != INSTALLER_SHA:
        raise ValueError("Unexpected NeoForge installer checksum")
    with (evidence / "install.log").open("w") as log:
        subprocess.run(["java", "-Xmx1G", "-jar", str(installer), "--installServer", str(server)],
                       cwd=server, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=240)
    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25582\nlevel-name=world\nlevel-type=minecraft:flat\n"
        "generate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nonline-mode=true\n"
    )
    for phase in ("seed", "verify"):
        log_path = evidence / f"{phase}.log"
        with log_path.open("w") as log:
            result = subprocess.run(
                ["java", "-Xms256m", "-Xmx1400m", "-Danya.acceptance=true", f"-Danya.ordinaryStorage={phase}",
                 f"-Danya.control={evidence}", f"-Danya.expectedJar={jar}",
                 "@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt", "nogui"],
                cwd=server, stdout=log, stderr=subprocess.STDOUT, timeout=180,
            )
        output = log_path.read_text(errors="replace")
        if result.returncode or not (evidence / f"ordinary-{phase}").is_file() or "All dimensions are saved" not in output:
            raise RuntimeError(f"{phase} did not complete cleanly\n{output[-6000:]}")
        print(f"Ordinary storage {phase}: {jar.name}, pockets off, dedicated server saved normally.", flush=True)


if __name__ == "__main__":
    run()

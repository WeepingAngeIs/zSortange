"""Continue the pinned jail's real hosted admission in its cleanly saved, disposable world."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def tail(path):
    if not path.is_file():
        return ""
    with path.open("rb") as handle:
        handle.seek(max(0, path.stat().st_size - 32768))
        return handle.read().decode(errors="replace")


def stop_group(process):
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=15)
    except ProcessLookupError:
        pass


def run_phase(root, server, client, jar, jail, proof, phase):
    env = dict(os.environ, ANYA_BAG_PHASE=phase, ANYA_ROSE_JAR=str(jail),
               LIBGL_ALWAYS_SOFTWARE="true", ALSOFT_DRIVERS="null")
    # The pack can put Xaero's optional update dialog in front of the title. The
    # Storage fixture uses the normal multiplayer connection after that dialog.
    assert not any(env.get(name) for name in ("UQ_PACKAGED_JAIL_SMOKE", "UQ_TEST_JOIN_FROM_TITLE"))
    server_env = dict(env, JAVA_TOOL_OPTIONS="-Danya.bagHistoryPlayable=" + str(jar))
    main_log, client_log = proof / (phase + "-server.log"), proof / (phase + "-client.log")
    marker = server / (phase + ".pass")
    assert not marker.exists()
    with main_log.open("x") as output, client_log.open("x") as graphics:
        srv = cli = None
        try:
            srv = subprocess.Popen(["java", "-Xms512m", "-Xmx3G", "-Dneoforge.readTimeout=180",
                                    "@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt", "nogui"],
                                   cwd=server, env=server_env, stdin=subprocess.PIPE, stdout=output,
                                   stderr=subprocess.STDOUT, text=True, start_new_session=True)
            deadline = time.monotonic() + 11 * 60
            while "Done (" not in tail(main_log):
                if srv.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError("Full server failed to start. " + tail(main_log)[-3000:])
                time.sleep(2)
            cli = subprocess.Popen(["xvfb-run", "-a", "-s", "-screen 0 800x600x24", sys.executable,
                                    str(root / ".github/client249/launch.py"), str(client),
                                    "--join", "127.0.0.1:25565", "--name", "Dev"],
                                   cwd=root, env=dict(env, UQ_TEST_JOIN_FROM_TITLE="1"), stdout=graphics, stderr=subprocess.STDOUT,
                                   start_new_session=True)
            deadline = time.monotonic() + 14 * 60
            while not marker.is_file():
                if cli.poll() is not None or srv.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError("Incomplete " + phase + ". Server: " + tail(main_log)[-4500:]
                                       + "\nClient: " + tail(client_log)[-1000:])
                time.sleep(2)
            if srv.wait(timeout=120) != 0 or "All dimensions are saved" not in tail(main_log):
                raise RuntimeError("Full storage server did not save cleanly")
            lines = marker.read_text().splitlines()
            assert lines[:2] == [phase + " " + digest(jar), "JVM " + str(srv.pid)], lines
            assert any(line.startswith("PASS ") for line in lines), lines
            return lines
        finally:
            stop_group(cli)
            stop_group(srv)


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted"
    assert os.environ.get("GITHUB_REPOSITORY", "").lower() == "weepingangeis/zsortange"
    assert os.environ.get("GITHUB_JOB") == "pack-custody"
    root = Path(os.environ["GITHUB_WORKSPACE"]).resolve(strict=True)
    assert Path(__file__).resolve() == root / "storage/fixtures/backpack-history/rosecourt_pack.py"
    scratch = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    server = (scratch / "pack249-server").resolve(strict=True)
    client = (scratch / "pack249-client-native").resolve(strict=True)
    assert server.parent == scratch and client.parent == scratch
    world = server / "jail_certified_smoke"
    report = root / "dist/packaged-smoke"
    proof = root / "dist/storage-proof"
    proof.mkdir()
    jar, helper = [Path(x).resolve(strict=True) for x in sys.argv[1:]]
    assert jar.parent == root / "verified-storage" and helper.parent == root / "test-only"
    assert jar.name == os.environ["PLAYABLE"] and helper.name.startswith("hosted-bag-history-fixture")
    assert (jar.parent / "SOURCE_COMMIT.txt").read_text().strip() == os.environ["GITHUB_SHA"]
    assert digest(jar) == (jar.parent / "SHA256SUMS.txt").read_text().split()[0]
    jail = server / "mods" / os.environ["JAIL"]
    assert digest(jail) == os.environ["JAIL_HASH"]
    originally = json.loads((report / "center-phase1.json").read_text())
    assert originally["commit"] == os.environ["GITHUB_SHA"] and originally["jail_sha256"] == digest(jail)
    assert all(originally[key] for key in ("preview_place_undo_and_six_intact_cells",
                                         "actual_neoforge_21_1_249", "escape_native_respawn_in_cell_six_without_client_action"))
    checkpoint = json.loads((report / "full249-first-session-saved.json").read_text())
    assert checkpoint == {"commit": os.environ["GITHUB_SHA"], "origin_config": digest(server / "config/neoorigins/admin.toml")}
    for key in ("level.dat", "integrated-jail-v1/escrow.v1", "custody-interface-v1/custody.v1",
                "data/unyielding-jail-placement.before"):
        assert (world / key).stat().st_size > 8, key
    assert list((world / "integrated-jail-v1").glob("*/items/escrow.v1"))
    assert len(list((client / "mods").glob("*.jar"))) == 367
    assert (server / "server.properties").read_text().find("server-ip=127.0.0.1") >= 0
    # Retire the original independent stimulus only after the upstream acceptance receipts.
    # The certified pack JARs and actual item and custody journals stay in place.
    for game in (server, client):
        stimulus = game / "mods/fullpack-stimulus.jar"
        assert digest(stimulus) == digest(report / stimulus.name)
        stimulus.unlink()
        for source in (jar, helper):
            dest = game / "mods" / source.name
            assert not dest.exists()
            shutil.copy2(source, dest)
            assert digest(dest) == digest(source)
    (server / "native-occupied.pass").write_text(digest(report / "center-phase1.json"))
    results = [
        "Storage revision " + os.environ["GITHUB_SHA"],
        "Pinned Jail source 411857009b8e05f606bb0b5b70648dcce8a9623b and binary SHA-256 " + digest(jail),
        "Original full-pack native Visitor admission, cell-six respawn and clean save before adding Storage: "
        + digest(report / "center-phase1.json"),
        "Separate imported held Anya bag uses beta.3 journal writers and the normal native console bind. "
        "The changed mod list is outside the native new-admission certificate.",
    ]
    pids = []
    for phase in ("pack-storage", "pack-restart"):
        lines = run_phase(root, server, client, server / "mods" / jar.name, jail, proof, phase)
        assert lines[1] not in pids
        pids.append(lines[1])
        results.extend(lines)
    (proof / "VERIFIED.txt").write_text("\n".join(results) + "\n")
    print("\n".join(results), flush=True)


if __name__ == "__main__":
    main()

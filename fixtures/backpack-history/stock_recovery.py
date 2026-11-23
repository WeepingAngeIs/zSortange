"""Continue the pinned jail's real hosted admission in its cleanly saved, disposable world."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys


from rosecourt_pack import digest, run_phase


def main():
    assert os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted"
    assert os.environ.get("GITHUB_REPOSITORY", "").lower() == "weepingangeis/zsortange"
    assert os.environ.get("GITHUB_JOB") == "pack-custody"
    root = Path(os.environ["GITHUB_WORKSPACE"]).resolve(strict=True)
    assert Path(__file__).resolve() == root / "storage/fixtures/backpack-history/stock_recovery.py"
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
    assert (jar.parent / "SOURCE_COMMIT.txt").read_text().strip() == os.environ["PLAYABLE_COMMIT"]
    assert digest(jar) == os.environ["PLAYABLE_HASH"]
    assert digest(jar) == (jar.parent / "SHA256SUMS.txt").read_text().split()[0]
    jail = server / "mods" / os.environ["JAIL"]
    assert digest(jail) == os.environ["JAIL_HASH"]
    from stock_admission import verify_saved, verify_roster, SEAL
    stock_receipt = root / "dist/stock-admission/occupied.json"
    checkpoint = json.loads(stock_receipt.read_text())
    assert checkpoint["harness_revision"] == os.environ["GITHUB_SHA"] and checkpoint["jail_sha256"] == digest(jail)
    assert checkpoint["clean_stop"] and checkpoint["admission_via_console"] and checkpoint["server_test_helpers"] == 1
    assert checkpoint["stock_server_file_count"] == 298 and checkpoint["helper_sha256"] == digest(report/"fullpack-stimulus.jar")
    assert checkpoint["origin_config"] == digest(server / "config/neoorigins/admin.toml")
    runtime = verify_roster(root, server/"mods", jail)
    saved = verify_saved(world, runtime)
    assert all(checkpoint[k] == v for k,v in saved.items()) and saved["sealed_mod_rows_sha256"] == SEAL
    for key in ("level.dat", "integrated-jail-v1/escrow.v1", "custody-interface-v1/custody.v1",
                "data/unyielding-jail-placement.before"):
        assert (world / key).stat().st_size > 8, key
    assert list((world / "integrated-jail-v1").glob("*/items/escrow.v1"))
    assert len(list((client / "mods").glob("*.jar"))) == 367
    assert (server / "server.properties").read_text().find("server-ip=127.0.0.1") >= 0
    # Both ends had the same original helper during admission. The second stage uses the Storage test helper.
    for game in (server, client):
        stimulus = game / "mods/fullpack-stimulus.jar"
        assert digest(stimulus) == checkpoint["helper_sha256"]
        stimulus.unlink()
        for source in (jar, helper):
            dest = game / "mods" / source.name
            assert not dest.exists()
            shutil.copy2(source, dest)
            assert digest(dest) == digest(source)
    (server / "native-occupied.pass").write_text(digest(stock_receipt))
    results = [
        "Storage revision " + os.environ["PLAYABLE_COMMIT"] + ", SHA-256 " + digest(jar),
        "Hosted fixture revision " + os.environ["GITHUB_SHA"],
        "Pinned Jail source 411857009b8e05f606bb0b5b70648dcce8a9623b and binary SHA-256 " + digest(jail),
        "Original full-pack native Visitor admission in cell six, exact escrow marker, fingerprint and clean save "
        "before adding Storage, with the original jail helper on both endpoints: " + digest(stock_receipt),
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

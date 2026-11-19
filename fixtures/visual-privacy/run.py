"""One fresh GitHub server and two real clients for the illustrated entrance permission check."""
import hashlib
import importlib.util
import io
import json
import os
import platform
import shutil
import signal
import subprocess
import time
import zipfile
from pathlib import Path
from uuid import UUID

ROOT = Path.cwd().resolve()
BASE = ROOT / "build/visual-privacy"
EVIDENCE = BASE / "evidence"
CONTROL = BASE / "control"
SERVER = BASE / "server"
ROLES = ("Alpha", "Beta")
CE_DIGEST = "6c58d232328146c9b76ceee1eb53969a149ce97ace5af4b50424a30581bf6187"
ART2_DIGEST = "0a7eb462e4256518b2bed9b52e782b80649a03f4068ebdad0f6742a1a6515ea7"
STILL_TRIAL = os.environ.get("ANYA_STILL_TRIAL") == "1"
VERSION = "0.3.0-beta.7-still.1" if STILL_TRIAL else "0.3.0-beta.7-art.2"
SAFETY_FLAGS = ["anyastorage.pocketPrototype", "anya.nonblockingPrepare", "anya.minimalOwnership",
                "anya.serializationBudget", "anya.backpressure", "anya.repairJournal"]
SPEC = json.loads((ROOT / "fixtures/renderer-prereq/inputs.json").read_text())
APPROVAL = json.loads((ROOT / "fixtures/ce-control/approval.json").read_text())
module = importlib.util.spec_from_file_location("baseline", ROOT / "fixtures/renderer-prereq/run_baseline.py")
baseline = importlib.util.module_from_spec(module)
module.loader.exec_module(baseline)


def write(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n")


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def settings(values):
    return "".join(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n" for key, value in values.items())


def verify_denied_visits():
    """Match each targeted client click with its actual server-side permission decision."""
    scene = json.loads((CONTROL / "server-result.json").read_text())
    log = (EVIDENCE / "server.log").read_text(errors="replace")
    use_marker = "Disposable fallback phase USE"
    revoked_marker = "Disposable fallback phase REVOKED"
    check(log.count(use_marker) == log.count(revoked_marker) == 1, "Missing server action phase boundary")
    use_log = log.split(use_marker, 1)[1].split(revoked_marker, 1)[0]
    counts = {}
    for role in ROLES:
        player = str(UUID(scene[f"{role.lower()}_uuid"]))
        started = json.loads((CONTROL / f"client-ready-{role}.json").read_text())
        check(str(UUID(started["uuid"])) == player and started["name"] == role, f"Player identity differs for {role}")
        reason = "Pocket permission denied; Visit is separate from storage rights"
        decision = f"Pocket action stopped for {player}: java.io.IOException: {reason}"
        counts[role] = use_log.count(decision)
        check(counts[role] == 1, f"Expected one denied Visit from the normal entrance click for {role}")
    write(EVIDENCE / "use-server-proof.json", counts)


def verify_inputs():
    publisher = ROOT / "build/renderer-prereq/provenance"
    ce = ROOT / "build/ce-control/approved-ce.jar"
    check((publisher / "STATIC_VERIFIED").is_file(), "Publisher preflight missing")
    check((ce.parent / "CE_BINARY_VERIFIED").read_text().strip() == CE_DIGEST, "Unverified CE candidate")
    base = ROOT / f"build/libs/anya-storage-plus-{VERSION}.jar"
    safety = ROOT / f"build/operator/anya-operator-resolution-experiment-{VERSION}.jar"
    fixture = BASE / f"anya-hosted-visual-privacy-{VERSION}.jar"
    publisher_files = {a["mod_id"]: publisher / "verified-jars" / a["filename"] for a in SPEC["artifacts"]}
    base_digest = ART2_DIGEST
    if STILL_TRIAL:
        check((EVIDENCE / "SOURCE_COMMIT.txt").read_text().strip() == os.environ["GITHUB_SHA"],
              "Candidate source differs from the branch run")
        base_digest, artifact = (EVIDENCE / "still-candidate.sha256").read_text().split()
        check(artifact == base.relative_to(ROOT).as_posix() and base_digest != ART2_DIGEST,
              "Candidate build receipt does not identify the still-frame JAR")
    pins = {base: base_digest, safety: SPEC["safety_sha256"], ce: CE_DIGEST}
    for artifact in SPEC["artifacts"]:
        pins[publisher_files[artifact["mod_id"]]] = artifact["sha256"]
    pins[fixture] = baseline.sha(fixture)
    for path, pin in pins.items():
        check(baseline.sha(path) == pin, f"Hash mismatch for {path.name}")
    with zipfile.ZipFile(fixture) as f:
        check(not any(n.endswith(".jar") for n in f.namelist()), "Fixture accidentally bundles a dependency")
    with (EVIDENCE / "SHA256SUMS.txt").open("w") as out:
        for path, pin in pins.items():
            out.write(f"{pin}  {path.relative_to(ROOT)}\n")
    common = {"minecraft": {"version": "1.21.1"}, "neoforge": {"version": "21.1.249"},
              "anyastorage": {"sha256": pins[base], "version": VERSION},
              "anyadurableprobe": {"sha256": pins[safety]}, "anyavisualcheck": {"sha256": pins[fixture]}}
    for mod_id in ("imm_ptl", "immersive_portals_core", "q_misc_util"):
        common[mod_id] = {"version": "6.1.4", "sha256": pins[ce]}
    client = dict(common)
    for artifact in SPEC["artifacts"]:
        mod_id = artifact["mod_id"]
        rule = {"version": artifact["mod_version"]}
        if mod_id != "sodium":
            rule["sha256"] = artifact["sha256"]
        if mod_id == "cloth_config":
            common[mod_id] = rule
        client[mod_id] = rule
    client["fabric_renderer_api_v1"] = {"version": "3.4.1+9125b6dc19"}
    # ModList selects the implementation nested inside Sodium's verified outer wrapper.
    with zipfile.ZipFile(publisher_files["sodium"]) as z:
        expected_name, nested_pin = next(iter(SPEC["expected_nested"].items()))
        nested = z.read(next(n for n in z.namelist() if n.endswith("/" + expected_name)))
    check(hashlib.sha256(nested).hexdigest() == nested_pin["sha256"], "Sodium nested bytes changed")
    with zipfile.ZipFile(io.BytesIO(nested)) as z:
        resources = sorted(n for n in z.namelist() if n.startswith("net/caffeinemc/mods/sodium/client/") and n.endswith(".class"))
        check(len(resources) > 4, "Sodium implementation classes missing")
        witnesses = {n: hashlib.sha256(z.read(n)).hexdigest() for n in (resources[0], resources[len(resources)//2], resources[-1])}
    allowed = set(APPROVAL["runtime_allowlist"]) - {"anyacecontrol"} | {"anyavisualcheck"}
    write(CONTROL / "inputs.json", {"server": common, "client": client, "allowlist": sorted(allowed),
                                    "sodium_class_resources": witnesses, "sodium_nested_sha256": nested_pin["sha256"]})
    return [base, safety, fixture, ce, publisher_files["cloth_config"]], [*publisher_files.values(), ce], pins


def prepare(launches):
    server_mods, client_mods, pins = verify_inputs()
    def stage(directory, sources):
        target = directory / "mods"
        target.mkdir()
        for src in sources:
            copied = shutil.copy2(src, target / src.name)
            check(baseline.sha(Path(copied)) == pins[src], "Runtime staging changed " + src.name)

    ce_config = {"maxPortalLayer": 1, "indirectLoadingRadiusCap": 1, "portalRenderLimit": 2,
                 "enableCrossPortalInteraction": False, "enableDatapackPortalGen": False, "enableMirrorCreation": False,
                 "checkModInfoFromInternet": False, "enableUpdateNotification": False, "initialScreenShown": True,
                 "enableCrossPortalSound": False, "netherPortalMode": "vanilla", "endPortalMode": "vanilla"}
    options = {**SPEC["profile_options"], "maxFps": 25, "pauseOnLostFocus": False, "guiScale": 2,
               "tutorialStep": "none", "bobView": False, "fovEffectScale": 0.0, "entityShadows": False}
    for role, launch in launches.items():
        directory = Path(launch["cwd"]).resolve()
        check(directory == BASE / ("client-" + role) and not (directory / "saves").exists(), "Fresh client directory required")
        directory.mkdir(exist_ok=True)
        stage(directory, client_mods)
        cfg = directory / "config"
        cfg.mkdir(exist_ok=True)
        write(cfg / "immersive_portals.json", ce_config)
        (cfg / "iris.properties").write_text(settings(SPEC["iris_options"]))
        (directory / "options.txt").write_text("".join(f"{k}:{str(v).lower()}\n" for k,v in options.items()))

    SERVER.mkdir()
    stage(SERVER, server_mods)
    url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar"
    data = baseline.bounded_download(url, 16 * 1024**2)
    digest = baseline.bounded_download(url + ".sha256", 512).decode().split()[0].lower()
    check(hashlib.sha256(data).hexdigest() == digest, "NeoForge installer differs from official hash")
    installer = BASE / "neoforge-installer.jar"
    installer.write_bytes(data)
    write(EVIDENCE / "installer-provenance.json", {"url": url, "sha256": digest})
    with (EVIDENCE / "installer.log").open("w") as log:
        subprocess.run(["java", "-Xmx1000m", "-jar", str(installer), "--installServer", str(SERVER)],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=240)
    (SERVER / "config").mkdir(exist_ok=True)
    write(SERVER / "config/immersive_portals.json", ce_config)
    (SERVER / "eula.txt").write_text("eula=true\n")
    (SERVER / "server.properties").write_text(settings({"level-name": "world", "level-type": "minecraft:flat",
        "level-seed": 860612, "generate-structures": False, "online-mode": False, "view-distance": 6, "simulation-distance": 5,
        "spawn-protection": 0, "server-ip": "127.0.0.1", "server-port": 25579, "allow-flight": True,
        "enable-rcon": False, "enable-query": False, "max-tick-time": 90000}))
    write(EVIDENCE / "host.json", {"platform": platform.uname()._asdict(), "ce_source": APPROVAL["commit"],
        "minecraft": SPEC["minecraft"], "neoforge": SPEC["neoforge"], "shader_enabled": False,
        "server_heap_mib": 2048, "client_heap_mib": 1000, "target_gpu": False, "render_options": options,
        "ce_options": ce_config, "scene": "single approved material on a still" if STILL_TRIAL else "art.2 illustrated entrance",
        "live_adapter_loaded": False})


def main():
    check(os.environ.get("GITHUB_ACTIONS") == "true" and platform.system() == "Linux", "Requires disposable GitHub infrastructure")
    check(os.environ.get("LIBGL_ALWAYS_SOFTWARE") == "true", "Expected software GL")
    check(not SERVER.exists() and not CONTROL.exists(), "Refusing to reuse prior server or control files")
    EVIDENCE.mkdir(exist_ok=True)
    CONTROL.mkdir()
    (CONTROL / ".disposable-visual-privacy").write_text("Two-client visual check in a fresh world\n")
    launches = json.loads((BASE / "launches.json").read_text())
    check(set(launches) == set(ROLES), "Expected exactly two distinct roles")
    processes = {}
    logs = []
    samples = []
    passed = False

    def launch(name, command, cwd, env=None):
        log = (EVIDENCE / (name + ".log")).open("w")
        logs.append(log)
        processes[name] = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        return processes[name]

    def until(marker, seconds):
        deadline = time.monotonic() + seconds
        while not (CONTROL / marker).is_file():
            errors = sorted(CONTROL.glob("FAILED*"))
            check(not errors, "; ".join(p.read_text() for p in errors))
            check(time.monotonic() < deadline, f"Timed out waiting for {marker}")
            done = (CONTROL / "phase.txt").is_file() and (CONTROL / "phase.txt").read_text().strip() == "done"
            for name, proc in processes.items():
                if not done or name == "server":
                    check(proc.poll() is None or (CONTROL / marker).is_file(), f"{name} exited with {proc.returncode}")
            rss = {name: baseline.group_rss(proc.pid) for name, proc in processes.items()}
            available = int(next(v for v in Path("/proc/meminfo").read_text().splitlines() if v.startswith("MemAvailable:")).split()[1]) * 1024
            samples.append({"rss_bytes": rss, "available_bytes": available})
            check(rss.get("server", 0) <= 3 * 1024**3 and sum(rss.values()) <= 8 * 1024**3 and available >= 2 * 1024**3,
                  "Resource limit reached")
            time.sleep(1)

    try:
        prepare(launches)
        flags = ["-D" + name + "=true" for name in SAFETY_FLAGS]
        if STILL_TRIAL:
            flags.append("-Danyastorage.stillFramePeek=true")
        flags += [f"-Danya.control={CONTROL}", f"-Danya.runtimeManifest={CONTROL / 'inputs.json'}"]
        server = launch("server", ["java", "-Xmx2048m", "-Xms256m", *flags,
                        "@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt", "nogui"], SERVER)
        until("server-ready", 180)
        for role, v in launches.items():
            private = BASE / ("native-" + role)
            tmp = private / "tmp"
            tmp.mkdir(parents=True)
            native = private / "lwjgl"
            native.mkdir()
            env = dict(os.environ)
            env["JAVA_TOOL_OPTIONS"] = f"-Djava.io.tmpdir={tmp} -Dorg.lwjgl.system.SharedLibraryExtractPath={native}"
            launch(role, v["command"], v["cwd"], env)
        until("PASSED", 600)
        check(server.wait(timeout=60) == 0, "Server did not shut down cleanly")
        for role in ROLES:
            check(processes[role].wait(timeout=60) == 0 and (CONTROL / f"client-finished-{role}.json").is_file(),
                  f"{role} did not finish its measurements and exit cleanly")
        check(not list(CONTROL.glob("FAILED*")), "A fixture reported a failure")
        for role in ROLES:
            photos = (("public", "granted", "changed", "cell_denied", "resumed", "revoked", "regranted", "final_public")
                      if STILL_TRIAL else ("public", "granted", "revoked"))
            for phase in photos:
                check((CONTROL / f"{phase}-{role}.png").stat().st_size > 0, f"Frame missing for {phase} {role}")
            for filename in (f"backend-{role}.json", f"runtime-mods-{role}.json"):
                json.loads((CONTROL / filename).read_text())
        for name in ("runtime-mods-server.json", "server-result.json"):
            json.loads((CONTROL / name).read_text())
        verify_denied_visits()
        passed = True
        print("Two clients completed their scene. Independent framebuffer check follows.", flush=True)
    finally:
        for process in processes.values():
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
        for process in processes.values():
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
        for handle in logs:
            handle.close()
        write(EVIDENCE / "process-result.json", {"scene_completed": passed,
              "frame_verification": "separate framebuffer comparison receipt required", "launched": list(processes),
              "server_exit": processes["server"].returncode if "server" in processes else None})
        write(EVIDENCE / "resources.json", samples)
        shutil.copytree(CONTROL, EVIDENCE / "control")
        for label, directory in [("server", SERVER), *((role, Path(v["cwd"])) for role, v in launches.items())]:
            for name in ("crash-reports", "logs", "screenshots", "config"):
                if (directory / name).is_dir():
                    shutil.copytree(directory / name, EVIDENCE / label / name)


if __name__ == "__main__":
    main()

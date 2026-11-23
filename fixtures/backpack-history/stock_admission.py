"""Native stock admission with the original full-pack helper on both ends of its required channel."""
import gzip
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import time
import uuid
import zipfile

import nbtlib
from rosecourt_pack import digest, stop_group, tail

SEAL = "4fe43c038efc2211fdb0be84e25efd4f0c279e900c9afc21574dff4cf2f4a8cd"
JAIL = "d7cd43db7d6c4ea88372a1a7e09faee843d51d566a03c398dc999505a8053243"
COMPANION = "ec3ae2c48399e40ffa615ddec85408bc29b2479c5a35eb7236c3e036ebdf762c"
STOCK_RUNTIME = "4ef154609c7d6fc407dd212ae702c87b6f55c4a9e7bfe565eeee860250d25248"
OWNER = uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:Visitor").digest(), version=3)
MARKER = "Stock custody checkpoint"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def sha512(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha512").hexdigest()


def nbt(data):
    stream = io.BytesIO(data)
    result = nbtlib.File.parse(stream)
    assert not stream.read(1), "Unexpected trailing NBT"
    return result


def uid(tag):
    return uuid.UUID(bytes=b"".join(struct.pack(">i", int(n)) for n in tag))


def verify_roster(root, mods, jail):
    profile = root / ".github/pack249"
    excluded = json.loads((profile/"server-exclusions.json").read_text())
    exceptions = {"SimplyTooltips-neoforge-0.1.5.jar", "cobblemon_party_extras-neoforge-1.8.15.jar"}
    rows = {r["name"]: r["sha512"] for r in json.loads((profile/"profile.json").read_text())["mods"]
            if (r["side"] != "unsupported" or r["name"] in exceptions) and r["name"] not in excluded}
    helper = root/"dist/packaged-smoke/fullpack-stimulus.jar"
    assert len(rows) == 296 and {p.name for p in mods.glob("*.jar")} == set(rows) | {jail.name, helper.name}
    assert digest(mods/helper.name) == digest(helper)
    assert all(sha512(mods/name) == expected for name, expected in rows.items())
    assert digest(jail) == JAIL
    with zipfile.ZipFile(jail) as binary:
        assert binary.read("assets/minecraftjail/unyielding-249-server.sha256").decode().strip() == SEAL
    first = (root/"stock-census-a/loaded-rows.txt").read_text().splitlines()
    assert first == (root/"stock-census-b/loaded-rows.txt").read_text().splitlines()
    assert first == sorted(set(first)) and sha("\n".join(first).encode()) == SEAL
    kernel = ["disposable-harness", "minecraft=1.21.1", "neoforge=21.1.249",
              "minecraftjail-implementation=player-only-autorespawn-v1:" + sha512(jail)]
    expected_runtime = sha("\n".join(sorted(first + kernel)).encode())
    assert expected_runtime == STOCK_RUNTIME, "The native full-pack runtime identity with the original helper changed"
    return expected_runtime


def held(path, tx):
    stream = io.BytesIO(path.read_bytes())
    assert stream.read(8) == b"JES1\x00\x00\x00\x01"
    snapshots, states = [], []
    while header := stream.read(8):
        typ, size = struct.unpack(">II", header)
        assert typ == 0x4a454631 and 40 < size < 5 * 1024 * 1024
        body = stream.read(size)
        assert len(body)==size and hashlib.sha256(body).digest()==stream.read(32)
        assert uuid.UUID(bytes=body[:16])==tx and uuid.UUID(bytes=body[16:32])==OWNER
        state, length = struct.unpack(">II", body[32:40])
        assert length == len(body)-40
        snapshots.append(body[40:]);states.append(state)
    assert states == [0,1,2] and all(s==snapshots[0] for s in snapshots)
    return snapshots[0]


def verify_index(file, tx, item_digest, locker_coordinates):
    stream = io.BytesIO(file.read_bytes())
    assert stream.read(8)==b"UCV1\x00\x00\x00\x01"
    seq=0;previous=bytes(32)
    while header:=stream.read(4):
        size,=struct.unpack(">I",header)
        assert 0<size<=16384
        data=stream.read(size)
        previous=hashlib.sha256(previous+data).digest()
        assert stream.read(32)==previous
        body=io.BytesIO(data);seq+=1
        assert body.read(8)==struct.pack(">Q",seq) and uuid.UUID(bytes=body.read(16))==tx
        assert uuid.UUID(bytes=body.read(16))==OWNER
        def text():
            length,=struct.unpack(">H",body.read(2))
            return body.read(length).decode()
        assert text()==f"integrated-jail-v1/{tx}/items" and text()==f"integrated-jail-v1/{tx}/origins"
        assert body.read(32).hex()==item_digest and text()=="minecraft:overworld"
        assert struct.unpack(">iii", body.read(12)) == locker_coordinates
        state = body.read(1)
        assert state in (b"\0", b"\1"), "An occupied locker cannot be released"
        if seq == 1:
            assert state == b"\0", "A new native locker must start quarantined"
        assert text() and text() and not body.read(1)
    assert seq>=1


def verify_saved(world, expected_runtime):
    entries=list((world/"integrated-jail-v1").glob("*/items/escrow.v1"))
    assert len(entries)==1
    items=entries[0]; tx=uuid.UUID(items.parent.parent.name)
    assert str(tx)==items.parent.parent.name
    receipt=held(items,tx); digest_items=sha(receipt)
    equipment=nbt(receipt)
    assert int(equipment["version"])==2 and str(equipment["runtime"])==expected_runtime
    origin=held(items.parent.parent/"origins/escrow.v1",tx)
    assert origin[:4]==b"JOL1" and origin[4:36].hex()==digest_items
    assert struct.unpack(">I",origin[36:40])[0]==len(origin)-40
    plan=nbt(held(world/"integrated-jail-v1/escrow.v1",tx))
    assert plan["digest"].tobytes().hex()==digest_items
    assert str(plan["contract"]) in ("native-supported-v2","native-retained-xp-v3")
    locker_coordinates = tuple(int(plan[axis]) for axis in ("x", "y", "z"))
    verify_index(world/"custody-interface-v1/custody.v1",tx,digest_items,locker_coordinates)
    inventory=nbt(equipment["vanilla"].tobytes())["items"]
    marker=lambda s: str(s["id"])=="minecraft:amethyst_shard" and MARKER in str(s.get("components",{}))
    matching=[s for s in inventory if marker(s)]
    assert len(matching)==1 and int(matching[0]["count"])==13
    player=nbt(gzip.decompress((world/"playerdata"/(str(OWNER)+".dat")).read_bytes()))
    assert uid(player["UUID"])==OWNER and uid(player["NeoForgeData"]["uq_integrated_custody"])==tx
    assert not any(marker(s) for s in player["Inventory"])
    jail_data=nbt(gzip.decompress((world/"data/unyielding_jail.dat").read_bytes()))["data"]
    assert jail_data["integratedCustodyInitialized"] and len(jail_data["prisoners"])==1
    row=jail_data["prisoners"][0]
    assert uid(row["player"])==OWNER and row["jail"]==plan["jail"] and row["prior"]==plan["prior"]
    assert int(row["geometry"])==int(plan["geometry"])==int(jail_data["geometry"])==4
    # DetentionCenter.cell records the room via the location of cell one and the
    # floor offsets from DetentionPlan. The journal does not store a cell number.
    first=jail_data["location"]; dest=plan["jail"]
    assert all(dest[k]==first[k] for k in ("dim","y","yaw","pitch"))
    assert float(dest["x"])==float(first["x"])+12 and float(dest["z"])==float(first["z"])+12
    assert locker_coordinates==(math.floor(first["x"])+8,math.floor(first["y"]),math.floor(first["z"])+12)
    terrain=world/"data/unyielding-jail-placement.before"
    assert terrain.stat().st_size>40
    return {"owner":str(OWNER),"native_cell":6,"native_transaction":str(tx),"marker_count":13,
            "native_item_sha256":digest_items,"native_origin_sha256":sha(origin),
            "native_index_sha256":digest(world/"custody-interface-v1/custody.v1"),"terrain_sha256":digest(terrain),
            "native_runtime_with_genuine_helper":expected_runtime,"sealed_mod_rows_sha256":SEAL}


def await_signal(condition, processes, timeout, hint):
    until=time.monotonic()+timeout
    while not condition():
        if any(p.poll() is not None for p in processes) or time.monotonic()>until:
            raise RuntimeError(hint)
        time.sleep(.3)


def main():
    assert os.environ.get("GITHUB_ACTIONS")=="true" and os.environ.get("RUNNER_ENVIRONMENT")=="github-hosted"
    assert os.environ.get("GITHUB_JOB")=="pack-custody" and os.environ["GITHUB_REPOSITORY"].lower()=="weepingangeis/zsortange"
    root=Path(os.environ["GITHUB_WORKSPACE"]).resolve(strict=True)
    scratch=Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    report=root/"dist/packaged-smoke"
    output=root/"dist/stock-admission"
    output.mkdir()
    server=scratch/"pack249-server"; client=scratch/"pack249-client-native"
    assert server.resolve().parent==scratch and client.resolve().parent==scratch
    world=server/"jail_certified_smoke"
    assert not world.exists()
    jail=Path(sys.argv[1]).resolve(strict=True)
    assert digest(jail)==JAIL and jail.name=="Minecraft-Jail-1.3.0-beta.3.jar"
    for game in (server,client):
        dest=game/"mods"/jail.name
        assert not dest.exists()
        shutil.copy2(jail,dest)
    # The original helper declares a required bidirectional NeoForge channel.
    helper=report/"fullpack-stimulus.jar"
    assert not (client/"mods"/helper.name).exists() and not (server/"mods"/helper.name).exists()
    shutil.copy2(helper,client/"mods"/helper.name)
    shutil.copy2(helper,server/"mods"/helper.name)
    assert digest(client/"mods"/helper.name) == digest(helper)
    runtime=verify_roster(root,server/"mods",server/"mods"/jail.name)
    print("All 296 published dedicated JARs and the original Jail and helper match. Both sides have the required channel.",flush=True)
    assert len(list((client/"mods").glob("*.jar")))==367
    assert "eula=true" in (root/"dist/packaged-harness/run-server/eula.txt").read_text(encoding="utf-8-sig").replace(" ","")
    shutil.copy2(root/"dist/packaged-harness/run-server/eula.txt",server/"eula.txt")
    (server/"server.properties").write_text("online-mode=false\nserver-ip=127.0.0.1\nserver-port=25565\n"
        "level-name=jail_certified_smoke\nlevel-type=minecraft:flat\ngenerate-structures=false\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n'
        "spawn-protection=0\ndifficulty=peaceful\nspawn-monsters=false\nmax-players=2\nview-distance=2\n"
        "simulation-distance=2\nenable-query=false\nenable-rcon=false\nmax-tick-time=-1\n")
    (client/"options.txt").write_text("guiScale:2\nrenderDistance:2\nsimulationDistance:2\nmaxFps:30\n"
        "enableVsync:false\nonboardAccessibility:false\nsoundCategory_master:0.0\ntutorialStep:none\n")
    config=digest(server/"config/neoorigins/admin.toml")
    server_log=output/"native-server.log"
    companion=server/"mods/Unyielding-1.2.0.jar"
    assert digest(companion)==COMPANION and digest(client/"mods"/companion.name)==COMPANION
    # full_prepare exports staging paths to later Actions steps. Bind the original
    # upstream verifier to the actual installed, pinned JARs as native249/full_run does.
    assert not any(os.environ.get(k) for k in ("JAVA_TOOL_OPTIONS","UQ_PACKAGED_JAIL_SMOKE","UQ_PACKAGED_PHASE"))
    env=dict(os.environ,UQ_PACKAGED_JAIL_SMOKE="1",UQ_JAIL_JAR=str(server/"mods"/jail.name),UQ_JAIL_SHA256=JAIL,
             UQ_BUNDLE_JAR=str(companion),UQ_BUNDLE_SHA256=COMPANION)
    srv=cli=None
    with server_log.open("x") as slog,(output/"native-client.log").open("x") as clog:
        try:
            # A single Visitor cannot start the original two-player certified lifecycle.
            # The separate player-only escape test is opt-in and is not part of this run.
            srv=subprocess.Popen(["java","-Xms512m","-Xmx3G","-Dneoforge.readTimeout=180",
                "-Duq.integratedJail=true","-Duq.certifiedJail=true","-Duq.certifiedPhase=1",
                "@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt","nogui"],cwd=server,env=env,
                stdin=subprocess.PIPE,stdout=slog,stderr=subprocess.STDOUT,text=True,start_new_session=True)
            identity=report/"split-identity-jail_certified_smoke-1-normal.json"
            def native_ready():
                log=tail(server_log)
                if "Encountered an unexpected exception" in log or list((server/"crash-reports").glob("*.txt")):
                    raise RuntimeError("Original stock server failed before verified startup: "+log[-2800:])
                if not identity.is_file():
                    return False
                return "Done (" in server_log.read_text(errors="replace")
            await_signal(native_ready,[srv],900,"Native verifier did not confirm the actual production JARs")
            ids=json.loads(identity.read_text())
            assert ids["separate_jars_loaded"] and ids["non_jail_mods_loaded"] and ids["old_jail_classes_absent"]
            assert ids["world"]==world.name and ids["phase"]=="1" and ids["probe"]=="normal"
            assert ids["commit"]==os.environ["GITHUB_SHA"] and ids["jail_sha256"]==JAIL and ids["companion_sha256"]==COMPANION
            shutil.copy2(identity,output/identity.name)
            print("Stock dedicated world started. Opening the independent client.",flush=True)
            cli_env=dict(env,LIBGL_ALWAYS_SOFTWARE="true",ALSOFT_DRIVERS="null",UQ_TEST_JOIN_FROM_TITLE="1",
                         JAVA_TOOL_OPTIONS="-Duq.integratedJail=true -Duq.certifiedJail=true -Duq.certifiedPhase=1 -Duq.playerOnly=true -Duq.playerOnlyPhase=1")
            cli=subprocess.Popen(["xvfb-run","-a","-s","-screen 0 800x600x24",sys.executable,str(root/".github/client249/launch.py"),
                str(client),"--join","127.0.0.1:25565","--name","Visitor"],cwd=root,env=cli_env,stdout=clog,
                stderr=subprocess.STDOUT,start_new_session=True)
            def visitor_joined():
                if list((server/"crash-reports").glob("*.txt")):
                    raise RuntimeError("Stock server crashed before Visitor could join")
                screen=report/"client249-phase1-status.txt"
                if screen.exists() and "DisconnectedScreen" in screen.read_text():
                    raise RuntimeError("Visitor disconnected before login. See the original server and client logs.")
                return "Visitor joined the game" in tail(server_log)
            await_signal(visitor_joined,[srv,cli],1200,"Independent Visitor did not join")
            print("The independent Visitor joined the stock server.",flush=True)
            # This is an instruction to the unchanged client picker after actual login, not its success receipt.
            (report/"full249-onboarding-start.txt").write_text("Separate stock console scenario requests normal picker onboarding.")
            completed=report/"full249-onboarding-complete.txt"
            await_signal(completed.is_file,[srv,cli],150,"Native Human/Warrior picker did not complete")
            assert "neoorigins:human" in completed.read_text() and "neoorigins:class_warrior" in completed.read_text()
            print("Player completed genuine Human and Warrior onboarding.",flush=True)
            def send(value):
                srv.stdin.write(value+"\n");srv.stdin.flush()
            send("scoreboard objectives add stockproof dummy")
            checks=[]
            def checked(command):
                nonce=uuid.uuid4().hex
                send("scoreboard players set #last stockproof 0")
                send("execute store result score #last stockproof run "+command)
                send("execute if score #last stockproof matches 1.. run say STOCK-"+nonce)
                await_signal(lambda:("[Server] STOCK-"+nonce) in tail(server_log),[srv,cli],40,"Native command failed: "+command+"\n"+tail(server_log)[-1900:])
                checks.append(command)
                print("Accepted native command", command.split()[0],flush=True)
            checked("op Visitor")
            checked("execute as Visitor at @s run fill ~-9 ~19 ~-2 ~9 ~19 ~33 minecraft:stone")
            checked("execute as Visitor at @s run tp @s ~ ~20 ~ 0 0")
            checked("execute as Visitor at @s run jailbuild preview")
            checked("execute as Visitor at @s run jailbuild place")
            checked("deop Visitor")
            # The ordinary world starter can put items in a new player. Only the disposable inventory is cleared.
            send("clear Visitor")
            checked("give Visitor minecraft:amethyst_shard[minecraft:custom_name='{\"text\":\"Stock custody checkpoint\"}'] 13")
            checked("save-all flush")
            checked("jailcustody init")
            checked("jailintegrated init")
            checked("jailintegrated mode supported")
            checked("jailintegrated check Visitor")
            checked("jail Visitor 6")
            checked("jailcells")
            assert re.search(r"Cell 6\s+Occupied\s+·\s+Visitor",tail(server_log))
            checked("jailintegrated mode disabled")
            checked("save-all flush")
            send("stop")
            assert srv.wait(timeout=150)==0 and "All dimensions are saved" in tail(server_log)
        finally:
            stop_group(cli);stop_group(srv)
    assert digest(server/"config/neoorigins/admin.toml")==config
    result=verify_saved(world,runtime)
    result.update(harness_revision=os.environ["GITHUB_SHA"],jail_sha256=JAIL,
                  origin_config=config,stock_server_file_count=298,server_test_helpers=1,helper_sha256=digest(helper),
                  admission_via_console=True,clean_stop=True,commands=checks)
    (output/"occupied.json").write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps(result,indent=2),flush=True)


if __name__=="__main__":
    main()

"""Admit on the certified pair, upgrade only Storage offline, and recover an independent bag."""
import hashlib
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import tomllib
from pathlib import Path

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def tail(path):
    if not path.is_file(): return ""
    with path.open("rb") as data:
        data.seek(max(0,path.stat().st_size-65536))
        return data.read().decode(errors="replace")

def stop(proc):
    if proc and proc.poll() is None:
        os.killpg(proc.pid,signal.SIGTERM)
        try: proc.wait(timeout=25)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid,signal.SIGKILL)
            proc.wait(timeout=15)

def server_command():
    return ["java","-Xms512m","-Xmx3G","-Dneoforge.readTimeout=180",
            "@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt","nogui"]

def resume(server,res,env,phase):
    receipt=server/("independent-"+phase+".txt")
    assert not receipt.exists()
    upstream=Path("/tmp/uq-player-only-result")
    assert not upstream.exists(),"Unexpected outcome before "+phase
    output=res/(phase+"-server.log")
    with output.open("w") as log:
        proc=subprocess.Popen(server_command(),cwd=server,env=dict(env,INDEPENDENT_PHASE=phase),
                stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,start_new_session=True,text=True)
        try:
            deadline=time.monotonic()+520
            while not receipt.is_file() and time.monotonic()<deadline:
                if upstream.is_file(): raise RuntimeError(phase+": "+upstream.read_text()[:550])
                if proc.poll() is not None: raise RuntimeError(phase+": dedicated server exited early")
                time.sleep(2)
            assert receipt.is_file(),"Missing result for "+phase
            proc.stdin.write("stop\n")
            proc.stdin.flush()
            assert proc.wait(timeout=100)==0 and "All dimensions are saved" in output.read_text(errors="replace")
        finally:
            if proc.poll() is None:
                try:
                    proc.stdin.write("stop\n")
                    proc.stdin.flush()
                    proc.wait(timeout=60)
                except (OSError,subprocess.TimeoutExpired): stop(proc)
    shutil.copy2(receipt,res/receipt.name)
    print(receipt.read_text(),flush=True)
    return receipt.read_text()

def describe_fixture_files(world,res):
    # This world was generated in this runner. Preserve names and TOML field types only.
    assert world.name=="jail_certified_smoke" and not world.is_symlink()
    def shape(table,prefix=""):
        for key,value in table.items():
            path=prefix+key
            if isinstance(value,dict):
                yield from shape(value,path+".")
            else:
                yield f"{path}: {type(value).__name__}"
    folders=set()
    for path in world.rglob("*"):
        if not path.is_file(): continue
        relative=path.relative_to(world)
        if path.suffix in {".mca",".mcc"} or relative.parts[0] in {"stats","advancements","datapacks"}: continue
        folders.add(str(relative))
        if relative.parent==Path("data/openpartiesandclaims/player-configs") and path.suffix==".toml" and path.stat().st_size<=262144:
            with path.open("rb") as content:
                fields=sorted(shape(tomllib.load(content)))
            (res/("claim-schema-"+path.stem+".txt")).write_text("\n".join(fields)+"\n")
    (res/"generated-storage-filenames.txt").write_text("\n".join(sorted(folders))+"\n")
    types={}
    for base in ("curanks","cobbledollarsplayerdata"):
        for path in (world/base).glob("*.json"):
            for entry in shape(json.loads(path.read_text())):
                key=entry.split(".",1)[-1] if base=="curanks" else entry
                types.setdefault(base,set()).add(key)
    for path in (world/"cobblemonraiddens").glob("bosses.bin.*"):
        if path.stat().st_size>16<<20: continue
        decoder=json.JSONDecoder()
        text=path.read_text()
        index=0
        forms=set()
        count=0
        while index<len(text) and count<16384:
            if text[index].isspace():
                index+=1
                continue
            boss,index=decoder.raw_decode(text,index)
            forms.add(",".join(sorted(boss)))
            count+=1
        types[path.name+" root options ("+str(count)+" templates)"]=forms
    (res/"generated-metadata-shapes.json").write_text(json.dumps({k:sorted(v) for k,v in types.items()},indent=2))

def main():
    assert os.environ.get("RUNNER_ENVIRONMENT")=="github-hosted" and os.environ.get("GITHUB_ACTIONS")=="true"
    root=Path.cwd()
    temp=Path(os.environ["RUNNER_TEMP"])
    server=temp/"pack249-server"
    client=temp/"pack249-client-native"
    res=root/"independent-evidence"
    res.mkdir()
    jail=Path(os.environ["CUSTODY_JAR"]).resolve(strict=True)
    storage=Path(os.environ["COMBINED_STORAGE_JAR"]).resolve(strict=True)
    release=Path(os.environ["FROZEN_RELEASE_JAR"]).resolve(strict=True)
    final_sha=os.environ["FROZEN_RELEASE_SHA"]
    stimulus=root/"dist/packaged-smoke/fullpack-stimulus.jar"
    assert sha(jail)=="6907d0bd9c2dd4b678cb9f6a241a3a58e1a23b6df35a0b9f0afac2defd80c47f"
    assert sha(storage)==os.environ["STORAGE_SHA"]
    assert sha(release)==final_sha and final_sha!=sha(storage)
    for game in (server,client):
        assert sha(game/"mods/Unyielding-1.2.0.jar")=="ec3ae2c48399e40ffa615ddec85408bc29b2479c5a35eb7236c3e036ebdf762c"
        for src in (jail,storage,stimulus):
            dest=game/"mods"/src.name
            assert not dest.exists()
            shutil.copy2(src,dest)
            assert sha(dest)==sha(src)
    assert len(list((client/"mods").glob("*.jar")))==368
    assert not (server/"jail_certified_smoke").exists()
    accepted=root/"dist/packaged-harness/run-server/eula.txt"
    assert "eula=true" in accepted.read_text(encoding="utf-8-sig").replace(" ","")
    shutil.copy2(accepted,server/"eula.txt")
    (server/"server.properties").write_text('online-mode=false\nserver-ip=127.0.0.1\nserver-port=25565\nlevel-name=jail_certified_smoke\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\nspawn-protection=0\ndifficulty=peaceful\nspawn-monsters=false\nmax-players=2\nview-distance=2\nsimulation-distance=2\ngenerate-structures=false\nenable-rcon=false\nmax-tick-time=-1\n')
    (client/"options.txt").write_text("guiScale:2\nrenderDistance:2\nsimulationDistance:2\nmaxFps:30\nenableVsync:false\nonboardAccessibility:false\nsoundCategory_master:0.0\ntutorialStep:none\n")
    env=dict(os.environ,INDEPENDENT_PHASE="seed",UQ_PACKAGED_JAIL_SMOKE="1",UQ_TEST_JOIN_FROM_TITLE="1",UQ_JAIL_JAR=str(server/"mods"/jail.name),
             UQ_BUNDLE_JAR=str(server/"mods/Unyielding-1.2.0.jar"),UQ_JAIL_SHA256=sha(jail),
             UQ_BUNDLE_SHA256=sha(server/"mods/Unyielding-1.2.0.jar"),LIBGL_ALWAYS_SOFTWARE="true",ALSOFT_DRIVERS="null",
             JAVA_TOOL_OPTIONS="-Duq.integratedJail=true -Duq.certifiedJail=true -Duq.playerOnly=true -Duq.playerOnlyPhase=1 -Duq.certifiedPhase=1")
    srv=cli=None
    done=server/"independent-loss.txt"
    with (res/"server.log").open("w") as so,(res/"client.log").open("w") as co:
        try:
            srv=subprocess.Popen(server_command(),cwd=server,env=env,stdin=subprocess.PIPE,stdout=so,stderr=subprocess.STDOUT,start_new_session=True,text=True)
            deadline=time.monotonic()+840
            while time.monotonic()<deadline and "Done (" not in tail(res/"server.log"):
                if srv.poll() is not None: raise RuntimeError("Server exited at startup")
                time.sleep(2)
            assert "Done (" in tail(res/"server.log")
            print("Dedicated published pack started. Connecting the hosted Visitor client.",flush=True)
            cli=subprocess.Popen(["xvfb-run","-a","-s","-screen 0 800x600x24",sys.executable,str(root/".github/client249/launch.py"),str(client),"--join","127.0.0.1:25565","--name","Visitor"],cwd=root,env=env,stdout=co,stderr=subprocess.STDOUT,start_new_session=True)
            deadline=time.monotonic()+900
            last=None
            while time.monotonic()<deadline and not done.exists():
                if srv.poll() is not None or cli.poll() is not None: raise RuntimeError("Game fixture exited before verification")
                marker=Path("/tmp/uq-player-only-result")
                if marker.exists() and not done.exists(): raise RuntimeError("Upstream fixture ended first: "+marker.read_text()[:300])
                view=tail(root/"dist/packaged-smoke/client249-phase1-status.txt").strip()
                if view and last!=view:
                    print("Hosted client:",view[:230],flush=True)
                    last=view
                if "DisconnectedScreen" in view or "QuickPlayErrorScreen" in view or "Visitor lost connection:" in tail(res/"server.log"):
                    raise RuntimeError("Full-pack client disconnected before completing the checks")
                if "OutOfMemoryError" in tail(res/"client.log") or "OutOfMemoryError" in tail(res/"server.log"):
                    raise RuntimeError("Full-pack process exhausted its hosted memory allowance")
                time.sleep(2)
            assert done.is_file(),"No custody outcome received"
            srv.stdin.write("stop\n")
            srv.stdin.flush()
            assert srv.wait(timeout=100)==0 and "All dimensions are saved" in (res/"server.log").read_text(errors="replace")
            shutil.copyfile(done,res/"SEED.txt")
            print(done.read_text(),flush=True)
        finally:
            stop(cli)
            if srv is not None and srv.poll() is None:
                try:
                    srv.stdin.write("stop\n")
                    srv.stdin.flush()
                    srv.wait(timeout=60)
                except (OSError,subprocess.TimeoutExpired): stop(srv)
            for kind,game in (("client",client),("server",server)):
                log=game/"logs/latest.log"
                if log.is_file(): shutil.copy2(log,res/(kind+"-latest.log"))
    for game in (server,client):
        old=game/"mods"/storage.name
        new=game/"mods"/release.name
        assert sha(old)==sha(storage) and not new.exists()
        shutil.copy2(release,new)
        assert sha(new)==final_sha
        old.unlink()
    env["STORAGE_SHA"]=final_sha
    try:
        receipts=[resume(server,res,env,phase) for phase in ("restore","replay")]
    finally:
        describe_fixture_files(server/"jail_certified_smoke",res)
    (res/"CUSTODY.txt").write_text(
        f"Certified admission Storage SHA256 {sha(storage)}\n"
        f"Occupied-world recovery and replay Storage SHA256 {final_sha}\n"
        f"Unchanged Jail SHA256 {sha(jail)}\n"
        f"Unchanged companion SHA256 {env['UQ_BUNDLE_SHA256']}\n"
        +done.read_text()+"".join(receipts))

if __name__=="__main__":
    main()

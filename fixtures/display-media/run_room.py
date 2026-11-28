"""Three independent clients, their real game renders and their separate hosted sound outputs."""
from contextlib import ExitStack
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import wave

import numpy as np
from run import diagnostic_tone, stop

ROLES = ("RoomHost", "RoomGuest", "LateGuest")


def check_audio(proof, target, role, bounds):
    data = np.memmap(target / (role + ".pcm"), dtype="<i2", mode="r")
    start, end = bounds
    period = 12000
    # Keep the actual interior audio that passed, independently for each client.
    sampled=[]
    strongest=(0,0,0,start)
    for i in range(max(0, start - 24000), min(end + 24000, len(data)) - period, period // 2):
        segment = data[i:i + period]
        ratio, rms, peak = diagnostic_tone(segment, 48000)
        sampled.append((ratio,rms,peak))
        if ratio*rms>strongest[0]*strongest[1]: strongest=(ratio,rms,peak,i)
        if ratio > .75 and rms >= 35 and peak == 440:
            with wave.open(str(proof / (role + "-room-audio.wav")), "wb") as evidence:
                evidence.setnchannels(1)
                evidence.setsampwidth(2)
                evidence.setframerate(48000)
                evidence.writeframes(segment.tobytes())
            return f"{role} heard {peak} Hz in its own PulseAudio monitor, RMS {rms}, tone share {ratio:.3f}."
    i=max(0,min(strongest[3],len(data)-48000))
    with wave.open(str(proof/(role+"-synthetic-diagnostic.wav")),"wb") as out:
        out.setnchannels(1);out.setsampwidth(2);out.setframerate(48000);out.writeframes(data[i:i+48000].tobytes())
    raise AssertionError(f"No movie tone on {role}: samples {start}..{end}; peak (share, RMS, Hz, offset) {strongest}, max RMS {max((s[1] for s in sampled),default=0)}")


def pulse_list(kind):
    return json.loads(subprocess.check_output(["pactl","--format=json","list",kind],text=True))


def actual_routes(clients, sinks, target):
    """Associate Pulse's real Java process with its launcher's process group or isolated game directory."""
    result={}
    for stream in pulse_list("sink-inputs"):
        props=stream.get("properties",{})
        raw_pid=props.get("application.process.id","")
        if not str(raw_pid).isdigit():continue
        pid=int(raw_pid)
        try:
            game=Path(f"/proc/{pid}/cwd").resolve(strict=True)
            group=os.getpgid(pid)
            executable=Path(f"/proc/{pid}/exe").resolve(strict=True).name
        except (FileNotFoundError,ProcessLookupError,PermissionError):
            continue
        if executable!="java":continue
        matches=[role for role,process in clients.items() if game==target/role or group==process.pid]
        if not matches:continue
        assert len(matches)==1, matches
        role=matches[0]
        assert role not in result, f"Ambiguous simultaneous OpenAL outputs for {role}"
        result[role]=(pid,int(stream["index"]),int(stream["sink"]))
        if int(stream["sink"])!=sinks[role]:
            subprocess.run(["pactl","move-sink-input",str(stream["index"]),str(sinks[role])],check=True)
            # Verify the resulting route on a fresh PulseAudio snapshot.
            return {}
        if stream.get("mute",False):
            subprocess.run(["pactl","set-sink-input-mute",str(stream["index"]),"0"],check=True)
            return {}
    return result


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        raise SystemExit("This multiplayer check needs an isolated GitHub runner.")
    root = Path(__file__).resolve().parents[2]
    target = root / "build/display-room"
    proof = target / "proof"
    proof.mkdir(parents=True)
    jar = Path(sys.argv[1]).resolve(strict=True)
    assert jar.parent == root / "upload" and jar.name in (
        "anya-storage-plus-0.3.0-beta.7-roommedia.1.jar", "anya-storage-plus-0.3.0-beta.8.jar", "anya-storage-plus-0.3.0-beta.9-media.jar")
    frozen = sha256(jar.read_bytes()).hexdigest()
    video = Path(__file__).parent / "inputs/room-color-tone.mp4"
    assert sha256(video.read_bytes()).hexdigest() == "0d3325307dddf50ef755e368fa6f3181aacb5ed9dc6d8e8770c3ada1a978cc76"
    tasks = ["prepareDisplay" + side for side in ("Server", *ROLES)]
    subprocess.run(["gradle", "--no-daemon", "--console=plain", "--max-workers=2",
                    "-I", "fixtures/display-media/fixture.gradle", "-ProomMedia=true",
                    "-PmediaPlayable=" + str(jar), *tasks], cwd=root, check=True)
    server = target / "Server"
    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=25574\nonline-mode=false\n"
        "spawn-protection=0\nlevel-name=shared-media\nlevel-type=minecraft:flat\ngenerate-structures=false\n"
        "difficulty=peaceful\nview-distance=3\nsimulation-distance=3\nspawn-monsters=false\nmax-players=3\n")
    assert subprocess.run(["pulseaudio","--start","--exit-idle-time=-1"],check=True).returncode==0
    env = dict(os.environ, LIBGL_ALWAYS_SOFTWARE="true", ALSOFT_DRIVERS="pulse", ALSOFT_LOGLEVEL="2")
    starts, ends, solo_spans, recorders, pids, metrics = {}, {}, {}, {}, set(), []
    with ExitStack() as ctx:
        def launch(args, folder, name, process_env, output=None):
            log = output if output is not None else ctx.enter_context((target/(name + ".log")).open("xb"))
            proc = subprocess.Popen(args,cwd=folder,env=process_env,stdin=subprocess.PIPE,
                                    stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            ctx.callback(stop, proc)
            return proc
        srv = launch(["bash",(server/"launch.txt").read_text().strip()],server,"server",env)
        deadline = time.monotonic() + 210
        while "Done (" not in (target / "server.log").read_text(errors="replace"):
            if srv.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError("Disposable room server startup failed")
            time.sleep(.5)
        clients = {}
        sinks = {}
        for role in ROLES:
            name = "anya_" + role.lower()
            subprocess.run(["pactl","load-module","module-null-sink","sink_name="+name,
                            "sink_properties=device.description="+name,"rate=48000","channels=2"],check=True)
            subprocess.run(["pactl","set-sink-volume",name,"100%"],check=True)
            subprocess.run(["pactl","set-sink-mute",name,"0"],check=True)
            subprocess.run(["pactl","set-source-volume",name+".monitor","100%"],check=True)
            subprocess.run(["pactl","set-source-mute",name+".monitor","0"],check=True)
            sinks[role]=int(next(s for s in pulse_list("sinks") if s["name"]==name)["index"])
            rc = ctx.enter_context((target/(role + ".pcm")).open("xb", buffering=0))
            recorders[role]=launch(["parec","--raw","--device="+name+".monitor","--format=s16le","--rate=48000",
                                  "--channels=1","--latency-msec=50"],target,role + "-capture",env,rc)
            folder = target / role
            (folder/"options.txt").write_text("onboardAccessibility:false\ntutorialStep:none\nguiScale:2\n"
                "renderDistance:3\nsimulationDistance:3\nenableVsync:false\nmaxFps:30\nfov:0.0\n"
                "soundCategory_master:1.0\nsoundCategory_block:1.0\nsoundCategory_music:0.0\n"
                "soundCategory_ambient:0.0\nsoundCategory_player:0.0\nsoundDevice:"+name+"\n")
            cli = launch(["xvfb-run","-a","-s","-screen 0 1024x768x24","bash",(folder/"launch.txt").read_text().strip()],
                         folder,role,dict(env,PULSE_SINK=name,ALSOFT_LOGFILE=str(target/(role+"-openal.log"))))
            clients[role]=cli
            pids.add(cli.pid)
        assert len(pids)==3
        deadline = time.monotonic() + 600
        routed=False
        next_route=0.
        while not (proof / "PASS").exists():
            if (proof/"FAILED").exists(): raise AssertionError((proof/"FAILED").read_text())
            if any(p.poll() is not None for p in (srv,*clients.values(),*recorders.values())) or time.monotonic()>deadline:
                raise RuntimeError("Incomplete three-player check: "+str(sorted(p.name for p in proof.iterdir())))
            if time.monotonic()>=next_route:
                routes=actual_routes(clients,sinks,target)
                next_route=time.monotonic()+(2 if routed else .5)
                if len(routes)==3 and not routed:
                    assert len({v[0] for v in routes.values()})==3 and len({v[2] for v in routes.values()})==3
                    (proof/"verified-mixer-routes").write_text(json.dumps(routes,indent=2))
                    print("Real client JVM PID, Pulse input, dedicated sink: "+str(routes),flush=True)
                    routed=True
                elif routed:
                    assert len(routes)==3,"A verified Java audio output changed or disconnected"
            for role in ROLES:
                count = (target / (role + ".pcm")).stat().st_size // 2
                if role not in starts and (proof / ("late-inside" if role=="LateGuest" else "screen")).exists():
                    starts[role]=count
                if role not in ends and (proof / ("movie-"+role)).exists():
                    ends[role]=count
                for marker in ("ended-RoomGuest","host-continued"):
                    key=(role,marker)
                    if key not in solo_spans and (proof/marker).exists():
                        solo_spans[key]=count
            time.sleep(.1)
        srv.stdin.write(b"stop\n");srv.stdin.flush()
        assert srv.wait(timeout=100)==0 and "All dimensions are saved" in (target/"server.log").read_text(errors="replace")
        failures=[]
        for role in ROLES:
            recorders[role].send_signal(signal.SIGINT);recorders[role].wait(timeout=10)
            try: metrics.append(check_audio(proof,target,role,(starts[role],ends[role])))
            except AssertionError as err: failures.append(str(err))
        (proof/"mixer-results.txt").write_text("\n".join(metrics+failures)+"\n")
        if failures: raise AssertionError("\n".join(failures))
    clocks = []
    for role in ROLES:
        receipt=(proof/("movie-"+role)).read_text()
        clock,sample=map(int,re.search(r"clock=(\d+) sample=(\d+)",receipt).groups())
        clocks.append((clock,sample))
        metrics.append(f"{role}: {receipt}")
        for color in ("red","green"):
            assert (proof/(role+"-"+color+".png")).stat().st_size>1000
    assert len(set(c[0] for c in clocks)) == 1 and clocks[2][1] > max(c[1] for c in clocks[:2])
    assert (proof/"excluded-LateGuest").read_text()=="Begin=0 Parts=0 Clock=0 End=0 streaming=false"
    assert abs(float((proof/"volume-RoomHost").read_text())-.35)<.05
    assert abs(float((proof/"volume-RoomGuest").read_text())-.70)<.05
    for role,expected in (("RoomHost",.35),("RoomGuest",.70)):
        personal=json.loads((target/role/"config/anya-storage-plus-client.json").read_text())["screenVolume"]
        assert abs(personal-expected)<.05
        metrics.append(f"{role} persisted its own volume at {personal:.2f}.")
        raw=np.memmap(target/(role+".pcm"),dtype="<i2",mode="r")
        # Leave a half-second for the host sound server to drain buffered samples.
        begin=solo_spans[(role,"ended-RoomGuest")]+24000
        finish=solo_spans[(role,"host-continued")]
        samples=[diagnostic_tone(raw[i:i+12000],48000) for i in range(begin,finish-11999,12000)]
        assert len(samples)>=2, (role,begin,finish)
        has_tone=[share>.75 and rms>=35 and peak==440 for share,rms,peak in samples]
        assert (any(has_tone) if role=="RoomHost" else not any(has_tone)),(role,samples)
        metrics.append(f"After the guest stopped locally, {role} had the 440 Hz movie tone in {sum(has_tone)}/{len(samples)} interior windows.")
    assert sha256(jar.read_bytes()).hexdigest()==frozen
    (proof/"CHECKS.txt").write_text((proof/"PASS").read_text()+"\n"+"\n".join(metrics)+"\nPlayable SHA-256 "+frozen+"\n")
    print((proof/"CHECKS.txt").read_text(),flush=True)


if __name__=="__main__":
    main()

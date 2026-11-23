"""Verify the frozen playable with one real client, a loopback server and a synthetic audio sink."""
from hashlib import sha256
import os
from pathlib import Path
import signal
import struct
import subprocess
import sys
import time
import wave
import zipfile

import numpy as np
from PIL import Image


def stop(process):
    if process is None or process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=25)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=10)


def diagnostic_tone(data, rate):
    window = rate // 4
    observations = []
    for at in range(0, len(data)-window+1, window//2):
        samples = np.array(data[at:at+window], dtype=np.float64)
        samples -= samples.mean()
        spectrum = np.abs(np.fft.rfft(samples * np.hanning(window)))**2
        signal = spectrum[109:112].sum() / max(1., spectrum[4:].sum())
        observations.append((round(float(signal),4), round(float(np.sqrt(np.mean(samples**2))),1),
                             int(spectrum.argmax())*4))
    return max(observations, default=(0,0,0))


def check_decoder(jar, source, target):
    helper=target/"decoder.jar"
    with zipfile.ZipFile(jar) as frozen:
        helper.write_bytes(frozen.read("META-INF/anya-media/screen-codec.jar"))
    with source.open("rb") as sample:
        binary=subprocess.run(["java","-Xmx160m","-Djava.awt.headless=true","-jar",str(helper)],
                              stdin=sample,stdout=subprocess.PIPE,timeout=90,check=True).stdout
    magic,w,h=struct.unpack_from(">iii",binary)
    assert magic==0x41535031 and 0<w<=640 and 0<h<=640
    i=12
    while i<len(binary):
        kind=binary[i];i+=1
        if kind==0: break
        if kind==1:
            i+=4+4*w*h
        else:
            assert kind==2
            rate,n=struct.unpack_from(">ii",binary,i);i+=8
            pcm=np.frombuffer(binary,dtype="<i2",offset=i,count=n//2)
            score,rms,hz=diagnostic_tone(pcm,rate)
            print(f"Bundled AAC decoder: {rate} Hz PCM, peak {hz} Hz, spectral share {score}, RMS {rms}",flush=True)
            return score>.75 and rms>=50
    return False


def capture_result(proof, raw, spans):
    records = []
    data = np.fromfile(raw, dtype="<i2")
    areas = {}
    for device in ("tv", "wall"):
        a, b = [spans[device + "-movie-" + marker] // 2 for marker in ("start", "end")]
        window = 12000
        best = None
        for at in range(max(0, a-window), min(b+window, len(data))-window, window//2):
            section = data[at:at+window].astype(np.float64)
            section -= section.mean()
            rms = np.sqrt(np.mean(section**2))
            spectrum = np.abs(np.fft.rfft(section * np.hanning(window)))**2
            band = spectrum[109:112].sum()  # 4 Hz/bin: target 440 Hz
            ratio = band / max(1, spectrum[4:].sum())
            if rms >= 50 and ratio >= .75 and (best is None or rms > best[0]):
                best = (rms, at, ratio, spectrum.argmax()*4)
        if best is None:
            print(f"{device} mixer samples {a}..{b}, available {len(data)}, "
                  f"window (share, RMS, peak Hz) {diagnostic_tone(data[a:b],48000)}, "
                  f"entire recording {diagnostic_tone(data,48000)}",flush=True)
            with wave.open(str(proof/(device+"-synthetic-diagnostic.wav")),"wb") as wav:
                wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(48000)
                wav.writeframes(data[max(0,a-48000):min(b+48000,a+12*48000)].tobytes())
            raise RuntimeError(f"No sustained 440 Hz output during {device} movie playback")
        rms, at, ratio, freq = best
        with wave.open(str(proof / (device+"-synthetic-tone.wav")), "wb") as wav:
            wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(48000)
            wav.writeframes(data[at:at+window].tobytes())
        records.append(f"{device}: OpenAL through PulseAudio monitor, {freq} Hz, {ratio:.3f} target spectral share, RMS {rms:.1f} PCM units.")
        photo = np.asarray(Image.open(proof / (device+"-movie-red.png")).convert("RGB"), dtype=np.int32)
        r, g, blue = photo[:,:,0], photo[:,:,1], photo[:,:,2]
        height = r.shape[0]
        region = (r>115)&(r>2*g)&(r>2*blue)
        region[:height//4] = False
        region[3*height//4:] = False
        areas[device] = int(region.sum())
        records.append(f"{device}: {areas[device]} predominantly red movie pixels within the center half of the actual game capture.")
        for suffix in ("camera-before","movie-red","movie-green","gif-blue","gif-yellow","camera-after"):
            assert (proof/(device+"-"+suffix+".png")).is_file()
    if areas["wall"] < areas["tv"] * 2 or areas["tv"] < 150:
        raise RuntimeError("The expanded wall did not show a larger real movie surface than the tabletop TV")
    records.append("A synthetic direct Discord attachment passed URI validation with its query preserved. A signed Discord attachment was not fetched.")
    records.append("The two audio clips contain only the generated test tone recorded from the hosted client mixer.")
    (proof/"CHECKS.txt").write_text((proof/"PASS").read_text() + "\n".join(records)+"\n")


def main():
    if os.environ.get("GITHUB_ACTIONS")!="true" or os.environ.get("RUNNER_ENVIRONMENT")!="github-hosted":
        raise SystemExit("This display fixture requires a standard hosted runner")
    root = Path(__file__).resolve().parents[2]
    target = root/"build/display-media"
    server, client, proof = [target/k for k in ("Server","Client","proof")]
    proof.mkdir(parents=True)
    jar = Path(sys.argv[1]).resolve(strict=True)
    if not jar.is_file() or jar.name!="anya-storage-plus-0.3.0-beta.7-screenmedia.1.jar":
        raise SystemExit("Expected the exact cumulative frozen candidate")
    candidate_hash = sha256(jar.read_bytes()).hexdigest()
    inputs = Path(__file__).resolve().parent/"inputs"
    for key, expected in {"cards.gif":"4963cf5d4649cf45ad53c9f12675536b3ea8d0371b61277fa1a63088cecf9693",
                          "color-tone.mp4":"2d416562be98876ef4ed51b139a75d377f100b886d6f4a5597c14c6268a6dc6f"}.items():
        assert sha256((inputs/key).read_bytes()).hexdigest()==expected
    assert check_decoder(jar,inputs/"color-tone.mp4",target), "The packaged AAC decoder did not produce the generated 440 Hz tone"
    subprocess.run(["gradle","--no-daemon","--console=plain","--max-workers=2","-I","fixtures/display-media/fixture.gradle",
                    "-PmediaPlayable="+str(jar),"prepareDisplayServer","prepareDisplayClient"],cwd=root,check=True)
    (server/"eula.txt").write_text("eula=true\n")
    (server/"server.properties").write_text("server-ip=127.0.0.1\nserver-port=25574\nonline-mode=false\nspawn-protection=0\n"
            "level-name=media-proof\nlevel-type=minecraft:flat\ngenerate-structures=false\ndifficulty=peaceful\n"
            "view-distance=3\nsimulation-distance=3\nspawn-monsters=false\n")
    (client/"options.txt").write_text("onboardAccessibility:false\ntutorialStep:none\nrenderDistance:3\nsimulationDistance:3\n"
            "guiScale:2\nfov:0.0\nmaxFps:40\nenableVsync:false\nsoundCategory_master:1.0\nsoundCategory_block:1.0\n"
            "soundCategory_music:0.0\nsoundCategory_record:0.0\nsoundCategory_ambient:0.0\nsoundCategory_player:0.0\n")
    subprocess.run(["pulseaudio","--start","--exit-idle-time=-1"],check=True)
    subprocess.run(["pactl","load-module","module-null-sink","sink_name=anya_ci",
                    "sink_properties=device.description=AnyaHostedMixer","rate=48000","channels=2"],check=True)
    subprocess.run(["pactl","set-default-sink","anya_ci"],check=True)
    subprocess.run(["pactl","set-sink-volume","anya_ci","100%"],check=True)
    subprocess.run(["pactl","set-sink-mute","anya_ci","0"],check=True)
    subprocess.run(["pactl","set-source-volume","anya_ci.monitor","100%"],check=True)
    subprocess.run(["pactl","set-source-mute","anya_ci.monitor","0"],check=True)
    env = dict(os.environ, ANYA_MEDIA_INPUTS=str(inputs), LIBGL_ALWAYS_SOFTWARE="true",
               ALSOFT_DRIVERS="pulse", PULSE_SINK="anya_ci",ALSOFT_LOGLEVEL="3",
               ALSOFT_LOGFILE=str(target/"openal.log"))
    raw = target/"synthetic-mixer.s16le"
    processes = []
    log_files = []
    def start(command, label, cwd):
        log = (target/(label+".log")).open("x");log_files.append(log)
        process = subprocess.Popen(command,cwd=cwd,env=env,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,
                                   text=True,start_new_session=True)
        processes.append(process);return process
    try:
        with raw.open("xb",buffering=0) as recording:
            mixer = subprocess.Popen(["parec","--raw","--device=anya_ci.monitor","--format=s16le","--rate=48000",
                                      "--channels=1","--latency-msec=25"],stdin=subprocess.DEVNULL,stdout=recording,
                                     cwd=target,env=env,start_new_session=True)
            processes.append(mixer)
        srv = start(["bash",(server/"launch.txt").read_text().strip()], "server",server)
        for _ in range(200):
            if srv.poll() is not None: raise RuntimeError("Server failed to start")
            if "Done (" in (target/"server.log").read_text(errors="replace"): break
            time.sleep(1)
        else: raise TimeoutError("Server startup")
        cli = start(["xvfb-run","-a","-s","-screen 0 1024x768x24","bash",(client/"launch.txt").read_text().strip()],
                    "client",client)
        spans = {}
        deadline = time.monotonic()+580
        while not (proof/"PASS").exists():
            if (proof/"FAILED").exists():raise RuntimeError((proof/"FAILED").read_text())
            if any(p.poll() is not None for p in (srv,cli,mixer)) or time.monotonic()>deadline:
                raise RuntimeError("Incomplete hosted display proof. Receipts: "+str(sorted(f.name for f in proof.iterdir())))
            for device in ("tv","wall"):
                for marker in ("start","end"):
                    key=device+"-movie-"+marker
                    if key not in spans and (proof/key).is_file():
                        spans[key] = raw.stat().st_size
                        print(key,spans[key],"mixer bytes; PulseAudio inputs:",
                              subprocess.check_output(["pactl","list","short","sink-inputs"],text=True).strip(),flush=True)
            time.sleep(.1)
        srv.stdin.write("stop\n");srv.stdin.flush()
        if srv.wait(timeout=100)!=0 or "All dimensions are saved" not in (target/"server.log").read_text(errors="replace"):
            raise RuntimeError("Dedicated server did not save and stop")
        mixer.send_signal(signal.SIGINT);mixer.wait(timeout=10)
        capture_result(proof,raw,spans)
        assert candidate_hash==sha256(jar.read_bytes()).hexdigest()
        print((proof/"CHECKS.txt").read_text())
    finally:
        for p in reversed(processes):stop(p)
        for log in log_files:log.close()


if __name__=="__main__":
    main()

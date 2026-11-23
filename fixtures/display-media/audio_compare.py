"""Compare public decoder binaries on the exact, committed synthetic AAC track. No game required."""
import hashlib, io, os, pathlib, struct, subprocess, tempfile, urllib.request
import numpy as np
import imageio_ffmpeg

root=pathlib.Path.cwd()
movie=root/"fixtures/display-media/inputs/color-tone.mp4"
assert hashlib.sha256(movie.read_bytes()).hexdigest()=="2d416562be98876ef4ed51b139a75d377f100b886d6f4a5597c14c6268a6dc6f"
ff=imageio_ffmpeg.get_ffmpeg_exe()
def audio(mode):
    return subprocess.check_output([ff,"-v","error","-i",str(movie),"-vn"]+mode+["pipe:1"])
ref=audio(["-ac","1","-ar","44100","-f","s16le"])
adts=audio(["-c:a","copy","-f","adts"])
inputs=[
("jcodec","org/jcodec/jcodec/0.2.5/jcodec-0.2.5.jar","890329dad124e8b739c1d6602a59a53c8a474daddff265c2561e21c498496c81"),
("jaad087","de/sfuhrm/jaad/0.8.7/jaad-0.8.7.jar","f6a179169c390e939d737435268406ff6d681af6c6e50a219ce473318174037f"),
("jaad094","com/tianscar/javasound/jaad/0.9.4/jaad-0.9.4.jar","22f9fba52e328bdda2bb01935c5c32fa0625d31fd557b909b5d138b188c8afca")]

def metrics(pcm,label):
    r=np.frombuffer(ref,dtype="<i2").astype(float)
    for endian in ["<i2",">i2"]:
        a=np.frombuffer(pcm,dtype=endian).astype(float)
        w=11025
        shares=[]
        for i in range(w,len(a)-w,w//2):
            f=np.abs(np.fft.rfft(a[i:i+w]*np.hanning(w)))**2
            shares.append(f[109:112].sum()/max(1,f[4:].sum()))
        # Include both encoder priming and positive/negative correlation. The tone alone is phase-periodic.
        start=20000; segment=r[start:start+22050]
        corr=np.correlate(a[start-2048:start+len(segment)+2048],segment,mode="valid")
        lag=int(np.argmax(corr)-2048); aligned=a[start+lag:start+lag+len(segment)]
        cc=float(np.dot(aligned,segment)/(np.linalg.norm(aligned)*np.linalg.norm(segment)))
        peaks=np.abs(np.fft.rfft(a[w:w+w]*np.hanning(w)))
        print(f"{label} {endian}: median {np.median(shares):.5f}, best {max(shares):.5f}, ffmpeg correlation {cc:.5f}, periodic lag {lag} samples, RMS {np.sqrt(np.mean(a*a)):.1f}, peaks Hz {4*np.argsort(peaks)[-5:][::-1]}, length {len(a)}, reference {len(r)}",flush=True)

with tempfile.TemporaryDirectory() as directory:
    tmp=pathlib.Path(directory)
    src=root/"fixtures/display-media/SourceAac.java"
    subprocess.run(["javac","-d",str(tmp),str(src)],check=True)
    metrics(ref,"FFmpeg reference")
    for name,path,expected in inputs:
        jar=tmp/(name+".jar")
        jar.write_bytes(urllib.request.urlopen("https://repo.maven.apache.org/maven2/"+path,timeout=30).read())
        assert hashlib.sha256(jar.read_bytes()).hexdigest()==expected
        out=subprocess.run(["java","-Xmx160m","-cp",str(tmp)+os.pathsep+str(jar),"SourceAac",name],input=adts,stdout=subprocess.PIPE)
        if out.returncode==0: metrics(out.stdout,name+" raw AAC")
        else: print(name,"decode error",out.returncode)
        if name=="jcodec":
            worker=root/"src/screenCodec/java/dev/anya/codec/ScreenCodec.java"
            subprocess.run(["javac","-cp",str(jar),"-d",str(tmp),str(worker)],check=True)
            payload=subprocess.check_output(["java","-Xmx160m","-cp",str(tmp)+os.pathsep+str(jar),"dev.anya.codec.ScreenCodec"],input=movie.read_bytes())
            stream=io.BytesIO(payload)
            magic,w,h=struct.unpack(">iii",stream.read(12)); assert magic==0x41535031
            while (tag:=stream.read(1))==b"\1":stream.seek(4+w*h*4,1)
            assert tag==b"\2"
            rate,n=struct.unpack(">ii",stream.read(8)); assert rate==44100
            metrics(stream.read(n),"exact production")

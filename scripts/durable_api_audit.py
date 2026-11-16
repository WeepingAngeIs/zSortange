"""Inspect exact resolved NeoForge/Minecraft binaries before designing completion handling."""
from pathlib import Path
import hashlib,json,os,subprocess,urllib.request,zipfile
assert os.environ.get('GITHUB_ACTIONS')=='true'
out=Path('build/durable-audit');cp=(out/'classpath.txt').read_text();paths=[Path(x) for x in cp.split(os.pathsep)]
classes=['net.minecraft.server.level.ChunkMap','net.minecraft.server.level.ChunkHolder','net.minecraft.server.level.GenerationChunkHolder','net.minecraft.server.level.ServerChunkCache','net.minecraft.world.level.chunk.storage.ChunkStorage','net.minecraft.world.level.chunk.storage.IOWorker','net.minecraft.world.level.chunk.storage.RegionFileStorage','net.minecraft.world.level.chunk.storage.RegionFile','net.minecraft.world.level.chunk.storage.ChunkSerializer','net.neoforged.neoforge.event.level.ChunkDataEvent']
selected={}
for p in paths:
    if p.is_file() and p.suffix=='.jar':
        with zipfile.ZipFile(p) as z:
            names=set(z.namelist())
            for cl in classes:
                if cl.replace('.','/')+'.class' in names:selected[cl]=str(p)
assert len(selected)==len(classes),(set(classes)-set(selected))
(out/'binary-provenance.json').write_text(json.dumps({cl:{'jar':p,'sha256':hashlib.sha256(Path(p).read_bytes()).hexdigest()} for cl,p in selected.items()},indent=2))
# javap preserves the actual signatures and exception tables for the pinned engine.
for cl in classes:
    r=subprocess.run(['javap','-p','-c','-s','-classpath',cp,cl],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True,timeout=40)
    (out/(cl.rsplit('.',1)[1]+'.javap.txt')).write_text(r.stdout)
# Decompiler is an audit aid only; artifacts contain no executable engine classes.
cfr=out/'cfr.jar';urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar',cfr)
(out/'cfr.sha256').write_text(hashlib.sha256(cfr.read_bytes()).hexdigest())
for cl in classes:
    with zipfile.ZipFile(selected[cl]) as z:raw=z.read(cl.replace('.','/')+'.class')
    f=out/'scratch.class';f.write_bytes(raw)
    r=subprocess.run(['java','-Xmx512m','-jar',str(cfr),str(f),'--extraclasspath',cp,'--comments','false'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=45,check=True)
    (out/(cl.rsplit('.',1)[1]+'.java.txt')).write_text(r.stdout)
(out/'scratch.class').unlink();cfr.unlink()
print('ANYA_DURABLE_API_AUDIT_OK: exact pinned binary signatures and completion/error paths exported')

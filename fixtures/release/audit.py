"""Read-only content audit of the distribution and its JVM helper, with Maven origin checks."""
import collections
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import struct
import sys
import tomllib
import urllib.request
import zipfile

DECODER = "META-INF/anya-media/screen-codec.jar"
META = "META-INF/neoforge.mods.toml"
VENDORS = [
    ("org/jcodec/jcodec/0.2.5/jcodec-0.2.5","890329dad124e8b739c1d6602a59a53c8a474daddff265c2561e21c498496c81"),
    ("com/tianscar/javasound/jaad/0.9.4/jaad-0.9.4","22f9fba52e328bdda2bb01935c5c32fa0625d31fd557b909b5d138b188c8afca"),
]
def sha(b):
    return hashlib.sha256(b).hexdigest()

def jar(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        files={}
        for info in z.infolist():
            n=info.filename
            assert not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts and '\\' not in n
            if info.is_dir(): continue
            assert n not in files and n.isascii() and info.file_size <= 16<<20, n
            files[n]=z.read(info)
        return files

def class_file(data):
    assert data[:4]==bytes.fromhex('cafebabe')
    assert 45<=int.from_bytes(data[6:8],'big')<=65

def png_metadata(b):
    assert b.startswith(b'\x89PNG\r\n\x1a\n')
    offset=8
    while offset<len(b):
        length=int.from_bytes(b[offset:offset+4],"big")
        name=b[offset+4:offset+8]
        assert name in (b'IHDR',b'IDAT',b'PLTE',b'tRNS',b'IEND'),name
        offset+=12+length
    assert offset==len(b) and name==b'IEND'

def active_jaad(n):
    return (n=="net/sourceforge/jaad/SampleBuffer.class"
        or n.startswith(("net/sourceforge/jaad/aac/","net/sourceforge/jaad/util/")))

def audit(before, target):
    old=jar(before); top=jar(target)
    assert sha(before)=="afd62612c2f09c6bf156831b0e798da58ef0fd1632297dc936ea69e11b66e91e"
    changes=sorted(k for k in set(old)|set(top) if old.get(k)!=top.get(k))
    assert changes==sorted([META,DECODER]),("unreviewed outer changes",changes[:35])
    outer_types=collections.Counter()
    for name,data in top.items():
        suffix=PurePosixPath(name).suffix
        outer_types[suffix]+=1
        assert suffix in {'.class','.json','.png','.mcmeta','.mtl','.obj','.ogg','.jar','.nbt','.toml','.cfg','.MF'},name
        assert not re.search(r'(^|/)(saves?|logs?|crash-reports|screenshots|avatars|playerdata|\.git)(/|$)',name,re.I),name
        assert not data.startswith((b'MZ',b'\x7fELF',b'#!',bytes.fromhex('cffaedfe'),bytes.fromhex('feedfacf'))),name
        if data.startswith(b'PK\x03\x04') or suffix=='.jar': assert name==DECODER
        if suffix=='.class': class_file(data)
        if suffix=='.png': png_metadata(data)
        if suffix=='.ogg': assert data.startswith(b'OggS')
        if suffix in ('.json','.mcmeta'): json.loads(data)
        if suffix=='.nbt':
            raw=gzip.decompress(data) if data[:2]==b'\x1f\x8b' else data
            assert name=='data/anyastorage/structure/empty.nbt' and len(raw)<160 and b'minecraft:air' in raw
        assert not re.search(rb'(?i)(?:[A-Z]:[\\/]Users[\\/]|/home/runner/work/|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|github_pat_[a-zA-Z0-9]{20,}|https://cdn\.discordapp\.com/attachments/[0-9]{8,})',data),name
    meta=tomllib.loads(top[META].decode())
    assert meta['mods'][0]['version']=='0.3.0-beta.8' and meta['mods'][0]['modId']=='anyastorage'
    assert meta['license']=='All Rights Reserved'
    dependencies=meta['dependencies']['anyastorage']
    assert {(x['modId'],x['versionRange'],x['type'],x['side']) for x in dependencies}=={
        ('minecraft','[1.21.1,1.21.2)','required','BOTH'),('neoforge','[21.1.249,21.2)','required','BOTH')}
    nested=jar(top[DECODER])
    previous=jar(old[DECODER])
    changed=[n for n in previous.keys() & nested.keys() if previous[n]!=nested[n]]
    assert changed==['META-INF/ScreenDecoder-NOTICES.txt'] and not (nested.keys()-previous.keys()),changed
    removed=previous.keys()-nested.keys()
    assert removed and all(n.startswith('org/jcodec/testing/') or
        (n.startswith('net/sourceforge/jaad/') and not active_jaad(n)) for n in removed)
    assert not any(n.startswith('net/sourceforge/jaad/mp4/') for n in nested)
    allowed_resources={'META-INF/MANIFEST.MF','META-INF/JAAD-APACHE-2.0.txt','META-INF/JCodec-LICENSE.txt',
        'META-INF/ScreenDecoder-NOTICES.txt','META-INF/maven/org.jcodec/jcodec/pom.properties',
        'META-INF/maven/org.jcodec/jcodec/pom.xml','git.properties'}
    assets=[n for n in nested if not n.endswith('.class')]
    for n, b in nested.items():
        if n.endswith('.class'):
            class_file(b); assert n.startswith(('org/jcodec/','net/sourceforge/jaad/')) or n=='dev/anya/codec/ScreenCodec.class'
        else:
            assert not b.startswith((b'PK\x03\x04',b'MZ',b'\x7fELF',b'#!')), n
            # Fixed harmless resources already present in the comparison JAR.
            assert n in allowed_resources, n
    origins=[]
    for index,(location,digest) in enumerate(VENDORS):
        url='https://repo.maven.apache.org/maven2/'+location+'.jar'
        with urllib.request.urlopen(url,timeout=25) as response: binary=response.read(10<<20)
        assert sha(binary)==digest
        vendor=jar(binary)
        prefix='org/jcodec/' if index==0 else 'net/sourceforge/jaad/'
        classes=[n for n in nested if n.startswith(prefix) and n.endswith('.class')]
        assert classes and all(nested[n]==vendor[n] for n in classes)
        origins.append(dict(source=url,sha256=digest,identical_classes=len(classes)))
    assert b'Main-Class: dev.anya.codec.ScreenCodec' in nested['META-INF/MANIFEST.MF']
    assert b'The Guava Authors' in nested['META-INF/ScreenDecoder-NOTICES.txt']
    assert b'Apache License' in nested['META-INF/JAAD-APACHE-2.0.txt'] and b'JCodecProject' in nested['META-INF/JCodec-LICENSE.txt']
    return dict(raw_sha256=sha(target), bytes=len(target),outer_entries=len(top),
        outer_types=dict(sorted(outer_types.items())),changed_outer_members=changes,
        decoder_path=DECODER,decoder_bytes=len(top[DECODER]),decoder_sha256=sha(top[DECODER]),
        decoder_entries=len(nested),decoder_resources=sorted(assets),removed_decoder_entries=len(removed),
        dependency_classes=origins,declared_dependencies=dependencies)

if __name__=='__main__':
    report=audit(Path(sys.argv[1]).read_bytes(),Path(sys.argv[2]).read_bytes())
    Path(sys.argv[3]).write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

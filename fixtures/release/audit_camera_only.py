"""Inspect the actual distributable without executing it."""
import hashlib
import io
import json
from pathlib import Path
import sys
import zipfile

candidate, previous = map(Path, sys.argv[1:3])
expected = "11e9ecd10fb4efcf1ba31d35942ffde05c09103ae7ddf110dc7894dee74fcfe9"
assert hashlib.sha256(previous.read_bytes()).hexdigest() == expected
allowed = {".class", ".json", ".mcmeta", ".cfg", ".mf", ".toml", ".png", ".ogg", ".obj", ".mtl", ".nbt"}
changed_resources = {"META-INF/neoforge.mods.toml", "assets/anyastorage/sounds.json", "assets/anyastorage/lang/en_us.json"}
with zipfile.ZipFile(candidate) as jar, zipfile.ZipFile(previous) as old:
    assert jar.testzip() is None
    seen = set()
    for info in jar.infolist():
        if info.is_dir():
            continue
        name = info.filename
        assert name not in seen and not name.startswith("/") and ".." not in Path(name).parts, name
        seen.add(name)
        data = jar.read(info)
        ext = Path(name).suffix.lower()
        assert ext in allowed and not zipfile.is_zipfile(io.BytesIO(data)), name
        assert not data.startswith((b"MZ", b"\x7fELF", b"#!", b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf")), name
        if ext == ".class":
            assert name.startswith("dev/anya/storage/") and data.startswith(bytes.fromhex("cafebabe")), name
            assert not any(word in data for word in (b"java/lang/ProcessBuilder",b"MediaDownload",b"RoomMediaNet",b"ScreenMedia",b"ScreenClip",b"org/jcodec")), name
        elif name not in changed_resources:
            assert old.read(name) == data, name
    assert not any(n.startswith("META-INF/anya-media/") for n in seen)
    lang=json.loads(jar.read("assets/anyastorage/lang/en_us.json"))
    assert not any(k.startswith("screen.anyastorage.media.") for k in lang)
    assert "Enter" in lang["tooltip.anyastorage.cinema_wall_monitor.3"]
print(f"Inspected {len(seen)} files. No nested archives, EXEs, native libraries, scripts or video decoder. Other packaged assets match audited beta.8.")

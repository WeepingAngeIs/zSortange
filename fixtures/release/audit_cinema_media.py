"""Compare the entire package, including the embedded decoder, with audited Storage beta.8."""
import hashlib
import io
from pathlib import Path
import sys
import zipfile

old, new = map(Path, sys.argv[1:])
assert hashlib.sha256(old.read_bytes()).hexdigest() == "11e9ecd10fb4efcf1ba31d35942ffde05c09103ae7ddf110dc7894dee74fcfe9"
changes = set()
with zipfile.ZipFile(old) as before, zipfile.ZipFile(new) as after:
    assert after.testzip() is None
    members = [n for n in after.namelist() if not n.endswith("/")]
    assert len(set(members)) == len(members)
    assert set(members) == {n for n in before.namelist() if not n.endswith("/")}
    decoder = "META-INF/anya-media/screen-codec.jar"
    for name in members:
        data = after.read(name)
        if before.read(name) != data:
            changes.add(name)
        if name != decoder:
            assert not zipfile.is_zipfile(io.BytesIO(data)), name
        assert not data.startswith((b"MZ", b"\x7fELF", b"#!")), name
    assert changes <= {"META-INF/neoforge.mods.toml", "assets/anyastorage/lang/en_us.json",
                       *("dev/anya/storage/pocket/"+s+".class" for s in
                         ["CameraControlsScreen","WallMonitorBlock","ScreenMedia","ScreenMedia$Session","ScreenMedia$MediaSound",
                          "ScreenMedia$MediaSound$1","CameraControlsScreen$Kind"])}, sorted(changes)
    assert {"dev/anya/storage/pocket/ScreenMedia$MediaSound.class","dev/anya/storage/pocket/CameraControlsScreen.class"} <= changes
    embedded = after.read(decoder)
    assert hashlib.sha256(embedded).hexdigest() == "dc6c5ffb2f5f808e8b760663b8111b5fc6992cfcbcf808f59652a5d4185eb95c"
    with zipfile.ZipFile(io.BytesIO(embedded)) as java:
        assert java.testzip() is None
        nested = [n for n in java.namelist() if not n.endswith("/")]
        for n in nested:
            assert Path(n).suffix in {".class",".MF",".txt",".xml",".properties"}
            payload=java.read(n)
            assert not payload.startswith((b"MZ",b"\x7fELF",b"#!")) and not zipfile.is_zipfile(io.BytesIO(payload)),n
print(f"Checked {len(members)} outer files and {len(nested)} decoder files. No native executables or scripts. The Java decoder matches the audited beta.8 decoder.")

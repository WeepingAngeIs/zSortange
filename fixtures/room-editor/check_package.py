"""Verify the playable archive and compare its tablet assets with the verified Twin Tails JAR."""
from hashlib import sha256
from pathlib import Path
import sys
import tomllib
from zipfile import ZipFile

playable, fixture = map(Path, sys.argv[1:])
# Captured from the playable Twin Tails JAR at commit 85b9bb6.
expected = {
    "textures/gui/tablet_twin_tails.png": "e64168156de620bec2de57f606067dbe59aed473a8fada89fc1f4841e25c2002",
    "textures/gui/tablet_device.png": "9123b8237735a89d9b6957a4119f2a6ce5b63e7a591daee2a2fb6910dd552cf9",
    "textures/gui/creative_tablet_device.png": "a8c9bfef3eef74713ed705581ea5b8849b334f63614a3a10a036371f827a4755",
    "sounds/tablet/warning.ogg": "8f0bf0a87b73000e9b9fd4deeacd35ff087968cd65204dfc0d2e351e537e6bab",
}
with ZipFile(playable) as jar, ZipFile(fixture) as future_test:
    assert jar.testzip() is None, "Corrupt playable archive"
    mods = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode())["mods"]
    assert [(mod["modId"], mod["version"]) for mod in mods] == [("anyastorage", "0.3.0-beta.7-roomeditor.1")]
    assert not any(name.startswith("dev/anya/storage/visual/") for name in jar.namelist())
    for name in ("EditorCamera", "EditorGuest"):
        assert "dev/anya/storage/visual/" + name + ".class" in future_test.namelist()
    for asset, digest in expected.items():
        assert sha256(jar.read("assets/anyastorage/" + asset)).hexdigest() == digest, asset
    atlas = "assets/anyastorage/textures/gui/control_atlas.png"
    assert jar.read(atlas) == (Path("src/main/resources") / atlas).read_bytes(), "Wrong control icons"

print("PASS: playable mod metadata and archive integrity; game fixture compiled separately.")
print("PASS: Twin Tails portrait, both tablet shells and warning sound match the verified asset bytes.")

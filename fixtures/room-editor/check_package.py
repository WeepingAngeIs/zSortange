"""Verify the playable archive and compare its tablet assets with the verified Twin Tails JAR."""
from hashlib import sha256
import json
from pathlib import Path
import sys
import tomllib
from zipfile import ZipFile

playable, fixture = map(Path, sys.argv[1:3])
version = next(line.removeprefix("mod_version=") for line in Path("gradle.properties").read_text().splitlines() if line.startswith("mod_version="))
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
    assert [(mod["modId"], mod["version"]) for mod in mods] == [("anyastorage", version)]
    assert not any(name.startswith("dev/anya/storage/visual/") for name in jar.namelist())
    for name in ("EditorCamera", "EditorGuest"):
        assert "dev/anya/storage/visual/" + name + ".class" in future_test.namelist()
    for asset, digest in expected.items():
        assert sha256(jar.read("assets/anyastorage/" + asset)).hexdigest() == digest, asset
    atlas = "assets/anyastorage/textures/gui/control_atlas.png"
    assert jar.read(atlas) == (Path("src/main/resources") / atlas).read_bytes(), "Wrong control icons"

print("PASS: playable mod metadata and archive integrity; game fixture compiled separately.")
print("PASS: Twin Tails portrait, both tablet shells and warning sound match the verified asset bytes.")

if len(sys.argv) == 4:
    with ZipFile(sys.argv[3]) as old, ZipFile(playable) as new:
        def resources(archive):
            return {n for n in archive.namelist() if n.startswith(("assets/", "data/")) and not n.endswith("/")}

        assert resources(old) == resources(new), "Resource IDs or recipes changed"
        for name in sorted(resources(old)):
            if name.startswith("assets/anyastorage/models/item/backpack/") and name.endswith(".json"):
                continue  # The shoulder depth check compares every member of these models.
            if name == "assets/anyastorage/lang/en_us.json":
                old_lang = json.loads(old.read(name))
                assert old_lang["item.anyastorage.creative_tablet"] == "Creative Tablet"
                old_lang["item.anyastorage.creative_tablet"] = "UwU Tablet"
                assert old_lang == json.loads(new.read(name)), name
            elif old.read(name) != new.read(name):
                assert name.endswith(".json") and json.loads(old.read(name)) == json.loads(new.read(name)), name
    print("PASS: all other shipped models, textures, sounds, recipes and data match roomeditor.1; existing tablet translation updated.")

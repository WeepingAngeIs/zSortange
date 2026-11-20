"""Verify the packaged physical item selects exactly the approved pixels used by the detailed hand."""
import io
import json
import sys
from zipfile import ZipFile

from PIL import Image


def check(jar):
    root = "assets/anyastorage/"
    models = root + "models/item/cupid_tablet"
    texture = root + "textures/item/cupid_tablet_face"

    def model(suffix):
        return json.loads(jar.read(models + suffix + ".json"))

    def image(name):
        with Image.open(io.BytesIO(jar.read(name))) as source:
            return source.convert("RGBA")

    shell = model("_shell")
    geometry, = shell["elements"]
    assert {side for side, values in geometry["faces"].items() if values["texture"] == "#face"} == {"south"}
    assert {"gui", "firstperson_righthand", "firstperson_lefthand", "thirdperson_righthand",
            "thirdperson_lefthand"}.issubset(shell["display"])
    shell_id = "anyastorage:item/cupid_tablet_shell"
    base = model("")
    assert base["parent"] == shell_id and "elements" not in base
    assert shell["textures"]["face"] == "anyastorage:item/cupid_tablet_face_dark"
    names = ("squint", "shut", "glance", "open")
    assert base["overrides"] == [{"predicate": {"anyastorage:cupid_face": n / 4},
                                  "model": "anyastorage:item/cupid_tablet_" + name}
                                 for n, name in enumerate(names, 1)]
    for name in names:
        assert model("_" + name) == {"parent": shell_id,
                                      "textures": {"face": "anyastorage:item/cupid_tablet_face_" + name}}
    strip = image(texture + ".png")
    assert strip.size == (256, 176 * 5)
    pixels = set()
    for index, name in enumerate(("dark", "open", "squint", "shut", "glance")):
        path = texture + "_" + name + ".png"
        frame = image(path)
        assert frame.size == (256, 176) and path + ".mcmeta" not in jar.namelist()
        expected = strip.crop((0, index * 176, 256, (index + 1) * 176))
        assert frame.tobytes() == expected.tobytes(), "Different expression pixels for " + name
        pixels.add(frame.tobytes())
    assert len(pixels) == 5
    print("PASS: physical tablet model overrides and five pixel-identical approved glass states")


if __name__ == "__main__":
    with ZipFile(sys.argv[1]) as jar:
        check(jar)

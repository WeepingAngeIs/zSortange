"""Verify the packaged physical item selects exactly the approved pixels used by the detailed hand."""
import hashlib
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
    cells = [(row, column, f"expression_{row}_{column}") for row in range(7) for column in range(9)]
    states = [(name, (1 + row * 9 + column) / 256) for row, column, name in cells]
    states.extend((name, n / 4) for n, name in enumerate(names, 1))
    assert base["overrides"] == [{"predicate": {"anyastorage:cupid_face": value},
                                  "model": "anyastorage:item/cupid_tablet_" + name}
                                 for name, value in states]
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
    creative = root + "models/item/creative_tablet"
    creative_shell = "anyastorage:item/creative_tablet_shell"
    def variant(suffix):
        return json.loads(jar.read(creative + suffix + ".json"))
    assert variant("_shell") == {"parent": shell_id, "textures": {"face": "anyastorage:item/creative_tablet_face_dark"}}
    assert variant("") == {"parent": creative_shell, "overrides": [
        {"predicate": {"anyastorage:cupid_face": value}, "model": "anyastorage:item/creative_tablet_" + name}
        for name, value in states]}
    for name in names:
        assert variant("_" + name) == {"parent": creative_shell, "textures": {"face": "anyastorage:item/creative_tablet_face_" + name}}
    def check_rim(ordinary, pink, aperture):
        assert ordinary.size == pink.size and ordinary.getchannel("A").tobytes() == pink.getchannel("A").tobytes()
        assert ordinary.crop(aperture).tobytes() == pink.crop(aperture).tobytes(), "Pink changed Cupid or her glass"
        assert ordinary.tobytes() != pink.tobytes(), "Missing Creative Tablet outline"
    check_rim(image(root + "textures/gui/tablet_device.png"), image(root + "textures/gui/creative_tablet_device.png"),
              (16, 20, 416, 288))
    for name in ("dark", *names):
        check_rim(image(texture + "_" + name + ".png"), image(root + "textures/item/creative_tablet_face_" + name + ".png"),
                  (10, 12, 246, 166))
    source = root + "textures/gui/tablet_twin_tails.png"
    assert hashlib.sha256(jar.read(source)).hexdigest() == "e64168156de620bec2de57f606067dbe59aed473a8fada89fc1f4841e25c2002"
    sheet = image(source)
    assert sheet.size == (2016, 896)
    for item, shell_name in (("cupid_tablet", "tablet_device"), ("creative_tablet", "creative_tablet_device")):
        hardware = image(root + f"textures/gui/{shell_name}.png")
        for row, col, name in cells:
            path = root + f"textures/item/{item}_face_{name}.png"
            actual = image(path)
            assert path + ".mcmeta" not in jar.namelist(), "Unexpected independent animator"
            cell = sheet.crop((224 * col, 128 * row, 224 * (col + 1), 128 * (row + 1)))
            expected = hardware.copy()
            expected.alpha_composite(cell.resize((400, 229), Image.Resampling.NEAREST), (16, 40))
            assert expected.resize((256, 176), Image.Resampling.NEAREST).tobytes() == actual.tobytes(), path
            child = json.loads(jar.read(root + f"models/item/{item}_{name}.json"))
            assert child == {"parent": f"anyastorage:item/{item}_shell", "textures": {"face": f"anyastorage:item/{item}_face_{name}"}}
    print("PASS: all 63 archived face cells on each tablet reproduce the source art and resolve in sorted predicate order")
    print("PASS: physical tablet model overrides and five pixel-identical approved glass states")


if __name__ == "__main__":
    with ZipFile(sys.argv[1]) as jar:
        check(jar)

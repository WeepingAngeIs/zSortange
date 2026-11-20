"""Offline packaged exterior checks. Java executes the selector and projection; javap inspects the wiring."""
import argparse
import os
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZipFile


HERE = Path(__file__).resolve().parent
PACKAGE = "dev.anya.storage.pocket."


def method(dump, signature):
    parts = re.split(r"(?m)(?=^  \S)", dump)
    matches = [part for part in parts if signature in part.splitlines()[0]]
    if len(matches) != 1:
        raise AssertionError(f"Expected one method with signature {signature!r}, found {len(matches)}")
    return matches[0]


def check_wiring(jar):
    def disassemble(name):
        return subprocess.check_output(["javap", "-p", "-c", "-classpath", str(jar), PACKAGE + name],
                                       text=True, timeout=30)

    packets = disassemble("PocketPadPackets")
    register = method(packets, " void register(")
    assert register.count("PayloadRegistrar.playToServer:") == register.count("PayloadRegistrar.playToClient:") == 1
    assert "PocketPadPackets$Focus.TYPE:" in register and "PocketPadPackets$Update.TYPE:" in register
    assert "$StillFocus" not in register and "$StillUpdate" not in register

    # The historical types and old-client replay signature can still link, but no other
    # production class may negotiate either payload, construct one, or install a handler.
    package = PACKAGE.replace(".", "/")
    permitted = {package + name + ".class" for name in
                 ("PocketPadPackets", "PocketPadPackets$StillFocus", "PocketPadPackets$StillUpdate", "EntranceLayoutClient")}
    with ZipFile(jar) as archive:
        references = {name for name in archive.namelist() if name.startswith("dev/anya/storage/") and name.endswith(".class")
                      and (package + "PocketPadPackets$Still").encode() in archive.read(name)}
        unexpected = references - permitted
        assert not unexpected, f"Legacy marker payload referenced outside the historical definitions: {unexpected}"
        for asset in ("starter_room_study.png", "framed_chest_still.png"):
            assert archive.getinfo("assets/anyastorage/textures/entity/" + asset).file_size > 0

    observation = disassemble("PocketPadObservation")
    client = disassemble("EntranceLayoutClient")
    sampler = method(observation, " int sample(")
    assert "PocketRoomLayout.positions:" in sampler and "PocketService.homeAccess:" in sampler
    assert "Safety.locationReason:" in sampler and "existingStorageIdentity:" in sampler
    assert "CYAN_CONCRETE" not in observation and "PURPLE_CONCRETE" not in observation
    assert "$StillUpdate" not in observation and "$StillFocus" not in observation
    assert "$StillFocus" not in client
    instructions = r"(?m)^\s+\d+:\s+(\S+)"
    assert re.findall(instructions, method(client, " void receiveStill(")) == ["return"]
    assert re.findall(instructions, method(client, " int visibleStill(")) == ["iconst_0", "ireturn"]
    assert "PocketPadPackets$Focus." in method(client, " void sendFocus(")

    renderer = disassemble("EntranceStudyRenderer")
    assert "textures/entity/framed_chest_still.png" in renderer and "textures/entity/starter_room_study.png" in renderer
    render = method(renderer, " void render(dev.anya.storage.pocket.PocketContent$EntranceEntity")
    assert "EntranceLayoutClient.CHEST_LAYOUT_STILL:" in render
    assert render.index("EntranceLayoutClient.visibleMask:") < render.index("Method drawChestLayout:")
    assert "synthetic_peek_" not in renderer and "visibleStill:" not in renderer
    frame = method(renderer, " void drawChestLayout(")
    # javac emits the public branch first. Its return precedes the private atlas and stamp operations.
    public_branch, stamps = frame.split("return", 1)
    assert "ChestLayoutProjection.validMask:" in public_branch
    assert re.search(r"\bifne\s+\d+", public_branch) and "Field STUDY:" in public_branch and "Method quad:" in public_branch
    assert "Field CHEST_LAYOUT:" not in public_branch
    assert "Field CHEST_LAYOUT:" in stamps and "ChestLayoutProjection.forEachChest:" in stamps
    print("PASS: packaged registration, registered pad sampler, renderer choice and public fallback bytecode")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--libraries", type=Path, default=Path("build/durable-audit/classpath.txt"),
                        help="Plain Java classpath written by durableAuditClasspath")
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    check_wiring(jar)
    libraries = args.libraries.read_text(encoding="utf-8-sig").strip()
    with TemporaryDirectory(prefix="anya-exterior-") as output:
        classpath = os.pathsep.join([output, str(jar), libraries])
        subprocess.run(["javac", "--release", "21", "-proc:none", "-cp", classpath, "-d", output,
                        str(HERE / "ExteriorCheck.java")], check=True, timeout=40)
        for ordinary, legacy in [(False, False), (True, False), (False, True), (True, True)]:
            subprocess.run(["java", f"-Danyastorage.chestLayoutStill={str(ordinary).lower()}",
                            f"-Danyastorage.stillFramePeek={str(legacy).lower()}", "-cp", classpath,
                            PACKAGE + "ExteriorCheck", str(jar), str(ordinary or legacy).lower()], check=True, timeout=30)
    print("PASS: all four flag combinations, unfocused public state, inert legacy replay and all 256 pad masks")


if __name__ == "__main__":
    main()

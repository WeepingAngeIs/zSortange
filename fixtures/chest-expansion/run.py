"""Run the offline chest-expansion check with an existing JDK 21 and local compiler dependencies.

--classpath-file accepts a javac argument file with -classpath and a quoted path list.
--support-jar is the existing mod JAR for unchanged supporting types. The tested
production classes are compiled from this checkout and loaded first. With
--packaged, supply the candidate JAR; only fixtures are compiled and all mod
classes are loaded directly from that archive.
"""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
SRC = HERE.parents[1] / "src/main/java/dev/anya/storage"
PRODUCTION = [
    "BasePermissions", "Config", "Content", "StorageEntity", "Transactions",
    "pocket/ChestLayoutProjection", "pocket/ChestPadGuide", "pocket/PocketLedger",
    "pocket/PocketRoomLayout", "pocket/RoomBounds", "pocket/RoomEditor",
]


def dependencies(argfile):
    lines = argfile.read_text(encoding="utf-8-sig").splitlines()
    paths = lines[lines.index("-classpath") + 1].strip('"').replace("\\\\", "/").split(os.pathsep)
    for raw in paths:
        path = Path(raw)
        marker = "/caches/modules-2/files-2.1/"
        if not path.is_file() and marker in path.as_posix():
            path = Path.home() / ".gradle/caches/modules-2/files-2.1" / path.as_posix().split(marker, 1)[1]
        if not path.is_file():
            raise FileNotFoundError(path)
        yield path.resolve().as_posix()


def main(jdk, support, argfile, packaged):
    if not support.is_file():
        raise FileNotFoundError(support)
    with tempfile.TemporaryDirectory(prefix="anya-pad-check-") as directory:
        scratch = Path(directory)
        classes = scratch / "classes"
        classes.mkdir()
        cp = os.pathsep.join([classes.as_posix(), support.resolve().as_posix(), *dependencies(argfile)])
        sources = [] if packaged else [(SRC / (name + ".java")).as_posix() for name in PRODUCTION]
        sources.extend(path.as_posix() for path in sorted(HERE.glob("*.java")))
        commands = [
            ("javac", ["--release", "21", "-proc:none", "-encoding", "UTF-8",
                       "-classpath", cp, "-d", classes.as_posix(), *sources]),
            ("java", ["-classpath", cp, "dev.anya.storage.pocket.ChestExpansionCheck", scratch.as_posix()]),
        ]
        for tool, arguments in commands:
            args = scratch / (tool + ".args")
            args.write_text("\n".join('"' + argument + '"' for argument in arguments), encoding="utf-8")
            subprocess.run([str(jdk.resolve() / "bin" / tool), "@" + str(args)], cwd=scratch, check=True, timeout=90)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--support-jar", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--packaged", action="store_true", help="Exercise bytecode directly from the supplied candidate JAR")
    args = parser.parse_args()
    main(args.jdk, args.support_jar, args.classpath_file, args.packaged)

"""Exercise the packaged tablet, binding signature, distance policy and session guards on an offline host."""

import argparse
import os
from pathlib import Path
import sys
import tempfile

from host import SOURCES

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tablet-lifecycle"))
from run import cached_paths, invoke


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("jdk", "classpath-file", "support-jar"):
        parser.add_argument("--" + flag, required=True, type=Path)
    args = parser.parse_args()
    jar = args.support_jar.resolve()
    with tempfile.TemporaryDirectory(prefix="anya-uwu-access-") as temp:
        root = Path(temp)
        classes = root / "classes"
        classes.mkdir()
        files = []
        for name, body in SOURCES.items():
            package, simple = name.rsplit(".", 1)
            path = root / "src" / package.replace(".", "/") / (simple + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + package + ";\n" + body, encoding="utf-8")
            files.append(path)
        cp = os.pathsep.join((str(classes), str(jar), cached_paths(args.classpath_file)))
        invoke(args.jdk, "javac", ["--release", "21", "-proc:none", "-classpath", cp, "-d", classes,
                                  *files, HERE / "AccessCheck.java"], root)
        invoke(args.jdk, "java", ["-Djava.awt.headless=true", "-classpath", cp,
                                 "dev.anya.storage.AccessCheck", jar], root)


if __name__ == "__main__":
    main()

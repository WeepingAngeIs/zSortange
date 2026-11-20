"""Check packaged room-planning logic with registered items and in-memory containers."""
import argparse
import os
from pathlib import Path
import runpy
import sys
import tempfile

here = Path(__file__).resolve().parent
host = runpy.run_path(str(here.parent / "chest-expansion/run.py"))
sys.path.insert(0, str(here.parent / "tablet-lifecycle"))
invoke = runpy.run_path(str(here.parent / "tablet-lifecycle/run.py"))["invoke"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("jdk", "classpath-file", "support-jar"):
        parser.add_argument("--" + flag, required=True, type=lambda s: Path(s).resolve())
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="room-outlines-") as tmp:
        scratch = Path(tmp)
        cp = os.pathsep.join([scratch.as_posix(), args.support_jar.as_posix(), *host["dependencies"](args.classpath_file)])
        sources = [here.parent/"chest-expansion/OfflineRegistries.java", here/"RoomPlanCheck.java"]
        invoke(args.jdk,"javac",["--release","21","-proc:none","-classpath",cp,"-d",scratch,*sources],scratch)
        invoke(args.jdk,"java",["-classpath",cp,"dev.anya.storage.pocket.RoomPlanCheck",scratch],scratch)


if __name__ == "__main__":
    main()

"""Use real NeoForge hand/input events and a recording display to exercise the .3 handling dispatch."""
import argparse
import os
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tablet-lifecycle"))
from run import cached_paths, invoke
from run_hand import sources


def run(options):
    with tempfile.TemporaryDirectory(prefix="anya-tablet-use-") as temp:
        scratch = Path(temp)
        host = scratch / "host"
        host.mkdir()
        libs = cached_paths(options.classpath_file)
        app = options.support_jar.resolve().as_posix()
        if not options.packaged:
            compiled = scratch / "candidate"
            compiled.mkdir()
            src = HERE.parents[1] / "src/main/java/dev/anya/storage/appearance"
            production = ("HeldTablet", "TabletIdle", "TabletLift", "TabletHandMotion", "TabletHandlingPose", "TabletArmRenderer",
                          "TabletDevice", "GuardianBlink")
            invoke(options.jdk, "javac", ["--release", "21", "-proc:none", "-classpath", os.pathsep.join((app, libs)),
                   "-d", compiled, *(src / (name + ".java") for name in production)], scratch)
            app = os.pathsep.join((compiled.as_posix(), app))
        cp = os.pathsep.join((host.as_posix(), app, libs))
        invoke(options.jdk, "javac", ["--release", "21", "-proc:none", "-classpath", cp, "-d", host,
               *sources(scratch / "stubs"), HERE / "HeldUseCheck.java",
               HERE.parent / "tablet-lifecycle/TabletIdleCheck.java"], scratch)
        invoke(options.jdk, "java", ["-classpath", cp, "dev.anya.storage.appearance.TabletIdleCheck"], scratch)
        invoke(options.jdk, "java", ["-Djava.awt.headless=true",
               "-classpath", cp, "dev.anya.storage.appearance.HeldUseCheck"], scratch)
        invoke(options.jdk, "java", ["-Djava.awt.headless=true", "-Danyastorage.tabletIntro=false",
               "-classpath", cp, "dev.anya.storage.appearance.HeldUseCheck", "opt-out"], scratch)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--support-jar", type=Path, required=True)
    parser.add_argument("--packaged", action="store_true")
    run(parser.parse_args())

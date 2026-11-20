"""Run the bounded handling checks with a local JDK and an existing cached NeoForge classpath."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from handoff_host import materialize


HERE = Path(__file__).resolve().parent
JAVA = HERE.parents[1] / "src/main/java/dev/anya/storage/appearance"


def invoke(jdk, tool, arguments, scratch):
    params = scratch / (tool + ".args")
    params.write_text("\n".join('"' + str(arg).replace("\\", "/") + '"' for arg in arguments), encoding="utf-8")
    subprocess.run([jdk / "bin" / tool, "@" + str(params)], cwd=scratch, check=True, timeout=45)


def main(options):
    lines = options.classpath_file.read_text(encoding="utf-8-sig").splitlines()
    libs = lines[lines.index("-classpath") + 1].strip('"').replace("\\\\", "/")
    with tempfile.TemporaryDirectory(prefix="anya-handling-") as temp:
        scratch = Path(temp)
        classes = scratch / "classes"
        classes.mkdir()
        cp = os.pathsep.join((classes.as_posix(), options.support_jar.as_posix(), libs))
        sources = [HERE / "HandlingCheck.java"]
        if not options.packaged:
            sources += [JAVA / (name + ".java") for name in
                        ("TabletLift", "TabletHandlingPose", "GuardianBlink", "TabletArmRenderer", "TabletDevice", "TabletBootScreen")]
            sources += [JAVA.parent / name for name in ("Client.java", "StoreMenu.java", "Config.java",
                        "ExpansionRules.java", "ServerSettings.java", "pocket/PocketService.java")]
        invoke(options.jdk, "javac", ["--release", "21", "-encoding", "UTF-8", "-proc:none",
               "-classpath", cp, "-d", classes, *sources], scratch)
        print("Production inputs:", "packaged candidate" if options.packaged else "handling source with support JAR", flush=True)
        invoke(options.jdk, "java", ["-Djava.awt.headless=true", "-classpath", cp,
               "dev.anya.storage.appearance.HandlingCheck"], scratch)
        host = scratch / "host"
        host.mkdir()
        host_path = os.pathsep.join((host.as_posix(), cp))
        invoke(options.jdk, "javac", ["--release", "21", "-encoding", "UTF-8", "-proc:none", "-classpath", host_path,
               "-d", host, *materialize(scratch / "desktop"), HERE / "HeldOpeningCheck.java"], scratch)
        invoke(options.jdk, "java", ["-Djava.awt.headless=true", "-classpath", host_path,
               "dev.anya.storage.appearance.HeldOpeningCheck"], scratch)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", required=True, type=lambda s: Path(s).resolve())
    parser.add_argument("--classpath-file", required=True, type=lambda s: Path(s).resolve())
    parser.add_argument("--support-jar", required=True, type=lambda s: Path(s).resolve())
    parser.add_argument("--packaged", action="store_true", help="Load production code directly from --support-jar.")
    main(parser.parse_args())

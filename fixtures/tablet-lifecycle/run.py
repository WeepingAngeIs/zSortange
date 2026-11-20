"""Bounded, offline screen-dispatch check using the existing Java 21/NeoForge compiler cache."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

from desktop_host import materialize


HERE = Path(__file__).resolve().parent
JAVA = HERE.parents[1] / "src/main/java/dev/anya/storage"


def invoke(jdk, program, arguments, directory):
    argfile = directory / (program + ".args")
    argfile.write_text("\n".join('"' + str(arg).replace("\\", "/") + '"' for arg in arguments),
                       encoding="utf-8")
    subprocess.run([jdk / "bin" / program, "@" + str(argfile)], cwd=directory, check=True, timeout=45)


def cached_paths(argfile):
    lines = argfile.read_text(encoding="utf-8-sig").splitlines()
    return lines[lines.index("-classpath") + 1].strip('"').replace("\\\\", "/")


def main(options):
    with tempfile.TemporaryDirectory(prefix="anya-tablet-screen-") as temp:
        scratch = Path(temp)
        host = scratch / "desktop"
        host.mkdir()
        libraries = cached_paths(options.classpath_file)
        support = options.support_jar.resolve().as_posix()
        app = support
        if not options.packaged:
            compiled = scratch / "candidate"
            compiled.mkdir()
            invoke(options.jdk, "javac", [
                "--release", "21", "-proc:none", "-encoding", "UTF-8", "-d", compiled,
                "-classpath", os.pathsep.join((support, libraries)), JAVA / "Client.java",
                JAVA / "appearance/GuardianBlink.java", JAVA / "appearance/TabletDevice.java",
                JAVA / "appearance/TabletBootScreen.java",
            ], scratch)
            app = os.pathsep.join((compiled.as_posix(), support))
        classpath = os.pathsep.join((host.as_posix(), app, libraries))
        invoke(options.jdk, "javac", [
            "--release", "21", "-proc:none", "-encoding", "UTF-8", "-classpath", classpath,
            "-d", host, *materialize(scratch / "src"), HERE / "ScreenDispatchCheck.java",
        ], scratch)
        print("Testing production screen " + ("from supplied JAR" if options.packaged else "compiled from source"), flush=True)
        invoke(options.jdk, "java", ["-Djava.awt.headless=true", "-classpath", classpath, "ScreenDispatchCheck"], scratch)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", required=True, type=lambda path: Path(path).resolve())
    parser.add_argument("--classpath-file", required=True, type=Path)
    parser.add_argument("--support-jar", required=True, type=Path)
    parser.add_argument("--packaged", action="store_true", help="Test the supplied candidate JAR bytecode directly")
    main(parser.parse_args())

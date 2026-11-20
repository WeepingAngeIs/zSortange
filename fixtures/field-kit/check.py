"""Exercise field kit transactions with real item registries and in-memory containers."""
import argparse
import os
import tempfile
import runpy
import subprocess
from pathlib import Path

here = Path(__file__).resolve().parent
dependencies = runpy.run_path(str(here.parent / "chest-expansion/run.py"))["dependencies"]


def invoke(jdk, tool, arguments, temp):
    args = temp / (tool + ".args")
    args.write_text("\n".join('"' + str(a).replace(chr(92), "/") + '"' for a in arguments), encoding="utf-8")
    subprocess.run([str(jdk / "bin" / tool), "@" + str(args)], cwd=temp, check=True, timeout=60)


def main(args):
    with tempfile.TemporaryDirectory(prefix="anya-field-kit-") as name:
        temp = Path(name)
        classes = temp / "classes"
        classes.mkdir()
        cp = os.pathsep.join([str(classes), str(args.support_jar.resolve()), *dependencies(args.classpath_file)])
        sources = [here / "FieldKitCheck.java", here.parent / "chest-expansion/OfflineRegistries.java"]
        if not args.packaged:
            src = here.parents[1] / "src/main/java/dev/anya/storage"
            sources.extend(src / (n+".java") for n in ("FieldKit","Transactions","BagInventory","StorageEntity","Profiles","StoreMenu","Client","Packets","Network"))
        invoke(args.jdk.resolve(),"javac",["--release","21","-proc:none","-encoding","UTF-8","-cp",cp,"-d",classes,*sources],temp)
        invoke(args.jdk.resolve(),"java",["-Djava.awt.headless=true","-cp",cp,"dev.anya.storage.FieldKitCheck",temp],temp)


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--jdk",type=Path,required=True)
    p.add_argument("--classpath-file",type=Path,required=True)
    p.add_argument("--support-jar",type=Path,required=True)
    p.add_argument("--packaged",action="store_true")
    main(p.parse_args())

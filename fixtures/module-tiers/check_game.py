"""Match Gradle's hosted GameTest mod output to the frozen playable, and require actual success."""
from pathlib import Path
import re
import sys
from zipfile import ZipFile

FIXTURE = "dev/anya/storage/ModuleTierGameTests"
TEMPLATE = "data/anyamoduletest/structure/empty.nbt"
PRODUCTION_TEMPLATE = "data/anyastorage/structure/empty.nbt"


def verify(jar_path, log_path):
    outputs = [Path("build/classes/java/main"), Path("build/resources/main")]
    files = {}
    for output in outputs:
        assert output.is_dir(), output
        for path in output.rglob("*"):
            if path.is_file():
                name = path.relative_to(output).as_posix()
                assert name not in files, name
                files[name] = path
    with ZipFile(jar_path) as jar:
        production = {i.filename for i in jar.infolist() if not i.is_dir()} - {"META-INF/MANIFEST.MF"}
        missing = production - files.keys()
        assert not missing, f"Runtime missing packaged files: {sorted(missing)}"
        for name in sorted(production):
            assert files[name].read_bytes() == jar.read(name), f"Changed at GameTest runtime: {name}"
        extra = files.keys() - production
        assert FIXTURE + ".class" in extra and TEMPLATE in extra
        allowed_classes = {n for n in extra if n == FIXTURE + ".class" or
                           (n.startswith(FIXTURE + "$") and n.endswith(".class"))}
        assert extra == allowed_classes | {TEMPLATE}, f"Unexpected runtime differences: {sorted(extra)}"
        assert files[TEMPLATE].read_bytes() == jar.read(PRODUCTION_TEMPLATE)
    print(f"PASS runtime identity: {len(production)} playable entries match the test mod byte-for-byte, with only the fixture and its test namespace template added.")

    log = Path(log_path).read_text(encoding="utf-8", errors="replace")
    assert re.search(r"Enabled Gametest Namespaces:\s*\[anyamoduletest\]", log, re.I), "Incorrect test namespace"
    fixture = Path("fixtures/module-tiers/java/dev/anya/storage/ModuleTierGameTests.java").read_text()
    expected = len(re.findall(r"^\s*@GameTest\(", fixture, re.M))
    result = re.findall(r"All\s+(\d+)\s+required tests? passed", log, re.I)
    assert expected > 0 and result and int(result[-1]) == expected, (expected, result)
    assert not re.search(r"\b[1-9]\d* required tests? (?:have )?failed", log, re.I)
    print(f"PASS server: all {expected} required anyamoduletest GameTests passed on Minecraft 1.21.1 / NeoForge 21.1.249, Java 21.")


if __name__ == "__main__":
    verify(sys.argv[1], sys.argv[2])

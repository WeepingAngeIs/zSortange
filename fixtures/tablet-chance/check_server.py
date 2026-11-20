"""Exercise the real config transaction and inspect the compiled menu's once-only server roll."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


HERE = Path(__file__).resolve().parent
JAVA = HERE.parents[1] / "src/main/java/dev/anya/storage"
sys.path.insert(0, str(HERE.parent / "tablet-lifecycle"))
from run import cached_paths, invoke


HOST = {
    "net.neoforged.fml.loading.FMLPaths": """
        public enum FMLPaths { CONFIGDIR;
            public java.nio.file.Path get() { return java.nio.file.Path.of(System.getProperty("fixture.config")); }
        }""",
    "net.minecraft.server.packs.repository.PackRepository": """
        public class PackRepository {
            public java.util.Collection<String> getSelectedIds() { return java.util.List.of("fixture"); }
        }""",
    "net.minecraft.server.MinecraftServer": """
        import net.minecraft.server.packs.repository.PackRepository;
        public class MinecraftServer {
            public boolean serverThread = true;
            public int reloads;
            public boolean isSameThread() { return serverThread; }
            public PackRepository getPackRepository() { return new PackRepository(); }
            public java.util.concurrent.CompletableFuture<Void> reloadResources(java.util.Collection<String> ids) {
                reloads++; return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            public void execute(Runnable callback) { callback.run(); }
        }""",
    "net.minecraft.server.level.ServerPlayer": """
        import net.minecraft.server.MinecraftServer;
        public class ServerPlayer {
            public boolean operator;
            private final MinecraftServer server = new MinecraftServer();
            public MinecraftServer getServer() { return server; }
            public boolean hasPermissions(int level) { return operator && level <= 2; }
            public java.util.UUID getUUID() { return new java.util.UUID(0,1); }
            public com.mojang.authlib.GameProfile getGameProfile() { return new com.mojang.authlib.GameProfile(getUUID(), "Test"); }
        }""",
    "dev.anya.storage.AnyaStorage": """
        public class AnyaStorage {
            public static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("tablet-fixture");
            public static void tell(net.minecraft.server.level.ServerPlayer player, String message) {}
        }""",
}


def check_menu(jdk, app):
    full = subprocess.check_output([jdk / "bin/javap", "-classpath", app, "-p", "-c", "dev.anya.storage.StoreMenu"],
                                   text=True, timeout=45)
    # javap lists a two-space signature line, followed by indented bytecode for its method.
    sections = re.split(r"(?m)(?=^  \S)", full)
    invokes = [s for s in sections if "// Method rollTabletOpening:" in s]
    assert len(invokes) == 1 and invokes[0].lstrip().startswith("private dev.anya.storage.StoreMenu(")
    assert invokes[0].count("// Method rollTabletOpening:") == 1
    assert "final int tabletOpeningFlags;" in full
    writes = [s for s in sections if re.search(r"putfield[^\n]+// Field tabletOpeningFlags:I", s)]
    assert len(writes) == 1 and "StoreMenu(int, net.minecraft.world.entity.player.Inventory, int);" in writes[0]
    storage = next(s for s in sections if s.strip().startswith("private void storage();"))
    assert re.search(r"getfield[^\n]+// Field tabletOpeningFlags:I\s+\d+: ior", storage)
    chooser = next(s for s in sections if s.strip().startswith("private static int rollTabletOpening("))
    assert chooser.index("isStorage:") < chooser.index("ireturn") < chooser.index("Options.rollTabletOpening:")
    assert "bipush        64" in chooser and "bipush        32" in chooser
    for name, bit in (("hasTabletOpeningRule", 64), ("showsTabletIntro", 32)):
        reader = next(s for s in sections if s.strip().startswith("public boolean " + name + "();"))
        assert "iconst_4" in reader and re.search(rf"bipush\s+{bit}\s", reader) and "iand" in reader
    print("PASS: compiled server constructor is the sole roll site; final result is carried in the storage flag word across refresh/navigation")


def run(options):
    jdk = options.jdk.resolve()
    libs = cached_paths(options.classpath_file.resolve())
    support = str(options.support_jar.resolve())
    with tempfile.TemporaryDirectory(prefix="anya-tablet-chance-") as tmp:
        temp = Path(tmp)
        configdir = temp / "config"
        host = temp / "host"
        configdir.mkdir()
        host.mkdir()
        app = support
        if not options.packaged:
            classes = temp / "compiled"
            classes.mkdir()
            invoke(jdk, "javac", ["--release", "21", "-proc:none", "-cp", os.pathsep.join((support, libs)),
                                  "-d", classes, *(JAVA / n for n in ("Config.java","StoreMenu.java","ExpansionRules.java"))], temp)
            app = os.pathsep.join((str(classes), support))
        check_menu(jdk, app)
        paths = []
        for name, body in HOST.items():
            pkg, simple = name.rsplit(".",1)
            path = temp / "stubs" / pkg.replace(".","/") / (simple + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + pkg + ";\n" + body, encoding="utf-8")
            paths.append(path)
        cp = os.pathsep.join((str(host), app, libs))
        invoke(jdk, "javac", ["--release","21","-proc:none","-cp",cp,"-d",host,*paths,HERE / "ServerChanceCheck.java"], temp)
        invoke(jdk, "java", ["-Djava.awt.headless=true","-Dfixture.config="+str(configdir),"-cp",cp,
                             "dev.anya.storage.ServerChanceCheck",configdir], temp)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--support-jar", type=Path, required=True)
    parser.add_argument("--packaged", action="store_true")
    run(parser.parse_args())

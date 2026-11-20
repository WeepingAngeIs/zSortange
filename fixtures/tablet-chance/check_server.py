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
            public int reloads, pocketTransitions, pocketDisables;
            public boolean pocketDimensionLoaded = true, pocketPreparing, deferReload;
            public java.util.concurrent.CompletableFuture<Void> pendingReload;
            public boolean isSameThread() { return serverThread; }
            public PackRepository getPackRepository() { return new PackRepository(); }
            public java.util.concurrent.CompletableFuture<Void> reloadResources(java.util.Collection<String> ids) {
                reloads++;
                if (deferReload) {
                    pendingReload = new java.util.concurrent.CompletableFuture<>();
                    return pendingReload;
                }
                return java.util.concurrent.CompletableFuture.completedFuture(null);
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
    "dev.anya.storage.pocket.PocketService": """
        import dev.anya.storage.Config;
        import net.minecraft.server.MinecraftServer;
        public final class PocketService {
            public static void validateStorageChange(MinecraftServer server, boolean enabled) {
                server.pocketTransitions++;
                if (enabled && !server.pocketDimensionLoaded)
                    throw new IllegalArgumentException("Missing pocket dimension");
                if (!enabled && server.pocketPreparing)
                    throw new IllegalArgumentException("Pocket preparation is active");
            }
            public static void storageDisabled(MinecraftServer server) {
                if (Config.pocketStorageEnabled()) throw new AssertionError("Cleaning leases before disabling storage");
                server.pocketDisables++;
            }
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
    assert "final int openingFlags;" in full
    writes = [s for s in sections if re.search(r"putfield[^\n]+// Field openingFlags:I", s)]
    assert len(writes) == 1 and "StoreMenu(int, net.minecraft.world.entity.player.Inventory, int);" in writes[0]
    planned = [s for s in sections if "// Method openingFlags:" in s]
    for entry in ("bag", "openNetwork", "container", "openPocket"):
        entry_points = [s for s in planned if re.match(r"\s+(?:public|private) static void "+entry+r"\(", s)]
        assert len(entry_points) == 1 and entry_points[0].count("// Method openingFlags:") == 1, entry
    assert len(planned) == 4
    storage = next(s for s in sections if s.strip().startswith("private void storage();"))
    assert "Field openingFlags:I" in storage and "// Method openingFlags:" not in storage
    chooser = next(s for s in sections if s.strip().startswith("private static int openingFlags("))
    assert chooser.index("ireturn") < chooser.index("Options.rollTabletOpening:")
    assert chooser.count("Options.rollTabletOpening:") == chooser.count("Options.rollChestCameo:") == 1
    assert "Options.backpackCameoEnabled:Z" in chooser
    for name, bit in (("hasTabletOpeningRule", 64), ("showsTabletIntro", 32),
                      ("remoteOpening", 128), ("showsStorageCameo", 256)):
        reader = next(s for s in sections if s.strip().startswith("public boolean " + name + "();"))
        assert "iconst_4" in reader and re.search(rf"(?:bi|si)push\s+{bit}\s", reader) and "iand" in reader
    print("PASS: four authorized opening paths select flags once; refresh reuses the immutable result and each client flag has its own bit")


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
            sources = ("Config.java", "StoreMenu.java", "ExpansionRules.java", "ServerSettings.java",
                       "pocket/PocketService.java")
            invoke(jdk, "javac", ["--release", "21", "-proc:none", "-cp", os.pathsep.join((support, libs)),
                                  "-d", classes, *(JAVA / name for name in sources)], temp)
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
        checks = ("ServerChanceCheck", "PocketSettingsCheck")
        invoke(jdk, "javac", ["--release", "21", "-proc:none", "-cp", cp, "-d", host,
                              *paths, *(HERE / (name + ".java") for name in checks)], temp)
        for name in checks:
            invoke(jdk, "java", ["-Djava.awt.headless=true", "-Dfixture.config=" + str(configdir), "-cp", cp,
                                 "dev.anya.storage." + name, configdir], temp)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--support-jar", type=Path, required=True)
    parser.add_argument("--packaged", action="store_true")
    run(parser.parse_args())

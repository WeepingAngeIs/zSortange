package probe;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

@Mod("anyadisplayprobe")
public final class MediaTrial {
    static final BlockPos TV = new BlockPos(8, 82, 8);
    static final BlockPos WALL = new BlockPos(8, 82, 20);
    static final BlockPos CAMERA = new BlockPos(18, 82, 8);
    static final Path proof = Path.of("../proof").toAbsolutePath().normalize();

    static void hosted() throws Exception {
        if (!"github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT"))
                || !"true".equals(System.getenv("GITHUB_ACTIONS")) || !Files.isDirectory(proof))
            throw new IllegalStateException("Hosted disposable display proof required");
        var actual = ModList.get().getModFileById("anyastorage").getFile().getFilePath();
        if (!Files.isSameFile(actual, Path.of(System.getProperty("anyadisplay.frozen"))))
            throw new IllegalStateException("Wrong playable JAR");
    }
}

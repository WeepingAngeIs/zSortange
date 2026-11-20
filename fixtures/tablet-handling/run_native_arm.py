"""Check the native arm entry point. Recording stubs replace the GPU, renderer and game client."""
import argparse
import os
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tablet-lifecycle"))
from run import cached_paths, invoke

HOST = {
    "net.minecraft.world.entity.Entity": "public class Entity {}",
    "net.minecraft.client.player.AbstractClientPlayer": """
        public class AbstractClientPlayer extends net.minecraft.world.entity.Entity {}
    """,
    "net.minecraft.client.player.LocalPlayer": """
        public class LocalPlayer extends AbstractClientPlayer {
            public boolean invisible, spectator;
            public boolean isInvisible() { return invisible; }
            public boolean isSpectator() { return spectator; }
        }
    """,
    "net.minecraft.client.renderer.MultiBufferSource": """
        public interface MultiBufferSource { class BufferSource implements MultiBufferSource {} }
    """,
    "net.minecraft.client.renderer.entity.EntityRenderer": """
        public class EntityRenderer<T extends net.minecraft.world.entity.Entity> {}
    """,
    "net.minecraft.client.renderer.entity.EntityRenderDispatcher": """
        public class EntityRenderDispatcher {
            public EntityRenderer<?> renderer;
            @SuppressWarnings("unchecked")
            public <T extends net.minecraft.world.entity.Entity> EntityRenderer<? super T> getRenderer(T entity) {
                return (EntityRenderer<? super T>) renderer;
            }
        }
    """,
    "net.minecraft.client.Minecraft": """
        public class Minecraft {
            private static final Minecraft instance = new Minecraft();
            private final net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher = new net.minecraft.client.renderer.entity.EntityRenderDispatcher();
            public static Minecraft getInstance() { return instance; }
            public net.minecraft.client.renderer.entity.EntityRenderDispatcher getEntityRenderDispatcher() { return dispatcher; }
        }
    """,
    "net.minecraft.client.gui.GuiGraphics": """
        import com.mojang.blaze3d.vertex.PoseStack;
        import net.minecraft.client.renderer.MultiBufferSource;
        public class GuiGraphics {
            private final PoseStack poses = new PoseStack();
            private final MultiBufferSource.BufferSource buffer = new MultiBufferSource.BufferSource();
            public int flushes;
            public PoseStack pose() { return poses; }
            public MultiBufferSource.BufferSource bufferSource() { return buffer; }
            public void flush() { flushes++; }
        }
    """,
    "net.minecraft.client.renderer.entity.player.PlayerRenderer": """
        import com.mojang.blaze3d.vertex.PoseStack;
        import net.minecraft.client.player.AbstractClientPlayer;
        import net.minecraft.client.renderer.MultiBufferSource;
        import org.joml.Matrix4f;
        public class PlayerRenderer extends net.minecraft.client.renderer.entity.EntityRenderer<AbstractClientPlayer> {
            public int draws, light;
            public boolean right, fail;
            public Matrix4f pose;
            public MultiBufferSource buffer;
            public AbstractClientPlayer player;
            private void record(boolean r, PoseStack p, MultiBufferSource b, int l, AbstractClientPlayer a) {
                draws++; right = r; light = l; pose = new Matrix4f(p.last().pose()); buffer = b; player = a;
                if (fail) throw new IllegalStateException("Injected draw failure");
            }
            public void renderRightHand(PoseStack p, MultiBufferSource b, int l, AbstractClientPlayer a) { record(true, p, b, l, a); }
            public void renderLeftHand(PoseStack p, MultiBufferSource b, int l, AbstractClientPlayer a) { record(false, p, b, l, a); }
        }
    """,
}


def main(options):
    libs = cached_paths(options.classpath_file)
    with tempfile.TemporaryDirectory(prefix="anya-native-arm-") as directory:
        scratch = Path(directory)
        code = options.support_jar
        if code is None:
            code = scratch / "compiled"
            code.mkdir()
            directory = HERE.parents[1] / "src/main/java/dev/anya/storage/appearance"
            sources = [directory / name for name in ("TabletArmRenderer.java", "TabletHandlingPose.java", "TabletHandMotion.java")]
            invoke(options.jdk, "javac", ["--release", "21", "-proc:none", "-classpath", libs, "-d", code, *sources], scratch)
        code = code.resolve()
        host = scratch / "host"
        host.mkdir()
        files = []
        for name, body in HOST.items():
            package, cls = name.rsplit(".", 1)
            file = scratch / "stubs" / package.replace(".", "/") / (cls + ".java")
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("package " + package + ";\n" + body, encoding="utf-8")
            files.append(file)
        cp = os.pathsep.join((host.as_posix(), code.as_posix(), libs))
        invoke(options.jdk, "javac", ["--release", "21", "-proc:none", "-classpath", cp, "-d", host,
                                       *files, HERE / "NativeArmCheck.java"], scratch)
        invoke(options.jdk, "java", ["-Djava.awt.headless=true", "-classpath", cp, "dev.anya.storage.appearance.NativeArmCheck"], scratch)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--support-jar", type=Path, help="Exercise compiled adapter from this candidate instead of recompiling it")
    main(parser.parse_args())

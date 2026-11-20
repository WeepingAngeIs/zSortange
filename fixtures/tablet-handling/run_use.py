"""Use real NeoForge hand/input events and a recording display to exercise the .3 handling dispatch."""
import argparse
import os
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tablet-lifecycle"))
from run import cached_paths, invoke
from run_hand import HOST

DEVICE = """
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
public class TabletDevice {
    public record Grip(boolean right, float swing, float equip, int light) {}
    public record Portrait(int row, int column) {}
    public record Held(Grip grip, float progress, int width, int height, LocalPlayer player,
                       ItemStack stack, InteractionHand hand, int slot, long time) {}
    public static Held saved;
    public static Grip painted;
    public static Portrait face;
    public static int visits;
    public static int draws;
    public static float progress;
    public static boolean rightSide(LocalPlayer player, InteractionHand hand) {
        return (player.getMainArm() == HumanoidArm.RIGHT) == (hand == InteractionHand.MAIN_HAND);
    }
    public static void forgetHeld() { saved = null; }
    public static void rememberHeld(Held held) { saved = held; }
    public static int chooseVisit() { visits++; return 4; }
    public static Portrait visit(int row, int column) { return column < 0 ? null : new Portrait(row, column); }
    public static void drawHandling(GuiGraphics g, int w, int h, LocalPlayer p, Grip grip,
                                    float value, Portrait portrait) {
        draws++; painted = grip; progress = value; face = portrait;
    }
}
"""


def stubs(directory):
    bodies = dict(HOST)
    player = "net.minecraft.client.player.LocalPlayer"
    bodies[player] = bodies[player].rsplit("}", 1)[0] + """
        public boolean sneak;
        public boolean isShiftKeyDown(){return sneak;}
    }"""
    bodies["dev.anya.storage.appearance.TabletDevice"] = DEVICE
    result = []
    for name, body in bodies.items():
        package, simple = name.rsplit(".", 1)
        path = directory / package.replace(".", "/") / (simple + ".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package " + package + ";\n" + body, encoding="utf-8")
        result.append(path)
    return result


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
            production = ("HeldTablet", "TabletIdle", "TabletLift", "TabletHandlingPose", "TabletArmRenderer",
                          "TabletDevice", "GuardianBlink")
            invoke(options.jdk, "javac", ["--release", "21", "-proc:none", "-classpath", os.pathsep.join((app, libs)),
                   "-d", compiled, *(src / (name + ".java") for name in production)], scratch)
            app = os.pathsep.join((compiled.as_posix(), app))
        cp = os.pathsep.join((host.as_posix(), app, libs))
        invoke(options.jdk, "javac", ["--release", "21", "-proc:none", "-classpath", cp, "-d", host,
               *stubs(scratch / "stubs"), HERE / "HeldUseCheck.java",
               HERE.parent / "tablet-lifecycle/TabletIdleCheck.java"], scratch)
        invoke(options.jdk, "java", ["-classpath", cp, "dev.anya.storage.appearance.TabletIdleCheck"], scratch)
        invoke(options.jdk, "java", ["-Djava.awt.headless=true", "-Danyastorage.tabletIntro=true",
               "-classpath", cp, "HeldUseCheck"], scratch)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--support-jar", type=Path, required=True)
    parser.add_argument("--packaged", action="store_true")
    run(parser.parse_args())

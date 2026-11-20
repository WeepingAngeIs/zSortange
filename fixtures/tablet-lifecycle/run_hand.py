"""Run the real first-person callbacks and NeoForge event objects against an in-memory desktop."""
import argparse
import os
from pathlib import Path
import tempfile

from run import cached_paths, invoke

HERE = Path(__file__).resolve().parent
SRC = HERE.parents[1] / "src/main/java/dev/anya/storage/appearance"
# The boundary supplies hand identity, camera/GUI state and a recording screen-space painter.
# The production event callbacks, idle state and actual NeoForge event classes run unmodified.
HOST = {
    "net.minecraft.Util": "public class Util { public static long now; public static long getMillis(){return now;} }",
    "dev.anya.storage.appearance.TabletWarningSound": """public class TabletWarningSound {
        public static int plays;
        public static void play(){ plays++; }
    }""",
    "net.minecraft.util.Mth": """public class Mth {
        public static final float PI=(float)Math.PI;
        public static float sin(float x){return (float)Math.sin(x);}
        public static float cos(float x){return (float)Math.cos(x);}
        public static float lerp(float t,float a,float b){return a+(b-a)*t;}
        public static float wrapDegrees(float x){
            x %= 360f;
            if (x >= 180f) x -= 360f;
            if (x < -180f) x += 360f;
            return x;
        }
        public static float sqrt(float x){return (float)Math.sqrt(x);}
        public static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    }""",
    "net.minecraft.world.item.Item": "public class Item {}",
    "dev.anya.storage.StorageItem": """public class StorageItem extends net.minecraft.world.item.Item {
        public static boolean isCreativeTablet(net.minecraft.world.item.ItemStack stack){return stack.is(Content.CREATIVE_TABLET.get());}
        public static boolean isTablet(net.minecraft.world.item.ItemStack stack){return stack.is(Content.CUPID_TABLET.get()) || isCreativeTablet(stack);}
    }""",
    "net.minecraft.world.item.ItemStack": """public class ItemStack {
        public static final ItemStack EMPTY=new ItemStack(null); public final Item item; public ItemStack(Item item){this.item=item;}
        public boolean is(Item candidate){return item==candidate;}
        public static boolean matches(ItemStack a, ItemStack b){return a.item == b.item;}
    }""",
    "net.neoforged.neoforge.registries.DeferredItem": """public class DeferredItem<T> {
        private final T value; public DeferredItem(T value){this.value=value;} public T get(){return value;}
    }""",
    "dev.anya.storage.Content": """public class Content {
        public static final net.neoforged.neoforge.registries.DeferredItem<StorageItem> REMOTE =
            new net.neoforged.neoforge.registries.DeferredItem<>(new StorageItem());
        public static final net.neoforged.neoforge.registries.DeferredItem<StorageItem> CREATIVE_TABLET =
            new net.neoforged.neoforge.registries.DeferredItem<>(new StorageItem());
        public static final net.neoforged.neoforge.registries.DeferredItem<StorageItem> CUPID_TABLET =
            new net.neoforged.neoforge.registries.DeferredItem<>(new StorageItem());
    }""",
    "net.minecraft.world.entity.Entity": "public class Entity {}",
    "net.minecraft.world.entity.HumanoidArm": "public enum HumanoidArm { LEFT, RIGHT }",
    "net.minecraft.client.KeyMapping": "public class KeyMapping { public boolean down; public boolean isDown(){return down;} }",
    "net.minecraft.client.CameraType": """public enum CameraType { FIRST_PERSON, THIRD_PERSON_BACK;
        public boolean isFirstPerson(){return this==FIRST_PERSON;}
    }""",
    "net.minecraft.client.OptionInstance": """public class OptionInstance<T> {
        private T value;
        public OptionInstance(T value){this.value=value;}
        public T get(){return value;}
        public void set(T value){this.value=value;}
    }""",
    "net.minecraft.client.Options": """public class Options {
        public boolean hideGui; public final KeyMapping keyUse = new KeyMapping(), keyAttack = new KeyMapping();
        public CameraType camera = CameraType.FIRST_PERSON; public CameraType getCameraType(){return camera;}
        private final OptionInstance<Boolean> bob = new OptionInstance<>(true);
        public OptionInstance<Boolean> bobView(){return bob;}
    }""",
    "net.minecraft.world.entity.player.Inventory": """public class Inventory {
        public int selected;
        public net.minecraft.client.player.LocalPlayer player;
        public final net.minecraft.world.item.ItemStack[] slots=new net.minecraft.world.item.ItemStack[36];
        public net.minecraft.world.item.ItemStack getItem(int slot){
            var stack=selected==slot ? player.main : slots[slot];
            return stack == null ? net.minecraft.world.item.ItemStack.EMPTY : stack;
        }
    }""",
    "net.minecraft.client.player.LocalPlayer": """public class LocalPlayer extends net.minecraft.world.entity.Entity {
        public int tickCount; public net.minecraft.world.item.ItemStack main,off; public boolean using;
        public float xBob,xBobO,yBob,yBobO,walkDist,walkDistO,bob,oBob;
        public float viewXRot,viewYRot;
        public float getViewXRot(float tick){return viewXRot;}
        public float getViewYRot(float tick){return viewYRot;}
        public net.minecraft.world.entity.HumanoidArm arm=net.minecraft.world.entity.HumanoidArm.RIGHT;
        public final net.minecraft.world.entity.player.Inventory inv=new net.minecraft.world.entity.player.Inventory();
        public net.minecraft.world.entity.player.Inventory getInventory(){inv.player=this;return inv;}
        public net.minecraft.world.item.ItemStack getMainHandItem(){return main;}
        public net.minecraft.world.item.ItemStack getOffhandItem(){return off==null ? net.minecraft.world.item.ItemStack.EMPTY : off;}
        public net.minecraft.world.item.ItemStack getItemInHand(net.minecraft.world.InteractionHand hand){
            return hand == net.minecraft.world.InteractionHand.MAIN_HAND ? main : off;
        }
        public net.minecraft.world.entity.HumanoidArm getMainArm(){return arm;}
        public boolean sneak;
        public boolean isShiftKeyDown(){return sneak;}
        public boolean isAlive(){return true;} public boolean isSpectator(){return false;}
        public boolean isScoping(){return false;} public boolean isUsingItem(){return using;}
    }""",
    "net.minecraft.client.gui.screens.Screen": "public class Screen {}",
    "net.minecraft.client.gui.screens.PauseScreen": "public class PauseScreen extends Screen {}",
    "dev.anya.storage.Client": "public class Client { public static class Screen extends net.minecraft.client.gui.screens.Screen {} }",
    "com.mojang.blaze3d.platform.Window": """public class Window {
        public int getGuiScaledWidth(){return 640;} public int getGuiScaledHeight(){return 360;}
    }""",
    "net.minecraft.client.Minecraft": """public class Minecraft {
        private static final Minecraft INSTANCE=new Minecraft(); public static Minecraft getInstance(){return INSTANCE;}
        public final Options options = new Options(); public net.minecraft.client.player.LocalPlayer player;
        public net.minecraft.client.gui.screens.Screen screen; public net.minecraft.world.entity.Entity camera;
        public net.minecraft.world.entity.Entity getCameraEntity(){return camera;}
        public com.mojang.blaze3d.platform.Window getWindow(){return new com.mojang.blaze3d.platform.Window();}
    }""",
    "net.minecraft.client.gui.GuiGraphics": """public class GuiGraphics {
        public int guiWidth(){return 640;} public int guiHeight(){return 360;}
    }""",
    "dev.anya.storage.appearance.TabletDevice": """public class TabletDevice {
        public record Grip(boolean right, float swing, float equip, int light, TabletHandMotion motion) {
            public Grip(boolean right, float swing, float equip, int light) {
                this(right, swing, equip, light, TabletHandMotion.STILL);
            }
        }
        public record Portrait(int row, int column) {}
        public record Held(Grip grip, float progress, int width, int height,
                net.minecraft.client.player.LocalPlayer player, net.minecraft.world.item.ItemStack stack,
                net.minecraft.world.InteractionHand hand, int slot, long time, Portrait portrait) {
            boolean stillHeld(net.minecraft.client.player.LocalPlayer by) {
                return by == player && by.getItemInHand(hand) == stack && by.getInventory().selected == slot
                        && rightSide(by, hand) == grip.right();
            }
            Held afterMenuSync(net.minecraft.client.player.LocalPlayer by) {
                if (stillHeld(by)) return this;
                if (by != player || by.getInventory().selected != slot || rightSide(by, hand) != grip.right()) return null;
                var current = by.getItemInHand(hand);
                return net.minecraft.world.item.ItemStack.matches(stack, current)
                        ? new Held(grip, progress, width, height, by, current, hand, slot, time, portrait) : null;
            }
        }
        public static Held saved, closing;
        public static Grip painted;
        public static Portrait face;
        public static boolean liveAtlas;
        public static int visits, draws, peeks;
        public static float progress;
        public static boolean enabled() {
            return Boolean.parseBoolean(System.getProperty("anyastorage.tabletIntro", "true"));
        }
        public static boolean rightSide(net.minecraft.client.player.LocalPlayer player, net.minecraft.world.InteractionHand hand) {
            return (player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT)
                    == (hand == net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        public static void forgetHeld() { saved = null; closing = null; }
        public static void forgetWorldFrame() { saved = null; }
        public static void lowerFrom(Held frame) { closing = frame; }
        public static boolean hasClosing() { return closing != null; }
        public static Held takeClosing() { var frame = closing; closing = null; return frame; }
        public static Held peekHeld() { return saved; }
        public static void rememberHeld(Held held) { saved = held; }
        public static Portrait physicalFace(TabletIdle.Face face, boolean creative) { return new Portrait(creative ? -2 : -1, face.ordinal()); }
        public static TabletIdle.Face faceOf(Portrait face) {
            return face == null ? TabletIdle.Face.DARK : TabletIdle.Face.values()[face.column()];
        }
        public static void drawHandling(net.minecraft.client.gui.GuiGraphics graphics, int width, int height,
                net.minecraft.client.player.LocalPlayer player, Grip grip, float value, Portrait portrait, boolean atlas, boolean creative) {
            draws++;
            if (portrait != null) peeks++;
            painted = grip;
            progress = value;
            face = portrait;
            liveAtlas = atlas;
        }
    }""",
}


def sources(path):
    result = []
    for name, code in HOST.items():
        package, java_class = name.rsplit(".", 1)
        source = path / package.replace(".", "/") / (java_class + ".java")
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("package " + package + ";\n" + code + "\n", encoding="utf-8")
        result.append(source)
    return result


def run(options):
    with tempfile.TemporaryDirectory(prefix="anya-hand-events-") as directory:
        scratch = Path(directory)
        host = scratch / "desktop"
        host.mkdir()
        app = options.support_jar.resolve().as_posix()
        libraries = cached_paths(options.classpath_file)
        if not options.packaged:
            compiled = scratch / "candidate"
            compiled.mkdir()
            invoke(options.jdk, "javac", ["--release", "21", "-proc:none", "-d", compiled,
                   "-classpath", os.pathsep.join([app,libraries]), *[SRC / (name + ".java") for name in
                   ("HeldTablet","TabletFaces","TabletIdle","TabletLift","TabletHandMotion","TabletHandlingPose","TabletArmRenderer",
                    "TabletDevice","GuardianBlink","TabletThreat","TabletWarningSound")]], scratch)
            app = os.pathsep.join([compiled.as_posix(), app])
        cp = os.pathsep.join([host.as_posix(), app, libraries])
        invoke(options.jdk, "javac", ["--release","21","-proc:none","-d",host,"-classpath",cp,
               *sources(scratch / "host-src"),HERE / "TabletHandCheck.java", HERE / "TabletIdleCheck.java"],scratch)
        invoke(options.jdk, "java", ["-classpath",cp,"dev.anya.storage.appearance.TabletIdleCheck"],scratch)
        for enabled in ("false","true"):
            invoke(options.jdk, "java", ["-Djava.awt.headless=true","-Danyastorage.tabletIntro="+enabled,
                   "-classpath",cp,"TabletHandCheck",enabled],scratch)


if __name__ == "__main__":
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--jdk",required=True,type=lambda p: Path(p).resolve())
    args.add_argument("--support-jar",required=True,type=Path)
    args.add_argument("--classpath-file",required=True,type=Path)
    args.add_argument("--packaged",action="store_true")
    run(args.parse_args())

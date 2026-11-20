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
    "net.minecraft.util.Mth": """public class Mth {
        public static final float PI=(float)Math.PI;
        public static float sin(float x){return (float)Math.sin(x);}
        public static float sqrt(float x){return (float)Math.sqrt(x);}
        public static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    }""",
    "net.minecraft.world.item.Item": "public class Item {}",
    "dev.anya.storage.StorageItem": "public class StorageItem extends net.minecraft.world.item.Item {}",
    "net.minecraft.world.item.ItemStack": """public class ItemStack {
        public final Item item; public ItemStack(Item item){this.item=item;}
        public boolean is(Item candidate){return item==candidate;}
    }""",
    "net.neoforged.neoforge.registries.DeferredItem": """public class DeferredItem<T> {
        private final T value; public DeferredItem(T value){this.value=value;} public T get(){return value;}
    }""",
    "dev.anya.storage.Content": """public class Content {
        public static final net.neoforged.neoforge.registries.DeferredItem<StorageItem> REMOTE =
            new net.neoforged.neoforge.registries.DeferredItem<>(new StorageItem());
    }""",
    "net.minecraft.world.entity.Entity": "public class Entity {}",
    "net.minecraft.world.entity.HumanoidArm": "public enum HumanoidArm { LEFT, RIGHT }",
    "net.minecraft.client.KeyMapping": "public class KeyMapping { public boolean down; public boolean isDown(){return down;} }",
    "net.minecraft.client.CameraType": """public enum CameraType { FIRST_PERSON, THIRD_PERSON_BACK;
        public boolean isFirstPerson(){return this==FIRST_PERSON;}
    }""",
    "net.minecraft.client.Options": """public class Options {
        public boolean hideGui; public final KeyMapping keyUse = new KeyMapping(), keyAttack = new KeyMapping();
        public CameraType camera = CameraType.FIRST_PERSON; public CameraType getCameraType(){return camera;}
    }""",
    "net.minecraft.world.entity.player.Inventory": "public class Inventory { public int selected; }",
    "net.minecraft.client.player.LocalPlayer": """public class LocalPlayer extends net.minecraft.world.entity.Entity {
        public net.minecraft.world.item.ItemStack main,off; public boolean using;
        public net.minecraft.world.entity.HumanoidArm arm=net.minecraft.world.entity.HumanoidArm.RIGHT;
        public final net.minecraft.world.entity.player.Inventory inv=new net.minecraft.world.entity.player.Inventory();
        public net.minecraft.world.entity.player.Inventory getInventory(){return inv;}
        public net.minecraft.world.item.ItemStack getMainHandItem(){return main;}
        public net.minecraft.world.item.ItemStack getOffhandItem(){return off;}
        public net.minecraft.world.entity.HumanoidArm getMainArm(){return arm;}
        public boolean isAlive(){return true;} public boolean isSpectator(){return false;}
        public boolean isScoping(){return false;} public boolean isUsingItem(){return using;}
    }""",
    "net.minecraft.client.gui.screens.Screen": "public class Screen {}",
    "dev.anya.storage.Client": "public class Client { public static class Screen extends net.minecraft.client.gui.screens.Screen {} }",
    "net.minecraft.client.Minecraft": """public class Minecraft {
        private static final Minecraft INSTANCE=new Minecraft(); public static Minecraft getInstance(){return INSTANCE;}
        public final Options options = new Options(); public net.minecraft.client.player.LocalPlayer player;
        public net.minecraft.client.gui.screens.Screen screen; public net.minecraft.world.entity.Entity camera;
        public net.minecraft.world.entity.Entity getCameraEntity(){return camera;}
    }""",
    "net.minecraft.client.gui.GuiGraphics": """public class GuiGraphics {
        public int guiWidth(){return 640;} public int guiHeight(){return 360;}
    }""",
    "dev.anya.storage.appearance.TabletDevice": """public class TabletDevice {
        public static final int WIDTH=432,HEIGHT=304;
        public record Bounds(float x,float y,float scale){}
        public static int dark,peek; public static Bounds last; public static Object lastStack;
        public static void drawDark(net.minecraft.client.gui.GuiGraphics g,Bounds b){dark++;}
        public static void drawIdle(net.minecraft.client.gui.GuiGraphics g,Bounds b){peek++;}
        public static void rememberHeld(Bounds b,int w,int h,Object p,Object stack,long t){last=b;lastStack=stack;}
        public static void forgetHeld(){last=null;lastStack=null;}
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
                   ("HeldTablet","TabletIdle","TabletDevice","GuardianBlink")]], scratch)
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

"""Extend the existing bounded screen host with recording sinks for the handling pass."""

from pathlib import Path
import runpy


def materialize(root):
    previous = Path(__file__).resolve().parents[1] / "tablet-lifecycle" / "desktop_host.py"
    sources = runpy.run_path(str(previous))["HOST"].copy()

    def extend(name, declarations):
        sources[name] = sources[name].rsplit("}", 1)[0] + declarations + "\n}"

    sources["net.minecraft.world.entity.Entity"] = "public class Entity {}"
    player = "net.minecraft.world.entity.player.Player"
    sources[player] = sources[player].replace("public class Player {", "public class Player extends net.minecraft.world.entity.Entity {")
    extend("net.minecraft.world.entity.player.Inventory", "public int selected;")
    extend("net.minecraft.client.Minecraft", """
        private static final Minecraft client = new Minecraft();
        public static Minecraft getInstance() { return client; }
        public net.minecraft.world.entity.Entity getCameraEntity() { return player; }
        public boolean expressionArt = true;
        public boolean tumbleArt = true;
        public int artLookups;
        public net.minecraft.server.packs.resources.ResourceManager getResourceManager() {
            return id -> {
                boolean present = switch (id.getPath()) {
                    case "textures/gui/tablet_expressions.png" -> expressionArt;
                    case "textures/gui/tablet_tumble.png" -> tumbleArt;
                    default -> throw new AssertionError("Wrong atlas");
                };
                artLookups++;
                return present ? java.util.Optional.of(new net.minecraft.server.packs.resources.Resource())
                        : java.util.Optional.empty();
            };
        }
    """)
    sources["net.minecraft.server.packs.resources.Resource"] = "public class Resource {}"
    sources["net.minecraft.server.packs.resources.ResourceManager"] = """public interface ResourceManager {
        java.util.Optional<Resource> getResource(net.minecraft.resources.ResourceLocation path);
    }"""
    extend("net.minecraft.client.Options", """
        public boolean hideGui;
        public net.minecraft.client.CameraType getCameraType() {return net.minecraft.client.CameraType.FIRST_PERSON;}
    """)
    sources["net.minecraft.client.resources.PlayerSkin"] = """public class PlayerSkin {
        public enum Model { SLIM, WIDE }
        public Model model(){return Model.WIDE;}
    }"""
    extend("net.minecraft.client.player.LocalPlayer", """
        public final net.minecraft.world.entity.player.Inventory slots = new net.minecraft.world.entity.player.Inventory(this);
        public boolean isScoping(){return false;} public boolean isAlive(){return true;}
        public boolean isInvisible(){return false;} public boolean isSpectator(){return false;}
        public net.minecraft.world.entity.player.Inventory getInventory(){return slots;}
        public net.minecraft.world.entity.HumanoidArm getMainArm(){return net.minecraft.world.entity.HumanoidArm.RIGHT;}
        public net.minecraft.client.resources.PlayerSkin getSkin(){return new net.minecraft.client.resources.PlayerSkin();}
        public net.minecraft.world.item.ItemStack getItemInHand(net.minecraft.world.InteractionHand hand){
            return hand == net.minecraft.world.InteractionHand.MAIN_HAND ? getMainHandItem() : getOffhandItem();
        }
    """)
    gui = "net.minecraft.client.gui.GuiGraphics"
    sources[gui] = sources[gui].replace("shells++;", """
        shells++;
        events.add("case");
        casePose = new org.joml.Matrix4f(pose.last().pose());
    """).replace("atlasDraws++;", """
        atlasDraws++;
        events.add("atlas");
        casePose = new org.joml.Matrix4f(pose.last().pose());
    """).replace("throw new StoragePaint();", 'events.add("inventory"); throw new StoragePaint();').replace("blits++;", """
        blits++;
        if (image.getPath().endsWith("tablet_expressions.png")) {
            if (tw != 2016 || th != 896 || width != 224 || height != 128 || u % 224 != 0 || v % 128 != 0)
                throw new AssertionError("Incorrect expression cell");
            expressionRow = (int)v / 128;
            expressionColumn = (int)u / 224;
        }
    """)
    extend(gui, """
        public int expressionRow = -1, expressionColumn = -1;
        public final java.util.List<String> events = new java.util.ArrayList<>();
        public org.joml.Matrix4f casePose, armPose;
    """)
    sources["dev.anya.storage.appearance.TabletArmRenderer"] = """public class TabletArmRenderer {
        public static void draw(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.player.LocalPlayer p,
                boolean right, int light, org.joml.Matrix4f pose) {
            g.events.add("arm");
            g.armPose = new org.joml.Matrix4f(g.pose().last().pose()).mul(pose);
        }
    }"""
    result = []
    for name, code in sources.items():
        package, simple = name.rsplit(".", 1)
        path = root / package.replace(".", "/") / (simple + ".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package " + package + ";\n" + code, encoding="utf-8")
        result.append(path)
    return result

"""Minimal desktop boundary for the unchanged, compiled Client.Screen bytecode.

These sources are compiled separately after the production sources. They exist only
inside the fixture's temporary JVM class path. No package is installed or delivered.
"""

HOST = {
    "net.minecraft.client.Minecraft": """
        public class Minecraft {
            public net.minecraft.client.player.LocalPlayer player;
            public net.minecraft.client.gui.screens.Screen screen;
            public final Options options = new Options();
            public int screenChanges;
            public boolean missingItemFace;
            public java.util.function.Function<net.minecraft.resources.ResourceLocation,
                    net.minecraft.client.renderer.texture.TextureAtlasSprite> getTextureAtlas(net.minecraft.resources.ResourceLocation id) {
                if (!id.equals(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS))
                    throw new AssertionError("Tablet looked up a non-item atlas");
                return name -> new net.minecraft.client.renderer.texture.TextureAtlasSprite(
                        missingItemFace ? net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation() : name);
            }
            public void setScreen(net.minecraft.client.gui.screens.Screen next) {
                screenChanges++;
                if (screen != null) screen.removed();
                screen = next;
            }
        }
    """,
    "net.minecraft.client.renderer.texture.TextureAtlas": """public class TextureAtlas {
        public static final net.minecraft.resources.ResourceLocation LOCATION_BLOCKS =
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/atlas/blocks.png");
    }""",
    "net.minecraft.client.renderer.texture.MissingTextureAtlasSprite": """public class MissingTextureAtlasSprite {
        public static net.minecraft.resources.ResourceLocation getLocation(){
            return net.minecraft.resources.ResourceLocation.withDefaultNamespace("missingno");
        }
    }""",
    "net.minecraft.client.renderer.texture.SpriteContents": """public record SpriteContents(net.minecraft.resources.ResourceLocation name) {}""",
    "net.minecraft.client.renderer.texture.TextureAtlasSprite": """public class TextureAtlasSprite {
        private final SpriteContents contents;
        public TextureAtlasSprite(net.minecraft.resources.ResourceLocation name){ contents = new SpriteContents(name); }
        public SpriteContents contents(){ return contents; }
    }""",
    "net.minecraft.client.player.LocalPlayer": """
        public class LocalPlayer extends net.minecraft.world.entity.player.Player {}
    """,
    "net.minecraft.world.entity.player.Player": """
        public class Player {
            public net.minecraft.world.inventory.AbstractContainerMenu containerMenu;
            private final net.minecraft.world.item.ItemStack main = new net.minecraft.world.item.ItemStack();
            private final net.minecraft.world.item.ItemStack off = new net.minecraft.world.item.ItemStack();
            public net.minecraft.world.item.ItemStack getMainHandItem() { return main; }
            public net.minecraft.world.item.ItemStack getOffhandItem() { return off; }
        }
    """,
    "net.minecraft.world.item.ItemStack": """
        /** The handoff tests object identity only; no item or registry is initialized. */
        public class ItemStack {}
    """,
    "net.minecraft.world.entity.player.Inventory": """
        public class Inventory {
            public final Player player;
            public Inventory(Player player) { this.player = player; }
        }
    """,
    "net.minecraft.client.Options": """
        public class Options { public final KeyMapping keyInventory = new KeyMapping(); }
    """,
    "net.minecraft.client.KeyMapping": """
        public class KeyMapping {
            public boolean matches(int key, int scan) { return key == 69; }
            public boolean matchesMouse(int button) { return button == 4; }
        }
    """,
    "net.minecraft.client.gui.screens.Screen": """
        public class Screen {
            protected net.minecraft.client.Minecraft minecraft;
            protected net.minecraft.client.gui.Font font;
            protected net.minecraft.network.chat.Component title;
            public int width, height;
            public void init(net.minecraft.client.Minecraft client, int w, int h) {
                minecraft = client; width = w; height = h; init();
            }
            protected void init() {}
            public void onClose() {}
            public void removed() {}
            public static boolean hasShiftDown() { return false; }
        }
    """,
    "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen": """
        public class AbstractContainerScreen<T extends net.minecraft.world.inventory.AbstractContainerMenu>
                extends net.minecraft.client.gui.screens.Screen {
            public static int closes, removals;
            protected final T menu;
            public AbstractContainerScreen(T menu, net.minecraft.world.entity.player.Inventory inventory,
                    net.minecraft.network.chat.Component title) { this.menu = menu; this.title = title; }
            // Record vanilla lifecycle calls at the boundary. Simulate setScreen(null) explicitly in the test.
            public void onClose() { closes++; }
            public void removed() { removals++; }
            protected void containerTick() {}
            public void fixtureTick() { containerTick(); }
        }
    """,
    "net.minecraft.world.inventory.AbstractContainerMenu": """
        public class AbstractContainerMenu { public int containerId; }
    """,
    "dev.anya.storage.StoreMenu": """
        public class StoreMenu extends net.minecraft.world.inventory.AbstractContainerMenu {
            public boolean storage = true;
            public boolean openingRule = true, openingHit = true, remote = true;
            public boolean cameo, privateStorage;
            public boolean backpack = true;
            public boolean hasTabletOpeningRule() { return openingRule; }
            public boolean showsTabletIntro() { return openingHit; }
            public boolean remoteOpening() { return remote; }
            public boolean showsStorageCameo() { return cameo; }
            public boolean privateView() { return privateStorage; }
            public int version = 1, rows = 5;
            public StoreMenu(int id) { containerId = id; }
            public boolean storageView() { return storage; }
            public boolean accessView() { return false; }
            public boolean backpackView() { return backpack; }
            public int revision() { return version; }
            public int session() { return 71; }
            public int visibleRows() { return rows; }
            public int resultCount() { return 54; }
            public int firstSlot() { return 0; }
            public int shownSlots() { return 45; }
        }
    """,
    "net.minecraft.client.gui.components.EditBox": """
        public class EditBox {
            public static int typed;
            private String value = "";
            private boolean focus;
            private final int x,y,w,h;
            public EditBox(net.minecraft.client.gui.Font font, int x, int y, int w, int h,
                    net.minecraft.network.chat.Component label) {
                this.x=x; this.y=y; this.w=w; this.h=h;
            }
            public void setMaxLength(int n) {}
            public void setValue(String s) { value=s; }
            public String getValue() { return value; }
            public void setHint(net.minecraft.network.chat.Component hint) {}
            public void setResponder(java.util.function.Consumer<String> listener) {}
            public boolean isFocused() { return focus; }
            public void setFocused(boolean f) { focus=f; }
            public boolean mouseClicked(double mx,double my,int button) {return mx>=x&&my>=y&&mx<x+w&&my<y+h;}
            public boolean charTyped(char ch,int mods) {typed++; value+=ch; return true;}
        }
    """,
    "net.minecraft.client.gui.GuiGraphics": """
        public class GuiGraphics {
            public static class StoragePaint extends RuntimeException {}
            public int fills, blits, shells, atlasDraws, row = -1;
            public net.minecraft.resources.ResourceLocation atlasSprite;
            public float storageX, storageY, storageScale;
            public float shellX, shellY, shellScale;
            private final com.mojang.blaze3d.vertex.PoseStack pose = new com.mojang.blaze3d.vertex.PoseStack();
            public com.mojang.blaze3d.vertex.PoseStack pose() { return pose; }
            public void fill(int x,int y,int right,int bottom,int z,int color) { fills++; }
            public void fill(int x,int y,int right,int bottom,int color) {
                fills++;
                // The existing menu panel is 382 logical px wide. End before GPU text/slot drawing.
                if (color == 0xff111823 && x == 0 && y == 0 && right == 382) {
                    var matrix = pose.last().pose();
                    storageX = matrix.m30(); storageY = matrix.m31(); storageScale = matrix.m00();
                    throw new StoragePaint();
                }
            }
            public void blit(int x,int y,int z,int width,int height,net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
                atlasDraws++;
                atlasSprite = sprite.contents().name();
            }
            public void blit(net.minecraft.resources.ResourceLocation image,int x,int y,float u,float v,
                    int width,int height,int tw,int th) {
                blits++;
                if (image.getPath().endsWith("tablet_device.png")) {
                    shells++;
                    var matrix = pose.last().pose();
                    shellX = matrix.m30(); shellY = matrix.m31(); shellScale = matrix.m00();
                }
                if (image.getPath().endsWith("tablet_guardian.png")) row=(int)v/144;
            }
        }
    """,
    "dev.anya.storage.appearance.ClientInteriors": """
        public class ClientInteriors {
            public static dev.anya.storage.ExpansionPackets.Look current;
            public static int clears, drops, ticks;
            public static void clear(){clears++;}
            public static void tick(){ticks++;}
            public static void drop(java.util.List<java.nio.file.Path> paths){drops++;}
        }
    """,
    "net.neoforged.neoforge.network.PacketDistributor": """
        public class PacketDistributor {
            public static final java.util.List<net.minecraft.network.protocol.common.custom.CustomPacketPayload>
                    sent = new java.util.ArrayList<>();
            public static void sendToServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload first,
                    net.minecraft.network.protocol.common.custom.CustomPacketPayload... others) {
                sent.add(first); sent.addAll(java.util.List.of(others));
            }
        }
    """,
}


def materialize(root):
    result = []
    for qualified, content in HOST.items():
        package, name = qualified.rsplit(".", 1)
        path = root / package.replace(".", "/") / (name + ".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package " + package + ";\n" + content.strip() + "\n", encoding="utf-8")
        result.append(path)
    return result

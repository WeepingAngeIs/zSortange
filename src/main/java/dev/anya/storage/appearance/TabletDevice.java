package dev.anya.storage.appearance;

import dev.anya.storage.AnyaStorage;
import dev.anya.storage.ui.StorageGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;


/** Shared hardware, aperture and placement for the held Cupid Tablet and its storage screen. */
public final class TabletDevice {
    public static final int WIDTH = TabletHandlingPose.WIDTH, HEIGHT = TabletHandlingPose.HEIGHT;
    private static final int SCREEN_X = 16, SCREEN_Y = 20, SCREEN_W = 400, SCREEN_H = 268;
    private static final ResourceLocation SHELL = gui("tablet_device");
    private static final ResourceLocation CREATIVE_SHELL = gui("creative_tablet_device");
    private static final int CELL_W = 224, CELL_H = 128;
    // These also name the sprite overrides in the physical item model. The detailed hand
    // samples the exact baked sprite rather than running a second atlas animation.
    private static final Portrait[] PHYSICAL = itemFaces("cupid_tablet");
    private static final Portrait[] CREATIVE = itemFaces("creative_tablet");
    private static Portrait[] itemFaces(String item) {
        return java.util.Arrays.stream(TabletIdle.Face.values()).map(face -> new Portrait(
                ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, "item/" + item + "_face_" + face.sprite),
                0, 0, 0, 0, face)).toArray(Portrait[]::new);
    }
    private static final ResourceLocation ORIGINAL = gui("tablet_guardian");
    private static final Portrait OPEN = new Portrait(ORIGINAL, 8, 8, 240, 432);
    private static final Portrait HALF = new Portrait(ORIGINAL, 8, 152, 240, 432);
    private static final Portrait CLOSED = new Portrait(ORIGINAL, 8, 296, 240, 432);
    private static final Portrait GAZE = new Portrait(gui("tablet_gaze_left"), 8, 8, 240, 144);
    private static final ResourceLocation TWIN_TAILS = gui("tablet_twin_tails");
    private static final Portrait[][] HEADS = headPortraits();

    private static Portrait[][] headPortraits() {
        Portrait[][] heads = new Portrait[7][9];
        for (int row = 0; row < heads.length; row++) {
            for (int column = 0; column < heads[row].length; column++) {
                heads[row][column] = new Portrait(TWIN_TAILS, column * CELL_W, row * CELL_H, 9 * CELL_W, 7 * CELL_H);
            }
        }
        return heads;
    }

    public record Bounds(float x, float y, float scale) {}
    record Portrait(ResourceLocation image, int u, int v, int atlasWidth, int atlasHeight, TabletIdle.Face itemFace) {
        Portrait(ResourceLocation image, int u, int v, int atlasWidth, int atlasHeight) {
            this(image, u, v, atlasWidth, atlasHeight, null);
        }
    }
    record Grip(boolean right, float swing, float equip, int light, TabletHandMotion motion) {
        Grip(boolean right, float swing, float equip, int light) {
            this(right, swing, equip, light, TabletHandMotion.STILL);
        }
    }
    record Held(Grip grip, float progress, int width, int height, LocalPlayer player, ItemStack stack,
                InteractionHand hand, int slot, long time, Portrait portrait) {
        Held(Grip grip, float progress, int width, int height, LocalPlayer player, ItemStack stack,
             InteractionHand hand, int slot, long time) {
            this(grip, progress, width, height, player, stack, hand, slot, time, null);
        }
        boolean stillHeld(LocalPlayer by) {
            return by == player && by.getItemInHand(hand) == stack && by.getInventory().selected == slot
                    && rightSide(by, hand) == grip.right();
        }
        /** Full menu sync replaces player slots with decoded ItemStacks. Adopt the copy only at the handoff. */
        Held afterMenuSync(LocalPlayer by) {
            if (stillHeld(by)) return this;
            if (by != player || by.getInventory().selected != slot || rightSide(by, hand) != grip.right()) return null;
            ItemStack synced = by.getItemInHand(hand);
            return ItemStack.matches(stack, synced)
                    ? new Held(grip, progress, width, height, by, synced, hand, slot, time, portrait) : null;
        }
    }
    private static Held lastHeld, closing;

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("anyastorage.tabletIntro", "true"));
    }

    private static ResourceLocation gui(String name) {
        return ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, "textures/gui/" + name + ".png");
    }

    static Portrait opening(GuardianBlink blink) {
        if (blink.column() < 0) return null;
        if (blink.sequence() >= 0 && blink.sequence() < HEADS.length) return HEADS[blink.sequence()][blink.column()];
        return switch (blink.frame()) {
            case OPEN -> OPEN;
            case HALF -> HALF;
            case CLOSED -> CLOSED;
            default -> null;
        };
    }
    static Portrait physicalFace(TabletIdle.Face face, boolean creative) {
        return (creative ? CREATIVE : PHYSICAL)[face.ordinal()];
    }
    static TabletIdle.Face faceOf(Portrait portrait) {
        if (portrait != null && portrait.image().equals(TWIN_TAILS))
            return TabletIdle.Face.expression(portrait.v() / CELL_H, portrait.u() / CELL_W);
        if (portrait == OPEN) return TabletIdle.Face.OPEN;
        if (portrait == HALF) return TabletIdle.Face.SQUINT;
        if (portrait == CLOSED) return TabletIdle.Face.SHUT;
        if (portrait != null && portrait.itemFace() != null) return portrait.itemFace();
        return TabletIdle.Face.DARK;
    }
    static boolean rightSide(LocalPlayer player, InteractionHand hand) {
        return (player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT) == (hand == InteractionHand.MAIN_HAND);
    }
    static void rememberHeld(Held sample) { lastHeld = sample; }
    static void forgetWorldFrame() { lastHeld = null; }
    public static void forgetHeld() { lastHeld = null; closing = null; }
    static void lowerFrom(Held sample) { closing = sample; }
    static boolean hasClosing() { return closing != null; }
    static Held takeClosing() {
        var sample = closing;
        closing = null;
        return sample;
    }
    static Held peekHeld() { return lastHeld; }
    static Held takeHeld() {
        Held held = lastHeld;
        forgetHeld();
        return held;
    }
    public static Bounds openBounds(int width, int height) {
        float scale = TabletHandlingPose.openScale(width, height);
        return new Bounds((width - WIDTH * scale) / 2, (height - HEIGHT * scale) / 2, scale);
    }
    public static StorageGrid.Layout fitMenu(StorageGrid.Layout grid, int width, int height) {
        Bounds b = openBounds(width, height);
        return new StorageGrid.Layout(grid.rows(), grid.height(), b.scale,
                b.x + (SCREEN_X + (SCREEN_W - StorageGrid.Layout.WIDTH) / 2f) * b.scale,
                b.y + (SCREEN_Y + (SCREEN_H - grid.height()) / 2f) * b.scale);
    }
    public static void drawDark(GuiGraphics graphics, Bounds bounds) { draw(graphics, bounds, null, false, false); }
    public static void drawCameo(GuiGraphics graphics, int x, int y) {
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, 350);
            pose.scale(.3125f, .3125f, 1);
            blitPortrait(graphics, OPEN);
        } finally {
            pose.popPose();
        }
    }
    static void drawHandling(GuiGraphics g, int width, int height, LocalPlayer armOwner, Grip grip,
                             float progress, Portrait portrait, boolean liveAtlas, boolean creative) {
        var pose = g.pose();
        pose.pushPose();
        try {
            grip.motion().apply(pose, width, height, progress);
            if (armOwner != null && !armOwner.isInvisible()) {
                boolean slim = armOwner.getSkin().model() == net.minecraft.client.resources.PlayerSkin.Model.SLIM;
                var arm = TabletHandlingPose.armMatrix(width, height, grip.right, slim, grip.swing, grip.equip, progress);
                TabletArmRenderer.draw(g, armOwner, grip.right, grip.light, arm);
            }
            pose.mulPose(TabletHandlingPose.deviceMatrix(width, height, grip.right, grip.swing, grip.equip, progress));
            var surface = new Bounds(0, 0, 1);
            if (progress < 1) {
                // The silver side lip becomes visible as the face tilts away.
                pose.translate(0, 0, -6);
                draw(g, surface, null, false, creative);
                pose.translate(0, 0, 3);
                draw(g, surface, null, false, creative);
                pose.translate(0, 0, 3);
            }
            draw(g, surface, portrait, liveAtlas, creative);
        } finally {
            pose.popPose();
        }
    }
    static void draw(GuiGraphics g, Bounds b, Portrait portrait, boolean liveAtlas, boolean creative) {
        var pose = g.pose();
        pose.pushPose();
        try {
            pose.translate(b.x, b.y, 0);
            pose.scale(b.scale, b.scale, 1);
            if ((portrait != null && portrait.itemFace() != null) || (liveAtlas && portrait == null)) {
                var face = faceOf(portrait);
                if (drawItemFace(g, physicalFace(face, creative).image())) return;
                // Resource packs can omit a sprite. Keep the corresponding approved GUI face.
                portrait = face.row() >= 0 ? HEADS[face.row()][face.column()] : switch (face) {
                    case SQUINT -> HALF;
                    case SHUT -> CLOSED;
                    case GLANCE -> GAZE;
                    case OPEN -> OPEN;
                    default -> null;
                };
            }
            g.blit(creative ? CREATIVE_SHELL : SHELL, 0, 0, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
            if (portrait != null) {
                float zoom = SCREEN_W / (float) CELL_W;
                pose.translate(SCREEN_X, SCREEN_Y + (SCREEN_H - CELL_H * zoom) / 2f, 0);
                pose.scale(zoom, zoom, 1);
                blitPortrait(g, portrait);
            }
        } finally {
            pose.popPose();
        }
    }
    private static boolean drawItemFace(GuiGraphics graphics, ResourceLocation face) {
        var sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(face);
        if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) return false;
        graphics.blit(0, 0, 0, WIDTH, HEIGHT, sprite);
        return true;
    }
    private static void blitPortrait(GuiGraphics graphics, Portrait portrait) {
        graphics.blit(portrait.image(), 0, 0, portrait.u(), portrait.v(), CELL_W, CELL_H,
                portrait.atlasWidth(), portrait.atlasHeight());
    }
    private TabletDevice() {}
}

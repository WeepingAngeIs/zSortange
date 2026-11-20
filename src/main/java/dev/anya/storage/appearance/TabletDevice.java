package dev.anya.storage.appearance;

import dev.anya.storage.AnyaStorage;
import dev.anya.storage.ui.StorageGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/** Shared hardware, aperture and placement for the held Cupid Tablet and its storage screen. */
public final class TabletDevice {
    public static final int WIDTH = TabletHandlingPose.WIDTH, HEIGHT = TabletHandlingPose.HEIGHT;
    private static final int SCREEN_X = 16, SCREEN_Y = 20, SCREEN_W = 400, SCREEN_H = 268;
    private static final ResourceLocation SHELL = gui("tablet_device");
    private static final ResourceLocation EXPRESSIONS = gui("tablet_expressions");
    private static final int CELL_W = 224, CELL_H = 128, COLUMNS = 9, OPENING_ROWS = 4, IDLE_ROWS = 3;
    private static final int TUMBLE_ROW = OPENING_ROWS + IDLE_ROWS;
    private static final ResourceLocation TUMBLE = gui("tablet_tumble");
    private static final Portrait[] TUMBLE_CELLS = row(TUMBLE, 0, 1);
    private static boolean lastVisitTumbled;
    private static final Rows VISITS = new Rows(OPENING_ROWS, IDLE_ROWS);
    private static final Portrait[][] CELLS = expressionSheet();
    private static final ResourceLocation ORIGINAL = gui("tablet_guardian");
    private static final Portrait OPEN = new Portrait(ORIGINAL, 8, 8, 240, 432);
    private static final Portrait HALF = new Portrait(ORIGINAL, 8, 152, 240, 432);
    private static final Portrait CLOSED = new Portrait(ORIGINAL, 8, 296, 240, 432);
    private static final Portrait GAZE = new Portrait(gui("tablet_gaze_left"), 8, 8, 240, 144);

    public record Bounds(float x, float y, float scale) {}
    record Portrait(ResourceLocation image, int u, int v, int atlasWidth, int atlasHeight) {}
    record Grip(boolean right, float swing, float equip, int light) {}
    record Held(Grip grip, float progress, int width, int height, LocalPlayer player, ItemStack stack,
                InteractionHand hand, int slot, long time) {
        boolean stillHeld(LocalPlayer by) {
            return by == player && by.getItemInHand(hand) == stack && by.getInventory().selected == slot
                    && rightSide(by, hand) == grip.right();
        }
    }
    private static Held lastHeld;

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("anyastorage.tabletIntro", "true"));
    }

    private static ResourceLocation gui(String name) {
        return ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, "textures/gui/" + name + ".png");
    }

    private static Portrait[][] expressionSheet() {
        var cells = new Portrait[OPENING_ROWS + IDLE_ROWS][];
        for (int row = 0; row < cells.length; row++) {
            cells[row] = row(EXPRESSIONS, row, cells.length);
        }
        return cells;
    }

    private static Portrait[] row(ResourceLocation image, int row, int height) {
        var cells = new Portrait[COLUMNS];
        for (int col = 0; col < COLUMNS; col++) {
            cells[col] = new Portrait(image, col * CELL_W, row * CELL_H, COLUMNS * CELL_W, height * CELL_H);
        }
        return cells;
    }

    /** Pick once per visible opening or visit. A pack reload is observed on the next appearance. */
    private static final class Rows {
        private final int first, count;
        private int previous = -1;

        Rows(int first, int count) { this.first = first; this.count = count; }

        int next() {
            if (Minecraft.getInstance().getResourceManager().getResource(EXPRESSIONS).isEmpty()) return -1;
            int choice = ThreadLocalRandom.current().nextInt(previous < 0 ? count : count - 1);
            if (previous >= 0 && choice >= previous) choice++;
            previous = choice;
            return first + choice;
        }
    }

    static int chooseVisit() {
        return chooseVisit(ThreadLocalRandom.current().nextInt(8));
    }

    static int chooseVisit(int roll) {
        if (roll >= 4) {
            lastVisitTumbled = false;
            return -1; // A short Twin Tails glance, using the approved portrait.
        }
        int regular = VISITS.next();
        lastVisitTumbled = regular >= 0 && !lastVisitTumbled && roll == 0
                && Minecraft.getInstance().getResourceManager().getResource(TUMBLE).isPresent();
        return lastVisitTumbled ? TUMBLE_ROW : regular;
    }

    static Portrait opening(int row, GuardianBlink blink) {
        if (blink.column() < 0) return null;
        if (row >= 0) return CELLS[row][blink.column()];
        return switch (blink.frame()) {
            case OPEN -> OPEN;
            case HALF -> HALF;
            case CLOSED -> CLOSED;
            default -> null;
        };
    }

    static Portrait visit(int row, int column) {
        if (column < 0) return null;
        if (row == TUMBLE_ROW) return TUMBLE_CELLS[column];
        return row < 0 ? GAZE : CELLS[row][column];
    }
    static boolean rightSide(LocalPlayer player, InteractionHand hand) {
        return (player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT) == (hand == InteractionHand.MAIN_HAND);
    }
    static void rememberHeld(Held sample) { lastHeld = sample; }
    public static void forgetHeld() { lastHeld = null; }
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
    public static void drawDark(GuiGraphics graphics, Bounds bounds) { draw(graphics, bounds, null); }
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
                             float progress, Portrait portrait) {
        if (armOwner != null && !armOwner.isInvisible()) {
            boolean slim = armOwner.getSkin().model() == net.minecraft.client.resources.PlayerSkin.Model.SLIM;
            var arm = TabletHandlingPose.armMatrix(width, height, grip.right, slim, grip.swing, grip.equip, progress);
            TabletArmRenderer.draw(g, armOwner, grip.right, grip.light, arm);
        }
        var pose = g.pose();
        pose.pushPose();
        try {
            pose.mulPose(TabletHandlingPose.deviceMatrix(width, height, grip.right, grip.swing, grip.equip, progress));
            var surface = new Bounds(0, 0, 1);
            if (progress < 1) {
                // The silver side lip becomes visible as the face tilts away.
                pose.translate(0, 0, -6);
                drawDark(g, surface);
                pose.translate(0, 0, 3);
                drawDark(g, surface);
                pose.translate(0, 0, 3);
            }
            draw(g, surface, portrait);
        } finally {
            pose.popPose();
        }
    }
    static void draw(GuiGraphics g, Bounds b, Portrait portrait) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate(b.x, b.y, 0);
        pose.scale(b.scale, b.scale, 1);
        g.blit(SHELL, 0, 0, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
        if (portrait != null) {
            float zoom = SCREEN_W / (float) CELL_W;
            pose.translate(SCREEN_X, SCREEN_Y + (SCREEN_H - CELL_H * zoom) / 2f, 0);
            pose.scale(zoom, zoom, 1);
            blitPortrait(g, portrait);
        }
        pose.popPose();
    }
    private static void blitPortrait(GuiGraphics graphics, Portrait portrait) {
        graphics.blit(portrait.image(), 0, 0, portrait.u(), portrait.v(), CELL_W, CELL_H,
                portrait.atlasWidth(), portrait.atlasHeight());
    }
    private TabletDevice() {}
}

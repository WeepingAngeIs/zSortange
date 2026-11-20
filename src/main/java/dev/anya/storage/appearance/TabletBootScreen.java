package dev.anya.storage.appearance;

import dev.anya.storage.StorageItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

/** Coordinates the actual hand-to-screen raise once for this opening; no menu is created here. */
public final class TabletBootScreen {
    // Claim the grip for this menu even if it is closed or replaced before its first paint.
    private TabletDevice.Held origin = TabletDevice.takeHeld();
    private boolean armDetached;
    private float paintedProgress;
    private TabletDevice.Portrait paintedFace;
    private long started = Long.MIN_VALUE;
    private int initialWidth, initialHeight;

    private void prepare(int width, int height, Player player, long now, boolean creative) {
        if (started == Long.MIN_VALUE) {
            started = now;
            initialWidth = width;
            initialHeight = height;
            if (origin != null) {
                origin = player instanceof LocalPlayer local && StorageItem.isCreativeTablet(origin.stack()) == creative && origin.width() == width && origin.height() == height
                        && now >= origin.time() && now - origin.time() <= 400 ? origin.afterMenuSync(local) : null;
            }
        }
        if (width != initialWidth || height != initialHeight) origin = null;
    }
    public void draw(GuiGraphics graphics, int width, int height, GuardianBlink blink, Player player, long now) {
        draw(graphics, width, height, blink, player, now, false);
    }
    public void draw(GuiGraphics graphics, int width, int height, GuardianBlink blink, Player player, long now, boolean creative) {
        prepare(width, height, player, now, creative);
        float elapsed = Math.clamp((now - started) / (float) TabletLift.RISE_MS, 0f, 1f);
        float dim = origin == null ? 1f : TabletHandlingPose.eased(elapsed);
        graphics.fill(0, 0, width, height, -900, (Math.round(153 * dim) << 24) | 0x000c1017);
        float progress = origin == null ? 1f : Math.min(1f, origin.progress() + elapsed);
        // The eligible visit starts on the glass while the hardware finishes its normal raise.
        drawDevice(graphics, width, height, player, TabletDevice.opening(blink), progress, false, creative);
    }

    public void drawSettled(GuiGraphics graphics, int width, int height, Player player) {
        drawSettled(graphics, width, height, player, false);
    }
    public void drawSettled(GuiGraphics graphics, int width, int height, Player player, boolean creative) {
        prepare(width, height, player, net.minecraft.Util.getMillis(), creative);
        drawDevice(graphics, width, height, player, null, 1f, false, creative);
    }
    /** Only an explicit close of this player's tablet hands its last painted hardware back to the HUD. */
    public void returnToHand(Player player, int width, int height) {
        if (origin == null || armDetached || paintedProgress <= 0 || origin.width() != width || origin.height() != height
                || !(player instanceof LocalPlayer local) || !origin.stillHeld(local)) return;
        TabletDevice.lowerFrom(new TabletDevice.Held(origin.grip(), paintedProgress, width, height, local, origin.stack(),
                origin.hand(), origin.slot(), net.minecraft.Util.getMillis(), paintedFace));
        origin = null;
    }
    private void drawDevice(GuiGraphics graphics, int width, int height, Player player,
                            TabletDevice.Portrait portrait, float progress, boolean carrying, boolean creative) {
        if (origin == null || !(player instanceof LocalPlayer local)) {
            TabletDevice.draw(graphics, TabletDevice.openBounds(width, height), portrait, false, creative);
            return;
        }
        var mc = Minecraft.getInstance();
        if (!origin.stillHeld(local) || mc.getCameraEntity() != local || !mc.options.getCameraType().isFirstPerson()
                || local.isSpectator() || !local.isAlive() || local.isScoping() || mc.options.hideGui) armDetached = true;
        TabletDevice.drawHandling(graphics, width, height, armDetached ? null : local,
                origin.grip(), progress, portrait, carrying, creative);
        paintedProgress = progress;
        // Once slots cover the glass, uncover the original held face when the menu is dismissed.
        paintedFace = portrait != null ? portrait : origin.portrait();
    }
}

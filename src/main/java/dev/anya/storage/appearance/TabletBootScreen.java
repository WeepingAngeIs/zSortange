package dev.anya.storage.appearance;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

/** Coordinates the actual hand-to-screen raise once for this opening; no menu is created here. */
public final class TabletBootScreen {
    // Claim the grip for this menu even if it is closed or replaced before its first paint.
    private TabletDevice.Held origin = TabletDevice.takeHeld();
    private boolean armDetached;
    private long started = Long.MIN_VALUE;
    private int initialWidth, initialHeight;
    private int openingRow = -2;

    private void prepare(int width, int height, Player player, long now) {
        if (started == Long.MIN_VALUE) {
            started = now;
            initialWidth = width;
            initialHeight = height;
            if (origin != null && (!(player instanceof LocalPlayer local) || !origin.stillHeld(local)
                    || origin.width() != width || origin.height() != height
                    || now < origin.time() || now - origin.time() > 400)) origin = null;
        }
        if (width != initialWidth || height != initialHeight) origin = null;
    }
    public void draw(GuiGraphics graphics, int width, int height, GuardianBlink blink, Player player, long now) {
        prepare(width, height, player, now);
        float elapsed = Math.clamp((now - started) / (float) TabletLift.RISE_MS, 0f, 1f);
        float dim = origin == null ? 1f : TabletHandlingPose.eased(elapsed);
        graphics.fill(0, 0, width, height, -900, (Math.round(153 * dim) << 24) | 0x000c1017);
        if (openingRow == -2 && blink.column() >= 0) {
            // Continue the blue Twin Tails portrait when raising a recently painted Remote.
            openingRow = origin == null ? TabletDevice.chooseOpening() : -1;
        }
        drawDevice(graphics, width, height, player, TabletDevice.opening(openingRow, blink),
                origin == null ? 1f : Math.min(1f, origin.progress() + elapsed));
    }

    public void drawSettled(GuiGraphics graphics, int width, int height, Player player) {
        if (started == Long.MIN_VALUE) prepare(width, height, player, net.minecraft.Util.getMillis());
        drawDevice(graphics, width, height, player, null, 1f);
    }
    private void drawDevice(GuiGraphics graphics, int width, int height, Player player,
                            TabletDevice.Portrait portrait, float progress) {
        if (origin == null || !(player instanceof LocalPlayer local)) {
            TabletDevice.draw(graphics, TabletDevice.openBounds(width, height), portrait);
            return;
        }
        var mc = Minecraft.getInstance();
        if (!origin.stillHeld(local) || mc.getCameraEntity() != local || !mc.options.getCameraType().isFirstPerson()
                || local.isSpectator() || !local.isAlive() || local.isScoping() || mc.options.hideGui) armDetached = true;
        TabletDevice.drawHandling(graphics, width, height, armDetached ? null : local, origin.grip(), progress, portrait);
    }
}

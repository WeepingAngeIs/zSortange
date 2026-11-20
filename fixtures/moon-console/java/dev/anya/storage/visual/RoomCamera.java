package dev.anya.storage.visual;

import com.mojang.blaze3d.platform.NativeImage;
import dev.anya.storage.Content;
import dev.anya.storage.appearance.ClientLooks;
import dev.anya.storage.pocket.PocketService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Observe the first natural Cupid visit from the normal landing. Never inject or query a cue. */
final class RoomCamera {
    private static BlockPos room;
    private static int ticks, settled, first, last, peak, beforeFov, disabledAt;
    private static boolean hiddenHud, capturedHidden, capturedDeparture, finished;
    private static int[] empty;
    private static int width, height;
    private static Path screenshots;

    static boolean run(Minecraft mc) throws IOException {
        if (finished) return true;
        if (++ticks > 500) throw new IllegalStateException("Room camera timed out, first=" + first + " last=" + last + " peak=" + peak);
        if (mc.level == null || mc.player == null || !PocketService.inPocket(mc.level))
            throw new IllegalStateException("Room camera requires physical arrival");
        if (room == null) {
            room = new BlockPos(Math.floorDiv(mc.player.blockPosition().getX(),512)*512, 65,
                    Math.floorDiv(mc.player.blockPosition().getZ(),512)*512);
            beforeFov = mc.options.fov().get();
            if (!ClientLooks.options.models) throw new IllegalStateException("Expected default Models setting on disposable client");
            hiddenHud = mc.options.hideGui;
            screenshots = mc.gameDirectory.toPath().resolve("screenshots");
            Files.createDirectories(screenshots);
            mc.options.fov().set(36);
            mc.options.hideGui = true;
        }
        mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(room.getX()+12.5, 65.98, room.getZ()+7.85));
        if (!mc.level.getBlockState(room.offset(10,0,6)).is(Content.CRATE.get())) return false;
        if (++settled < 24 || ticks%3 != 0) return false;
        try (var pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            if (empty == null) {
                width = pixels.getWidth();
                height = pixels.getHeight();
                empty = new int[width*height];
                sample(pixels, true);
                pixels.writeToFile(screenshots.resolve("room-before.png"));
                return false;
            }
            if (pixels.getWidth() != width || pixels.getHeight() != height)
                throw new IllegalStateException("Room camera changed size");
            int changed = sample(pixels, false);
            // Briefly switch off the normal client preference while the same visit continues.
            if (disabledAt != 0 && !capturedHidden) {
                if (ticks-disabledAt > 12) throw new IllegalStateException("Models-off still displays Cupid");
                if (ticks-disabledAt >= 3 && changed < 15) {
                    pixels.writeToFile(screenshots.resolve("room-models-off.png"));
                    ClientLooks.options.models = true;
                    capturedHidden = true;
                }
                return false;
            }
            if (changed >= 30) {
                if (first == 0) first = ticks;
                last = ticks;
                if (ticks-first <= 28 && changed > peak) {
                    peak = changed;
                    pixels.writeToFile(screenshots.resolve("room-cupid.png"));
                }
                if (ticks-first >= 33 && disabledAt == 0) {
                    ClientLooks.options.models = false;
                    disabledAt = ticks;
                }
                if (ticks-first >= 76 && !capturedDeparture) {
                    pixels.writeToFile(screenshots.resolve("room-departing.png"));
                    capturedDeparture = true;
                }
                return false;
            }
            if (first == 0 || ticks-last < 15) return false;
            if (last-first < 76 || last-first > 115 || !capturedHidden || !capturedDeparture)
                throw new IllegalStateException("Expected full visit, measured " + (last-first) + " client ticks");
            pixels.writeToFile(screenshots.resolve("room-after.png"));
            Files.writeString(mc.gameDirectory.toPath().resolve("room-camera.txt"),
                    "Physical pocket, normal landing, stationary camera.\nFirst blue silhouette at tick " + first
                    + ", last at " + last + ", peak changed two-pixel samples " + peak + ", clear at " + ticks
                    + ". Captured a visit and the empty room after departure.\n");
        }
        empty = null;
        mc.options.hideGui = hiddenHud;
        mc.options.fov().set(beforeFov);
        finished = true;
        return true;
    }

    private static int sample(NativeImage image, boolean capture) {
        int count = 0;
        for (int y=height*28/100; y<height*73/100; y+=2) {
            for (int x=width*15/100; x<width*85/100; x+=2) {
                int index = y*width+x;
                int color = image.getPixelRGBA(x,y);
                if (capture) { empty[index]=color; continue; }
                int r=color&255, g=(color>>>8)&255, b=(color>>>16)&255;
                int old = empty[index];
                if (b>90 && b-r>30 && g-r>18 && b-((old>>>16)&255)>24 && g-((old>>>8)&255)>18) count++;
            }
        }
        return count;
    }
    private RoomCamera() {}
}

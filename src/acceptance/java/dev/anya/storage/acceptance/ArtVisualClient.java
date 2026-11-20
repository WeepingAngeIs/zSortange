package dev.anya.storage.acceptance;

import dev.anya.storage.Packets;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.pocket.PocketContent;
import dev.anya.storage.ui.ControlGlyph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Captures the actual framebuffer after the requested pose or owner menu reaches the client. */
final class ArtVisualClient {
    private static int steadyTicks;
    private static CompletableFuture<Component> writing;

    static boolean run(Minecraft mc, String action, int tick) throws Exception {
        Acceptance.require("art".equals(System.getenv("ANYA_ACCEPTANCE_SCENARIO")), "Visual fixture scenario required");
        Acceptance.require(tick <= 300, "Unsettled client state in " + action);
        Path image = mc.gameDirectory.toPath().resolve("screenshots").resolve(action + ".png");
        if (tick == 1) {
            steadyTicks = 0;
            writing = null;
            Acceptance.require(!Files.exists(image), "Repeated art capture " + image);
        }
        if (writing != null) {
            if (!writing.isDone()) return false;
            Acceptance.require(Files.isRegularFile(image) && Files.size(image) > 1024,
                    "Could not write " + image + ": " + writing.get().getString());
            return true;
        }
        boolean members = action.equals("art-members");
        mc.options.hideGui = !members;
        boolean ready = members ? memberPage(mc, tick) : inWorld(mc, action);
        steadyTicks = ready ? steadyTicks + 1 : 0;
        if (steadyTicks >= 15) {
            if (action.equals("art-window") || action.equals("art-veil")) {
                var camera = mc.gameRenderer.getMainCamera();
                var eye = camera.getPosition();
                var aim = camera.getLookVector();
                var up = camera.getUpVector();
                var pos = ArtVisualProbe.ENTRANCE;
                Acceptance.marker(action + "-camera.json", String.format(Locale.ROOT,
                        "{\"eye\":[%.8f,%.8f,%.8f],\"aim\":[%.8f,%.8f,%.8f],"
                        + "\"up\":[%.8f,%.8f,%.8f],\"fov\":%d,\"entrance\":[%d,%d,%d]}",
                        eye.x, eye.y, eye.z, aim.x(), aim.y(), aim.z(), up.x(), up.y(), up.z(),
                        mc.options.fov().get(), pos.getX(), pos.getY(), pos.getZ()));
            }
            writing = new CompletableFuture<>();
            Screenshot.grab(mc.gameDirectory, image.getFileName().toString(), mc.getMainRenderTarget(), writing::complete);
        }
        return false;
    }

    private static boolean inWorld(Minecraft mc, String action) {
        if (mc.screen != null || mc.getCameraEntity() != mc.player) return false;
        boolean chest = action.equals("art-closed") || action.equals("art-open");
        boolean veil = action.equals("art-veil");
        Acceptance.require(chest || veil || action.equals("art-window"), "Unknown art request " + action);
        BlockPos target = chest ? ArtVisualProbe.CHEST : ArtVisualProbe.ENTRANCE;
        if (!mc.level.hasChunkAt(target)) return false;
        mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(target));
        boolean picked = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target) && hit.getDirection().getAxis().isHorizontal();
        if (!chest) return mc.level.getBlockState(target).is(PocketContent.ENTRANCE.get()) && (veil != picked)
                && mc.player.position().distanceToSqr(Vec3.atCenterOf(target)) < (veil ? 64 : 16);
        return picked && mc.level.getBlockEntity(target) instanceof StorageEntity entity
                && entity.openness == (action.equals("art-open") ? 1f : 0f);
    }

    private static boolean memberPage(Minecraft mc, int tick) {
        if (!(mc.player.containerMenu instanceof StoreMenu menu) || mc.screen == null) return false;
        // Both clients share a virtual display. GLFW may ignore a pointer warp in the
        // unfocused window, so deliver its ordinary pointer-move callback without a click.
        mc.getToasts().clear();
        if (mc.mouseHandler.xpos() > 8 || mc.mouseHandler.ypos() > 8) {
            long window = mc.getWindow().getWindow();
            var move = GLFW.glfwSetCursorPosCallback(window, null);
            Acceptance.require(move != null, "Expected Minecraft's cursor movement callback");
            GLFW.glfwSetCursorPosCallback(window, move);
            move.invoke(window, 4, 4);
            return false;
        }
        var allow = ControlGlyph.read(menu.getSlot(28).getItem());
        var deny = ControlGlyph.read(menu.getSlot(30).getItem());
        if (menu.accessView() && menu.controlSlot(27)
                && menu.getSlot(27).getItem().getHoverName().getString().equals("AnyaQABeta")
                && new ControlGlyph.Face(ControlGlyph.VIEW, ControlGlyph.State.ON).equals(allow)
                && new ControlGlyph.Face(ControlGlyph.WITHDRAW, ControlGlyph.State.OFF).equals(deny)) {
            for (int slot : new int[]{0, 27, 28, 29, 30, 31, 32, 33, 34, 45, 46, 47, 50, 53})
                if (!menu.controlSlot(slot)) return false;
            return !menu.controlSlot(35);
        }
        // All three buttons navigate or select; none of the permission toggles are clicked.
        if (tick % 13 == 0) {
            if (hasGlyph(menu, 51, ControlGlyph.BASE)) click(menu, 51);
            else if (hasGlyph(menu, 12, ControlGlyph.MEMBERS)) click(menu, 12);
            else if (menu.accessView() && hasGlyph(menu, 0, ControlGlyph.MEMBER)) click(menu, 0);
        }
        return false;
    }

    private static boolean hasGlyph(StoreMenu m, int slot, ControlGlyph glyph) {
        var face = ControlGlyph.read(m.getSlot(slot).getItem());
        return m.controlSlot(slot) && face != null && face.glyph() == glyph;
    }

    private static void click(StoreMenu m, int slot) {
        PacketDistributor.sendToServer(new Packets.Action(m.containerId, m.session(), m.revision(), "slot", "", slot));
    }
}

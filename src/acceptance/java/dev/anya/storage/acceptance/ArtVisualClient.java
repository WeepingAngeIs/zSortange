package dev.anya.storage.acceptance;

import dev.anya.storage.Packets;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.pocket.PocketContent;
import dev.anya.storage.ui.ControlGlyph;
import java.nio.file.Files;
import java.nio.file.Path;
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
        var allow = ControlGlyph.read(menu.getSlot(28).getItem());
        var deny = ControlGlyph.read(menu.getSlot(30).getItem());
        if (menu.accessView() && menu.getSlot(27).getItem().getHoverName().getString().equals("AnyaQABeta")
                && new ControlGlyph.Face(ControlGlyph.VIEW, ControlGlyph.State.ON).equals(allow)
                && new ControlGlyph.Face(ControlGlyph.WITHDRAW, ControlGlyph.State.OFF).equals(deny)) return true;
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
        return face != null && face.glyph() == glyph;
    }

    private static void click(StoreMenu m, int slot) {
        PacketDistributor.sendToServer(new Packets.Action(m.containerId, m.session(), m.revision(), "slot", "", slot));
    }
}

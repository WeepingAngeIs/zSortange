package dev.anya.storage.appearance;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.joml.Matrix4f;

/** Draws the local player's actual arm and enabled sleeve behind the device in GUI space. */
public final class TabletArmRenderer {
    public static void draw(GuiGraphics graphics, LocalPlayer player, boolean right, int packedLight, Matrix4f matrix) {
        if (player == null || player.isInvisible() || player.isSpectator()) return;
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        if (!(dispatcher.<AbstractClientPlayer>getRenderer(player) instanceof PlayerRenderer renderer)) return;

        // Keep the current GUI projection and lighting. The arm event can supply a custom skin/mesh.
        graphics.flush();
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(0, 0, -260);
            pose.mulPose(matrix);
            if (right) renderer.renderRightHand(pose, graphics.bufferSource(), packedLight, player);
            else renderer.renderLeftHand(pose, graphics.bufferSource(), packedLight, player);
        } finally {
            pose.popPose();
            graphics.flush();
        }
    }

    private TabletArmRenderer() {}
}

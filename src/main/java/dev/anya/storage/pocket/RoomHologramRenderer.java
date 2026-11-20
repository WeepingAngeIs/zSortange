package dev.anya.storage.pocket;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.anya.storage.AnyaStorage;
import dev.anya.storage.appearance.ClientLooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@EventBusSubscriber(modid=AnyaStorage.ID, value=Dist.CLIENT)
public final class RoomHologramRenderer {
    private static final RenderType INK = RenderType.entityTranslucentEmissive(
            ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID,"textures/entity/pocket_cupid.png"), false);
    private record Visit(ClientLevel level, BlockPos room, int act, long start) {
        boolean contains(Vec3 point) {
            return point.x >= room.getX() && point.x < room.getX()+32
                && point.z >= room.getZ() && point.z < room.getZ()+32
                && point.y >= room.getY() && point.y < room.getY()+32;
        }
    }
    private static Visit visit;
    static { RoomHologram.receiver = RoomHologramRenderer::receive; }

    private static void receive(Connection connection, RoomHologram cue) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.getConnection().getConnection() != connection) return;
        visit = null;
        if (cue.act() < 0 || cue.act() > 2 || mc.level == null || mc.player == null
                || !PocketService.inPocket(mc.level) || !mc.player.isAlive() || mc.player.isSpectator()) return;
        var next = new Visit(mc.level, cue.origin(), cue.act(), mc.level.getGameTime());
        if (next.contains(mc.player.position())) visit = next;
    }

    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (visit != null && event.getLevel() == visit.level()) visit = null;
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { visit = null; }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (visit == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        var mc = Minecraft.getInstance();
        if (mc.level != visit.level() || mc.player == null || mc.player.level() != mc.level
                || !mc.player.isAlive() || mc.player.isSpectator() || !visit.contains(mc.player.position())) {
            visit = null;
            return;
        }
        float age = (mc.level.getGameTime() - visit.start()) + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if (age < 0 || age >= RoomVisitSchedule.DURATION) { visit = null; return; }
        var camera = event.getCamera();
        // Draw once the main world has finished, with its real depth buffer, before the hand pass.
        if (!ClientLooks.options.models || camera != mc.gameRenderer.getMainCamera() || camera.getEntity() != mc.player
                || mc.getCameraEntity() != mc.player || event.getLevelRenderer() != mc.levelRenderer
                || !visit.contains(camera.getPosition()) || camera.getPosition().distanceToSqr(mc.player.getEyePosition()) > 36
                || GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) != mc.getMainRenderTarget().frameBufferId) return;

        float entrance = smooth(age / 30), exit = smooth((age-64) / 32);
        float act = Mth.clamp((age-30) / 34, 0, 1);
        float bow = Mth.sin(act * Mth.PI);
        double x = visit.room().getX() + 8.75 + 3.75 * entrance + 3.5 * exit;
        double y = visit.room().getY() + .91;
        double z = visit.room().getZ() + 7.85 - bow * .26;
        boolean strolling = age < 30 || age > 64;
        float roll = strolling ? Mth.sin(age*.62f)*3 : -9*bow;
        int row = 0, column = 1;
        if (strolling) {
            float stride = Mth.sin(age*.62f);
            y += .075*Math.abs(stride);
            column = stride < -.35 ? 0 : stride > .35 ? 2 : 1;
        } else if (visit.act() == 2) {
            roll = 360*smooth(act);
            y += .42*bow;
            row = 1;
        } else if (visit.act() == 1) {
            row = 1;
            column = (int) (act*8)%3;
            roll = 7*bow;
        } else {
            column = act < .28f ? 0 : act > .72f ? 2 : 1;
        }

        var cameraPos = camera.getPosition();
        var pose = event.getPoseStack();
        pose.pushPose();
        try {
            pose.mulPose(event.getModelViewMatrix());
            pose.translate(x-cameraPos.x, y-cameraPos.y, z-cameraPos.z);
            pose.mulPose(camera.rotation());
            pose.mulPose(Axis.ZP.rotationDegrees(roll));
            var buffers = mc.renderBuffers().bufferSource();
            var vertices = buffers.getBuffer(INK);
            int alpha = Math.round(225*Math.min(smooth(age/9), smooth((RoomVisitSchedule.DURATION-age)/10)));
            float u = column/3f, v = row/2f;
            vertex(vertices, pose.last(), -.86f, -.86f, u, v+.5f, alpha);
            vertex(vertices, pose.last(), .86f, -.86f, u+1/3f, v+.5f, alpha);
            vertex(vertices, pose.last(), .86f, .86f, u+1/3f, v, alpha);
            vertex(vertices, pose.last(), -.86f, .86f, u, v, alpha);
            buffers.endBatch(INK);
        } finally { pose.popPose(); }
    }
    private static float smooth(float t) { float s=Mth.clamp(t,0,1); return s*s*(3-2*s); }
    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, float x, float y, float u, float v, int alpha) {
        vertices.addVertex(pose.pose(), x, y, 0).setColor(255,255,255,alpha).setUv(u,v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose,0,0,1);
    }
    private RoomHologramRenderer() {}
}

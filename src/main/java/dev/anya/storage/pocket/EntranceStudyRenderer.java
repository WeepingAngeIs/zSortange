package dev.anya.storage.pocket;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.anya.storage.AnyaStorage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Draws the public study or the selected, currently authorized view of the registered pads. */
@EventBusSubscriber(modid = AnyaStorage.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class EntranceStudyRenderer implements BlockEntityRenderer<PocketContent.EntranceEntity> {
    private static final ResourceLocation STUDY = ResourceLocation.fromNamespaceAndPath(
            AnyaStorage.ID, "textures/entity/starter_room_study.png");
    private static final ResourceLocation PLAN = ResourceLocation.fromNamespaceAndPath(
            AnyaStorage.ID, "textures/entity/pocket_pad_plan.png");
    private static final ResourceLocation CHEST_LAYOUT = ResourceLocation.fromNamespaceAndPath(
            AnyaStorage.ID, "textures/entity/framed_chest_still.png");
    private static final ResourceLocation FRAME_GLINTS = ResourceLocation.fromNamespaceAndPath(
            AnyaStorage.ID, "textures/entity/framed_still_glints.png");
    // Face texels in the art atlas, ordered as the four starters then the four optional pads.
    private static final int[] PAD_X = {88,110,131,153,76,97,161,183};
    private static final int[] PAD_Y = {116,117,119,121,136,137,142,144};

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> { PocketPadPackets.receiver = EntranceLayoutClient::receive; });
    }

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(PocketContent.ENTITY.get(), context -> new EntranceStudyRenderer());
    }

    @Override
    public int getViewDistance() {
        return 8;
    }

    @Override
    public void render(PocketContent.EntranceEntity entrance, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        Minecraft client = Minecraft.getInstance();
        if (entrance.getLevel() != client.level || !(client.hitResult instanceof BlockHitResult hit)
                || !EntranceFaceHit.accepts(hit, false) || !hit.getBlockPos().equals(entrance.getBlockPos())) {
            return;
        }
        // Reuse the normal pick. Looking away immediately exposes the original baked, animated face.
        Direction face = hit.getDirection();
        pose.pushPose();
        pose.translate(.5, .5, .5);
        EntranceFacePose.orient(pose, face, client.gameRenderer.getMainCamera().getYRot());
        if (EntranceLayoutClient.CHEST_LAYOUT_STILL) {
            drawChestLayout(EntranceLayoutClient.visibleMask(entrance), pose, buffers, face, overlay);
            pose.popPose();
            EntranceFrameSounds.drewFace(entrance);
            return;
        }
        int mask = EntranceLayoutClient.visibleMask(entrance);
        boolean plan = mask >= 0;
        ResourceLocation surface = plan ? PLAN : STUDY;
        VertexConsumer material = buffers.getBuffer(RenderType.entityCutout(surface));
        // The diagram and its repeated chest sprites share one public atlas and one render type.
        quad(pose, material, face, 0, 0, 1, 1, 0, 0, plan ? .5f : 1, 1, -.501f, overlay);
        if (plan) {
            ChestLayoutProjection.placed(mask,EntranceLayoutClient.coordinates,(i,left,top)->{
                quad(pose, material, face, left/256f, top/256f, (left+25)/256f, (top+22)/256f,
                        .5f, 0, 281/512f, 22/256f, -.5015f, overlay);
            });
        }
        pose.popPose();
        EntranceFrameSounds.drewFace(entrance);
    }

    private static void drawChestLayout(int mask, PoseStack pose, MultiBufferSource buffers, Direction face,
                                        int overlay) {
        if (!ChestLayoutProjection.validMask(mask)) {
            VertexConsumer publicArt = buffers.getBuffer(RenderType.entityCutout(STUDY));
            quad(pose, publicArt, face, 0, 0, 1, 1, 0, 0, 1, 1, -.501f, overlay);
            return;
        }
        float size = ChestLayoutProjection.FACE_SIZE;
        float width = ChestLayoutProjection.ATLAS_WIDTH;
        VertexConsumer atlas = buffers.getBuffer(RenderType.entityCutout(CHEST_LAYOUT));
        quad(pose, atlas, face, 0, 0, 1, 1, 0, 0, size / width, 1, -.501f, overlay);
        int sx = ChestLayoutProjection.STAMP_X, sy = ChestLayoutProjection.STAMP_Y;
        int sw = ChestLayoutProjection.STAMP_WIDTH, sh = ChestLayoutProjection.STAMP_HEIGHT;
        ChestLayoutProjection.placed(mask,EntranceLayoutClient.coordinates, (bit, left, top) ->
                quad(pose, atlas, face, left / size, top / size, (left + sw) / size, (top + sh) / size,
                        sx / width, sy / size, (sx + sw) / width, (sy + sh) / size, -.5015f, overlay));

        // Two small reflections in the frame use the local clock and one strip of eight 16x8 sprites.
        int phase = Math.floorMod(Util.getMillis() / 600L, 8);
        VertexConsumer glints = buffers.getBuffer(RenderType.entityCutout(FRAME_GLINTS));
        float v0 = phase / 8f, v1 = (phase + 1) / 8f;
        quad(pose, glints, face, 120 / size, 8 / size, 136 / size, 16 / size, 0, v0, 1, v1, -.5015f, overlay);
        quad(pose, glints, face, 120 / size, 240 / size, 136 / size, 248 / size, 0, v0, 1, v1, -.5015f, overlay);
    }

    private static void quad(PoseStack pose, VertexConsumer material, Direction face, float left, float top,
                             float right, float bottom, float u0, float v0, float u1, float v1, float z, int overlay) {
        vertex(pose, material, face, .5f-left, .5f-bottom, z, u0, v1, overlay);
        vertex(pose, material, face, .5f-right, .5f-bottom, z, u1, v1, overlay);
        vertex(pose, material, face, .5f-right, .5f-top, z, u1, v0, overlay);
        vertex(pose, material, face, .5f-left, .5f-top, z, u0, v0, overlay);
    }

    private static void vertex(PoseStack pose, VertexConsumer material, Direction face, float x, float y,
                               float z, float u, float v, int overlay) {
        material.addVertex(pose.last().pose(), x, y, z)
                .setColor(255, 255, 255, 255).setUv(u, v).setOverlay(overlay).setLight(LightTexture.FULL_BRIGHT)
                .setNormal(face.getStepX(), face.getStepY(), face.getStepZ());
    }
}

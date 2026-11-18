package dev.anya.storage.pocket;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.anya.storage.AnyaStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** A public, artist-authored starter room illustration, shared by every entrance. */
@EventBusSubscriber(modid = AnyaStorage.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class EntranceStudyRenderer implements BlockEntityRenderer<PocketContent.EntranceEntity> {
    private static final ResourceLocation STUDY = ResourceLocation.fromNamespaceAndPath(
            AnyaStorage.ID, "textures/entity/starter_room_study.png");

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
                || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(entrance.getBlockPos())
                || !hit.getDirection().getAxis().isHorizontal()) {
            return;
        }
        // Reuse the normal pick. Looking away immediately exposes the original baked, animated face.
        Direction face = hit.getDirection();
        pose.pushPose();
        pose.translate(.5, .5, .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - face.toYRot()));
        VertexConsumer material = buffers.getBuffer(RenderType.entityCutout(STUDY));
        vertex(pose, material, face, .5f, -.5f, 0, 1, light, overlay);
        vertex(pose, material, face, -.5f, -.5f, 1, 1, light, overlay);
        vertex(pose, material, face, -.5f, .5f, 1, 0, light, overlay);
        vertex(pose, material, face, .5f, .5f, 0, 0, light, overlay);
        pose.popPose();
    }

    private static void vertex(PoseStack pose, VertexConsumer material, Direction face, float x, float y,
                               float u, float v, int light, int overlay) {
        material.addVertex(pose.last().pose(), x, y, -.501f)
                .setColor(255, 255, 255, 255).setUv(u, v).setOverlay(overlay).setLight(light)
                .setNormal(face.getStepX(), 0, face.getStepZ());
    }
}

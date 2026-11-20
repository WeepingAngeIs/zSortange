package dev.anya.storage.furniture;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.anya.storage.AnyaStorage;
import dev.anya.storage.Content;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Only a public, ordinary-world base item reaches this renderer. Pocket displays use the viewer-specific grant. */
@EventBusSubscriber(modid=AnyaStorage.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class KeepsakeGlobeRenderer implements BlockEntityRenderer<FurnitureEntity> {
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(Content.FURNITURE.get(),context -> new KeepsakeGlobeRenderer());
    }
    @Override public void render(FurnitureEntity f,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        if (f.publicModel.isEmpty() || f.getLevel()==null) return;
        float ticks=f.getLevel().getGameTime()%240 + partial;
        pose.pushPose();
        pose.translate(.5,.55+.012*Math.sin(ticks*Math.PI/60),.5);
        pose.mulPose(Axis.YP.rotationDegrees(1.5f*ticks));
        pose.scale(.45f,.45f,.45f);
        Minecraft.getInstance().getItemRenderer().renderStatic(f.publicModel,ItemDisplayContext.FIXED,
                light,overlay,pose,buffers,f.getLevel(),0);
        pose.popPose();
    }
}

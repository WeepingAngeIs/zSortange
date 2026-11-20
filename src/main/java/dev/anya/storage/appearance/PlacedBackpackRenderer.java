package dev.anya.storage.appearance;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.anya.storage.AnyaStorage;
import dev.anya.storage.Content;
import dev.anya.storage.PlacedBackpackBlock;
import dev.anya.storage.PlacedBackpackEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** The ground backpack uses the same baked three-dimensional model and skin as the carried bag. */
@EventBusSubscriber(modid = AnyaStorage.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PlacedBackpackRenderer implements BlockEntityRenderer<PlacedBackpackEntity> {
    private final ItemRenderer items;

    public PlacedBackpackRenderer(BlockEntityRendererProvider.Context context) {
        items = context.getItemRenderer();
    }

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(Content.PLACED_BAG_ENTITY.get(), PlacedBackpackRenderer::new);
    }

    @Override
    public void render(PlacedBackpackEntity bag, float partial, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        var shell = bag.displayStack();
        if (shell.isEmpty()) return;
        pose.pushPose();
        try {
            // The baked item centers all axes at 8/16, and its feet sit at 2/16.
            pose.translate(.5, 6 / 16.0, .5);
            pose.mulPose(Axis.YP.rotationDegrees(-bag.getBlockState().getValue(PlacedBackpackBlock.FACING).toYRot()));
            items.renderStatic(shell, ItemDisplayContext.NONE, light, overlay, pose, buffers,
                    bag.getLevel(), (int)bag.getBlockPos().asLong());
        } finally {
            pose.popPose();
        }
    }
}

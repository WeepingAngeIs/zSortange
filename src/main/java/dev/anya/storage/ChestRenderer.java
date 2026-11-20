package dev.anya.storage;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

import java.util.HashMap;
import java.util.Map;

/** The item and placed chest use the same baked shell; only the placed lid rotates. */
@EventBusSubscriber(modid = AnyaStorage.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ChestRenderer implements BlockEntityRenderer<StorageEntity> {
    private static final ModelResourceLocation BASE = model("chest_base");
    private static final ModelResourceLocation LID = model("chest_lid");
    private static final ModelResourceLocation CUBE_BASE = model("uwu_cube_base");
    private static final ModelResourceLocation CUBE_LID = model("uwu_cube_lid");
    // The cube's baked shell scales around the floor centre in scripts/moon_cube.py.
    private static final float CUBE_WIDTH = 1.10f, CUBE_HEIGHT = 1.105f, CUBE_DEPTH = 1.075f;
    private static final float HINGE_Y = 8.54f / 16;
    private static final float HINGE_Z = 14.58f / 16;
    private static final Map<String, RenderType> LABELS = new HashMap<>();
    static {
        for (String symbol : Config.SYMBOLS) {
            ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, "textures/entity/label_" + symbol + ".png");
            LABELS.put(symbol, RenderType.entityCutoutNoCull(texture));
        }
    }

    private final BlockRenderDispatcher blocks;

    public ChestRenderer(BlockEntityRendererProvider.Context context) {
        blocks = context.getBlockRenderDispatcher();
    }

    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(AnyaStorage.ID, "block/" + name));
    }

    @SubscribeEvent
    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(BASE);
        event.register(LID);
        event.register(CUBE_BASE);
        event.register(CUBE_LID);
    }

    @Override
    public AABB getRenderBoundingBox(StorageEntity chest) {
        // The open crown clears the top of the block and the rear hinge.
        return new AABB(chest.getBlockPos()).inflate(.25, 0, .25).expandTowards(0, .65, 0);
    }

    @Override
    public void render(StorageEntity chest, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        boolean cube = chest.getBlockState().is(Content.CUBE.get());
        if (!cube && !chest.getBlockState().is(Content.CRATE.get())) return;
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - chest.getBlockState().getValue(StorageBlock.FACING).toYRot()));
        pose.translate(-.5, 0, -.5);
        drawModel(cube ? CUBE_BASE : BASE, chest, pose, buffers, light, overlay);
        if (chest.showLabels) drawLabel(chest.symbol, cube, pose, buffers, 6.35f, 8.32f, .5f, 1, light, overlay);

        float progress = Mth.lerp(partial, chest.previousOpenness, chest.openness);
        float eased = progress * progress * (3 - 2 * progress);
        float hingeY = cube ? HINGE_Y * CUBE_HEIGHT : HINGE_Y;
        float hingeZ = cube ? .5f + (HINGE_Z - .5f) * CUBE_DEPTH : HINGE_Z;
        pose.translate(0, hingeY, hingeZ);
        pose.mulPose(Axis.XP.rotationDegrees(72 * eased));
        pose.translate(0, -hingeY, -hingeZ);
        drawModel(cube ? CUBE_LID : LID, chest, pose, buffers, light, overlay);
        if (chest.showLabels) drawLabel(chest.symbol, cube, pose, buffers, 8.65f, 10.62f, 0, .5f, light, overlay);
        pose.popPose();
    }

    private void drawModel(ModelResourceLocation id, StorageEntity chest, PoseStack pose, MultiBufferSource buffers,
                           int light, int overlay) {
        var model = Minecraft.getInstance().getModelManager().getModel(id);
        blocks.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(Sheets.cutoutBlockSheet()),
                chest.getBlockState(), model, 1, 1, 1, light, overlay);
    }

    private static void drawLabel(String symbol, boolean cube, PoseStack pose, MultiBufferSource buffers, float bottom, float top,
                                  float vTop, float vBottom, int light, int overlay) {
        if (cube) {
            pose.pushPose();
            pose.translate(.5f, 0, .5f);
            pose.scale(CUBE_WIDTH, CUBE_HEIGHT, CUBE_DEPTH);
            pose.translate(-.5f, 0, -.5f);
        }
        VertexConsumer out = buffers.getBuffer(LABELS.getOrDefault(symbol, LABELS.get("heart")));
        vertex(out, pose.last(), 6, bottom, 0, vBottom, light, overlay);
        vertex(out, pose.last(), 6, top, 0, vTop, light, overlay);
        vertex(out, pose.last(), 10, top, 1, vTop, light, overlay);
        vertex(out, pose.last(), 10, bottom, 1, vBottom, light, overlay);
        if (cube) pose.popPose();
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, float x, float y, float u, float v,
                               int light, int overlay) {
        out.addVertex(pose, x/16, y/16, .84f/16).setColor(-1).setUv(u, v)
                .setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, -1);
    }
}

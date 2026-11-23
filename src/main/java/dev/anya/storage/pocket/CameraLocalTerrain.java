package dev.anya.storage.pocket;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper;

/** Draws only the packet-approved faces with Minecraft's block shaders and local lightmap. */
final class CameraLocalTerrain {
    private static final List<RenderType> LAYERS = List.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout());
    private record FaceKey(int state, Direction side, double plane) {}
    private record FaceMesh(BlockState state, RenderType layer, List<Part> parts) {}
    private record Part(boolean shade, FaceModel model) {}

    static void draw(CameraPicture.SurfaceView view, PoseStack pose, MultiBufferSource.BufferSource batch) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) return;
        var renderer = mc.getBlockRenderer().getModelRenderer();
        var cache = new HashMap<FaceKey, FaceMesh>();
        for (var layer : LAYERS) {
            ShadedConsumer vertices = null;
            for (var face : view.surfaces) {
                var s = face.s;
                var key = new FaceKey(s.stateId(), s.face(), face.localPlane);
                var mesh = cache.computeIfAbsent(key, ignored -> mesh(face));
                if (mesh.layer != layer || mesh.parts.isEmpty()) continue;
                if (vertices == null) vertices = new ShadedConsumer(batch.getBuffer(layer));
                int light = LightTexture.pack(s.blockLight(), s.skyLight());
                float red = (s.tint() >> 16 & 255) / 255f;
                float green = (s.tint() >> 8 & 255) / 255f;
                float blue = (s.tint() & 255) / 255f;
                pose.pushPose();
                try {
                    pose.translate(s.x(), s.y(), s.z());
                    for (var part : mesh.parts) {
                        // Directional shade is dimension metadata. No source blocks, neighbours or entities are read.
                        vertices.shade = level.getShade(s.face(), part.shade);
                        renderer.renderModel(pose.last(), vertices, mesh.state, part.model, red, green, blue,
                                light, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, layer);
                    }
                } finally {
                    pose.popPose();
                }
            }
        }
    }

    private static FaceMesh mesh(CameraPicture.ProjectedSurface face) {
        var state = Block.stateById(face.s.stateId());
        var baked = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        RenderType layer;
        try {
            var layers = baked.getRenderTypes(state, RandomSource.create(42), ModelData.EMPTY).asList();
            // Multi-layer models and translucent geometry use the conservative existing compositor.
            if (layers.size() != 1 || !LAYERS.contains(layers.getFirst())) return new FaceMesh(state, null, List.of());
            layer = layers.getFirst();
        } catch (RuntimeException unsupportedWithoutModelData) {
            return new FaceMesh(state, null, List.of());
        }
        var shaded = new ArrayList<BakedQuad>();
        var unshaded = new ArrayList<BakedQuad>();
        for (var facet : face.facets) {
            var quad = facet.sourceQuad;
            if (quad == null || !approvedPlane(quad, face)) continue;
            (quad.isShade() ? shaded : unshaded).add(quad);
        }
        var parts = new ArrayList<Part>(2);
        if (!shaded.isEmpty()) parts.add(new Part(true, new FaceModel(baked, shaded)));
        if (!unshaded.isEmpty()) parts.add(new Part(false, new FaceModel(baked, unshaded)));
        return new FaceMesh(state, layer, parts);
    }

    private static boolean approvedPlane(BakedQuad quad, CameraPicture.ProjectedSurface face) {
        if (quad.getDirection() != face.s.face()) return false;
        var data = quad.getVertices();
        int stride = data.length / 4;
        if (stride < 6 || stride * 4 != data.length) return false;
        for (int i = 0; i < data.length; i += stride) {
            float x = Float.intBitsToFloat(data[i]), y = Float.intBitsToFloat(data[i + 1]), z = Float.intBitsToFloat(data[i + 2]);
            if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1 && z >= 0 && z <= 1)) return false;
            if (Math.abs(face.s.face().getAxis().choose((double)x, y, z) - face.localPlane) > .009) return false;
        }
        return true;
    }

    /** renderModel requests culled and unculled groups; return the filtered set exactly once. */
    private static final class FaceModel extends BakedModelWrapper<BakedModel> {
        private final List<BakedQuad> quads;
        FaceModel(BakedModel model, List<BakedQuad> quads) {
            super(model);
            this.quads = quads;
        }
        @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
            return side == null ? quads : List.of();
        }
        @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random,
                                                  ModelData data, RenderType layer) {
            return getQuads(state, side, random);
        }
    }

    private static final class ShadedConsumer extends VertexConsumerWrapper {
        float shade;
        ShadedConsumer(VertexConsumer target) { super(target); }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            parent.setColor(Math.round(r * shade), Math.round(g * shade), Math.round(b * shade), a);
            return this;
        }
    }

    private CameraLocalTerrain() {}
}

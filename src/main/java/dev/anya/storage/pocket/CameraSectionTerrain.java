package dev.anya.storage.pocket;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;

/** The chunk shader and section-local VBO draw from the world terrain pass, fed only approved faces. */
final class CameraSectionTerrain {
    // A very fragmented scene falls back to the existing certified raster beyond this draw budget.
    private static final int MAX_DRAWS = 64;
    private static VertexBuffer gpu;
    private final List<Section> sections;
    private final ByteBufferBuilder storage;
    private final Matrix4f view;
    private final Matrix4f projection;
    private int draws;

    record Section(SectionPos pos, List<CameraPicture.ProjectedSurface> faces) {}
    interface Emitter {
        void emit(Section section, VertexConsumer vertices);
    }

    CameraSectionTerrain(CameraPicture.SurfaceView approved, ByteBufferBuilder storage, Matrix4f view, Matrix4f projection) {
        this.storage = storage;
        this.view = view;
        this.projection = projection;
        var groups = new LinkedHashMap<SectionPos, Section>();
        for (var face : approved.surfaces) {
            var s = face.s;
            var pos = SectionPos.of(s.x() >> 4, s.y() >> 4, s.z() >> 4);
            groups.computeIfAbsent(pos, p -> new Section(p, new ArrayList<>())).faces().add(face);
        }
        sections = new ArrayList<>(groups.values());
        // Positions in the camera packet are relative to the lens block.
        sections.sort(Comparator.comparingInt(section -> {
            var pos = section.pos();
            int x = pos.minBlockX() + 8, y = pos.minBlockY() + 8, z = pos.minBlockZ() + 8;
            return x*x + y*y + z*z;
        }));
    }

    void drawLayer(RenderType layer, Emitter emitter) {
        if (draws >= MAX_DRAWS || sections.isEmpty()) return;
        var previous = RenderSystem.getShader();
        layer.setupRenderState();
        try {
            var shader = RenderSystem.getShader();
            // Custom shaders without the world section offset cannot place these vertices correctly.
            if (shader == null || shader.CHUNK_OFFSET == null) return;
            var offset = shader.CHUNK_OFFSET;
            var values = offset.getFloatBuffer();
            float ox = values.get(0), oy = values.get(1), oz = values.get(2);
            shader.setDefaultUniforms(layer.mode(), view, projection, Minecraft.getInstance().getWindow());
            shader.apply();
            try {
                for (var section : sections) {
                    var builder = new BufferBuilder(storage, layer.mode(), layer.format());
                    emitter.emit(section, builder);
                    var mesh = builder.build();
                    if (mesh == null) continue;
                    if (gpu == null) {
                        try {
                            gpu = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                        } catch (RuntimeException failure) {
                            mesh.close();
                            throw failure;
                        }
                    }
                    var pos = section.pos();
                    offset.set((float)pos.minBlockX(), (float)pos.minBlockY(), (float)pos.minBlockZ());
                    offset.upload();
                    gpu.bind();
                    gpu.upload(mesh);
                    gpu.draw();
                    if (++draws == MAX_DRAWS) break;
                }
            } finally {
                offset.set(ox, oy, oz);
                offset.upload();
                shader.clear();
            }
        } finally {
            VertexBuffer.unbind();
            storage.clear();
            layer.clearRenderState();
            RenderSystem.setShader(() -> previous);
        }
    }

    static void clear() {
        if (gpu != null) {
            gpu.close();
            gpu = null;
        }
    }
}

package dev.anya.storage.pocket;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.anya.storage.AnyaStorage;
import java.nio.FloatBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/** A private, depth-tested view of the approved outdoor faces using the player's baked meshes and block atlas. */
final class CameraTerrain {
    private static final float NEAR = .03f, FAR = CameraScene.FAR_CLIP;
    private static TextureTarget target;
    private static ByteBufferBuilder storage;
    private static NativeImage color;
    private static FloatBuffer depth;
    private static boolean failed;

    static boolean draw(CameraScene scene, CameraPicture.SurfaceView view, NativeImage frame, float[] sceneDepth) {
        if (failed) return false;
        RenderSystem.assertOnRenderThread();
        var mc = Minecraft.getInstance();
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        float[] tint = RenderSystem.getShaderColor().clone();
        modelView.pushMatrix();
        try {
            int size = frame.getWidth();
            if (target == null || target.width != size) {
                clear();
                target = new TextureTarget(size, size, true, Minecraft.ON_OSX);
                target.setClearColor(0, 0, 0, 0);
                storage = new ByteBufferBuilder(1 << 18);
                color = new NativeImage(size, size, false);
                depth = MemoryUtil.memAllocFloat(size * size);
            }
            modelView.identity();
            RenderSystem.applyModelViewMatrix();
            float fov = (float)(2 * Math.atan(scene.up().length()));
            var lensProjection = new Matrix4f().perspective(fov, 1, NEAR, FAR);
            RenderSystem.setProjectionMatrix(lensProjection, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShaderFogStart(FAR + 1);
            RenderSystem.setShaderFogEnd(FAR + 2);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            var lens = new CameraActors.Lens(scene);
            var pose = new PoseStack();
            pose.mulPose(lens.rotation().conjugate(new Quaternionf()));
            pose.translate(-scene.eye().x, -scene.eye().y, -scene.eye().z);
            if (scene.localLighting()) {
                CameraLocalTerrain.draw(new CameraSectionTerrain(view, storage, pose.last().pose(), lensProjection));
            } else {
                drawFaces(view, pose);
            }
            target.bindWrite(false);
            depth.clear();
            GL11.glReadPixels(0, 0, size, size, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            RenderSystem.bindTexture(target.getColorTextureId());
            color.downloadTexture(0, false);
            composite(scene, view, lensProjection, frame, sceneDepth);
            return true;
        } catch (RuntimeException unavailable) {
            CameraSectionTerrain.clear();
            failed = true;
            AnyaStorage.LOG.warn("Outdoor camera using its software renderer for this view", unavailable);
            return false;
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderColor(tint[0], tint[1], tint[2], tint[3]);
            RenderSystem.depthMask(depthWrite);
            if (depthWasEnabled) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    private static void drawFaces(CameraPicture.SurfaceView view, PoseStack pose) {
        var batch = MultiBufferSource.immediate(storage);
        // A camera in another dimension supplies its own light, independent of Skygrid's lightmap.
        var vertices = batch.getBuffer(RenderType.beaconBeam(InventoryMenu.BLOCK_ATLAS, false));
        for (var face : view.surfaces) {
            var s = face.s;
            float brightness = face.brightness;
            for (var facet : face.facets) {
                if (Math.abs(facet.a.plane() - face.localPlane) > .009) continue;
                int rgb = facet.tinted ? face.litTint : face.litColor;
                int argb = 0xff000000 | (int)((rgb >> 16 & 255) * brightness) << 16
                        | (int)((rgb >> 8 & 255) * brightness) << 8 | (int)((rgb & 255) * brightness);
                emit(pose.last(), vertices, s, facet.a, facet.sprite, argb);
                emit(pose.last(), vertices, s, facet.b, facet.sprite, argb);
                emit(pose.last(), vertices, s, facet.c, facet.sprite, argb);
                emit(pose.last(), vertices, s, facet.c, facet.sprite, argb);
            }
        }
        batch.endBatch();
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer buffer, CameraScene.Surface s,
                             CameraPicture.Vertex vertex, TextureAtlasSprite sprite, int argb) {
        var axis = s.face().getAxis();
        float x = s.x() + (axis == Direction.Axis.X ? vertex.plane() : vertex.s());
        float y = s.y() + (axis == Direction.Axis.Y ? vertex.plane() : vertex.t());
        float z = s.z() + switch (axis) { case X -> vertex.s(); case Y -> vertex.t(); case Z -> vertex.plane(); };
        float u = sprite.getU0() + vertex.u() * (sprite.getU1() - sprite.getU0());
        float v = sprite.getV0() + vertex.v() * (sprite.getV1() - sprite.getV0());
        buffer.addVertex(pose, x, y, z).setColor(argb).setUv(u, v).setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, s.face().getStepX(), s.face().getStepY(), s.face().getStepZ());
    }

    private static void composite(CameraScene scene, CameraPicture.SurfaceView view, Matrix4f projection,
                                  NativeImage out, float[] sceneDepth) {
        int grid = CameraScene.SIDE, size = out.getWidth(), tile = size / grid;
        int[] samples = scene.cells();
        var eye = scene.eye();
        var forward = scene.forward();
        var up = scene.up();
        var right = scene.right();
        var resolved = new CameraPicture.SurfacePixel();
        for (int row = 0; row < grid; row++) for (int col = 0; col < grid; col++) {
            int index = row * grid + col, cell = samples[index];
            int sx = col * tile, sy = row * tile;
            if (cell == 0) {
                out.fillRect(sx, sy, tile, tile, CameraPicture.MASK);
                continue;
            }
            int backing = cell < 0 ? scene.backdrop()[index] : cell;
            for (int y = sy; y < sy + tile; y++) {
                double ny = 1 - (y + .5) * 2 / size;
                int glY = size - 1 - y;
                for (int x = sx; x < sx + tile; x++) {
                    var permitted = view.at(scene, index, x-sx, y-sy);
                    if (permitted == null) {
                        sceneDepth[y * size + x] = 0;
                        out.setPixelRGBA(x, y, CameraPicture.MASK);
                        continue;
                    }
                    double nx = (x + .5) * 2 / size - 1;
                    double dx = forward.x + nx * right.x + ny * up.x;
                    double dy = forward.y + nx * right.y + ny * up.y;
                    double dz = forward.z + nx * right.z + ny * up.z;
                    int sample = color.getPixelRGBA(x, glY);
                    float z = depth.get(glY * size + x);
                    double distance = linearDepth(z,projection);
                    if ((sample >>> 24) >= 32 && sample != CameraPicture.MASK && z < 1 && matches(view, permitted,
                            eye.x + dx * distance, eye.y + dy * distance, eye.z + dz * distance)) {
                        sceneDepth[y * size + x] = (float)distance;
                        out.setPixelRGBA(x, y, sample | 0xff000000);
                    } else {
                        // An ambiguous GL depth value cannot prove which block is in front.
                        // Intersect the actual triangles and use only the approved material.
                        view.sample(index,permitted,dx,dy,dz,resolved);
                        sceneDepth[y * size + x] = (float)resolved.distance;
                        int rgba=resolved.color!=0 ? resolved.color : resolved.distance==0 ? CameraPicture.MASK
                                : CameraPicture.background(scene.sky(),backing,dx,dy,dz);
                        out.setPixelRGBA(x,y,rgba);
                    }
                }
            }
        }
    }

    /** Depth unprojects to a unique point. A face visible in a different part of the image is insufficient. */
    private static boolean matches(CameraPicture.SurfaceView view, int[] permitted, double x, double y, double z) {
        for (int ref : permitted) {
            var s = view.surfaces[ref].s;
            if (Math.abs(s.face().getAxis().choose(x, y, z) - s.plane()) <= .010
                    && x >= s.x() - .001 && x <= s.x() + 1.001
                    && y >= s.y() - .001 && y <= s.y() + 1.001
                    && z >= s.z() - .001 && z <= s.z() + 1.001) return true;
        }
        return false;
    }

    static double linearDepth(float sample, Matrix4f projection) {
        // Use the matrix actually submitted to GL and avoid subtracting nearly equal floats.
        return projection.m32() / ((2.0*sample-1.0) + projection.m22());
    }

    static void clear() {
        CameraSectionTerrain.clear();
        if (target != null) { target.destroyBuffers(); target = null; }
        if (color != null) { color.close(); color = null; }
        if (storage != null) { storage.close(); storage = null; }
        if (depth != null) { MemoryUtil.memFree(depth); depth = null; }
        failed = false;
    }

    private CameraTerrain() {}
}

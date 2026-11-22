package dev.anya.storage.pocket;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import dev.anya.storage.AnyaStorage;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/** Rasterize Minecraft's own baked actor models without creating an entity in the viewer's world. */
final class CameraActors {
    private record Body(ModelLayerLocation layer, String texture) {}
    private static final Map<EntityType<?>, Body> BODIES = Map.of(
            EntityType.PLAYER, new Body(ModelLayers.PLAYER, "player/wide/steve"),
            EntityType.CREEPER, new Body(ModelLayers.CREEPER, "creeper/creeper"),
            EntityType.COW, new Body(ModelLayers.COW, "cow/cow"),
            EntityType.PIG, new Body(ModelLayers.PIG, "pig/pig"),
            EntityType.ZOMBIE, new Body(ModelLayers.ZOMBIE, "zombie/zombie"),
            EntityType.SKELETON, new Body(ModelLayers.SKELETON, "skeleton/skeleton"));
    private static final Map<Body, ModelPart> meshes = new HashMap<>();
    private static final Body SLIM = new Body(ModelLayers.PLAYER_SLIM, "player/slim/alex");
    private static TextureTarget target;
    private static ByteBufferBuilder storage;
    private static NativeImage readback;
    private static FloatBuffer depth;
    private static final float NEAR = .03f, FAR = CameraScene.FAR_CLIP;
    private static boolean failed;

    static final class Lens extends Camera {
        Lens(CameraScene scene) {
            var direction = scene.forward();
            double angle = Math.abs(direction.y) > .999
                    ? Math.atan2(-scene.right().z, -scene.right().x) : Math.atan2(-direction.x, direction.z);
            float yaw = (float) Math.toDegrees(angle);
            float pitch = (float) -Math.toDegrees(Math.asin(Mth.clamp(direction.y, -1, 1)));
            setRotation(yaw, pitch, 0);
            setPosition(scene.eye());
        }
    }

    static void draw(CameraScene scene, NativeImage frame, float[] sceneDepth) {
        if (failed || scene.actors().isEmpty()) return;
        RenderSystem.assertOnRenderThread();
        var mc = Minecraft.getInstance();
        int size = frame.getWidth();
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        float[] color = RenderSystem.getShaderColor().clone();
        boolean oldDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean oldDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        modelView.pushMatrix();
        try {
            if (target == null || target.width != size) {
                clear();
                target = new TextureTarget(size, size, true, Minecraft.ON_OSX);
                target.setClearColor(0, 0, 0, 0);
                storage = new ByteBufferBuilder(65536);
                readback = new NativeImage(size, size, false);
                depth = MemoryUtil.memAllocFloat(size * size);
            }
            modelView.identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().perspective((float)(2 * Math.atan(scene.up().length())), 1, NEAR, FAR), VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderFogStart(64);
            RenderSystem.setShaderFogEnd(80);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            var lens = new Lens(scene);
            Lighting.setupLevel();
            for (int index = 0; index < scene.actors().size(); index++) {
                var actor = scene.actors().get(index);
                var type = BuiltInRegistries.ENTITY_TYPE.byId(actor.typeId());
                var profile = type == EntityType.PLAYER ? CameraPlayerAppearance.resolve(actor.appearance()) : null;
                var skin = profile == null ? null : profile.getSkin();
                var body = skin != null && skin.model() == PlayerSkin.Model.SLIM ? SLIM : BODIES.get(type);
                if (body == null) continue;
                target.clear(Minecraft.ON_OSX);
                target.bindWrite(true);
                var pose = new PoseStack();
                pose.mulPose(lens.rotation().conjugate(new Quaternionf()));
                Vec3 feet = actor.feet().subtract(lens.getPosition());
                pose.translate(feet.x, feet.y, feet.z);
                pose.mulPose(Axis.YP.rotationDegrees(180 - actor.yaw()));
                pose.scale(-1, -1, 1);
                pose.translate(0, -1.501, 0);
                var model = meshes.computeIfAbsent(body, b -> mc.getEntityModels().bakeLayer(b.layer()));
                poseModel(model, actor);
                var texture = skin == null ? ResourceLocation.withDefaultNamespace("textures/entity/" + body.texture() + ".png") : skin.texture();
                if (type == EntityType.PLAYER) setSkinParts(model, profile == null ? 127 : actor.skinParts());
                var batch = MultiBufferSource.immediate(storage);
                int shade = Math.clamp(Math.round(255 * CameraPicture.light(actor.skyLight(), actor.blockLight(), scene)), 0, 255);
                if (profile != null && net.neoforged.fml.ModList.get().isLoaded("figura"))
                    CameraFigura.draw(profile.getProfile().getId(), model, pose, batch, shade);
                model.render(pose, batch.getBuffer(RenderType.entityCutoutNoCull(texture)), LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY, 0xff000000 | shade * 0x010101);
                batch.endBatch();
                target.bindWrite(false);
                depth.clear();
                GL11.glReadPixels(0, 0, size, size, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
                RenderSystem.bindTexture(target.getColorTextureId());
                // Preserve the alpha silhouette; Screenshot.takeScreenshot deliberately forces opaque pixels.
                readback.downloadTexture(0, false);
                int ref = -1 - index;
                int side = CameraScene.SIDE;
                for (int cell = 0; cell < scene.cells().length; cell++) {
                    if (scene.cells()[cell] != ref) continue;
                    int x0 = cell % side * size / side, y0 = cell / side * size / side;
                    int x1 = (cell % side + 1) * size / side, y1 = (cell / side + 1) * size / side;
                    for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                        if (scene.edgeBackdrop()[cell * 4 + CameraPicture.quadrant(x - x0, y - y0, x1 - x0)] < 0) continue;
                        int glY = size - 1 - y;
                        int pixel = readback.getPixelRGBA(x, glY);
                        if ((pixel >>> 24) <= 127) continue;
                        float z = depth.get(glY * size + x);
                        float distance = NEAR * FAR / (FAR - z * (FAR - NEAR));
                        int i = y * size + x;
                        if (z < 1 && distance < sceneDepth[i]) {
                            frame.setPixelRGBA(x, y, pixel);
                            sceneDepth[i] = distance;
                        }
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            failed = true;
            AnyaStorage.LOG.warn("Outdoor camera could not draw its visible figures", unavailable);
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.depthMask(oldDepthMask);
            if (oldDepth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (mc.level != null && mc.level.effects().constantAmbientLight()) Lighting.setupNetherLevel();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    private static void poseModel(ModelPart root, CameraScene.Actor actor) {
        root.getAllParts().forEach(ModelPart::resetPose);
        if (root.hasChild("head")) {
            var head = root.getChild("head");
            head.yRot = Mth.wrapDegrees(actor.headYaw() - actor.yaw()) * Mth.DEG_TO_RAD;
            head.xRot = actor.pitch() * Mth.DEG_TO_RAD;
            if (root.hasChild("hat")) root.getChild("hat").copyFrom(head);
        }
        float stride = Mth.cos(actor.phase() * .6662f) * actor.speed();
        limb(root, "right_leg", 1.4f * stride);
        limb(root, "left_leg", -1.4f * stride);
        limb(root, "right_arm", -stride);
        limb(root, "left_arm", stride);
        limb(root, "right_pants", 1.4f * stride);
        limb(root, "left_pants", -1.4f * stride);
        limb(root, "right_sleeve", -stride);
        limb(root, "left_sleeve", stride);
        limb(root, "right_hind_leg", 1.4f * stride);
        limb(root, "left_hind_leg", -1.4f * stride);
        limb(root, "right_front_leg", -1.4f * stride);
        limb(root, "left_front_leg", 1.4f * stride);
        for (String attachment : new String[]{"ear", "cloak"}) {
            if (root.hasChild(attachment)) root.getChild(attachment).visible = false;
        }
    }

    private static void limb(ModelPart root, String name, float swing) {
        if (root.hasChild(name)) root.getChild(name).xRot = swing;
    }

    private static void setSkinParts(ModelPart model, int mask) {
        // This mesh is shared by players of the same build. Undo any prior Figura visibility.
        for (String name : new String[]{"head","body","left_arm","right_arm","left_leg","right_leg"})
            model.getChild(name).visible = true;
        model.getChild("hat").visible = (mask & PlayerModelPart.HAT.getMask()) != 0;
        model.getChild("jacket").visible = (mask & PlayerModelPart.JACKET.getMask()) != 0;
        model.getChild("left_sleeve").visible = (mask & PlayerModelPart.LEFT_SLEEVE.getMask()) != 0;
        model.getChild("right_sleeve").visible = (mask & PlayerModelPart.RIGHT_SLEEVE.getMask()) != 0;
        model.getChild("left_pants").visible = (mask & PlayerModelPart.LEFT_PANTS_LEG.getMask()) != 0;
        model.getChild("right_pants").visible = (mask & PlayerModelPart.RIGHT_PANTS_LEG.getMask()) != 0;
    }

    static void clear() {
        failed = false;
        meshes.clear();
        if (readback != null) { readback.close(); readback = null; }
        if (depth != null) { MemoryUtil.memFree(depth); depth = null; }
        if (storage != null) { storage.close(); storage = null; }
        if (target != null) { target.destroyBuffers(); target = null; }
    }

    private CameraActors() {}
}

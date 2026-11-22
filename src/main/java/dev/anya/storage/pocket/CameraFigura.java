package dev.anya.storage.pocket;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.model.FiguraModelPart;
import org.figuramc.figura.model.ParentType;
import org.figuramc.figura.model.PartCustomization;
import org.figuramc.figura.model.VanillaModelData;
import org.figuramc.figura.model.rendering.Vertex;
import org.figuramc.figura.model.rendering.texture.FiguraTexture;
import org.figuramc.figura.model.rendering.texture.FiguraTextureSet.OverrideType;
import org.figuramc.figura.model.rendering.texture.RenderTypes;
import org.figuramc.figura.permissions.Permissions;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/** Reads an already loaded avatar's ordinary textured body mesh. No Lua calls, render tasks,
 * world/GUI parts, synthetic player entity or avatar network lookup. */
final class CameraFigura {
    private record Piece(PoseStack.Pose pose, Matrix3f uv, FiguraTexture texture,
                         List<Vertex> vertices, int color) {}
    private final List<Piece> pieces = new ArrayList<>();
    private final VanillaModelData vanilla = new VanillaModelData();
    private int facesLeft, partsLeft = 1024;

    private CameraFigura(int faces, ModelPart model) {
        facesLeft = Math.min(faces, 4096);
        vanilla.update(ParentType.Head, model.getChild("head"));
        vanilla.update(ParentType.Body, model.getChild("body"));
        vanilla.update(ParentType.LeftArm, model.getChild("left_arm"));
        vanilla.update(ParentType.RightArm, model.getChild("right_arm"));
        vanilla.update(ParentType.LeftLeg, model.getChild("left_leg"));
        vanilla.update(ParentType.RightLeg, model.getChild("right_leg"));
    }

    static void draw(UUID id, ModelPart model, PoseStack pose, MultiBufferSource buffers, int shade) {
        // Unknown/incompatible optional versions leave the viewer's normal player skin usable.
        try {
            Avatar avatar = AvatarManager.getLoadedAvatar(id);
            if (avatar == null || !avatar.loaded || avatar.renderer == null || avatar.renderer.root == null) return;
            int allowed = avatar.permissions.get(Permissions.COMPLEXITY);
            if (allowed <= 0) return;
            var view = new CameraFigura(allowed, model);
            pose.pushPose();
            try {
                // Figura's ordinary body space has its origin at the feet, Y up, in model pixels.
                pose.translate(0, 1.5, 0);
                pose.scale(-1f/16, -1f/16, 1f/16);
                if (!view.collect(avatar.renderer.root, pose, new Matrix3f(),
                        new Vector3f(shade), 1, 0) || view.pieces.isEmpty()) return;
            } finally {
                pose.popPose();
            }
            for (Piece piece : view.pieces) piece.texture.uploadIfDirty();
            for (Piece piece : view.pieces) {
                var consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(piece.texture.getLocation()));
                var uv = new Vector3f();
                for (Vertex v : piece.vertices) {
                    piece.uv.transform(uv.set(v.u / piece.texture.getWidth(), v.v / piece.texture.getHeight(), 1));
                    consumer.addVertex(piece.pose.pose(), v.x, v.y, v.z).setColor(piece.color).setUv(uv.x, uv.y)
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                            .setNormal(piece.pose, v.nx, v.ny, v.nz);
                }
            }
            if (avatar.luaRuntime != null && avatar.luaRuntime.vanilla_model != null
                    && avatar.permissions.get(Permissions.VANILLA_MODEL_EDIT) > 0) {
                var parts = avatar.luaRuntime.vanilla_model;
                visible(model,"head",parts.HEAD.getVisible()); visible(model,"hat",parts.HAT.getVisible());
                visible(model,"body",parts.BODY.getVisible()); visible(model,"jacket",parts.JACKET.getVisible());
                visible(model,"left_arm",parts.LEFT_ARM.getVisible()); visible(model,"right_arm",parts.RIGHT_ARM.getVisible());
                visible(model,"left_sleeve",parts.LEFT_SLEEVE.getVisible()); visible(model,"right_sleeve",parts.RIGHT_SLEEVE.getVisible());
                visible(model,"left_leg",parts.LEFT_LEG.getVisible()); visible(model,"right_leg",parts.RIGHT_LEG.getVisible());
                visible(model,"left_pants",parts.LEFT_PANTS.getVisible()); visible(model,"right_pants",parts.RIGHT_PANTS.getVisible());
            }
        } catch (LinkageError | RuntimeException unavailable) {
            // Figura can unload an avatar, and its optional API varies between releases.
        }
    }

    private static void visible(ModelPart model, String name, Boolean flag) {
        if (Boolean.FALSE.equals(flag)) model.getChild(name).visible = false;
    }

    private boolean collect(FiguraModelPart part, PoseStack pose, Matrix3f parentUv,
                            Vector3f parentColor, float parentAlpha, int depth) {
        if (--partsLeft < 0 || depth > 40) return false;
        var source = part.customization;
        if (part.parentType.isSeparate || part.parentType.isPivot || part.parentType.isRenderLayer
                || !source.visible || source.getPrimaryRenderType() == RenderTypes.NONE) return true;
        // Never resolve user-specified resource locations, secondary passes or custom render tasks.
        if (source.primaryTexture != null && source.primaryTexture.getOverrideType() != OverrideType.PRIMARY) return true;
        float alpha = parentAlpha * (source.alpha == null ? 1 : source.alpha);
        if (!(alpha > .5f)) return true;
        var local = new PartCustomization();
        source.copyTo(local);
        var attachment = vanilla.partMap.get(part.parentType);
        if (attachment != null) {
            if (!attachment.visible) return true;
            if (!part.overrideVanillaRot()) local.addOffsetRot(attachment.rot);
            if (!part.overrideVanillaPos()) {
                var displacement = part.parentType.offset.copy().subtract(attachment.pos);
                local.addOffsetPivot(displacement);
                local.addOffsetPos(displacement);
            }
        }
        local.recalculate();
        pose.pushPose();
        try {
            pose.mulPose(local.positionMatrix.toMatrix4f());
            var uv = new Matrix3f(parentUv).mul(local.uvMatrix.toMatrix3f());
            var color = new Vector3f(parentColor).mul((float)local.color.x, (float)local.color.y, (float)local.color.z);
            int argb = channel(alpha * 255) << 24 | channel(color.x) << 16 | channel(color.y) << 8 | channel(color.z);
            for (var group : part.vertices == null ? java.util.Map.<Integer,List<Vertex>>of().entrySet() : part.vertices.entrySet()) {
                var vertices = group.getValue();
                if (vertices == null || vertices.isEmpty()) continue;
                if (vertices.size() % 4 != 0 || vertices.size() / 4 > facesLeft) return false;
                facesLeft -= vertices.size() / 4;
                int index = group.getKey();
                if (part.textures == null || index < 0 || index >= part.textures.size()) continue;
                var textures = part.textures.get(index).textures;
                if (textures.length > 0 && textures[0] != null)
                    pieces.add(new Piece(pose.last().copy(), uv, textures[0], vertices, argb));
            }
            if (part.children != null)
                for (var child : part.children) if (!collect(child, pose, uv, color, alpha, depth + 1)) return false;
            return true;
        } finally {
            pose.popPose();
        }
    }

    private static int channel(float value) {
        return Mth.clamp(Math.round(value), 0, 255);
    }
}

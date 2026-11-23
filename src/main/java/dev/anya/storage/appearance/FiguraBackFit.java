package dev.anya.storage.appearance;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.model.FiguraModelPart;
import org.figuramc.figura.model.PartCustomization;
import org.figuramc.figura.model.rendering.ImmediateAvatarRenderer;
import org.figuramc.figura.model.rendering.PartFilterScheme;
import org.joml.Matrix4f;

/** The optional adapter runs on the render thread and observes only an already-loaded avatar. */
public final class FiguraBackFit {
    private record Rig(FiguraModelPart root, FiguraBackAnchor anchor) {}
    private record Draw(AbstractClientPlayer player, PoseStack pose, MultiBufferSource buffers,
                        ImmediateAvatarRenderer renderer, FiguraBackAnchor anchor) {}

    private static final LinkedHashMap<UUID, Rig> rigs = new LinkedHashMap<>(16, .75f, true);
    private static Draw draw;
    private static Matrix4f itemPose;

    static void begin(AbstractClientPlayer player, PoseStack pose, MultiBufferSource buffers) {
        try {
            var avatar = AvatarManager.getLoadedAvatar(player.getUUID());
            if (avatar == null || !avatar.loaded || !(avatar.renderer instanceof ImmediateAvatarRenderer renderer)
                    || renderer.root == null) return;
            var rig = rigs.get(player.getUUID());
            if (rig == null || rig.root != renderer.root) {
                rig = new Rig(renderer.root, FiguraBackAnchor.find(renderer.root));
                if (rigs.size() >= 32 && !rigs.containsKey(player.getUUID())) rigs.pollFirstEntry();
                rigs.put(player.getUUID(), rig);
            }
            if (rig.anchor != null) draw = new Draw(player, pose, buffers, renderer, rig.anchor);
        } catch (RuntimeException | LinkageError unsupported) {
            end();
        }
    }

    /** The matrix already contains the player pose, interpolated animation and all parent scaling. */
    public static void observe(ImmediateAvatarRenderer renderer, FiguraModelPart part, PartCustomization transform) {
        var current = draw;
        if (current == null || current.anchor.part() != part || current.renderer != renderer
                || renderer.entity != current.player || renderer.bufferSource != current.buffers
                || renderer.currentFilterScheme != PartFilterScheme.MODEL) return;
        try {
            var anchor = current.anchor;
            Matrix4f matrix = transform.positionMatrix.toMatrix4f()
                    .translate(anchor.x(), anchor.y(), anchor.z()).scale(anchor.scale());
            float determinant = matrix.determinant3x3();
            itemPose = matrix.isFinite() && Math.abs(determinant) > 1e-9f ? matrix : null;
        } catch (RuntimeException | LinkageError unsupported) {
            itemPose = null;
        }
    }

    static boolean apply(AbstractClientPlayer player, PoseStack pose) {
        if (draw == null || draw.player != player || draw.pose != pose || itemPose == null) return false;
        pose.setIdentity();
        pose.mulPose(itemPose);
        return true;
    }

    static void end() {
        draw = null;
        itemPose = null;
    }

    static void clear() {
        end();
        rigs.clear();
    }

    private FiguraBackFit() {}
}

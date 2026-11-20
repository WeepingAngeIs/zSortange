package dev.anya.storage.pocket;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.core.Direction;

final class EntranceFacePose {
    /** The unrotated quad faces north, with the top of the artwork pointing up. */
    static void orient(PoseStack pose, Direction face, float cameraYaw) {
        if (face.getAxis().isHorizontal()) {
            pose.mulPose(Axis.YP.rotationDegrees(180 - face.toYRot()));
            return;
        }
        // Quarter turns keep the complete square inside the face at oblique viewing angles.
        pose.mulPose(Axis.YP.rotationDegrees(-Direction.fromYRot(cameraYaw).toYRot()));
        pose.mulPose(Axis.XP.rotationDegrees(face == Direction.UP ? 90 : -90));
    }

    private EntranceFacePose() {}
}

package dev.anya.storage.appearance;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

/** The hand pass's walk and view motion, applied equally to the wrist and the tablet. */
record TabletHandMotion(float yaw, float pitch, float walkX, float walkY, float roll) {
    static final TabletHandMotion STILL = new TabletHandMotion(0, 0, 0, 0, 0);

    TabletHandMotion toward(TabletHandMotion next, float amount) {
        return new TabletHandMotion(yaw + (next.yaw - yaw) * amount, pitch + (next.pitch - pitch) * amount,
                walkX + (next.walkX - walkX) * amount, walkY + (next.walkY - walkY) * amount,
                roll + (next.roll - roll) * amount);
    }

    void apply(PoseStack pose, int width, int height, float progress) {
        float weight = 1f - TabletHandlingPose.eased(progress);
        if (weight == 0 || equals(STILL)) return;
        float unit = Math.min(width, height);
        float dx = unit * (-yaw * .006f + walkX * .12f) * weight;
        float dy = unit * (pitch * .006f - walkY * .12f) * weight;
        pose.mulPose(new Matrix4f()
                .translation(width / 2f + dx, height / 2f + dy, 0)
                .rotateZ((float) Math.toRadians(roll * weight))
                .rotateY((float) Math.toRadians(-yaw * weight))
                .rotateX((float) Math.toRadians(pitch * weight))
                .translate(-width / 2f, -height / 2f, 0));
    }
}

package dev.anya.storage.appearance;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Native device coordinates are measured on its 432 by 304 pixel face. GUI Y increases downwards. */
public final class TabletHandlingPose {
    static final int WIDTH = 432, HEIGHT = 304;
    public static float eased(float progress) {
        float t = Math.clamp(progress, 0f, 1f);
        return t * t * (3f - 2f * t);
    }
    static float openScale(int width, int height) {
        return Math.min(1f, Math.min(Math.max(1, width - 8) / (float) WIDTH, Math.max(1, height - 8) / (float) HEIGHT));
    }
    public static Matrix4f deviceMatrix(int width, int height, boolean right, float swing, float equip, float progress) {
        float lift = eased(progress), rest = 1f - lift, side = right ? 1f : -1f;
        float heldScale = Math.min(width * .36f / WIDTH, height * .42f / HEIGHT);
        float arc = (float) Math.sin(Math.sqrt(Math.clamp(swing, 0f, 1f)) * Math.PI);
        float heldX = width * (.5f + side * (.225f - arc * .025f));
        float heldY = height * (.78f + .15f * Math.clamp(equip, 0f, 1f) - .052f * arc);
        float scale = rest * heldScale + lift * openScale(width, height);
        return new Matrix4f()
                .translation(rest * heldX + lift * width / 2f, rest * heldY + lift * height / 2f, 0)
                .scale(scale)
                .rotateZ(rad(side * (5f + 3f * arc) * rest))
                .rotateY(rad(-side * 19f * rest))
                .rotateX(rad(39f * rest))
                .translate(-WIDTH / 2f, -HEIGHT / 2f, 0);
    }
    public static Matrix4f armMatrix(int width, int height, boolean right, boolean slim, float swing, float equip, float progress) {
        float side = right ? 1f : -1f;
        float wristX = -side * (slim ? 5.5f : 6f) / 16f;
        // HumanoidModel.setupAnim resets both shoulder pivots to Y=2, including the slim model.
        float wristY = 12f / 16f;
        var tablet = deviceMatrix(width, height, right, swing, equip, progress);
        var wrist = tablet.transformPosition(right ? 423f : 9f, 229f, -5f, new Vector3f());
        float armScale = 248f * tablet.getScale(new Vector3f()).x;
        // The wrist tracks the edge; the elbow approaches from below instead of rotating like a bolted handle.
        return new Matrix4f().translation(wrist)
                .rotateZ(rad(side * (145f + 2f * eased(progress))))
                .rotateX(rad(-23f - 13f * eased(progress))).rotateY(rad(side * 16f))
                .scale(armScale, armScale, -armScale)
                .translate(-wristX, -wristY, 0);
    }
    private static float rad(float degrees) { return (float) Math.toRadians(degrees); }
    private TabletHandlingPose() {}
}

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
        float heldScale = Math.min(width * .36f / WIDTH, height * .43f / HEIGHT);
        float arc = (float) Math.sin(Math.sqrt(Math.clamp(swing, 0f, 1f)) * Math.PI);
        // Leave enough room beyond the outside rail to see the wrist coming in from below.
        float heldX = width * (.5f + side * (.258f - arc * .018f));
        // Keep the glass visible and let the lower edge of the case run out of the shot.
        float heldY = height - heldScale * HEIGHT * .35f
                + height * (.13f * Math.clamp(equip, 0f, 1f) - .032f * arc);
        // Lift the wrist clear of the hotbar before bringing the glass square to the camera.
        float travelY = lift + .82f * lift * rest;
        float scale = rest * heldScale + lift * openScale(width, height);
        return new Matrix4f()
                .translation(rest * heldX + lift * width / 2f, (1f - travelY) * heldY + travelY * height / 2f, 0)
                .scale(scale)
                .rotateZ(rad(side * (4f + 3f * arc) * rest))
                .rotateY(rad(-side * 17f * rest))
                .rotateX(rad(30f * rest))
                .translate(-WIDTH / 2f, -HEIGHT / 2f, 0);
    }
    public static Matrix4f armMatrix(int width, int height, boolean right, boolean slim, float swing, float equip, float progress) {
        float side = right ? 1f : -1f;
        // Vanilla's neutral player arm has a 0.1 radian roll. Locate the center of its actual
        // hand end after that roll and its (-/+5, 2, 0) shoulder translation, in model units.
        float offset = slim ? .5f : 1f;
        float wristX = -side * (5f + offset * (float) Math.cos(.1) + 10f * (float) Math.sin(.1)) / 16f;
        float wristY = (2f + 10f * (float) Math.cos(.1) - offset * (float) Math.sin(.1)) / 16f;
        var tablet = deviceMatrix(width, height, right, swing, equip, progress);
        var wrist = tablet.transformPosition(right ? 420f : 12f, 170f, -5f, new Vector3f());
        float armScale = 248f * tablet.getScale(new Vector3f()).x;
        // The wrist tracks the edge; the elbow approaches from below instead of rotating like a bolted handle.
        return new Matrix4f().translation(wrist)
                .rotateZ(rad(side * (140f + 7f * eased(progress))))
                .rotateX(rad(-22f - 14f * eased(progress))).rotateY(rad(side * 16f))
                .scale(armScale, armScale, -armScale)
                .translate(-wristX, -wristY, 0);
    }
    private static float rad(float degrees) { return (float) Math.toRadians(degrees); }
    private TabletHandlingPose() {}
}

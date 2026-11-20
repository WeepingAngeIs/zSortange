package dev.anya.storage.appearance;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/** Samples the candidate's local pose and anticipation clock without starting a game or a window. */
public final class HandlingCheck {
    private static void check(boolean condition, String explanation) {
        if (!condition) throw new AssertionError(explanation);
    }

    private static void close(float expected, float actual, String explanation) {
        check(Math.abs(expected - actual) < 0.001f, explanation + " (expected " + expected + ", got " + actual + ")");
    }

    private static void anticipation() {
        var lift = new TabletLift();
        var repeatedUse = new TabletLift();
        long start = 2000;
        close(0, lift.progress(start - 1), "Held before use");
        lift.use(start);
        repeatedUse.use(start);
        boolean atHold = false;
        boolean reversing = false;
        int idle = -1;
        float previous = 0;
        for (int elapsed = 0; elapsed < 3000; elapsed++) {
            long now = start + elapsed;
            float p = lift.progress(now);
            check(Float.isFinite(p) && p >= 0 && p <= TabletLift.ANTICIPATION && p < 1,
                    "Use alone cannot occupy the fully open menu pose");
            if (atHold && p < previous) reversing = true;
            check(reversing ? p <= previous : p >= previous, "A denied opening has exactly one rise and return");
            if (p > 0 && elapsed % 47 == 0) repeatedUse.use(now);
            close(p, repeatedUse.progress(now), "Repeated use must not postpone the return");
            if (elapsed == 0) close(0, p, "No use-edge position jump");
            if (elapsed == TabletLift.RISE_MS / 2) check(p > 0 && p < 1, "Partial progress before any menu response");
            float rising = TabletLift.RISE_MS * TabletLift.ANTICIPATION;
            if (elapsed >= rising && elapsed <= rising + TabletLift.HOLD_MS)
                close(TabletLift.ANTICIPATION, p, "Partial raise held briefly while the menu is pending");
            if (p == TabletLift.ANTICIPATION) atHold = true;
            if (reversing && p == 0) { idle = elapsed; break; }
            previous = p;
        }
        check(idle == (int) Math.ceil((TabletLift.RISE_MS + TabletLift.RETURN_MS) * TabletLift.ANTICIPATION
                        + TabletLift.HOLD_MS),
                "A declined open returns at the configured deadline");
        for (int elapsed : new int[]{1, TabletLift.RISE_MS / 2, TabletLift.RISE_MS, idle - 1}) {
            start += 4000;
            lift.use(start);
            lift.progress(start + elapsed);
            lift.reset();
            close(0, lift.progress(start + elapsed), "Cancellation drops outstanding pose");
            close(0, lift.progress(start + 3500), "Cancelled anticipation cannot wake up later");
        }
    }

    private static void samePoint(Vector3f expected, Vector3f actual, String explanation) {
        close(expected.x(), actual.x(), explanation + " X");
        close(expected.y(), actual.y(), explanation + " Y");
        close(expected.z(), actual.z(), explanation + " Z");
    }

    private static Vector3f point(Matrix4f transform, float x, float y, float z) {
        return transform.transformPosition(new Vector3f(x, y, z));
    }

    // Project all six faces as the native arm and hardware share an orthographic HUD projection.
    private static Area silhouette(Vector3f[] corners) {
        var result = new Area();
        for (int[] face : new int[][]{{0,1,3,2}, {4,5,7,6}, {0,1,5,4}, {2,3,7,6}, {0,2,6,4}, {1,3,7,5}}) {
            var polygon = new Path2D.Float();
            polygon.moveTo(corners[face[0]].x, corners[face[0]].y);
            for (int i = 1; i < face.length; i++) polygon.lineTo(corners[face[i]].x, corners[face[i]].y);
            polygon.closePath();
            result.add(new Area(polygon));
        }
        return result;
    }

    private static Vector3f skinPoint(Matrix4f arm, float side, float x, float y, float z) {
        // Vanilla's PlayerModel rotates its local mesh before applying the shoulder pivot.
        return arm.transformPosition(new Vector3f(x, y, z).rotateZ(side * .1f).add(-side * 5f, 2, 0).div(16));
    }

    private static void exposedSleeve(boolean right, boolean slim) {
        int width = 960, height = 640;
        var pixels = new Matrix4f().scaling(2, 2, 1);
        var arm = pixels.mul(TabletHandlingPose.armMatrix(width / 2, height / 2, right, slim, 0, 0, 0), new Matrix4f());
        var tablet = pixels.mul(TabletHandlingPose.deviceMatrix(width / 2, height / 2, right, 0, 0, 0), new Matrix4f());
        float side = right ? 1 : -1;
        float centerX = -side * (slim ? .5f : 1f);
        float halfWidth = (slim ? 1.5f : 2f) + .25f; // The sleeve is one quarter texel proud of the arm.
        var sleeve = new Vector3f[8];
        var bezel = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            sleeve[i] = skinPoint(arm, side, centerX + ((i & 1) == 0 ? -halfWidth : halfWidth),
                    (i & 2) == 0 ? -2.25f : 10.25f, (i & 4) == 0 ? -2.25f : 2.25f);
            bezel[i] = point(tablet, (i & 1) == 0 ? 0 : 432, (i & 2) == 0 ? 0 : 304, (i & 4) == 0 ? -6 : 0);
        }
        var visible = silhouette(sleeve);
        visible.intersect(new Area(new Rectangle2D.Float(0, 0, width, height)));
        visible.subtract(silhouette(bezel));
        var bounds = visible.getBounds2D();
        check(bounds.getWidth() >= 45 && bounds.getHeight() >= 45, "Only a corner of the cuff is visible: " + bounds);

        var hand = skinPoint(arm, side, centerX, 10, 0);
        var elbow = skinPoint(arm, side, centerX, -2, 0);
        double dx = elbow.x - hand.x, dy = elbow.y - hand.y, length = Math.hypot(dx, dy);
        int exposed = 0;
        for (int d = 0; d < length; d++) {
            if (visible.contains(hand.x + dx * d / length, hand.y + dy * d / length)) exposed++;
        }
        check(exposed >= 45 && exposed <= 70, "Visible forearm behind the bezel was " + exposed + " pixels");
        System.out.printf("PASS: %s %s cuff, %d visible forearm pixels at 960x640%n",
                right ? "right" : "left", slim ? "slim" : "wide", exposed);
    }

    private static void geometry(int width, int height, float swing, float equip) {
        int w = TabletHandlingPose.WIDTH, h = TabletHandlingPose.HEIGHT;
        var opening = TabletDevice.openBounds(width, height);
        float previousEase = 0;
        for (int step = 0; step <= 20; step++) {
            float p = step / 20f;
            float ease = TabletHandlingPose.eased(p);
            check(ease >= previousEase && ease <= 1, "Ease stays monotonic and bounded");
            previousEase = ease;
            Matrix4f right = TabletHandlingPose.deviceMatrix(width, height, true, swing, equip, p);
            Matrix4f left = TabletHandlingPose.deviceMatrix(width, height, false, swing, equip, p);
            for (int x : new int[]{0, w}) {
                for (int y : new int[]{0, h}) {
                    Vector3f r = point(right, x, y, 0);
                    Vector3f l = point(left, w - x, y, 0);
                    samePoint(new Vector3f(width - r.x(), r.y(), r.z()), l, "Mirrored physical handling");
                }
            }
            for (boolean rightHand : new boolean[]{true, false}) {
                Matrix4f device = rightHand ? right : left;
                // Local screen-left stays screen-left even when held in the left hand.
                check(point(device, w, h / 2f, 0).x() > point(device, 0, h / 2f, 0).x(), "Front art flipped");
                for (boolean slim : new boolean[]{false, true}) {
                    Matrix4f arm = TabletHandlingPose.armMatrix(width, height, rightHand, slim, swing, equip, p);
                    float side = rightHand ? 1 : -1;
                    var handEnd = new Vector3f(-side * (slim ? .5f : 1f), 10, 0)
                            .rotateZ(side * .1f).add(-side * 5f, 2, 0).div(16);
                    Vector3f wrist = arm.transformPosition(new Vector3f(handEnd));
                    Vector3f grip = point(device, rightHand ? 420 : 12, 170, -5);
                    samePoint(grip, wrist, "Wrist must stay attached to its side rail during the entire lift");
                    if (step == 0 && swing == 0 && equip == 0) {
                        Vector3f shoulder = point(arm, -side * 5f / 16f, 2f / 16f, 0);
                        check(rightHand ? shoulder.x() > wrist.x() : shoulder.x() < wrist.x(),
                                "Idle forearm approaches the rail from the selected side");
                        check(shoulder.y() > wrist.y() + 0.1f * Math.min(width, height), "Forearm must remain visible");
                        check(shoulder.y() >= height || shoulder.x() <= 0 || shoulder.x() >= width,
                                "Idle arm must enter from the viewport edge");
                        check(wrist.x() > 3 && wrist.x() < width - 3 && wrist.y() < height - .035f * height,
                                "The hand end must remain visibly inside the viewport at rest");
                    }
                }
                if (step == 0) {
                    Vector3f normal = device.transformDirection(new Vector3f(0, 0, 1)).normalize();
                    check(normal.y() < -0.25 && normal.z() > 0.25 && normal.z() < 0.95, "Upward-facing rest angle");
                    check(rightHand ? normal.x() < -0.1 : normal.x() > 0.1, "Screen inclined toward its holder");
                    Vector3f center = point(device, w / 2f, h / 2f, 0);
                    check(center.y() > height * .76f, "Resting device is low");
                    if (swing == 0 && equip == 0) {
                        Vector3f bottom = point(device, rightHand ? w : 0, h, 0);
                        check(bottom.y() > height + 2, "The outside rim must run naturally out of the shot");
                    }
                } else if (step == 20) {
                    samePoint(new Vector3f(opening.x(), opening.y(), 0), point(device, 0, 0, 0), "GUI top left");
                    samePoint(new Vector3f(opening.x() + w * opening.scale(), opening.y() + h * opening.scale(), 0),
                            point(device, w, h, 0), "GUI bottom right");
                }
            }
        }
    }

    public static void main(String[] args) {
        anticipation();
        for (boolean right : new boolean[]{false, true}) {
            for (boolean slim : new boolean[]{false, true}) exposedSleeve(right, slim);
        }
        for (int[] viewport : new int[][]{{480, 312}, {270, 198}, {854, 480}}) {
            geometry(viewport[0], viewport[1], 0, 0);
            geometry(viewport[0], viewport[1], 0.6f, 0.3f);
        }
        System.out.println("PASS: bounded use-edge rise/decline, mirrored wide/slim skin hand ends touch the rail, "
                + "visible grip with cropped resting rim, upward and inward tilt, exact GUI bounds at three sizes");
    }
}

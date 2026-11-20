import dev.anya.storage.appearance.GuardianBlink;
import dev.anya.storage.appearance.TabletHandlingPose;
import dev.anya.storage.appearance.TabletLift;
import org.joml.Matrix4f;

/** Export the production transforms and original blink clock, without a level or game runtime. */
public final class PreviewPose {
    private static void write(Matrix4f matrix) {
        for (float value : matrix.get(new float[16])) System.out.print(" " + value);
    }

    public static void main(String[] args) {
        int width = Integer.parseInt(args[0]), height = Integer.parseInt(args[1]);
        boolean right = args[2].equals("right"), slim = Boolean.parseBoolean(args[3]);
        var blink = new GuardianBlink(true);
        var lift = new TabletLift();
        lift.use(0);
        float handoff = lift.progress(60);
        for (int ms = 0; ms <= 1200; ms += 10) {
            String face = ms < 60 ? "WAIT" : blink.present(ms, true, true).name();
            float progress = ms < 60 ? lift.progress(ms) : Math.min(1, handoff + (ms-60f)/TabletLift.RISE_MS);
            float dim = ms < 60 ? 0f : TabletHandlingPose.eased((ms-60f) / TabletLift.RISE_MS);
            System.out.print(ms + " " + face + " " + progress + " " + dim);
            write(TabletHandlingPose.deviceMatrix(width, height, right, 0, 0, progress));
            write(TabletHandlingPose.armMatrix(width, height, right, slim, 0, 0, progress));
            System.out.println();
            if (face.equals("DONE")) return;
        }
        throw new AssertionError("The existing menu did not reach its reveal");
    }
}

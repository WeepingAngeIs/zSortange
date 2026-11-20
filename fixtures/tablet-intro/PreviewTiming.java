import dev.anya.storage.appearance.GuardianBlink;

/** Exports transitions from the actual client presentation clock at GIF's 10 ms resolution. */
public final class PreviewTiming {
    public static void main(String[] args) {
        GuardianBlink blink = new GuardianBlink(true);
        GuardianBlink.Frame previous = null;
        for (int elapsed = 0; elapsed <= 3000; elapsed += 10) {
            var frame = blink.present(elapsed, true, true);
            if (frame != previous) {
                System.out.println(elapsed + " " + frame);
                previous = frame;
            }
            if (frame == GuardianBlink.Frame.DONE) return;
        }
        throw new AssertionError("Storage did not appear within the preview time limit");
    }
}

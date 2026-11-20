import dev.anya.storage.appearance.GuardianBlink;
import static dev.anya.storage.appearance.GuardianBlink.Frame.*;

/** Run with the candidate's GuardianBlink on the class path. No game classes are needed. */
public final class GuardianBlinkCheck {
    public static void main(String[] args) {
        int[] ends = {260, 310, 390, 440, 610, 660, 760, 810, 1050};
        var frames = new GuardianBlink.Frame[] {OPEN, HALF, CLOSED, HALF, OPEN, HALF, CLOSED, HALF, OPEN};
        var blink = new GuardianBlink(true);
        require(!blink.coversInput(), "No hidden input before the first draw");
        expect(blink, 5000, false, false, WAIT);
        long start = 5040;
        for (int i = 0; i < ends.length; i++) {
            int from = i == 0 ? 0 : ends[i - 1];
            for (int ms = from; ms < ends[i]; ms++) expect(blink, start + ms, true, true, frames[i]);
        }
        expect(blink, start + 1050, true, true, DONE);
        expect(blink, start + 2000, true, true, DONE);

        var slowSync = new GuardianBlink(true);
        expect(slowSync, 70, false, false, WAIT);
        expect(slowSync, 369, false, false, WAIT);
        expect(slowSync, 370, false, false, DONE);
        expect(slowSync, 371, true, true, DONE);
        var settings = new GuardianBlink(true);
        expect(settings, 0, false, true, DONE);
        expect(settings, 60, true, true, DONE);
        var closed = new GuardianBlink(true);
        expect(closed, 0, true, true, OPEN);
        closed.dismiss();
        expect(closed, 100, true, true, DONE);
        var pageChanged = new GuardianBlink(true);
        expect(pageChanged, 5, true, true, OPEN);
        expect(pageChanged, 70, false, true, DONE);
        expect(pageChanged, 90, true, true, DONE);
        expect(new GuardianBlink(false), 0, true, true, DONE);
        expect(new GuardianBlink(true), 2000, true, true, OPEN);
        System.out.println("PASS: two blinks in 1050 ms, opt-out, input coverage, bounded sync, nonstorage, close, page changes and independent reopen");
    }

    private static void expect(GuardianBlink blink, long time, boolean storage, boolean known, GuardianBlink.Frame expected) {
        require(blink.present(time, storage, known) == expected, "Unexpected frame at " + time);
        require(blink.coversInput() == (expected != DONE), "Unexpected input coverage at " + time);
    }

    private static void require(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}

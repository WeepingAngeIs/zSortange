package dev.anya.storage.appearance;

/** Sample representative rows of the production clocks for an offline preview at 10 ms intervals. */
public final class PortraitTimeline {
    public static void main(String[] args) {
        for (int row = 0; row < 4; row++) {
            var opening = new GuardianBlink(true);
            for (int ms = 0; ms <= 1110; ms += 10) {
                if (ms >= 60) opening.present(ms, true, true);
                int col = ms < 60 ? -1 : opening.column();
                System.out.printf("open%d %d %d %d%n", row, ms, col < 0 ? -1 : row, col);
            }
        }
        for (int visit = 0; visit < 4; visit++) {
            for (int ms = 0; ; ms += 10) {
                int col = TabletIdle.columnAt(ms);
                System.out.printf("idle%d %d %d %d%n", visit, ms, col < 0 ? -1 : 4 + visit, col);
                if (col < 0) break;
                if (ms >= 2000) throw new AssertionError("Unexpected idle duration");
            }
        }
    }
}

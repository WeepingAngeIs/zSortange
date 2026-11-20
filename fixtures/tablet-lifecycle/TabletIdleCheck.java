package dev.anya.storage.appearance;

/** Headless fixture for the timer behind the first-person hand/HUD presentation. */
public final class TabletIdleCheck {
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        int[] draws = {0};
        var idle = new TabletIdle(() -> 1000L, () -> { draws[0]++; return 4; });
        Object player = new Object(), first = new Object(), second = new Object();
        check(idle.paintColumn(0) < 0, "Absent Remote painted an idle visit");
        check(idle.bind(player, first, false, 2), "Initial ownership");
        check(idle.paintColumn(0) < 0 && idle.paintColumn(999) < 0 && draws[0] == 0, "Quiet hold");
        int[] starts = {0,85,175,275,385,635,760,885,1010};
        for (int i = 0; i < starts.length; i++) {
            check(idle.paintColumn(1000 + starts[i]) == i && idle.row() == 4, "Visit frame " + i);
            int end = i + 1 < starts.length ? starts[i+1] : 1140;
            check(idle.paintColumn(999 + end) == i, "Ending frame too early " + i);
        }
        check(draws[0] == 1 && idle.paintColumn(2140) < 0, "One selection until disappearance");
        check(idle.paintColumn(3140) == 0 && draws[0] == 2, "Another quiet interval starts a new visit");

        idle.quiet();
        check(idle.paintColumn(3141) < 0 && idle.paintColumn(4141) == 0, "Use or opening resets the visit");
        check(idle.bind(player, first, false, 3) && idle.paintColumn(4142) < 0, "Hotbar reset");
        check(idle.bind(player, first, true, 3) && idle.paintColumn(4143) < 0, "Hand reset");
        check(idle.bind(player, second, true, 3) && idle.paintColumn(4144) < 0, "Stack reset");
        check(idle.bind(new Object(), second, true, 3) && idle.paintColumn(4145) < 0, "Player reset");
        check(idle.paintColumn(8600) < 0 && idle.paintColumn(8400) < 0, "Gap and clock reset");
        check(idle.bind(null, null, false, -1) && idle.paintColumn(9400) < 0, "Disconnect or empty hand");

        var fallback = new TabletIdle(() -> 1000L, () -> -1);
        fallback.bind(player, first, false, 2);
        fallback.paintColumn(0);
        check(fallback.paintColumn(1000) == 0 && fallback.row() == -1, "Original gaze on missing texture");
        check(fallback.paintColumn(1499) >= 0 && fallback.paintColumn(1500) < 0, "500-ms fallback");
        System.out.println("PASS: all nine idle visit beats and cancellation, once-per-visit selection, missing-art gaze");
    }
}

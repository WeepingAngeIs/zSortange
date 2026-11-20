package dev.anya.storage.appearance;

import static dev.anya.storage.appearance.TabletIdle.Face.*;

public final class TabletIdleCheck {
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static void catchLegacy(int when, TabletIdle.Face expected, int remaining) {
        var visit = new TabletIdle(0, 0);
        visit.select(true, when);
        check(visit.frame(when) == expected && visit.frame(when + remaining) == DARK, "Caught legacy face");
    }

    private static void catchExpression(int row, int age, int expectedColumn) {
        long caught = 1368 + age;
        var visit = new TabletIdle(0, row - 4);
        var before = visit.frame(caught);
        check(before == expression(row, expectedColumn), "Wrong unselected expression");
        visit.select(true, caught);
        int last = expectedColumn;
        boolean hidden = false;
        for (int tick = 0; tick < 16; tick++) {
            if (tick == 2) visit.select(false, caught + tick);
            if (tick == 3) visit.select(true, caught + tick);
            var frame = visit.frame(caught + tick);
            if (tick == 0) check(frame == before, "Equipping changed pose");
            if (frame == DARK) { hidden = true; continue; }
            check(!hidden && frame.row() == row && frame.column() >= last
                    && frame.column() <= last + 1, "Row changed, rewind, or skipped a cell during departure");
            last = frame.column();
        }
        check(hidden && last == 8 && visit.frame(caught + 3000) == DARK, "Unfinished held visit");
    }

    public static void main(String[] args) {
        var visit = new TabletIdle(0, 0);
        check(visit.frame(0) == SQUINT && visit.frame(159) == SQUINT && visit.frame(160) == SHUT
                && visit.frame(163) == GLANCE && visit.frame(168) == DARK && visit.frame(1367) == DARK,
                "Initial mystery squint and quiet period");
        int[] ages = {0, 2, 4, 6, 8, 155, 158, 161, 164};
        for (int row = 4; row <= 6; row++) {
            for (int col = 0; col < ages.length; col++) {
                check(visit.frame((row - 3) * 1368L + ages[col]) == expression(row, col), "Ambient progression");
                catchExpression(row, ages[col], col);
            }
            catchExpression(row, 153, 4);
        }
        check(visit.frame(4 * 1368L) == SQUINT, "Squint must return among the archived visits");
        visit.select(true, 75);
        check(visit.frame(81) == SQUINT && visit.frame(82) == SHUT && visit.frame(85) == GLANCE
                && visit.frame(90) == DARK && visit.frame(9000) == DARK, "Initial equipped exit");
        visit.select(false, 9000);
        check(visit.frame(10199) == DARK && visit.frame(10200) == expression(4, 0), "Next visit while put away");
        catchLegacy(161, SHUT, 8);
        catchLegacy(165, GLANCE, 5);
        catchLegacy(200, DARK, 0);
        check(DARK.property() == 0 && SQUINT.property() == .25f && SHUT.property() == .5f
                && GLANCE.property() == .75f && OPEN.property() == 1f, "Baked legacy thresholds");
        for (int row = 0; row < 7; row++) for (int col = 0; col < 9; col++) {
            var face = expression(row, col);
            check(face.row() == row && face.column() == col && face.property() == (1 + 9 * row + col) / 256f
                    && face.sprite.equals("expression_" + row + "_" + col), "Native item override and carried GUI cell");
        }
        System.out.println("PASS: eight-second mystery squint, three long expressive idles, 27 mid-visit departures, 63 matching GUI/item expression IDs");
    }
}

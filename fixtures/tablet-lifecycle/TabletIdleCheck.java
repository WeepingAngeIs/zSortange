package dev.anya.storage.appearance;

import static dev.anya.storage.appearance.TabletIdle.Face.*;

public final class TabletIdleCheck {
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        var visit = new TabletIdle(100);
        check(visit.frame(100) == SQUINT && visit.frame(259) == SQUINT, "Eight-second unselected squint");
        check(visit.frame(260) == SHUT && visit.frame(263) == GLANCE && visit.frame(268) == DARK
                && visit.frame(1467) == DARK && visit.frame(1468) == SQUINT, "Ambient close and quiet interval");

        visit.select(true, 1477);
        check(visit.frame(1477) == SQUINT && visit.frame(1483) == SQUINT, "Equip must keep the existing face");
        visit.select(false, 1480);
        visit.select(true, 1482);
        check(visit.frame(1484) == SHUT && visit.frame(1487) == GLANCE && visit.frame(1492) == DARK,
                "Switching away and back must finish the original exit");
        check(visit.frame(8000) == DARK, "Remaining equipped must never replay");
        visit.select(false, 9000);
        check(visit.frame(10199) == DARK && visit.frame(10200) == SQUINT, "An unselected tablet can visit again");
        var shut = new TabletIdle(0);
        shut.select(true, 161);
        check(shut.frame(161) == SHUT && shut.frame(164) == GLANCE && shut.frame(169) == DARK,
                "Picking up closed eyes must not rewind to a squint");
        var glance = new TabletIdle(0);
        glance.select(true, 165);
        check(glance.frame(165) == GLANCE && glance.frame(170) == DARK, "Finish the gaze");
        var quiet = new TabletIdle(0);
        quiet.select(true, 200);
        check(quiet.frame(200) == DARK && quiet.frame(2000) == DARK, "Dark tablet must remain dark on equip");
        check(DARK.property() == 0 && SQUINT.property() == .25f && SHUT.property() == .5f
                && GLANCE.property() == .75f && OPEN.property() == 1f, "Baked override thresholds");
        System.out.println("PASS: per-tablet close-ups, long squint, selection exit, quiet interval, rapid switches");
    }
}

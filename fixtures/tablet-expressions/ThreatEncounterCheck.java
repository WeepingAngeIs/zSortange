package dev.anya.storage;

import java.util.HashSet;

/** Run against the packaged encounter clock, candidate limiter and direction conversion. */
public final class ThreatEncounterCheck {
    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static void crowded() {
        var encounter = new ThreatEncounter<Integer>(Integer::intValue);
        var seen = new HashSet<Integer>();
        for (int round = 0; round < 4; round++) {
            encounter.targeted(round * 5);
            for (int id = 31; id >= 0; id--) {
                encounter.offer(id);
                encounter.offer(id);
            }
            int before = seen.size();
            var visible = encounter.check(true, id -> {
                require(seen.add(id), "A hidden attacker starved another candidate");
                return id == 31;
            });
            require(seen.size() - before <= 8, "Too many visibility checks in one sampling interval");
            require(round == 3 ? Integer.valueOf(31).equals(visible) : visible == null, "Wrong visible attacker");
        }
        require(seen.size() == 32, "Did not rotate through the whole crowd");
    }

    private static void continuing() {
        var encounter = new ThreatEncounter<Integer>(Integer::intValue);
        encounter.targeted(10);
        encounter.offer(1);
        require(encounter.check(false, id -> { throw new AssertionError("Raycast without tablet"); }) == null,
                "Warning without a tablet");
        encounter.targeted(15);
        encounter.offer(1);
        require(encounter.check(true, id -> false) == null, "Hidden target warned");
        encounter.targeted(20);
        encounter.offer(1);
        require(Integer.valueOf(1).equals(encounter.check(true, id -> true)), "Expected first warning");
        // Continuous nearby targeting and brief gaps keep the same encounter through an equipment change.
        for (int tick : new int[]{25, 50, 129}) {
            encounter.targeted(tick);
            encounter.offer(2);
            require(encounter.check(tick != 25, id -> { throw new AssertionError("Raycast during latched encounter"); })
                    == null, "A second attacker, a slot switch or brief occlusion repeated the warning");
        }
        require(!encounter.expired(208) && encounter.expired(209), "Fresh encounter boundary");
        encounter.targeted(209);
        encounter.offer(2);
        require(Integer.valueOf(2).equals(encounter.check(true, id -> true)), "Failed to rearm");
    }

    private static void directions() {
        for (int yaw : new int[]{0, 45, 90, 135, 180, -90, -180, 720}) {
            for (int expected = 0; expected < 8; expected++) {
                double angle = Math.toRadians(yaw + 45 * expected);
                require(ThreatEncounter.direction(-Math.sin(angle), Math.cos(angle), yaw) == expected,
                        "Direction " + expected + " at yaw " + yaw);
            }
        }
        require(ThreatEncounter.direction(0, 0, 90) == 0, "Overhead direction");
    }

    public static void main(String[] args) {
        directions();
        continuing();
        crowded();
        System.out.println("Threat cue directions, actual carry gating, sustained encounters and bounded fair sampling passed");
    }
}

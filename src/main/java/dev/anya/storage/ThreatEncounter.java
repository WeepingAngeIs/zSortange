package dev.anya.storage;

import java.util.ArrayList;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** One encounter per target player. Rotate a bounded batch through all attackers in a dense crowd. */
final class ThreatEncounter<T> {
    static final int CLEAR_TICKS = 80;
    static final int BATCH = 8;
    private final ToIntFunction<T> idOf;
    private final ArrayList<T> candidates = new ArrayList<>(BATCH);
    private long lastTargetTick;
    private int nextId;
    private boolean warned;

    ThreatEncounter(ToIntFunction<T> idOf) { this.idOf = idOf; }

    void targeted(long tick) {
        if (expired(tick)) warned = false;
        lastTargetTick = tick;
    }

    boolean expired(long tick) { return tick - lastTargetTick >= CLEAR_TICKS; }

    void offer(T mob) {
        if (warned) return;
        int id = idOf.applyAsInt(mob);
        int offset = id - nextId;
        int index = 0;
        while (index < candidates.size()) {
            int other = idOf.applyAsInt(candidates.get(index));
            if (other == id) return;
            if (Integer.compareUnsigned(offset, other - nextId) < 0) break;
            index++;
        }
        if (index == BATCH) return;
        if (candidates.size() == BATCH) candidates.removeLast();
        candidates.add(index, mob);
    }

    T check(boolean carrying, Predicate<T> visible) {
        if (candidates.isEmpty()) return null;
        nextId = idOf.applyAsInt(candidates.getLast()) + 1;
        try {
            if (carrying) {
                for (T mob : candidates) {
                    if (visible.test(mob)) {
                        warned = true;
                        return mob;
                    }
                }
            }
            return null;
        } finally {
            candidates.clear();
        }
    }

    /** Minecraft yaw increases from south toward west. Relative octants proceed clockwise from forward. */
    static int direction(double dx, double dz, float yaw) {
        if (dx * dx + dz * dz < 1.0e-8) return 0;
        double angle = Math.toDegrees(Math.atan2(-dx, dz)) - yaw;
        return Math.floorMod((int)Math.floor((angle + 22.5) / 45.0), 8);
    }
}

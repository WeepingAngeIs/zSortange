package dev.anya.storage.pocket;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Each physical visitor has their own clock. All state is discarded on leaving. */
final class RoomVisitSchedule {
    static final int NONE = -2, HIDE = -1, DURATION = 96;
    private static final class Visit {
        long next;
        boolean showing;
        Visit(long next) { this.next = next; }
    }
    private final Map<UUID, Visit> visits = new HashMap<>();
    private final RandomGenerator random;
    RoomVisitSchedule(RandomGenerator random) { this.random = random; }

    int advance(UUID viewer, long tick) {
        Visit visit = visits.get(viewer);
        if (visit == null) {
            visits.put(viewer, new Visit(tick + 100 + random.nextInt(121)));
            return NONE;
        }
        if (tick < visit.next) return NONE;
        if (visit.showing) {
            visit.showing = false;
            visit.next = tick + 900 + random.nextInt(1201);
            return HIDE;
        }
        visit.showing = true;
        visit.next = tick + DURATION;
        return random.nextInt(3);
    }

    boolean end(UUID viewer) {
        Visit visit = visits.remove(viewer);
        return visit != null && visit.showing;
    }
    void clear() { visits.clear(); }
}

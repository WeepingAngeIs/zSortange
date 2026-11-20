package dev.anya.storage.appearance;

/** One local tablet's visit. Game ticks keep the baked icon and glass on the same beat. */
final class TabletIdle {
    enum Face {
        DARK("dark"), SQUINT("squint"), SHUT("shut"), GLANCE("glance"), OPEN("open");
        final String sprite;
        Face(String sprite) { this.sprite = sprite; }
        float property() { return ordinal() * .25f; }
    }

    private static final int SQUINT_TICKS = 160, QUIET_TICKS = 1200, EXIT_TICKS = 15;
    private static final long NEVER = Long.MIN_VALUE;
    private long nextVisit, leaving = NEVER;
    private boolean held;

    TabletIdle(long tick) { nextVisit = tick; }

    Face frame(long tick) {
        if (leaving != NEVER && tick - leaving < EXIT_TICKS) {
            long age = tick - leaving;
            return age < 7 ? Face.SQUINT : age < 10 ? Face.SHUT : Face.GLANCE;
        }
        if (held || tick < nextVisit) return Face.DARK;
        long age = (tick - nextVisit) % (SQUINT_TICKS + 8 + QUIET_TICKS);
        if (age < SQUINT_TICKS) return Face.SQUINT;
        if (age < SQUINT_TICKS + 3) return Face.SHUT;
        return age < SQUINT_TICKS + 8 ? Face.GLANCE : Face.DARK;
    }

    void select(boolean selected, long tick) {
        if (selected == held) return;
        if (selected) {
            // Putting it away and picking it straight up cannot restart the exit.
            if (leaving == NEVER || tick - leaving >= EXIT_TICKS) {
                leaving = switch (frame(tick)) {
                    case SQUINT -> tick;
                    case SHUT -> tick - 7;
                    case GLANCE -> tick - 10;
                    default -> NEVER;
                };
            }
        } else {
            nextVisit = tick + QUIET_TICKS;
        }
        held = selected;
    }
}

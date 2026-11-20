package dev.anya.storage.appearance;

import java.util.concurrent.ThreadLocalRandom;

/** One local tablet's visit. Game ticks keep the baked icon and glass on the same beat. */
final class TabletIdle {
    enum Face {
        DARK("dark"), SQUINT("squint"), SHUT("shut"), GLANCE("glance"), OPEN("open"),
        SHY_0, SHY_1, SHY_2, SHY_3, SHY_4, SHY_5, SHY_6, SHY_7, SHY_8,
        TONGUE_0, TONGUE_1, TONGUE_2, TONGUE_3, TONGUE_4, TONGUE_5, TONGUE_6, TONGUE_7, TONGUE_8,
        WINK_0, WINK_1, WINK_2, WINK_3, WINK_4, WINK_5, WINK_6, WINK_7, WINK_8,
        SMUG_0, SMUG_1, SMUG_2, SMUG_3, SMUG_4, SMUG_5, SMUG_6, SMUG_7, SMUG_8,
        NOTICE_0, NOTICE_1, NOTICE_2, NOTICE_3, NOTICE_4, NOTICE_5, NOTICE_6, NOTICE_7, NOTICE_8,
        GIGGLE_0, GIGGLE_1, GIGGLE_2, GIGGLE_3, GIGGLE_4, GIGGLE_5, GIGGLE_6, GIGGLE_7, GIGGLE_8,
        PEEK_0, PEEK_1, PEEK_2, PEEK_3, PEEK_4, PEEK_5, PEEK_6, PEEK_7, PEEK_8;

        private static final int LEGACY_COUNT = 5, COLUMNS = 9;
        private static final Face[] ALL = values();
        final String sprite;
        private final int row, column;
        Face(String legacySprite) {
            row = column = -1;
            sprite = legacySprite;
        }
        Face() {
            int cell = ordinal() - LEGACY_COUNT;
            row = cell / COLUMNS;
            column = cell % COLUMNS;
            sprite = "expression_" + row + "_" + column;
        }
        static Face expression(int row, int column) {
            if (row < 0 || row >= 7 || column < 0 || column >= COLUMNS)
                throw new IllegalArgumentException("Unknown tablet expression cell " + row + "," + column);
            return ALL[LEGACY_COUNT + row * COLUMNS + column];
        }
        int row() { return row; }
        int column() { return column; }
        float property() {
            // Old model thresholds stay stable. All archived cells have distinct exact binary fractions.
            return row < 0 ? ordinal() * .25f : (1 + row * COLUMNS + column) / 256f;
        }
    }

    private static final int SHOW = 168, QUIET = 1200, PERIOD = SHOW + QUIET;
    private static final long NEVER = Long.MIN_VALUE;
    private final int firstIdleRow;
    private long nextVisit, sequence, leaving = NEVER;
    private Face exitFrom = Face.DARK;
    private boolean held;

    TabletIdle(long tick) { this(tick, ThreadLocalRandom.current().nextInt(3)); }
    TabletIdle(long tick, int firstIdleRow) {
        if (firstIdleRow < 0 || firstIdleRow > 2) throw new IllegalArgumentException("Unknown idle sequence");
        this.firstIdleRow = firstIdleRow;
        nextVisit = tick;
    }

    Face frame(long tick) {
        var departing = departure(tick);
        if (departing != Face.DARK) return departing;
        if (held || tick < nextVisit) return Face.DARK;
        long elapsed = tick - nextVisit;
        int age = (int)(elapsed % PERIOD);
        if (age >= SHOW) return Face.DARK;
        int visit = (int)((sequence + elapsed / PERIOD) % 4);
        if (visit == 0) return age < 160 ? Face.SQUINT : age < 163 ? Face.SHUT : Face.GLANCE;
        int row = 4 + (firstIdleRow + visit - 1) % 3;
        // The original approach stays quick. Linger on her signature expression, then play the authored retreat.
        int column;
        if (age < 8) column = age / 2;
        else if (age < 155) column = 4;
        else column = Math.min(8, 5 + (age - 155) / 3);
        return Face.expression(row, column);
    }

    private Face departure(long tick) {
        if (leaving == NEVER || exitFrom == Face.DARK || tick - leaving >= 15) return Face.DARK;
        int age = (int)(tick - leaving);
        if (exitFrom.row() < 0) {
            int beat = age + (exitFrom == Face.SHUT ? 7 : exitFrom == Face.GLANCE ? 10 : 0);
            return beat < 7 ? Face.SQUINT : beat < 10 ? Face.SHUT : beat < 15 ? Face.GLANCE : Face.DARK;
        }
        int from = exitFrom.column();
        // Start on precisely the frame she was caught in. Later dim cells need only finish fading.
        int hold = from <= 4 ? 5 : 0;
        int stride = from >= 4 ? 2 : 1;
        int column = from + Math.max(0, age - hold) / stride;
        return column > 8 ? Face.DARK : Face.expression(exitFrom.row(), column);
    }

    void select(boolean selected, long tick) {
        if (selected == held) return;
        if (selected && tick >= nextVisit && departure(tick) == Face.DARK) {
            exitFrom = frame(tick);
            leaving = tick;
            sequence += 1 + (tick - nextVisit) / PERIOD;
        } else if (!selected) {
            nextVisit = tick + QUIET;
        }
        held = selected;
    }
}

package dev.anya.storage.appearance;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** A silent, local timer, scoped to one player, held object, hand and selected slot. */
final class TabletIdle {
    private static final int LEGACY_GLANCE_MS = 500;
    private static final int[] BEAT_END = {85, 175, 275, 385, 635, 760, 885, 1010, 1140};
    private static final int UNPICKED = -2;
    private static final int MAX_PAINT_GAP_MS = 1500;
    private final LongSupplier quietTime;
    private final IntSupplier chooseRow;
    private Object owner;
    private Object stack;
    private boolean offhand;
    private int slot = -1;
    private long lastPaint = Long.MIN_VALUE;
    private long glanceAt;
    private int row = UNPICKED;

    TabletIdle(IntSupplier chooseRow) {
        this(() -> ThreadLocalRandom.current().nextLong(55_000L, 110_001L), chooseRow);
    }

    TabletIdle(LongSupplier quietTime, IntSupplier chooseRow) {
        this.quietTime = quietTime;
        this.chooseRow = chooseRow;
    }

    /** Returns true when the last painted pose can no longer belong to the same held Remote. */
    boolean bind(Object player, Object held, boolean inOffhand, int selectedSlot) {
        if (owner == player && stack == held && offhand == inOffhand && slot == selectedSlot) return false;
        owner = player;
        stack = held;
        offhand = inOffhand;
        slot = selectedSlot;
        quiet();
        return true;
    }

    void quiet() {
        lastPaint = Long.MIN_VALUE;
        row = UNPICKED;
    }

    static int columnAt(long age) {
        if (age < 0) return -1;
        for (int i = 0; i < BEAT_END.length; i++) {
            if (age < BEAT_END[i]) return i;
        }
        return -1;
    }

    int row() { return row; }

    int paintColumn(long now) {
        if (owner == null || stack == null) return -1;
        if (lastPaint == Long.MIN_VALUE || now < lastPaint || now - lastPaint > MAX_PAINT_GAP_MS
                || now >= glanceAt + BEAT_END[BEAT_END.length - 1]) {
            glanceAt = now + quietTime.getAsLong();
            row = UNPICKED;
        }
        lastPaint = now;
        long age = now - glanceAt;
        if (age < 0) return -1;
        if (row == UNPICKED) row = chooseRow.getAsInt();
        if (row == -1 && age >= LEGACY_GLANCE_MS) {
            glanceAt = now + quietTime.getAsLong();
            row = UNPICKED;
            return -1;
        }
        return columnAt(age);
    }
}

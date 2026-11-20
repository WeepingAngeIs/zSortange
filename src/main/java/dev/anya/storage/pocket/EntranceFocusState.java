package dev.anya.storage.pocket;

import java.util.concurrent.TimeUnit;

/** Main-thread state for the entrance the player is currently looking at. */
final class EntranceFocusState {
    private static final int LEASE_TICKS = 24;
    private static final long LEASE_NANOS = TimeUnit.MILLISECONDS.toNanos(1200);

    private long nextToken, token, validUntilTick, validUntilNanos;
    private int mask = -1;

    long begin(long tick, long now) {
        token = ++nextToken;
        mask = -1;
        renew(tick, now);
        return token;
    }

    boolean accepts(long incomingToken) {
        return token != 0 && token == incomingToken;
    }

    boolean expired(long tick, long now) {
        return token == 0 || tick >= validUntilTick || now - validUntilNanos >= 0;
    }

    void renew(long tick, long now) {
        validUntilTick = tick + LEASE_TICKS;
        validUntilNanos = now + LEASE_NANOS;
    }

    boolean applyMask(long incomingToken, int value, long tick, long now) {
        if (!accepts(incomingToken) || expired(tick, now)) return false;
        if (value < 0 || value > 255) clear();
        else {
            mask = value;
            renew(tick, now);
        }
        return true;
    }

    void clear() {
        token = validUntilTick = validUntilNanos = 0;
        mask = -1;
    }

    long token() { return token; }

    int visibleMask(long tick, long now) {
        return expired(tick, now) ? -1 : mask;
    }
}

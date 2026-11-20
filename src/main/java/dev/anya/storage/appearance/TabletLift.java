package dev.anya.storage.appearance;

/** Anticipation on the local use edge. A denied use returns the tablet to rest without opening anything. */
public final class TabletLift {
    public static final int RISE_MS = 190, HOLD_MS = 160, RETURN_MS = 190;
    public static final float ANTICIPATION = .64f;
    private long start = Long.MIN_VALUE;

    public void use(long now) {
        if (start == Long.MIN_VALUE || now - start >= (RISE_MS + RETURN_MS) * ANTICIPATION + HOLD_MS) start = now;
    }
    public float progress(long now) {
        if (start == Long.MIN_VALUE || now < start) return 0;
        long elapsed = now - start;
        float rising = RISE_MS * ANTICIPATION;
        if (elapsed < rising) return elapsed / (float) RISE_MS;
        if (elapsed < rising + HOLD_MS) return ANTICIPATION;
        return Math.max(0f, ANTICIPATION - (elapsed - rising - HOLD_MS) / RETURN_MS);
    }
    public void reset() { start = Long.MIN_VALUE; }
}

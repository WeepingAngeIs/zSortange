package dev.anya.storage.pocket;

/** Client main-thread display state. A focus nonce is never reused for a later grant. */
final class StillFrameState {
    private long lastToken, activeToken, lastSequence;
    private int material;

    void begin(long token) {
        if (token <= lastToken) throw new IllegalArgumentException("Still focus nonce must advance");
        lastToken = activeToken = token;
        lastSequence = 0;
        material = 0;
    }

    boolean apply(long token, long sequence, int value) {
        if (activeToken == 0 || token != activeToken || sequence <= 0 || value < 0 || value > 2) return false;
        if (value == 0) {
            clear();
            return true;
        }
        if (sequence <= lastSequence) return false;
        lastSequence = sequence;
        material = value;
        return true;
    }

    void clear() { activeToken = lastSequence = 0; material = 0; }
    int material() { return material; }
}

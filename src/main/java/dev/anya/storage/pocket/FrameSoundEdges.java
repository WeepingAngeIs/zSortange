package dev.anya.storage.pocket;

/** Debounces the visible frame as a whole. Only completed render frames supply samples. */
final class FrameSoundEdges {
    enum Cue { NONE, ON, OFF }

    private boolean initialized, settled, candidate;
    private long started, lastSample, candidateSince, nextCue;

    Cue sample(boolean visible, long nowMs) {
        if (!initialized || nowMs < lastSample || nowMs - lastSample >= 750) {
            initialized = true;
            settled = candidate = visible;
            lastSample = started = candidateSince = nextCue = nowMs;
            return Cue.NONE;
        }
        lastSample = nowMs;
        if (visible != candidate) {
            candidate = visible;
            candidateSince = nowMs;
        }
        // Acquire the current view silently after joining, resuming or a long frame stall.
        if (nowMs - started < 250) {
            settled = visible;
            return Cue.NONE;
        }
        if (visible == settled || nowMs < nextCue || nowMs - candidateSince < (visible ? 100 : 160)) {
            return Cue.NONE;
        }
        settled = visible;
        nextCue = nowMs + 300;
        return visible ? Cue.ON : Cue.OFF;
    }

    void reset() { initialized = false; }
}

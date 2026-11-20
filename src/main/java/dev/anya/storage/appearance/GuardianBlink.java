package dev.anya.storage.appearance;

/** One storage-screen opening, driven only by its local monotonic presentation clock. */
public final class GuardianBlink {
    public enum Frame { WAIT, OPEN, HALF, CLOSED, DONE }

    private static final int SYNC_GRACE_MS = 300;
    // The expression sheet has one column for each successive beat of this same double blink.
    private static final int[] BEAT_END = {260, 310, 390, 440, 610, 660, 760, 810, 1050};
    private static final Frame[] FRAMES = {Frame.OPEN, Frame.HALF, Frame.CLOSED, Frame.HALF,
            Frame.OPEN, Frame.HALF, Frame.CLOSED, Frame.HALF, Frame.OPEN};
    private static final long UNSET = Long.MIN_VALUE;
    private long firstPaint = UNSET;
    private long blinkStarted = UNSET;
    private Frame frame = Frame.DONE;
    private int column = -1;
    private boolean finished;

    public GuardianBlink(boolean enabled) {
        finished = !enabled;
    }

    public Frame present(long now, boolean storageView, boolean kindKnown) {
        if (finished) return Frame.DONE;
        if (firstPaint == UNSET) firstPaint = now;

        if (!storageView) {
            if (blinkStarted != UNSET || kindKnown || now - firstPaint >= SYNC_GRACE_MS) {
                dismiss();
            } else {
                frame = Frame.WAIT;
            }
            return frame;
        }

        if (blinkStarted == UNSET) blinkStarted = now;
        long age = now - blinkStarted;
        for (int i = 0; i < BEAT_END.length; i++) {
            if (age < BEAT_END[i]) {
                column = i;
                frame = FRAMES[i];
                return frame;
            }
        }
        dismiss();
        return frame;
    }

    int column() { return column; }
    Frame frame() { return frame; }
    public boolean coversInput() { return frame != Frame.DONE; }

    public void dismiss() {
        frame = Frame.DONE;
        column = -1;
        finished = true;
    }
}

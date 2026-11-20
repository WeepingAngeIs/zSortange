package dev.anya.storage.appearance;

/** One storage-screen opening, driven only by its local monotonic presentation clock. */
public final class GuardianBlink {
    public enum Frame { WAIT, OPEN, HALF, CLOSED, DONE }
    static final int CLASSIC = 7;
    /** One shuffle per local player's eight appearances, shared by both tablet colors. */
    public static final class Playlist {
        private final int[] order = {0,1,2,3,4,5,6,CLASSIC};
        private int index = order.length;
        private int previous = -1;

        private int next() {
            if (index == order.length) {
                var random = java.util.concurrent.ThreadLocalRandom.current();
                for (int i = order.length - 1; i > 0; i--) swap(i,random.nextInt(i + 1));
                if (order[0] == previous) swap(0,random.nextInt(1,order.length));
                index = 0;
            }
            previous = order[index++];
            return previous;
        }

        private void swap(int a, int b) {
            int saved = order[a]; order[a] = order[b]; order[b] = saved;
        }
    }

    private static final int SYNC_GRACE_MS = 300;
    // The expression sheet has one column for each successive beat of this same double blink.
    private static final int[] BEAT_END = {260, 310, 390, 440, 610, 660, 760, 810, 1050};
    // The final darkening pose of the three idle visits fits in the same 1050 ms opening.
    private static final int[] IDLE_BEAT_END = {85, 175, 275, 385, 635, 760, 885, 1010, 1050};
    private static final Frame[] FRAMES = {Frame.OPEN, Frame.HALF, Frame.CLOSED, Frame.HALF,
            Frame.OPEN, Frame.HALF, Frame.CLOSED, Frame.HALF, Frame.OPEN};
    private static final long UNSET = Long.MIN_VALUE;
    private long firstPaint = UNSET;
    private long blinkStarted = UNSET;
    private Frame frame = Frame.DONE;
    private int column = -1;
    private int sequence = -1;
    private boolean finished;

    public GuardianBlink(boolean enabled) {
        finished = !enabled;
    }

    public void choose(Playlist choices) {
        if (!finished && sequence < 0) sequence = choices.next();
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
        int[] ends = sequence >= 4 && sequence < CLASSIC ? IDLE_BEAT_END : BEAT_END;
        for (int i = 0; i < ends.length; i++) {
            if (age < ends[i]) {
                column = i;
                frame = FRAMES[i];
                return frame;
            }
        }
        dismiss();
        return frame;
    }

    public int sequence() { return sequence; }
    public int column() { return column; }
    Frame frame() { return frame; }
    public boolean coversInput() { return frame != Frame.DONE; }

    public void dismiss() {
        frame = Frame.DONE;
        column = -1;
        finished = true;
    }
}

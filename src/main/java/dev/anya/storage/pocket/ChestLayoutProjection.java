package dev.anya.storage.pocket;

/** Projects the eight public, registered pad centres onto the illustrated face, in face texels. */
public final class ChestLayoutProjection {
    public static final int FACE_SIZE = 256;
    public static final int ATLAS_WIDTH = 512;
    public static final int STAMP_X = 256, STAMP_Y = 0;
    public static final int STAMP_WIDTH = 22;
    public static final int STAMP_HEIGHT = 20;

    // Same wire order as PocketService.chestPositions followed by PocketRoomLayout.pads.
    private static final int[] PAD_X = {6, 10, 14, 18, 6, 10, 22, 26};
    private static final int[] PAD_Z = {6, 6, 6, 6, 14, 14, 14, 14};

    private ChestLayoutProjection() {}

    // Increasing room X moves right/down. Increasing room Z moves left/down.
    // The anchor is the centre of the symbol's footprint. Symbol size is fixed for legibility.
    private static int stampLeft(int bit) { return 56 + 6 * PAD_X[bit] - 2 * PAD_Z[bit] - STAMP_WIDTH / 2; }
    private static int stampTop(int bit) { return 96 + PAD_X[bit] + 3 * PAD_Z[bit] - STAMP_HEIGHT + 1; }

    /** A complete observation includes all four verified starter chests and up to four optional pads. */
    public static boolean validMask(int mask) {
        return (mask & ~0xff) == 0 && (mask & 0x0f) == 0x0f;
    }

    @FunctionalInterface
    public interface Stamp {
        void draw(int bit, int left, int top);
    }

    /** Rear row to front row; within each row, increasing X is closer to the viewer. */
    public static void forEachChest(int mask, Stamp stamp) {
        if (!validMask(mask)) return;
        for (int bit = 0; bit < PAD_X.length; bit++) {
            if ((mask & (1 << bit)) != 0) stamp.draw(bit, stampLeft(bit), stampTop(bit));
        }
    }
}

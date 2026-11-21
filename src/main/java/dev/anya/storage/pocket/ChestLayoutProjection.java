package dev.anya.storage.pocket;

import java.util.List;

/** Projects the eight public, registered pad centres onto the illustrated face, in face texels. */
public final class ChestLayoutProjection {
    public static final int FACE_SIZE = 256;
    public static final int ATLAS_WIDTH = 512;
    public static final int STAMP_X = 256, STAMP_Y = 0;
    public static final int STAMP_WIDTH = 22;
    public static final int STAMP_HEIGHT = 20;

    private ChestLayoutProjection() {}

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
        forEachRow(mask, 0, RoomBounds.STARTER_CHEST_X, RoomBounds.STARTER_CHEST_Z, stamp);
        forEachRow(mask, RoomBounds.STARTER_CHEST_X.size(), RoomBounds.PAD_X, RoomBounds.PAD_Z, stamp);
    }

    static void placed(int mask,long coords,Stamp stamp){
        if(!validMask(mask))return;
        forEachRow(mask,0,RoomBounds.STARTER_CHEST_X,RoomBounds.STARTER_CHEST_Z,stamp);
        for(int i=0;i<4;i++)if((mask&(16<<i))!=0){
            int cell=(int)(coords>>>(10*i)),x=cell&31,z=(cell>>5)&31;
            stamp.draw(i+4,Math.clamp(56+6*x-2*z-STAMP_WIDTH/2,0,FACE_SIZE-STAMP_WIDTH),
                    96+x+3*z-STAMP_HEIGHT+1);
        }
    }

    private static void forEachRow(int mask, int firstBit, List<Integer> roomXs, int roomZ, Stamp stamp) {
        for (int i = 0; i < roomXs.size(); i++) {
            int bit = firstBit + i;
            if ((mask & (1 << bit)) == 0) continue;
            int roomX = roomXs.get(i);
            // Increasing room X moves right/down. Increasing room Z moves left/down.
            // Anchor the fixed-size symbol to the chest's footprint on the illustrated floor.
            int left = 56 + 6 * roomX - 2 * roomZ - STAMP_WIDTH / 2;
            int top = 96 + roomX + 3 * roomZ - STAMP_HEIGHT + 1;
            stamp.draw(bit, left, top);
        }
    }
}

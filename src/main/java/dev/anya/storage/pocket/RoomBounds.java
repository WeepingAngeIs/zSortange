package dev.anya.storage.pocket;

import java.util.List;

/** Relative coordinates only. The shell, landing/exit route and chest approaches are never editable. */
public final class RoomBounds {
    // Stable layout order: the starter row occupies mask bits 0–3, then the optional row occupies bits 4–7.
    static final List<Integer> STARTER_CHEST_X = List.of(6, 10, 14, 18);
    static final int CHEST_Y = 65;
    static final int STARTER_CHEST_Z = 6;
    static final int PAD_Z = 14;
    public static final List<Integer> PAD_X = List.of(6, 10, 22, 26);

    public static int starterChestCount() {
        return STARTER_CHEST_X.size();
    }

    public static boolean chestPad(int x,int y,int z){
        return y == CHEST_Y && z == PAD_Z && PAD_X.contains(x);
    }
    public static boolean decoration(int x,int y,int z){
        if(x<2||x>29||z<2||z>29||y<65||y>72)return false;
        // Keep a five-block-wide, full-height path between landing and the exit lodestone.
        if(x>=14&&x<=18&&z>=19)return false;
        // The original four chests and their lids/approach retain a protected air envelope.
        if(x>=4&&x<=20&&z>=4&&z<=8&&y<=68)return false;
        return true;
    }
    private RoomBounds(){}
}

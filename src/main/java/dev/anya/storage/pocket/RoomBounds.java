package dev.anya.storage.pocket;

import java.util.List;

/** Relative coordinates only. The shell and safe landing/return route stay clear. */
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
    public static boolean interior(int x,int y,int z){
        return x>=0 && x<32 && z>=0 && z<32 && y>=65 && y<=96;
    }
    public static boolean decoration(int x,int y,int z){
        return interior(x,y,z) && !(x>=15 && x<=17 && z>=23 && z<=29 && y<=68);
    }
    private RoomBounds(){}
}

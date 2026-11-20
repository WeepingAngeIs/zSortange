package dev.anya.storage.pocket;

/** Relative coordinates only. The shell, landing/exit route and chest approaches are never editable. */
public final class RoomBounds {
    public static final java.util.List<Integer> PAD_X=java.util.List.of(6,10,22,26);
    public static boolean chestPad(int x,int y,int z){
        if(y!=65||z!=14)return false;
        for(int pad:PAD_X)if(x==pad)return true;
        return false;
    }
    public static boolean decoration(int x,int y,int z){
        if(x<2||x>29||z<2||z>29||y<65||y>72)return false;
        // Keep a five-block-wide, full-height path between landing and the exit lodestone.
        if(x>=14&&x<=18&&z>=19)return false;
        // The original four chests and their lids/approach retain a protected air envelope.
        if(x>=4&&x<=20&&z>=4&&z<=8&&y<=68)return false;
        // A pad and its approach cannot be hidden inside decorative blocks.
        for(int pad:PAD_X)if(Math.abs(x-pad)<=1&&Math.abs(z-14)<=1&&y<=68)return false;
        return true;
    }
    private RoomBounds(){}
}

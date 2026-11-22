package dev.anya.storage;
import dev.anya.storage.pocket.RoomBounds;
/** Pure negative geometry and independent-permission tests; no world or renderer claims. */
public final class RoomBoundsTests {
    public static void main(String[] args){
        int checks=0,editable=0,pads=0;
        for(int x=-2;x<=34;x++)for(int z=-2;z<=34;z++)for(int y=60;y<=99;y++){
            boolean d=RoomBounds.decoration(x,y,z),p=RoomBounds.chestPad(x,y,z);checks+=3;
            boolean inside=x>=0&&x<=31&&z>=0&&z<=31&&y>=65&&y<=96;
            boolean exit=inside&&x>=15&&x<=17&&z>=23&&z<=29&&y<=68;
            if(RoomBounds.interior(x,y,z)!=inside || d!=(inside&&!exit))throw new AssertionError("Wrong shell, corner or exit bounds");
            if(p&&!d)throw new AssertionError("Legacy floor spot inaccessible");
            if(d)editable++;if(p)pads++;
        }
        if(pads!=4||editable!=32*32*32-3*7*4)throw new AssertionError("Bad geometry budget");
        if(BasePermissions.allows(BasePermissions.VISIT,BasePermissions.BUILD)||BasePermissions.allows(BasePermissions.BUILD,BasePermissions.VISIT)||BasePermissions.allows(BasePermissions.VISIT|BasePermissions.BUILD,BasePermissions.LEGACY))throw new AssertionError("Rights conflated");
        System.out.println("PASS: room shell, full-height corners, legacy positions, landing, exit, and separate build rights; "+editable+" buildable cells");
    }
}

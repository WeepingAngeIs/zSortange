package dev.anya.storage;
import dev.anya.storage.pocket.RoomBounds;
/** Pure negative geometry and independent-permission tests; no world or renderer claims. */
public final class RoomBoundsTests {
    public static void main(String[] args){
        int checks=0,editable=0,pads=0;
        for(int x=-2;x<=34;x++)for(int z=-2;z<=34;z++)for(int y=60;y<=99;y++){
            boolean d=RoomBounds.decoration(x,y,z),p=RoomBounds.chestPad(x,y,z);checks+=3;
            if(d&&(x<2||x>29||z<2||z>29||y<65||y>72))throw new AssertionError("Escaped build inset");
            if(d&&x>=14&&x<=18&&z>=19)throw new AssertionError("Exit corridor obstruction");
            if(p&&d)throw new AssertionError("Decor occupies a container pad");
            if(d)editable++;if(p)pads++;
        }
        if(pads!=4||editable<=0)throw new AssertionError("Bad geometry budget");
        if(BasePermissions.allows(BasePermissions.VISIT,BasePermissions.BUILD)||BasePermissions.allows(BasePermissions.BUILD,BasePermissions.VISIT)||BasePermissions.allows(BasePermissions.VISIT|BasePermissions.BUILD,BasePermissions.LEGACY))throw new AssertionError("Rights conflated");
        System.out.println("PASS: "+(checks+5)+" room geometry/permission assertions; "+editable+" decorative cells; "+pads+" optional chest pads");
    }
}

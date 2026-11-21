package dev.anya.storage.pocket;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Rays for a square Skyglass sensor. Upward cameras keep north at the top of the monitor. */
final class CameraOptics {
    static final int SIDE=16;
    static Vec3 ray(Direction direction,int column,int row){
        if(column<0 || row<0 || column>=SIDE || row>=SIDE)throw new IllegalArgumentException("Outside sensor");
        Vec3 forward=Vec3.atLowerCornerOf(direction.getNormal());
        Vec3 up=direction.getAxis().isVertical()?new Vec3(0,0,-1):new Vec3(0,1,0);
        Vec3 right=forward.cross(up);
        double x=(2.0*column+1)/SIDE-1,y=1-(2.0*row+1)/SIDE;
        return forward.add(right.scale(x)).add(up.scale(y)).normalize();
    }
    private CameraOptics(){}
}

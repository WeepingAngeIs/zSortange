package dev.anya.storage.pocket;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Rays for a square Skyglass sensor. Upward cameras keep north at the top of the monitor. */
final class CameraOptics {
    static final int SIDE=16;
    static Vec3 ray(Direction direction,int column,int row){
        return ray(direction,column,row,SIDE);
    }
    static Vec3 ray(Direction direction,int column,int row,int side){
        return ray(direction,column,row,side,1);
    }
    static Vec3 ray(Direction direction,int column,int row,int side,double aperture){
        if(column<0 || row<0 || column>=side || row>=side)throw new IllegalArgumentException("Outside sensor");
        Vec3 forward=Vec3.atLowerCornerOf(direction.getNormal());
        Vec3 up=direction.getAxis().isVertical()?new Vec3(0,0,-1):new Vec3(0,1,0);
        Vec3 right=forward.cross(up);
        double x=(2.0*column+1)/side-1,y=1-(2.0*row+1)/side;
        return forward.add(right.scale(x*aperture)).add(up.scale(y*aperture)).normalize();
    }
    /** Frame the observable cone of sky. Looking up from a loaded outdoor camera never creates a ticket. */
    static double skyAperture(net.minecraft.server.level.ServerLevel level,Vec3 lens) {
        var blocks=new LoadedPocketBlocks(level);
        int cx=net.minecraft.util.Mth.floor(lens.x)>>4,cz=net.minecraft.util.Mth.floor(lens.z)>>4;
        int radius=0;
        for(int ring=1;ring<=6;ring++){
            boolean full=true;
            for(int n=-ring;n<=ring&&full;n++){
                full=blocks.chunk(cx+n,cz-ring)!=null&&blocks.chunk(cx+n,cz+ring)!=null;
                if(full&&Math.abs(n)<ring)full=blocks.chunk(cx-ring,cz+n)!=null&&blocks.chunk(cx+ring,cz+n)!=null;
            }
            if(!full)break;
            radius=ring;
        }
        double west=lens.x-(cx-radius)*16,east=(cx+radius+1)*16-lens.x;
        double north=lens.z-(cz-radius)*16,south=(cz+radius+1)*16-lens.z;
        double span=Math.min(Math.min(west,east),Math.min(north,south))-1;
        return Math.clamp(span/Math.max(1,level.getMaxBuildHeight()-lens.y),.075,.30);
    }
    private CameraOptics(){}
}

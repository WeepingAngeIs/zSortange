package dev.anya.storage.pocket;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.*;

/** Anonymous figure based on the flyer's actual location, pose and heading. */
record CameraFigure(AABB box,int color) {
    private static final int LEGS=0x47384f,BODY=0x6c5874,HEAD=0x927d93;
    static void add(List<CameraFigure> parts,ServerPlayer player){
        var bounds=player.getBoundingBox();var forward=player.getDirection();var right=forward.getClockWise();
        double x=(bounds.minX+bounds.maxX)*.5,z=(bounds.minZ+bounds.maxZ)*.5;
        double h=bounds.getYsize(),w=bounds.getXsize();
        boolean flyingFlat=player.isFallFlying()||player.isVisuallySwimming();
        if(flyingFlat){
            box(parts,x,bounds.minY,z,right,forward,0,.48*w,.62*h,.25*w,.38*h,.4*w,HEAD);
            box(parts,x,bounds.minY,z,right,forward,0,0,.4*h,.43*w,.36*h,.7*w,BODY);
            for(int sign:new int[]{-1,1})box(parts,x,bounds.minY,z,right,forward,sign*.22*w,-.78*w,.3*h,.15*w,.24*h,.54*w,LEGS);
        }else{
            box(parts,x,bounds.minY,z,right,forward,0,0,.88*h,.34*w,.12*h,.34*w,HEAD);
            box(parts,x,bounds.minY,z,right,forward,0,0,.59*h,.31*w,.18*h,.2*w,BODY);
            for(int sign:new int[]{-1,1}){
                box(parts,x,bounds.minY,z,right,forward,sign*.40*w,0,.56*h,.10*w,.18*h,.16*w,BODY);
                box(parts,x,bounds.minY,z,right,forward,sign*.18*w,0,.205*h,.13*w,.205*h,.21*w,LEGS);
            }
        }
    }
    private static void box(List<CameraFigure> out,double x,double y,double z,Direction right,Direction forward,
                            double across,double along,double height,double w,double h,double d,int color){
        double cx=x+right.getStepX()*across+forward.getStepX()*along,cz=z+right.getStepZ()*across+forward.getStepZ()*along;
        double dx=right.getStepX()==0?d:w,dz=right.getStepZ()==0?d:w;
        out.add(new CameraFigure(new AABB(cx-dx,y+height-h,cz-dz,cx+dx,y+height+h,cz+dz),color));
    }
}

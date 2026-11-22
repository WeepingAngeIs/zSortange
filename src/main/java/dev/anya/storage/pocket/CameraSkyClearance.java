package dev.anya.storage.pocket;

import dev.anya.storage.Safety;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

/** Clear sky observation without chunk tickets. The legacy ceiling sensor uses sectors;
 * the sharp wall lens proves its own foreground and any individual celestial sightline. */
final class CameraSkyClearance {
    private static final int SECTORS=3, LEGACY_CLAIM_LIMIT=4096, WALL_CLAIM_LIMIT=3072;
    private final ServerPlayer viewer;
    private final ServerLevel level;
    private final LoadedPocketBlocks blocks;
    private final BlockPos lens;
    private final Vec3 eye;
    private final int claimLimit;
    private final Map<BlockPos,Boolean> access=new HashMap<>();
    private final Boolean[] clear=new Boolean[SECTORS*SECTORS];
    private Boolean overhead;
    private enum Sight { CLEAR, SOLID, DENIED, UNLOADED, CLIPPED, BUDGET }

    CameraSkyClearance(ServerPlayer viewer,ServerLevel level,LoadedPocketBlocks blocks,BlockPos lens,Vec3 eye,boolean wall) {
        this.claimLimit=wall?WALL_CLAIM_LIMIT:LEGACY_CLAIM_LIMIT;
        this.viewer=viewer;this.level=level;this.blocks=blocks;this.lens=lens;this.eye=eye;
    }

    boolean sector(int column,int row,int side,Vec3 direction) {
        if(direction.y<=.15 || !level.dimensionType().hasSkyLight())return false;
        if((level.getMaxBuildHeight()-.0001-eye.y)/direction.y>624)return false;
        int index=(row*SECTORS/side)*SECTORS+column*SECTORS/side;
        if(clear[index]==null)clear[index]=open(direction);
        return clear[index];
    }

    boolean overhead() {
        if(overhead==null)overhead=open(new Vec3(0,1,0));
        return overhead;
    }

    // Call only after this exact foreground ray reached its near clipping plane through
    // loaded, permitted open space. The color is sampled at the lens; it cannot distinguish
    // any material, actor, claim, lighting or celestial detail beyond that clipping plane.
    int climate(Vec3 direction,SourceSky sky) {
        return sky!=null && direction.y>0 && overhead()?CameraScene.ATMOSPHERE:0;
    }

    // A clear 96-block foreground can show actual celestial detail only when this particular
    // ray also reaches world sky. A neighbouring ray never supplies that proof.
    int wall(Vec3 direction,SourceSky sky) {
        if(sky==null || direction.y<=0 || !level.dimensionType().hasSkyLight())return 0;
        int local=climate(direction,sky);
        if(local!=0 && sky.direction(direction)==CameraFeed.SKY)return local;
        if(direction.y<=.15 || (level.getMaxBuildHeight()-.0001-eye.y)/direction.y>624)return local;
        return switch(trace(direction,624)){
            case CLEAR -> 1;
            case CLIPPED, BUDGET -> local;
            case SOLID, DENIED, UNLOADED -> 0;
        };
    }

    boolean open(Vec3 direction) {
        if(direction.y<=.15 || !level.dimensionType().hasSkyLight())return false;
        double distance=(level.getMaxBuildHeight()-.0001-eye.y)/direction.y;
        return distance<=624 && trace(direction,624)==Sight.CLEAR;
    }

    private Sight trace(Vec3 direction,int limit) {
        double toSky=(level.getMaxBuildHeight()-.0001-eye.y)/direction.y;
        if(toSky<=0)return Sight.CLEAR;
        Vec3 end=eye.add(direction.scale(Math.min(toSky,limit)));
        return BlockGetter.traverseBlocks(eye,end,this,(observer,pos)->{
            if(!level.getWorldBorder().isWithinBounds(pos))return Sight.DENIED;
            // Stop before touching the first unloaded column. A missing foreground chunk was
            // already masked by the actual picture ray; this result contains no distant geometry.
            if(blocks.chunk(pos.getX()>>4,pos.getZ()>>4)==null)return Sight.UNLOADED;
            if(access.size()>=claimLimit && !access.containsKey(pos))return Sight.BUDGET;
            if(!observer.allowed(pos))return Sight.DENIED;
            if(pos.equals(lens) || observer.blocks.getBlockState(pos).isAir())return null;
            return Sight.SOLID;
        },observer->toSky<=limit?Sight.CLEAR:Sight.CLIPPED);
    }

    private boolean allowed(BlockPos pos) {
        Boolean old=access.get(pos);
        if(old!=null)return old;
        if(access.size()>=claimLimit || blocks.chunk(pos.getX()>>4,pos.getZ()>>4)==null)return false;
        boolean ok=Safety.locationReason(viewer,level,pos).isEmpty();
        access.put(pos.immutable(),ok);
        return ok;
    }
}

package dev.anya.storage.pocket;

import dev.anya.storage.Safety;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

/** Only the atmosphere beyond the foreground is depicted. Each distant sector uses one proved
 * sightline; a distant private voxel, solid or air, has the same unavailable result. */
final class CameraSkyClearance {
    private static final int SECTORS=3, CLAIM_LIMIT=4096;
    private final ServerPlayer viewer;
    private final ServerLevel level;
    private final LoadedPocketBlocks blocks;
    private final BlockPos lens;
    private final Vec3 eye;
    private final Map<BlockPos,Boolean> access=new HashMap<>();
    private final Boolean[] clear=new Boolean[SECTORS*SECTORS];

    CameraSkyClearance(ServerPlayer viewer,ServerLevel level,LoadedPocketBlocks blocks,BlockPos lens,Vec3 eye) {
        this.viewer=viewer;this.level=level;this.blocks=blocks;this.lens=lens;this.eye=eye;
    }

    boolean sector(int column,int row,int side,Vec3 direction) {
        if(direction.y<=.15 || !level.dimensionType().hasSkyLight())return false;
        if((level.getMaxBuildHeight()-.0001-eye.y)/direction.y>624)return false;
        int index=(row*SECTORS/side)*SECTORS+column*SECTORS/side;
        if(clear[index]==null)clear[index]=open(direction);
        return clear[index];
    }

    boolean open(Vec3 direction) {
        if(direction.y<=.15 || !level.dimensionType().hasSkyLight())return false;
        double distance=(level.getMaxBuildHeight()-.0001-eye.y)/direction.y;
        if(distance<=0)return true;
        if(distance>624)return false;
        Vec3 end=eye.add(direction.scale(distance));
        return BlockGetter.traverseBlocks(eye,end,this,(observer,pos)->{
            if(!observer.allowed(pos))return Boolean.FALSE;
            if(pos.equals(lens) || observer.blocks.getBlockState(pos).isAir())return null;
            return Boolean.FALSE;
        },observer->Boolean.TRUE);
    }

    private boolean allowed(BlockPos pos) {
        Boolean old=access.get(pos);
        if(old!=null)return old;
        if(access.size()>=CLAIM_LIMIT || blocks.chunk(pos.getX()>>4,pos.getZ()>>4)==null)return false;
        boolean ok=Safety.locationReason(viewer,level,pos).isEmpty();
        access.put(pos.immutable(),ok);
        return ok;
    }
}

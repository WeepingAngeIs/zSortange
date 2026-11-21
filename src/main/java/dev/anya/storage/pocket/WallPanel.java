package dev.anya.storage.pocket;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.*;

/** Rectangle in wall cells; the player's original anchor may be anywhere inside it. */
record WallPanel(int width,int height,int left,int below) {
    static final WallPanel ONE=new WallPanel(1,1,0,0);
    static WallPanel read(CompoundTag n,WallMonitorBlock tier){
        int w=Math.clamp(n.getInt("W"),1,tier.maxW),h=Math.clamp(n.getInt("H"),1,tier.maxH);
        return new WallPanel(w,h,Math.clamp(n.getInt("L"),0,w-1),Math.clamp(n.getInt("B"),0,h-1));
    }
    CompoundTag save(){var n=new CompoundTag();n.putInt("W",width);n.putInt("H",height);n.putInt("L",left);n.putInt("B",below);return n;}
    static Direction face(CameraDevices.Device d){return d.getBlockState().getValue(BlockStateProperties.FACING);}
    static Direction right(Direction face){return face.getAxis().isHorizontal()?face.getCounterClockWise():Direction.EAST;}
    static BlockPos cell(CameraDevices.Device d,int x,int y){return d.getBlockPos().relative(right(face(d)),x).above(y);}
    boolean covers(CameraDevices.Device d,BlockPos pos){
        var delta=pos.subtract(d.getBlockPos());var f=face(d);var r=right(f);
        int x=delta.getX()*r.getStepX()+delta.getZ()*r.getStepZ();
        return delta.getX()*f.getStepX()+delta.getZ()*f.getStepZ()==0
                &&x>=-left&&x<width-left&&delta.getY()>=-below&&delta.getY()<height-below;
    }
    Vec3 center(CameraDevices.Device d){
        var f=face(d);
        return Vec3.atCenterOf(d.getBlockPos()).add(Vec3.atLowerCornerOf(f.getNormal()).scale(-.4375+.003))
                .add(Vec3.atLowerCornerOf(right(f).getNormal()).scale((width-1)*.5-left)).add(0,(height-1)*.5-below,0);
    }
    AABB bounds(CameraDevices.Device d){
        var a=cell(d,-left,-below);var b=cell(d,width-left-1,height-below-1);
        return new AABB(a).minmax(new AABB(b));
    }
    private static final Map<Level,Map<Long,Set<CameraDevices.Device>>> levels=new WeakHashMap<>();
    static synchronized void index(CameraDevices.Device d,boolean add){
        var level=d.getLevel();
        if(level==null||!(d.getBlockState().getBlock() instanceof WallMonitorBlock))return;
        var map=add?levels.computeIfAbsent(level,k->new HashMap<>()):levels.get(level);if(map==null)return;
        var a=cell(d,-d.panel.left,-d.panel.below);var b=cell(d,d.panel.width-d.panel.left-1,d.panel.height-d.panel.below-1);
        for(int x=Math.min(a.getX(),b.getX())>>4;x<=Math.max(a.getX(),b.getX())>>4;x++)
            for(int z=Math.min(a.getZ(),b.getZ())>>4;z<=Math.max(a.getZ(),b.getZ())>>4;z++){
                long key=ChunkPos.asLong(x,z);
                if(add)map.computeIfAbsent(key,k->new HashSet<>()).add(d);
                else{var set=map.get(key);if(set!=null&&set.remove(d)&&set.isEmpty())map.remove(key);}
            }
        if(map.isEmpty())levels.remove(level);
    }
    static synchronized void unload(Level l){levels.remove(l);}
    static synchronized Iterable<CameraDevices.Device> at(Level level,BlockPos pos){
        var map=levels.get(level);return map==null?Set.of():Set.copyOf(map.getOrDefault(ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4),Set.of()));
    }
    static CameraDevices.Device atHit(Level level,BlockHitResult hit,Vec3 eye,Vec3 look){
        var face=hit.getDirection();if(!face.getAxis().isHorizontal())return null;
        for(int layer=0;layer<=1;layer++){
            var cell=hit.getBlockPos().relative(face,layer);
            for(var d:at(level,cell)){
                if(d.isRemoved()||face(d)!=face||!d.panel.covers(d,cell))continue;
                Vec3 point=CameraFeed.looking(d,eye,look);
                if(point!=null&&eye.distanceTo(point)<=eye.distanceTo(hit.getLocation())+.025)return d;
            }
        }
        return null;
    }
}

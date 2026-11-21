package probe;

import dev.anya.storage.StorageAccessEvent;
import dev.anya.storage.pocket.*;
import java.nio.file.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

@EventBusSubscriber(modid="wallprobe")
public final class WallProbe {
    static final BlockPos CAM=new BlockPos(1,92,1), FAR=new BlockPos(1000000,92,1000000);
    static int stage;
    static boolean hide;
    static int farLoads;
    @SubscribeEvent public static void deny(StorageAccessEvent e){
        if(hide && e.level.dimension()==Level.OVERWORLD && e.position.equals(CAM.east(2)))e.setCanceled(true);
    }
    @SubscribeEvent public static void load(ChunkEvent.Load e){
        if(e.getChunk().getPos().x==(FAR.getX()>>4) && e.getChunk().getPos().z==(FAR.getZ()>>4))farLoads++;
    }
    static boolean seen(String s){return Files.exists(Path.of("../Client/"+s));}
    static boolean checkWall(ServerPlayer p,BlockPos pos)throws Exception{
        var outside=p.getServer().overworld();var room=p.serverLevel();var monitor=pos.above();var facing=BlockStateProperties.FACING;
        if(stage==0){
            var sky=(CameraDevices.Device)room.getBlockEntity(pos.above(3));
            var tag=sky.saveWithoutMetadata(room.registryAccess());
            room.setBlockAndUpdate(monitor,CameraDevices.WALL.get().defaultBlockState().setValue(facing,Direction.SOUTH));
            ((CameraDevices.Device)room.getBlockEntity(monitor)).loadWithComponents(tag,room.registryAccess());
            for(int i=-1;i<=1;i++)for(int j=-1;j<=1;j++){
                outside.setBlockAndUpdate(CAM.north(2).offset(i,j,0),Blocks.RED_CONCRETE.defaultBlockState());
                outside.setBlockAndUpdate(CAM.east(2).offset(0,j,i),Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState());
            }
            outside.setBlockAndUpdate(CAM,outside.getBlockState(CAM).setValue(facing,Direction.NORTH));
            p.connection.teleport(monitor.getX()+.5,65,monitor.getZ()+3.5,180,0);stage++;return false;
        }
        if(stage==1 && seen("north")){
            outside.setBlockAndUpdate(CAM,outside.getBlockState(CAM).setValue(facing,Direction.EAST));stage++;
        }else if(stage==2 && seen("east")){hide=true;stage++;}
        else if(stage==3 && seen("masked")){
            hide=false;
            var d=(CameraDevices.Device)room.getBlockEntity(monitor);
            var tag=d.saveWithoutMetadata(room.registryAccess());tag.putLong("Position",FAR.asLong());d.loadWithComponents(tag,room.registryAccess());
            CameraProbe.check(outside.getChunkSource().getChunkNow(FAR.getX()>>4,FAR.getZ()>>4)==null,"Remote column already loaded");
            Files.writeString(Path.of("checking-unloaded"),"1");stage++;
        }else if(stage==4 && seen("unavailable")){
            CameraProbe.check(farLoads==0 && outside.getChunkSource().getChunkNow(FAR.getX()>>4,FAR.getZ()>>4)==null,"Camera loaded missing source");
            var eye=Vec3.atCenterOf(CAM).add(.58,0,0);var cow=EntityType.COW.create(outside);
            cow.moveTo(eye.x,eye.y,eye.z+3);cow.setNoAi(true);cow.setNoGravity(true);outside.addFreshEntity(cow);
            CameraProbe.check(glance(p,outside,eye)==cow,"Visible cow skipped");
            cow.setInvisible(true);CameraProbe.check(glance(p,outside,eye)!=cow,"Invisible cow selected");cow.setInvisible(false);
            for(int j=-1;j<=2;j++)outside.setBlockAndUpdate(BlockPos.containing(eye).offset(0,j,1),Blocks.STONE.defaultBlockState());
            CameraProbe.check(glance(p,outside,eye)!=cow,"Hidden cow selected");cow.discard();stage++;return true;
        }
        return stage==5;
    }
    static Object glance(ServerPlayer p,ServerLevel l,Vec3 eye)throws Exception{
        var method=CameraFeed.class.getDeclaredMethod("glance",ServerPlayer.class,ServerLevel.class,Vec3.class);method.setAccessible(true);
        return method.invoke(null,p,l,eye);
    }
}

package probe;

import dev.anya.storage.StorageAccessEvent;
import dev.anya.storage.pocket.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import static probe.Orders.check;

@EventBusSubscriber(modid="roomroles")
public final class StudioEvidence {
    static final BlockPos CAMERA=new BlockPos(0,82,0);
    static Mob creeper,cow,skeleton;
    static BlockPos screen,denied;
    static UUID cameraId;
    @SubscribeEvent public static void claim(StorageAccessEvent e){if(denied!=null&&e.position.equals(denied))e.setCanceled(true);}
    static CameraDevices.Device device(ServerLevel level,BlockPos pos){return (CameraDevices.Device)level.getBlockEntity(pos);}
    static CompoundTag tag(ServerLevel level,BlockPos pos){return device(level,pos).saveWithoutMetadata(level.registryAccess());}
    static int property(ServerLevel level,BlockPos pos,String key){
        if(key.equals("width")||key.equals("height"))return tag(level,pos).getCompound("Panel").getInt(key.equals("width")?"W":"H");
        var state=level.getBlockState(pos);return Integer.parseInt(state.getValue(state.getBlock().getStateDefinition().getProperty(key)).toString());
    }
    static UUID tracked(ServerLevel level)throws Exception{
        var field=CameraDevices.Device.class.getDeclaredField("tracking");field.setAccessible(true);
        var t=field.get(device(level,CAMERA));var f=t.getClass().getDeclaredField("target");f.setAccessible(true);
        return (UUID)f.get(t);
    }
    static void scenery(ServerLevel level){
        for(int x=-6;x<=6;x++)for(int y=-3;y<=4;y++){
            var block=y<0?Blocks.MOSS_BLOCK:Blocks.LIGHT_BLUE_CONCRETE;
            if((x==0&&y>=-1&&y<=1)||(x==-4&&y>=-1&&y<=0))block=Blocks.OAK_LOG;
            if((Math.abs(x)<=1&&y>=2&&y<=3)||(Math.abs(x+4)<=1&&y>=1&&y<=2))block=Blocks.CHERRY_LEAVES;
            if(x==4&&y==3)block=Blocks.YELLOW_CONCRETE;
            level.setBlockAndUpdate(CAMERA.north(5).offset(x,y,0),block.defaultBlockState());
        }
    }
    static Mob mob(ServerLevel level,EntityType<? extends Mob> type,double x,double z){
        var mob=type.create(level);mob.setNoAi(true);mob.setNoGravity(true);mob.setInvulnerable(true);
        mob.moveTo(x,81,z,0,0);level.addFreshEntity(mob);return mob;
    }
    static void spawn(ServerLevel level){
        creeper=mob(level,EntityType.CREEPER,4.2,-1.9);cow=mob(level,EntityType.COW,-3,-1.4);skeleton=mob(level,EntityType.SKELETON,-4,-1.7);
    }
    static void move(Mob m){m.setPos(m.getX(),m.getY(),m.getZ()< -1.8?-1.35:-2.15);}
    static void settings(ServerLevel level){
        var t=tag(level,CAMERA).getCompound("Tracking");
        check(t.getString("Mode").equals("MOTION")&&t.getString("Group").equals("SELECTED")
                &&t.getCompound("Types").getByte("minecraft:creeper")==1&&t.getCompound("Types").getByte("minecraft:cow")==2,"Saved camera rules");
    }
    static void checkpoint(MinecraftServer server,ServerLevel room,BlockPos monitor)throws Exception{
        settings(server.overworld());var data=tag(room,monitor);
        check(cameraId.equals(data.getUUID("Camera")),"Existing link lost after resize");
        Orders.write("geometry.txt",monitor.asLong()+"\n"+cameraId+"\n"+data.getUUID("Identity"));
        var old=tag(server.overworld(),CAMERA);old.remove("Tracking");old.putBoolean("Glances",true);
        var copy=new CameraDevices.Device(CAMERA,server.overworld().getBlockState(CAMERA));
        copy.loadWithComponents(old,server.registryAccess());
        check(copy.saveWithoutMetadata(server.registryAccess()).getCompound("Tracking").getString("Mode").equals("GLANCE"),"Legacy glances");
    }
    static void reloaded(MinecraftServer s)throws Exception{
        var lines=Files.readAllLines(Orders.ROOT.resolve("geometry.txt"));
        var level=s.overworld();var room=PocketService.dimension(s);var pos=BlockPos.of(Long.parseLong(lines.get(0)));
        level.getChunkAt(CAMERA);room.getChunkAt(pos);
        settings(level);
        check(tracked(level)==null&&property(room,pos,"width")==3&&property(room,pos,"height")==3,"Stored panel size or stale motion");
        var data=tag(room,pos);
        check(data.getUUID("Camera").toString().equals(lines.get(1))&&data.getUUID("Identity").toString().equals(lines.get(2))
                &&tag(level,CAMERA).getUUID("Identity").toString().equals(lines.get(1)),"Reloaded camera/monitor identities");
        Orders.write("restored","PASS clean dedicated restart restored the 3×3 wall, tablet link, camera identity, and Watch/Ignore choices; transient motion was cleared.");
        s.halt(false);
    }
}

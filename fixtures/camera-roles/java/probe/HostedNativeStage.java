package probe;

import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.anya.storage.pocket.CameraDevices;
import dev.anya.storage.pocket.CameraFeed;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import static probe.Orders.check;

/** The two synthetic hosted clients share a private loopback runner and a single fixed outdoor lens. */
@EventBusSubscriber(modid="roomroles")
public final class HostedNativeStage {
    static final String TRIAL="sharp-horizon-new", STEP="live-stock-lens-on-skygrid-wall";
    static final String VISTA="vista", STATE_ONE="copper", STATE_TWO="blue";
    // Lens to near face at the marker center is 95.72 blocks. The target stands between
    // the purple and blue landmarks so the separate orange cross at 112 stays visible.
    static final BlockPos LENS=new BlockPos(168,82,8), MARK=LENS.north(93).offset(25,5,0);
    private static final Map<BlockPos,BlockState> replaced=new LinkedHashMap<>();
    private static boolean active, placed, changed;
    static boolean hosted(String trial){
        return "true".equals(System.getenv("GITHUB_ACTIONS")) && trial.equals(System.getProperty("roomroles.trial"))
                && Orders.ROOT.toAbsolutePath().normalize().endsWith(Path.of("role-check",trial,"control"));
    }
    static void steps(){
        Scenario.step(STEP,()->{
            check(hosted(TRIAL),"Native transport requires the dedicated synthetic hosted trial");
            var owner=Scenario.player("CamOwner");var outside=Scenario.player("RoomBuilder");
            check(owner.level()!=outside.level() && outside.serverLevel()==outside.getServer().overworld(),"Expected Skygrid and outside observers");
            var wall=Scenario.studioWall();var viewer=new Vec3(wall.getX()+1.2,65,wall.getZ()-3.5);
            Scenario.order("CamOwner","sharp.native.watch",wall,viewer,Vec3.atCenterOf(wall),Items.AIR,false,null,0);
            var standing=Vec3.atBottomCenterOf(LENS).add(-2.8,-1,-4.5);
            Scenario.order("RoomBuilder","sharp.native.send",LENS,standing,standing.add(7,1.6,3),Items.AIR,false,null,0);
            // Preserve every replaced block, including whatever the landmark course placed nearby.
            var level=outside.serverLevel();
            for(BlockPos p:BlockPos.betweenClosed(MARK.offset(-5,-4,0),MARK.offset(5,4,0))){
                check(level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)!=null,"Marker must load naturally");
                replaced.put(p.immutable(),level.getBlockState(p));
                check(level.getBlockState(p).isAir(),"Unobscured first native vista requires an empty marker position");
            }
            placed=false;changed=false;active=true;
        },()->true);
        Scenario.verify("close-stock-lens-session",()->{
            active=false;Files.deleteIfExists(Orders.ROOT.resolve("native-permit"));
            var level=Scenario.player("RoomBuilder").serverLevel();
            replaced.forEach(level::setBlockAndUpdate);replaced.clear();
            check(changed && Files.isRegularFile(Orders.ROOT.resolve("native-presented-blue")),"Missing second live picture");
        });
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(!active || !hosted(TRIAL))return;
        var owner=Scenario.player("CamOwner");var outside=Scenario.player("RoomBuilder");
        var monitor=owner.serverLevel().getBlockEntity(Scenario.studioWall());
        var method=CameraFeed.class.getDeclaredMethod("source",ServerPlayer.class,CameraDevices.Device.class);
        method.setAccessible(true);
        boolean permitted=monitor instanceof CameraDevices.Device d && method.invoke(null,owner,d) instanceof CameraDevices.Device source
                && source.getBlockPos().equals(LENS) && source.getLevel()==outside.level() && owner.level()!=source.getLevel()
                && outside.distanceToSqr(Vec3.atCenterOf(LENS))<36 && event.getServer().getPlayerCount()==2;
        if(!permitted){Files.deleteIfExists(Orders.ROOT.resolve("native-permit"));return;}
        Orders.write("native-permit",Long.toString(System.currentTimeMillis()));
        if(!placed && Files.isRegularFile(Orders.ROOT.resolve("native-presented-vista"))){
            for(var cell:replaced.keySet())outside.serverLevel().setBlockAndUpdate(cell,Blocks.ORANGE_CONCRETE.defaultBlockState());
            placed=true;
        }else if(placed && !changed && Files.isRegularFile(Orders.ROOT.resolve("native-presented-copper"))){
            for(var cell:replaced.keySet())outside.serverLevel().setBlockAndUpdate(cell,Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState());
            changed=true;
        }
    }
}

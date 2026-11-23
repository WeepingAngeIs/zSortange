package probe;

import dev.anya.storage.BasePermissions;
import dev.anya.storage.Content;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.pocket.CameraDevices;
import dev.anya.storage.pocket.PocketContent;
import dev.anya.storage.pocket.PocketService;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid="anyadisplayprobe")
public final class RoomMediaServer {
    private static final BlockPos HUB = new BlockPos(8,81,8), ENTRY = HUB.east(2);
    private static int step, age;
    private static BlockPos screen;
    private static StorageEntity hub;
    private static PocketContent.EntranceEntity entrance;
    static final String SIDE = System.getProperty("anyadisplay.room", "");
    static boolean flag(String name) { return Files.exists(MediaTrial.proof.resolve(name)); }
    static void mark(String name, String message) throws Exception { Files.writeString(MediaTrial.proof.resolve(name),message); }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static void next() { step++; age=0; }
    private static void click(ServerPlayer p, BlockPos pos, Direction side) {
        p.gameMode.useItemOn(p,p.serverLevel(),p.getMainHandItem(),InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos).relative(side,.49),side,pos,false));
    }
    private static void look(ServerPlayer p,int offset) {
        double x=screen.getX()+.5+offset, y=screen.getY()-1, z=screen.getZ()+3.1;
        Vec3 delta=Vec3.atCenterOf(screen).subtract(x,y+p.getEyeHeight(),z);
        float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
        float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,delta.horizontalDistance()));
        p.teleportTo(p.serverLevel(),x,y,z,yaw,pitch);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) throws Exception {
        if(!SIDE.equals("Server")||flag("PASS")||flag("FAILED"))return;
        try {
            var server=event.getServer();
            var owner=server.getPlayerList().getPlayerByName("RoomHost");
            var guest=server.getPlayerList().getPlayerByName("RoomGuest");
            var late=server.getPlayerList().getPlayerByName("LateGuest");
            if(owner==null || guest==null || late==null || !flag("verified-mixer-routes")
                    || !flag("audit-RoomHost") || !flag("audit-RoomGuest") || !flag("audit-LateGuest"))return;
            require(++age<1500,"Hosted room step "+step+" timed out");
            var home=server.overworld();
            switch(step){
                case 0 -> {
                    MediaTrial.hosted();
                    for(var pos:BlockPos.betweenClosed(HUB.offset(-5,-1,-4),HUB.offset(6,-1,6)))
                        home.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
                    home.setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());
                    home.setBlockAndUpdate(ENTRY,PocketContent.ENTRANCE.get().defaultBlockState());
                    hub=(StorageEntity)home.getBlockEntity(HUB);hub.owner=owner.getUUID();
                    entrance=(PocketContent.EntranceEntity)home.getBlockEntity(ENTRY);entrance.owner=owner.getUUID();
                    hub.setMember(guest.getUUID(),BasePermissions.VISIT);
                    for(ServerPlayer p:List.of(owner,guest,late)){
                        require(!p.hasPermissions(2) && p.gameMode.getGameModeForPlayer()==GameType.SURVIVAL,"Expected three ordinary Survival players");
                        p.teleportTo(home,ENTRY.getX()+.5,81,ENTRY.getZ()+2.5,180,0);
                    }
                    var camera=ENTRY.east(2);
                    var cameraBlock=CameraDevices.CAMERA.get();
                    var cameraState=cameraBlock.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.SOUTH);
                    home.setBlockAndUpdate(camera.north(),Blocks.STONE.defaultBlockState());
                    home.setBlockAndUpdate(camera,cameraState);
                    cameraBlock.setPlacedBy(home,camera,cameraState,guest,new ItemStack(cameraBlock));
                    guest.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Content.CUPID_TABLET.get()));
                    guest.setShiftKeyDown(true);click(guest,camera,Direction.SOUTH);guest.setShiftKeyDown(false);
                    guest.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                    owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Content.CUPID_TABLET.get()));
                    owner.setShiftKeyDown(true);click(owner,HUB,Direction.SOUTH);click(owner,ENTRY,Direction.SOUTH);
                    owner.setShiftKeyDown(false);owner.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                    require(entrance.pocket!=null,"Actual tablet binding failed");
                    click(owner,ENTRY,Direction.SOUTH);next();
                }
                case 1 -> {if(PocketService.inPocket(owner.level())){click(guest,ENTRY,Direction.SOUTH);next();}}
                case 2 -> {
                    if(!PocketService.inPocket(guest.level()))return;
                    var room=PocketService.ledger(server).get(entrance.pocket);
                    screen=new BlockPos(room.x()+12,66,room.z()+15);
                    var level=owner.serverLevel();
                    level.setBlockAndUpdate(screen.north(),Blocks.SMOOTH_STONE.defaultBlockState());
                    var model=CameraDevices.WIDE.get();
                    var state=model.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.SOUTH);
                    level.setBlockAndUpdate(screen,state);
                    model.setPlacedBy(level,screen,state,owner,new ItemStack(model));
                    look(owner,1);look(guest,0);
                    mark("screen",Long.toString(screen.asLong()));next();
                }
                case 3 -> {
                    if(!flag("movie-RoomHost")||!flag("movie-RoomGuest")||!flag("excluded-LateGuest"))return;
                    if(!flag("volume-test")){
                        mark("volume-test","Two players independently adjust their own volume");
                        return;
                    }
                    if(!flag("volume-RoomHost")||!flag("volume-RoomGuest"))return;
                    require(!hub.permits(guest,BasePermissions.BUILD),"Sharing granted Build");
                    guest.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Content.CUPID_TABLET.get()));
                    guest.setShiftKeyDown(true);click(guest,screen,Direction.SOUTH);guest.setShiftKeyDown(false);
                    var device=owner.serverLevel().getBlockEntity(screen);
                    require(!device.saveWithoutMetadata(owner.registryAccess()).hasUUID("Camera"),"Visit-only guest linked the owner's monitor");
                    var target=screen.east(3).below();
                    guest.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.CHERRY_PLANKS));
                    click(guest,target.below(),Direction.UP);
                    require(guest.getMainHandItem().getCount()==1 && !guest.serverLevel().getBlockState(target).is(Blocks.CHERRY_PLANKS),"Visit-only block placement");
                    guest.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                    look(guest,0);
                    require(late.level()==home && !hub.permits(late,BasePermissions.VISIT),"Third player already authorized");
                    hub.setMember(late.getUUID(),BasePermissions.VISIT);
                    click(late,ENTRY,Direction.SOUTH);next();
                }
                case 4 -> {if(PocketService.inPocket(late.level())){look(late,-1);mark("late-inside","Visitor entered while the movie was playing");next();}}
                case 5 -> {
                    if(!flag("movie-LateGuest"))return;
                    hub.setMember(late.getUUID(),0);mark("revoked","LateGuest's Visit grant removed");next();
                }
                case 6 -> {
                    if(!flag("ended-LateGuest") || PocketService.inPocket(late.level()))return;
                    mark("stop-request","Guest presses Stop");next();
                }
                case 7 -> {
                    if(!flag("ended-RoomGuest")||!flag("host-continued"))return;
                    if(!flag("stop-host"))mark("stop-host","Owner stops only their playback");
                    if(!flag("ended-RoomHost"))return;
                    require(!hub.permits(guest,BasePermissions.BUILD),"Guest gained Build");
                    var block=owner.serverLevel().getBlockEntity(screen);
                    require(!block.saveWithoutMetadata(owner.registryAccess()).hasUUID("Camera"),"Guest playback linked a camera");
                    var level=owner.serverLevel();
                    level.setBlockAndUpdate(screen.below(),Blocks.QUARTZ_BLOCK.defaultBlockState());
                    var tv=CameraDevices.TV.get();
                    var state=tv.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.SOUTH);
                    level.setBlockAndUpdate(screen,state);tv.setPlacedBy(level,screen,state,owner,new ItemStack(tv));
                    look(owner,1);look(guest,0);mark("tv-gif","Place a tabletop TV and start a second session");next();
                }
                case 8 -> {
                    if(!flag("gif-RoomHost")||!flag("gif-RoomGuest")||!flag("gif-excluded"))return;
                    mark("PASS","Visit-only room Play, owner automatic playback on the wall and on a separate tabletop TV, independent personal volume and Stop, outside packet exclusion, late arrival, Visit revocation, and no Build or camera link. Three real Survival players.");
                }
                default -> throw new IllegalStateException();
            }
        }catch(Exception failure){mark("FAILED","server step "+step+": "+failure);throw failure;}
    }
    private RoomMediaServer(){}
}

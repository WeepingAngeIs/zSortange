package probe;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.*;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import static probe.Orders.check;

@Mod("roomroles")
@EventBusSubscriber(modid="roomroles")
public final class Scenario {
    private static final String OWNER="CamOwner", BUILDER="RoomBuilder", GUEST="RoomVisitor";
    private static final boolean BASELINE="baseline".equals(System.getProperty("roomroles.trial"));
    private static final BlockPos HUB=new BlockPos(7,81,-5), DOOR=HUB.east(2);
    private static final BlockPos CAMERA=new BlockPos(0,82,0), WALL=new BlockPos(6,82,5), UP=new BlockPos(-6,81,0);
    private static final Direction NORTH=Direction.NORTH, SOUTH=Direction.SOUTH;
    private static final List<String> ROLES=BASELINE?List.of(OWNER,BUILDER):List.of(OWNER,BUILDER,GUEST);
    private static final List<Step> steps=new ArrayList<>();
    private static final Map<String,Orders.Order> pending=new HashMap<>();
    private static final Set<String> receivedClicks=new HashSet<>();
    private static MinecraftServer server;
    private static ServerLevel home;
    private static StorageEntity hub;
    private static PocketLedger.Pocket room;
    private static String current;
    private static int index,started,acks;
    private static boolean finished;
    private static UUID cameraId;
    private interface Work {void run()throws Exception;}
    private interface Ready {boolean get()throws Exception;}
    private record Step(String name,Work start,Ready ready){}
    private static ServerPlayer player(String name){return server.getPlayerList().getPlayerByName(name);}
    private static void step(String name,Work action,Ready ready){steps.add(new Step(name,action,ready));}
    private static void verify(String name,Work action){step(name,action,()->true);}
    private static BlockPos roomPos(int x,int y,int z){return new BlockPos(room.x()+x,y,room.z()+z);}
    private static ServerLevel inside(){return PocketService.dimension(server);}
    private static void writeLog(String value)throws Exception{
        Files.writeString(Orders.ROOT.resolve("events.txt"),value+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST,receiveCanceled=true)
    public static void rightClick(PlayerInteractEvent.RightClickBlock e){
        if(!(e.getEntity() instanceof ServerPlayer p) || e.getHand()!=InteractionHand.MAIN_HAND)return;
        var role=p.getGameProfile().getName();var order=pending.get(role);
        if(order!=null && order.action().equals("use") && order.target().equals(e.getPos())
                && order.sneak()==p.isShiftKeyDown())receivedClicks.add(role);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(finished)return;
        try{update(event.getServer());}
        catch(Throwable failure){
            finished=true;Orders.write("FAIL-Server",current+" "+failure);throw failure;
        }
    }
    private static void update(MinecraftServer instance)throws Exception{
        server=instance;
        if(steps.isEmpty()){
            if(ROLES.stream().anyMatch(role->player(role)==null || player(role).tickCount<50))return;
            prepare();
        }
        var now=server.getTickCount();
        if(current==null){
            if(index==steps.size()){
                finished=true;Orders.write("PASS.txt",Files.readString(Orders.ROOT.resolve("events.txt")));return;
            }
            var next=steps.get(index);
            current=next.name();started=now;acks=0;pending.clear();receivedClicks.clear();
            Orders.write("step.txt",current);next.start().run();return;
        }
        if(now-started>600)throw new AssertionError("Timed out at "+current+" pending="+pending.keySet()+" received="+receivedClicks);
        for(var entry:pending.entrySet()){
            var role=entry.getKey();
            if(!Files.exists(Orders.ack(role,current)) || entry.getValue().action().equals("use")&&!receivedClicks.contains(role))return;
        }
        if(++acks<6 || !steps.get(index).ready().get())return;
        writeLog("PASS "+current);index++;current=null;
    }
    private static void prepare()throws Exception{
        home=server.overworld();
        var options=ExpansionRules.get();options.pocketStorage=true;options.pocketEditing=true;
        home.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        home.setDayTime(6000);home.setWeatherParameters(24000,0,false,false);
        for(var cell:BlockPos.betweenClosed(-14,80,-12,16,80,16))home.setBlockAndUpdate(cell,Blocks.SMOOTH_STONE.defaultBlockState());
        home.setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());
        home.setBlockAndUpdate(DOOR,PocketContent.ENTRANCE.get().defaultBlockState());
        var owner=player(OWNER);hub=(StorageEntity)home.getBlockEntity(HUB);hub.owner=owner.getUUID();
        ((PocketContent.EntranceEntity)home.getBlockEntity(DOOR)).owner=owner.getUUID();
        hub.setMember(player(BUILDER).getUUID(),BasePermissions.VISIT|BasePermissions.BUILD);
        if(!BASELINE)hub.setMember(player(GUEST).getUUID(),BasePermissions.VISIT);
        int i=0;
        for(String name:ROLES){
            var p=player(name);
            check(!p.hasPermissions(2) && p.gameMode.getGameModeForPlayer()==GameType.SURVIVAL,"Expected ordinary Survival "+name);
            p.teleportTo(home,11.5+i,81,8.5,0,0);i++;
        }
        owner.getInventory().setItem(1,new ItemStack(Content.CUPID_TABLET.get()));
        frontUse("bind-tablet-to-hub",HUB,Content.CUPID_TABLET.get(),true);
        frontUse("bind-room-cube",DOOR,Content.CUPID_TABLET.get(),true);
        if(!BASELINE)outsideCameraSteps();
        for(String role:ROLES){
            String name="enter-"+role;
            step(name,()->front(role,DOOR,Items.AIR,false),()->{
                var p=player(role);
                if(!PocketService.inPocket(p.level()))return false;
                var entrance=(PocketContent.EntranceEntity)home.getBlockEntity(DOOR);
                room=PocketService.ledger(server).get(entrance.pocket);
                var staging=roomPos(8+ROLES.indexOf(role)*3,65,23);
                p.connection.teleport(staging.getX()+.5,65,staging.getZ()+.5,0,0);
                return true;
            });
        }
        step("all-builders-inside-with-no-exterior-preview-viewers",()->{},()->{
            var viewers=PocketPadObservation.class.getDeclaredField("viewers");viewers.setAccessible(true);
            return ((Map<?,?>)viewers.get(null)).isEmpty();
        });
        build("owner-places-cherry-planks",OWNER,0,Items.CHERRY_PLANKS,true);
        build(BASELINE?"reproduce-build-guest-cannot-place-door-on-arrival":"build-guest-places-door-on-arrival",BUILDER,3,Items.OAK_DOOR,!BASELINE);
        if(BASELINE)return;
        build("build-guest-places-cherry-planks-without-edit-menu",BUILDER,4,Items.CHERRY_PLANKS,true);
        build("visit-only-guest-denied-door-keeps-item",GUEST,6,Items.OAK_DOOR,false);
        build("visit-only-guest-denied-planks-keeps-item",GUEST,7,Items.CHERRY_PLANKS,false);
        step("builder-denied-exit-path",()->floorUse(BUILDER,roomPos(15,65,21),Items.OAK_DOOR),()->{
            check(inside().isEmptyBlock(roomPos(15,65,21)) && player(BUILDER).getMainHandItem().getCount()==1,"Protected path");return true;
        });
        skySteps();
        verify("remove-build-grant-while-guest-is-inside",()->hub.setMember(player(BUILDER).getUUID(),BasePermissions.VISIT));
        build("revoked-builder-denied-new-door",BUILDER,8,Items.OAK_DOOR,false);
    }

    private static void equip(ServerPlayer p,Item item){
        int slot=item==Content.CUPID_TABLET.get()?1:0;
        if(slot==0)p.getInventory().setItem(0,item==Items.AIR?ItemStack.EMPTY:new ItemStack(item));
        p.getInventory().selected=slot;
        p.connection.send(new ClientboundSetCarriedItemPacket(slot));p.inventoryMenu.broadcastChanges();
    }
    private static void order(String role,String action,BlockPos target,Vec3 feet,Vec3 aim,Item item,boolean sneak,Direction face,int color)throws Exception{
        var p=player(role);equip(p,item);
        double height=p.getEyeHeight(sneak?net.minecraft.world.entity.Pose.CROUCHING:net.minecraft.world.entity.Pose.STANDING);
        var delta=aim.subtract(feet.add(0,height,0));
        float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),pitch=(float)-Math.toDegrees(Math.atan2(delta.y,delta.horizontalDistance()));
        p.connection.teleport(feet.x,feet.y,feet.z,yaw,pitch);
        var cmd=new Orders.Order(current,action,p.level().dimension().location().toString(),target.asLong(),BuiltInRegistries.ITEM.getKey(item).toString(),sneak,
                face==null?"":face.getName(),feet.x,feet.y,feet.z,color);
        pending.put(role,cmd);Orders.write(role+".json",Orders.JSON.toJson(cmd));
    }
    private static void front(String role,BlockPos target,Item item,boolean sneak)throws Exception{
        var feet=new Vec3(target.getX()+.5,81,target.getZ()+3.1);
        order(role,"use",target,feet,Vec3.atCenterOf(target),item,sneak,null,0);
    }
    private static void frontUse(String label,BlockPos target,Item item,boolean sneak){
        step(label,()->front(OWNER,target,item,sneak),()->true);
    }
    private static void floorUse(String role,BlockPos dest,Item item)throws Exception{
        var feet=Vec3.atBottomCenterOf(dest).add(0,0,2.2);
        order(role,"use",dest.below(),feet,Vec3.atBottomCenterOf(dest),item,false,Direction.UP,0);
    }
    private static void build(String label,String role,int offset,Item item,boolean permitted){
        step(label,()->floorUse(role,roomPos(5+offset,65,16),item),()->{
            var pos=roomPos(5+offset,65,16);var p=player(role);
            if(permitted){
                check(inside().getBlockState(pos).is(Block.byItem(item)) && p.getMainHandItem().isEmpty(),"Block placement and Survival cost at "+label);
                if(item==Items.OAK_DOOR)check(inside().getBlockState(pos.above()).is(Blocks.OAK_DOOR),"Missing upper door half");
            }else check(inside().isEmptyBlock(pos) && inside().isEmptyBlock(pos.above())
                    && p.getMainHandItem().is(item)&&p.getMainHandItem().getCount()==1,"Denied placement or item count at "+label);
            return true;
        });
    }
    private static void outsideCameraSteps(){
        verify("six-attachment-faces-selection-collision-legacy-facing-and-lens-rays",()->{
            MountProbe.sixFaces(player(OWNER));
            home.setBlockAndUpdate(CAMERA.south(),Blocks.SMOOTH_STONE.defaultBlockState());
            home.setBlockAndUpdate(WALL.south(),Blocks.SMOOTH_STONE.defaultBlockState());
        });
        step("mount-outdoor-camera-on-wall",()->northUse(CAMERA.south(),CameraDevices.CAMERA.get().asItem(),false),()->{
            var d=(CameraDevices.Device)home.getBlockEntity(CAMERA);
            check(d!=null && d.owned(player(OWNER)),"Camera owner");
            cameraId=d.saveWithoutMetadata(home.registryAccess()).getUUID("Identity");return true;
        });
        step("photograph-wall-bracket-housing-and-lens",()->order(OWNER,"photo",CAMERA,new Vec3(-1.25,81,-1.55),
                Vec3.atCenterOf(CAMERA),Items.AIR,false,null,0),()->true);
        step("select-mounted-camera-with-tablet",()->northUse(CAMERA,Content.CUPID_TABLET.get(),true),()->true);
        step("place-wall-monitor",()->northUse(WALL.south(),CameraDevices.WALL.get().asItem(),false),()->true);
        step("link-wall-monitor-with-tablet",()->northUse(WALL,Content.CUPID_TABLET.get(),true),()->{
            var d=(CameraDevices.Device)home.getBlockEntity(WALL);
            check(d!=null && d.saveWithoutMetadata(home.registryAccess()).getUUID("Camera").equals(cameraId),"Normal link");
            paint(CAMERA.north(4),true,Blocks.RED_CONCRETE);paint(CAMERA.east(4),false,Blocks.LIGHT_BLUE_CONCRETE);return true;
        });
        wallView("wall-north-red-owner-and-private-guests",MapColor.COLOR_RED.col);
        verify("replace-outdoor-scene-at-fixed-aim",()->paint(CAMERA.north(4),true,Blocks.LIME_CONCRETE));
        wallView("wall-north-lime-same-aim",MapColor.COLOR_LIGHT_GREEN.col);
        step("turn-installed-camera-with-empty-hand",()->northUse(CAMERA,Items.AIR,false),()->{
            var d=(CameraDevices.Device)home.getBlockEntity(CAMERA);
            check(d.saveWithoutMetadata(home.registryAccess()).getUUID("Identity").equals(cameraId),"Swivel changed camera identity");return true;
        });
        wallView("wall-east-blue-after-player-swivel",MapColor.COLOR_LIGHT_BLUE.col);
        step("photograph-swiveled-camera-on-original-bracket",()->order(OWNER,"photo",CAMERA,new Vec3(2.1,81,-1.4),
                Vec3.atCenterOf(CAMERA),Items.AIR,false,null,0),()->true);
        step("place-upward-camera",()->floorUse(OWNER,UP,CameraDevices.CAMERA.get().asItem()),()->true);
        frontUse("select-upward-camera-with-tablet",UP,Content.CUPID_TABLET.get(),true);
    }
    private static void northUse(BlockPos target,Item item,boolean sneak)throws Exception{
        var feet=new Vec3(target.getX()+.5,81,target.getZ()-2.1);
        order(OWNER,"use",target,feet,Vec3.atCenterOf(target),item,sneak,NORTH,0);
    }
    private static void paint(BlockPos middle,boolean north,Block block){
        for(int x=-2;x<=2;x++)for(int y=-1;y<=1;y++)
            home.setBlockAndUpdate(middle.offset(north?x:0,y,north?0:x),block.defaultBlockState());
    }
    private static void wallView(String label,int color){
        step(label,()->{
            for(String role:ROLES){
                double x=role.equals(OWNER)?0:role.equals(BUILDER)?-1.35:1.35;
                var feet=new Vec3(WALL.getX()+.5+x,81,WALL.getZ()-2.45);
                order(role,role.equals(OWNER)?"view":"private",WALL,feet,Vec3.atCenterOf(WALL),Items.AIR,false,null,color);
            }
        },()->true);
    }
    private static void skySteps(){
        verify("provide-ceiling-attachment",()->inside().setBlockAndUpdate(roomPos(10,69,13),PocketContent.PANEL.get().defaultBlockState()));
        step("place-ceiling-skyglass-in-survival",()->{
            var pos=roomPos(10,69,13);
            order(OWNER,"use",pos,new Vec3(pos.getX()+.5,65,pos.getZ()+.5),Vec3.atBottomCenterOf(pos),CameraDevices.SKY.get().asItem(),false,Direction.DOWN,0);
        },()->{check(inside().getBlockState(roomPos(10,68,13)).is(CameraDevices.SKY.get()),"Ceiling screen");return true;});
        step("link-ceiling-skyglass-with-tablet",()->{
            var pos=roomPos(10,68,13);
            order(OWNER,"use",pos,new Vec3(pos.getX()+.5,65,pos.getZ()+.5),Vec3.atBottomCenterOf(pos),Content.CUPID_TABLET.get(),true,Direction.DOWN,0);
        },()->true);
        verify("grant-builder-storage-view-before-testing-private-camera",()->hub.setMember(player(BUILDER).getUUID(),BasePermissions.VISIT|BasePermissions.BUILD|BasePermissions.VIEW));
        verify("set-source-noon",()->home.setDayTime(6000));
        skyView("skyglass-noon-and-guest-privacy",0x4000000);
        verify("cover-source-camera",()->home.setBlockAndUpdate(UP.above(),Blocks.GOLD_BLOCK.defaultBlockState()));
        skyView("skyglass-physical-roof",MapColor.GOLD.col);
        verify("uncover-source-at-midnight",()->{home.removeBlock(UP.above(),false);home.setDayTime(18000);});
        skyView("skyglass-midnight-and-guest-privacy",0x5000000);
    }
    private static void skyView(String label,int color){
        step(label,()->{
            var pos=roomPos(10,68,13);
            for(String role:ROLES){
                double x=role.equals(OWNER)?0:role.equals(BUILDER)?-1.05:1.05;
                var feet=new Vec3(pos.getX()+.5+x,65,pos.getZ()+.5);
                order(role,role.equals(OWNER)?"view":"private",pos,feet,Vec3.atBottomCenterOf(pos),Items.AIR,false,null,color);
            }
        },()->true);
    }
}


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
    private static final String FLAVOR=System.getProperty("roomroles.trial","");
    private static final boolean SHARP=FLAVOR.startsWith("sharp-"), SOLO=FLAVOR.endsWith("-solo");
    private static final boolean RANGE=FLAVOR.startsWith("sharp-range-"), RANGE_OLD=FLAVOR.equals("sharp-range-old");
    private static final boolean HORIZON=FLAVOR.startsWith("sharp-horizon-"), HORIZON_OLD=FLAVOR.equals("sharp-horizon-old");
    private static final boolean OLD_PICTURE=FLAVOR.equals("sharp-old");
    private static final String OWNER="CamOwner", BUILDER="RoomBuilder", GUEST="RoomVisitor";
    private static final boolean STUDIO="studio".equals(System.getProperty("roomroles.trial"));
    private static final boolean BASELINE="baseline".equals(System.getProperty("roomroles.trial"));
    private static final BlockPos HUB=new BlockPos(7,81,-5), DOOR=HUB.east(2);
    private static final BlockPos CAMERA=new BlockPos(0,82,0), WALL=new BlockPos(6,82,5), UP=new BlockPos(-6,81,0);
    private static final Direction NORTH=Direction.NORTH, SOUTH=Direction.SOUTH;
    private static final List<String> ROLES=SOLO?List.of(OWNER):BASELINE||STUDIO||SHARP?List.of(OWNER,BUILDER):List.of(OWNER,BUILDER,GUEST);
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
    interface Work {void run()throws Exception;}
    interface Ready {boolean get()throws Exception;}
    private record Step(String name,Work start,Ready ready){}
    static ServerPlayer player(String name){return server.getPlayerList().getPlayerByName(name);}
    static void step(String name,Work action,Ready ready){steps.add(new Step(name,action,ready));}
    static void verify(String name,Work action){step(name,action,()->true);}
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
        if(finished || FLAVOR.equals("guide-solo"))return;
        try{update(event.getServer());}
        catch(Throwable failure){
            finished=true;Orders.write("FAIL-Server",current+" "+failure);throw failure;
        }
    }
    private static void update(MinecraftServer instance)throws Exception{
        server=instance;
        if(STUDIO && Files.exists(Orders.ROOT.resolve("restart"))){
            StudioEvidence.reloaded(instance);finished=true;return;
        }
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
        int timeout = current.equals("remove-outdoor-load-cause")||current.equals("integrated-room-releases-bootstrap-spawn-after-link") ? 1800 : SHARP ? 1000 : 600;
        if(now-started>timeout)throw new AssertionError("Timed out at "+current+" pending="+pending.keySet()+" received="+receivedClicks);
        if(STUDIO&&current.equals("motion-on-indoor-monitor")&&(now-started)%8==0)StudioEvidence.move(StudioEvidence.creeper);
        for(var entry:pending.entrySet()){
            var role=entry.getKey();
            if(!Files.exists(Orders.ack(role,current)) || entry.getValue().action().equals("use")&&!receivedClicks.contains(role))return;
        }
        if(++acks<6 || !steps.get(index).ready().get())return;
        writeLog("PASS "+current);index++;current=null;
    }
    private static void prepare()throws Exception{
        home=server.overworld();
        var options=ExpansionRules.get();
        if ("true".equals(System.getenv("VERIFY_POCKET_DEFAULT"))) {
            check(Config.pocketStorageEnabled() && options.pocketEditing,"Fresh settings must allow Skygrid and editing");
            var config=Path.of("config","anya-storage-plus.json");
            var saved=com.google.gson.JsonParser.parseString(Files.readString(config)).getAsJsonObject();
            var read=Config.class.getDeclaredMethod("read",Path.class);read.setAccessible(true);
            var sample=Orders.ROOT.resolve("settings-sample.json");
            try {
                var expansion=saved.getAsJsonObject("expansion");expansion.addProperty("pocketStorage",false);
                expansion.addProperty("pocketEditing",false);
                Files.writeString(sample,saved.toString());
                check(!((Config.Data)read.invoke(null,sample)).expansion.pocketStorage,"Saved off must survive loading");
                expansion.remove("pocketStorage");Files.writeString(sample,saved.toString());
                check(((Config.Data)read.invoke(null,sample)).expansion.pocketStorage,"Missing pocket setting must inherit on");
            } finally {Files.deleteIfExists(sample);}
        } else {options.pocketStorage=true;options.pocketEditing=true;}
        home.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        home.setDayTime(6000);home.setWeatherParameters(24000,0,false,false);
        for(var cell:BlockPos.betweenClosed(-14,80,-12,16,80,16))home.setBlockAndUpdate(cell,Blocks.SMOOTH_STONE.defaultBlockState());
        home.setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());
        home.setBlockAndUpdate(DOOR,PocketContent.ENTRANCE.get().defaultBlockState());
        var owner=player(OWNER);hub=(StorageEntity)home.getBlockEntity(HUB);hub.owner=owner.getUUID();
        ((PocketContent.EntranceEntity)home.getBlockEntity(DOOR)).owner=owner.getUUID();
        if(!SOLO)hub.setMember(player(BUILDER).getUUID(),BasePermissions.VISIT|BasePermissions.BUILD);
        if(!BASELINE&&!STUDIO&&!SHARP)hub.setMember(player(GUEST).getUUID(),BasePermissions.VISIT);
        int i=0;
        for(String name:ROLES){
            var p=player(name);
            check((SOLO || !p.hasPermissions(2)) && p.gameMode.getGameModeForPlayer()==GameType.SURVIVAL,"Expected ordinary Survival "+name);
            p.teleportTo(home,11.5+i,81,8.5,0,0);i++;
        }
        owner.getInventory().setItem(1,new ItemStack(Content.CUPID_TABLET.get()));
        frontUse("bind-tablet-to-hub",HUB,Content.CUPID_TABLET.get(),true);
        if(FLAVOR.startsWith("sharp-wall-night-")){wallNightSteps();return;}
        frontUse("bind-room-cube",DOOR,Content.CUPID_TABLET.get(),true);
        if(SHARP){sharpSteps();return;}
        if(STUDIO)studioOutside();else if(!BASELINE)outsideCameraSteps();
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
        if(STUDIO){studioInside();return;}
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
    static void order(String role,String action,BlockPos target,Vec3 feet,Vec3 aim,Item item,boolean sneak,Direction face,int color)throws Exception{
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
    static final BlockPos SOURCE=new BlockPos(168,82,8);
    private static net.minecraft.world.entity.animal.Cow sharpCow;
    private static void fixtureRoof(boolean present){
        for(var cell:BlockPos.betweenClosed(SOURCE.offset(-5,3,-8),SOURCE.offset(5,3,1))){
            if(present)check(home.getBlockState(cell).isAir(),"Fixture roof would replace an existing block");
            home.setBlockAndUpdate(cell,(present?Blocks.SPRUCE_PLANKS:Blocks.AIR).defaultBlockState());
        }
    }
    private static boolean tracked(int z){
        return home.getChunkSource().getChunkNow(SOURCE.getX()>>4,z)!=null;
    }
    private static int frontierDistance;
    private static void moveToFrontier(){
        // Measure the outdoor player's actual vanilla loaded radius. Room viewing must not add to it.
        int distance=1;
        while(distance<15 && tracked(-distance))distance++;
        check(distance<15,"Could not locate the native tracking frontier");
        frontierDistance=distance-1;
        moveObserverSouth();
    }
    private static void moveObserverSouth(){
        player(BUILDER).connection.teleport(SOURCE.getX()+.5,-59,SOURCE.getZ()+.5+frontierDistance*16,180,0);
    }

    static void wallScene(Block center) {
        for(int x=-6;x<=6;x++)for(int y=-1;y<=2;y++) {
            Block block=Math.abs(x)<=2 ? center : x<0 ? Blocks.BOOKSHELF : Blocks.OAK_LOG;
            home.setBlockAndUpdate(SOURCE.north(5).offset(x,y,0),block.defaultBlockState());
        }
    }
    private static void sharpStage(){
        // Vanilla spawn spans the hub and lens until the outdoor player reaches the camera.
        // A single-player visit continues to require a loaded home hub independent of the feed.
        if(HORIZON&&!SOLO){
            home.setDefaultSpawnPos(HUB,0);
            player(BUILDER).connection.teleport(SOURCE.getX()+4.5,81,SOURCE.getZ()+4.5,180,0);
        }else{
            home.setDefaultSpawnPos(new BlockPos(88,81,0),0);
            home.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(7,server);
        }
        home.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
        home.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
        for(var cell:BlockPos.betweenClosed(SOURCE.offset(-9,-2,-9),SOURCE.offset(9,-2,7)))
            home.setBlockAndUpdate(cell,Blocks.MOSS_BLOCK.defaultBlockState());
        home.setBlockAndUpdate(SOURCE.south(),Blocks.SMOOTH_STONE.defaultBlockState());
        wallScene(Blocks.STONE);
    }
    private static void sharpView(String name,String action){
        viewStudio(name,OWNER,"sharp."+action,-1);
    }
    private static void sharpReference(String name){
        if(SOLO || OLD_PICTURE)return;
        step(name,()->{
            var p=player(BUILDER);var feet=p.position();
            order(BUILDER,name.startsWith("stock-wide-")?"sharp.reference-wide":"sharp.reference",SOURCE,feet,
                    HORIZON?feet.add(0,1.6,-100):feet.add(2,1.6,2),Items.AIR,false,null,0);
        },()->true);
    }
    private static void wallNightSteps(){
        BlockPos backing=CAMERA.south(),panel=backing.south(),sign=CAMERA.north(12).above();
        verify("same-wall-cherry-lane-at-2059",()->{
            home.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
            home.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
            home.setDayTime(14990);home.setWeatherParameters(24000,0,false,false);
            for(int x=-16;x<=16;x++)for(int z=-30;z<=8;z++){
                Block floor=Math.abs(x)<=3 || x>=-6 && x<=-3 && z<=-8 ? Blocks.SMOOTH_STONE : Blocks.GRASS_BLOCK;
                home.setBlockAndUpdate(CAMERA.offset(x,-2,z),floor.defaultBlockState());
            }
            for(int x=-3;x<=3;x++)for(int y=-1;y<=3;y++)
                home.setBlockAndUpdate(backing.offset(x,y,0),Blocks.CHERRY_PLANKS.defaultBlockState());
            for(int x=-5;x<=5;x++)for(int z=-3;z<=3;z++)
                home.setBlockAndUpdate(CAMERA.offset(x,3,z),Blocks.CHERRY_SLAB.defaultBlockState());
            for(int x=-7;x<=7;x++)for(int y=-1;y<=5;y++){
                if(x>=-4 && x<=-2 && y<=1)continue; // Open alley under the facade through to the 20-block pavement.
                Block wall=Math.floorMod(x,4)==0?Blocks.STRIPPED_CHERRY_LOG:Blocks.CHERRY_PLANKS;
                if(y==2 && Math.abs(x)==3)wall=Blocks.SEA_LANTERN;
                home.setBlockAndUpdate(CAMERA.offset(x,y,-12),wall.defaultBlockState());
            }
            for(int x=-8;x<=8;x++)for(int tier=0;tier<3;tier++)
                home.setBlockAndUpdate(CAMERA.offset(x,5+tier,-11-tier),Blocks.CHERRY_STAIRS.defaultBlockState());
            for(int z=-22;z<=-4;z++)for(int y=-1;y<=3;y++){
                home.setBlockAndUpdate(CAMERA.offset(-7,y,z),(z%4==0?Blocks.CHERRY_LOG:Blocks.CHERRY_PLANKS).defaultBlockState());
                if(z<=-6)home.setBlockAndUpdate(CAMERA.offset(7,y,z),(y==3?Blocks.CHERRY_STAIRS:Blocks.CHERRY_PLANKS).defaultBlockState());
            }
            wallNightSign(sign,Blocks.GOLD_BLOCK);
            home.setBlockAndUpdate(CAMERA.offset(2,0,-6),Blocks.LANTERN.defaultBlockState());
        });
        step("mount-outside-lens",()->northUse(backing,CameraDevices.CAMERA.get().asItem(),false),()->{
            var d=StudioEvidence.device(home,CAMERA);return d!=null&&d.owned(player(OWNER));
        });
        step("select-lens-with-tablet",()->northUse(CAMERA,Content.CUPID_TABLET.get(),true),()->true);
        frontUse("mount-indoor-monitor",backing,CameraDevices.WALL.get().asItem(),false);
        frontUse("link-indoor-monitor",panel,Content.CUPID_TABLET.get(),true);
        frontUse("open-monitor-size",panel,Items.AIR,false);
        uiStep("expand-width-to-two","width");uiStep("expand-width-to-three","width");
        uiStep("expand-height-to-two","height");uiStep("expand-height-to-three","height");uiStep("close-monitor-settings","close");
        for(String state:List.of("gold","blue")){
            if(state.equals("blue"))verify("change-outdoor-sign",()->wallNightSign(sign,Blocks.LAPIS_BLOCK));
            wallNightView("wall-"+state,panel,panel,"sharp.wallnight."+state);
            wallNightView("stock-"+state,panel,CAMERA,"sharp.reference");
        }
        verify("protect-air-in-front-of-the-sign",()->StudioEvidence.curtain=CAMERA.north(9));
        wallNightView("private-sign",panel,panel,"sharp.wallnight.masked");
        verify("claim-hides-lens",()->{StudioEvidence.curtain=null;StudioEvidence.denied=CAMERA;});
        wallNightView("private-screen",panel,panel,"sharp.unavailable");
        verify("restore-owner-view",()->StudioEvidence.denied=null);
        wallNightView("wall-restored",panel,panel,"sharp.wallnight.blue");
        if ("true".equals(System.getenv("WALL_FOLLOWUP"))) {
            wallPlayerReference("night",panel);
            verify("switch-lane-to-noon",()->home.setDayTime(6000));
            wallNightView("day-wall-blue",panel,panel,"sharp.wallnight.blue");
            wallNightView("day-stock-blue",panel,CAMERA,"sharp.reference");
            wallPlayerReference("day",panel);
        }
    }
    private static void wallPlayerReference(String period, BlockPos panel) {
        step(period+"-player-view",()->{
            var p=player(OWNER);
            // Spectator fixes the physical eye at the lens even inside its collision shape.
            // All operational monitor interactions are performed in Survival.
            p.setGameMode(GameType.SPECTATOR);
            var eye=Vec3.atCenterOf(CAMERA).add(0,0,-(3.5/16+.015625));
            var feet=eye.subtract(0,p.getEyeHeight(net.minecraft.world.entity.Pose.STANDING),0);
            order(OWNER,"sharp.player-reference",CAMERA,feet,eye.add(0,0,-1),Items.AIR,false,null,0);
        },()->true);
        verify(period+"-return-to-viewer",()->{
            var p=player(OWNER);p.connection.teleport(panel.getX()+.35,81,panel.getZ()+3.25,180,0);
            p.setGameMode(GameType.SURVIVAL);
        });
    }
    private static void wallNightSign(BlockPos sign,Block block){
        for(int x=-1;x<=1;x++)for(int y=-1;y<=0;y++)
            home.setBlockAndUpdate(sign.offset(x,y,0),block.defaultBlockState());
    }
    private static void wallNightView(String label,BlockPos panel,BlockPos target,String action){
        var feet=new Vec3(panel.getX()+.35,81,panel.getZ()+3.25);
        step(label,()->order(OWNER,action,target,feet,Vec3.atCenterOf(panel),Items.AIR,false,null,0),()->true);
    }
    private static void sharpSteps(){
        verify("set-up-identical-outdoor-subject-and-blocks",Scenario::sharpStage);
        step("mount-outdoor-camera-using-held-block",()->northUse(SOURCE.south(),CameraDevices.CAMERA.get().asItem(),false),()->{
            var d=StudioEvidence.device(home,SOURCE);return d!=null && d.owned(player(OWNER));
        });
        step("select-outdoor-camera-with-tablet",()->northUse(SOURCE,Content.CUPID_TABLET.get(),true),()->true);
        for(String role:ROLES){
            if(HORIZON&&role.equals(BUILDER))continue; // This real player stays at the source throughout the measurements.
            step("enter-room-"+role,()->front(role,DOOR,Items.AIR,false),()->{
                var p=player(role);if(!PocketService.inPocket(p.level()))return false;
                room=PocketService.ledger(server).get(((PocketContent.EntranceEntity)home.getBlockEntity(DOOR)).pocket);
                var pos=roomPos(role.equals(OWNER)?9:13,65,22);
                p.connection.teleport(pos.getX()+.5,65,pos.getZ()+.5,0,0);return true;
            });
        }
        verify("provide-wall-backing-inside-skygrid",()->{
            var screen=studioWall();
            for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)
                inside().setBlockAndUpdate(screen.south().offset(x,y,0),PocketContent.PANEL.get().defaultBlockState());
        });
        studioHit("place-flat-screen",OWNER,0,1,CameraDevices.WALL.get().asItem(),false);
        studioHit("link-flat-screen-through-tablet",OWNER,0,0,Content.CUPID_TABLET.get(),true);
        sharpView("small-day","clear");
        studioHit("open-screen-size-controls",OWNER,0,0,Items.AIR,false);
        uiStep("widen-to-two","width");uiStep("widen-to-three","width");uiStep("raise-to-two","height");uiStep("raise-to-three","height");uiStep("close-settings","close");
        if(HORIZON){horizonSteps();return;}
        if(!SOLO){
            verify("grant-ordinary-storage-view-without-camera-ownership",()->hub.setMember(player(BUILDER).getUUID(),BasePermissions.VISIT|BasePermissions.BUILD|BasePermissions.VIEW));
            viewStudio("private-while-source-loaded",BUILDER,"sharp.private",-1);
            if(RANGE && !RANGE_OLD){
                perspective("guest-denied-back",BUILDER,"back","private",false);
                perspective("guest-denied-front",BUILDER,"front","private",false);
            }
            step("outside-observer-returns-to-the-real-source",()->PocketService.recover(player(BUILDER)),()->player(BUILDER).level()==home);
            verify("outside-player-naturally-tracks-source",()->{
                player(BUILDER).connection.teleport(SOURCE.getX()+4.5,81,SOURCE.getZ()+4.5,180,0);
                home.setDefaultSpawnPos(HUB,0);home.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(2,server);
            });
        }
        if(RANGE){rangeSteps();return;}
        sharpView("large-day","clear");
        if(!OLD_PICTURE){
            sharpView("day-open-atmosphere","open-sky");
            verify("deny-air-between-lens-and-the-visible-sky",()->StudioEvidence.denied=SOURCE.above().north());
            sharpView("upper-foreground-denied","shadow-sky");
            verify("clear-foreground-denial",()->StudioEvidence.denied=null);
        }
        verify("replace-stone-with-same-map-color-stone-bricks",()->{
            check(Blocks.STONE.defaultBlockState().getMapColor(home,SOURCE).col==Blocks.STONE_BRICKS.defaultBlockState().getMapColor(home,SOURCE).col,"Map colors differ");
            wallScene(Blocks.STONE_BRICKS);
        });
        sharpView("large-brick","brick");
        if(!OLD_PICTURE){
            verify("place-a-distinct-visible-block",()->home.setBlockAndUpdate(SOURCE.north(5),Blocks.GOLD_BLOCK.defaultBlockState()));
            sharpView("source-cell-clear","gold");
            verify("deny-only-that-source-cell",()->StudioEvidence.denied=SOURCE.north(5));
            sharpView("source-cell-masked","masked");
            verify("restore-matched-subject",()->{StudioEvidence.denied=null;home.setBlockAndUpdate(SOURCE.north(5),Blocks.STONE_BRICKS.defaultBlockState());});
        }
        verify("spawn-visible-vanilla-cow",()->{
            sharpCow=net.minecraft.world.entity.EntityType.COW.create(home);
            sharpCow.setNoAi(true);sharpCow.setNoGravity(true);sharpCow.setPersistenceRequired();
            sharpCow.moveTo(SOURCE.getX()-1.0,81,SOURCE.getZ()-2.7,52,0);
            sharpCow.setYHeadRot(52);sharpCow.yBodyRot=52;
            home.addFreshEntity(sharpCow);
        });
        sharpView("large-cow","cow");
        if(!SOLO){
            verify("outside-player-walks-obliquely-into-view",()->{
                var other=player(BUILDER);
                other.connection.teleport(SOURCE.getX()+1.55,81,SOURCE.getZ()-3.0,-45,0);
                other.setYHeadRot(-45);other.yBodyRot=-45;
            });
            sharpView("large-player","player");
            if(!OLD_PICTURE)sharpView("clear-repeat","player");
            if(!OLD_PICTURE)sharpView("terrain-renderer-comparison","renderer-compare");
            sharpReference("reference-day");
            if(!OLD_PICTURE)sharpView("stock-lens-on-the-indoor-wall","reference-transfer");
            if(!OLD_PICTURE){
                verify("outside-actors-location-becomes-private",()->StudioEvidence.denied=player(BUILDER).blockPosition());
                sharpView("private-outdoor-player","actor-denied");
                verify("clear-the-actor-location-veto",()->StudioEvidence.denied=null);
            }
            verify("restore-oblique-player-yaw",()->{
                var p=player(BUILDER);p.connection.teleport(p.getX(),p.getY(),p.getZ(),-45,0);p.setYHeadRot(-45);p.yBodyRot=-45;
            });
            verify("outside-player-becomes-invisible",()->player(BUILDER).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY,1200,0,false,false)));
            sharpView("large-invisible","invisible");
            sharpReference("reference-invisible");
            verify("restore-outside-visibility",()->player(BUILDER).removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY));
        }
        step("start-rain-at-the-outdoor-lens",()->home.setWeatherParameters(0,24000,true,false),()->home.getRainLevel(1)>.93f);
        sharpView("large-rain","rain");sharpReference("reference-rain");
        if(!OLD_PICTURE){
            sharpView("rain-repeat","rain");
            sharpView("rain-third","rain");
            verify("build-an-opaque-roof-over-the-lens",()->fixtureRoof(true));
            sharpView("rain-under-roof","roof");
            verify("remove-the-fixture-roof",()->fixtureRoof(false));
        }
        step("clear-rain-and-switch-to-midnight",()->{home.setWeatherParameters(24000,0,false,false);home.setDayTime(18000);},()->home.getRainLevel(1)<.03f);
        sharpView("large-night","night");sharpReference("reference-night");
        verify("source-claim-denies-this-viewer",()->StudioEvidence.denied=SOURCE);
        sharpView("claim-denied","unavailable");
        verify("allow-lens-in-daylight-again",()->{StudioEvidence.denied=null;home.setDayTime(6000);});
        sharpView("restored-after-claim","restored");
        if(!SOLO && !OLD_PICTURE){
            step("keep-the-lens-loaded-at-the-outdoor-players-actual-tracking-edge",Scenario::moveToFrontier,()->{
                int elapsed=server.getTickCount()-started;
                if(elapsed<160)return false;
                if(tracked(0) && !tracked(-1))return true;
                if(elapsed%160==0){
                    frontierDistance+=tracked(0)?1:-1;
                    check(frontierDistance>=2&&frontierDistance<=12,"No natural source tracking frontier");
                    moveObserverSouth();
                }
                return false;
            });
            sharpView("unloaded-foreground","edge-sky");
            verify("monitor-did-not-load-the-unobserved-frontier",()->check(tracked(0) && !tracked(-1),"Camera pulled in a missing foreground chunk"));
        }
        step("remove-outdoor-load-cause",()->{
            home.setDefaultSpawnPos(HUB,0);home.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(2,server);
            if(!SOLO)player(BUILDER).connection.teleport(HUB.getX()+3,81,HUB.getZ()+3,0,0);
        },()->home.getChunkSource().getChunkNow(SOURCE.getX()>>4,SOURCE.getZ()>>4)==null);
        sharpView("source-unloaded","unavailable");
        step("watching-does-not-reload-the-empty-source",()->{},()->{
            check(home.getChunkSource().getChunkNow(SOURCE.getX()>>4,SOURCE.getZ()>>4)==null,"Watching forced the source back into memory");
            return server.getTickCount()-started>=50;
        });
    }

    private static void perspective(String name,String role,String view,String expectation,boolean offscreen){
        step(name,()->{
            BlockPos screen=studioWall();
            Vec3 feet=new Vec3(screen.getX()+1.65,65,screen.getZ()-4);
            Vec3 eye=feet.add(0,player(role).getEyeHeight(),0);
            Vec3 direction=Vec3.atCenterOf(screen).add(0,0,.435).subtract(eye);
            boolean backwards=view.equals("front")!=offscreen;
            order(role,"sharp.range."+view+"."+expectation,screen,feet,
                    eye.add(backwards?direction.scale(-1):direction),Items.AIR,false,null,0);
        },()->true);
    }
    private static void marker(int distance,Block block){
        BlockPos center=SOURCE.north(distance);
        for(BlockPos cell:BlockPos.betweenClosed(center.offset(-4,-4,0),center.offset(4,4,0))){
            check(home.getChunkSource().getChunkNow(cell.getX()>>4,cell.getZ()>>4)!=null,"Missing preloaded range marker chunk at "+distance);
            home.setBlockAndUpdate(cell,block.defaultBlockState());
        }
    }
    private static void skygridBlocker(boolean enabled){
        BlockPos center=studioWall().north(2);
        for(BlockPos pos:BlockPos.betweenClosed(center.offset(-3,-2,0),center.offset(3,3,0))){
            if(enabled)check(inside().isEmptyBlock(pos),"Unexpected wall by fixture monitor: "+pos);
            inside().setBlockAndUpdate(pos,(enabled?Blocks.STONE:Blocks.AIR).defaultBlockState());
        }
    }
    private static void horizonView(String label,String condition){
        viewStudio(label,OWNER,"sharp.horizon."+condition,-1);
    }
    private static void horizonPair(String label,String condition,boolean wide){
        horizonView(label,condition);
        sharpReference("stock-"+(wide?"wide-":"standard-")+label);
    }
    private static void changeLens(boolean wide){
        String name=wide?"wide":"standard";
        studioHit("open-controls-for-"+name,OWNER,0,0,Items.AIR,false);
        uiStep("choose-"+name+"-source-lens","lens-"+name);
        uiStep("close-after-"+name+"-lens","close");
        verify("persisted-"+name+"-source-lens",()->{
            var saved=StudioEvidence.tag(home,SOURCE);
            var d=StudioEvidence.device(home,SOURCE);
            var tracking=d.getClass().getDeclaredField("tracking");tracking.setAccessible(true);
            var flags=tracking.get(d);var setting=flags.getClass().getDeclaredField("wideLens");setting.setAccessible(true);
            check(setting.getBoolean(flags)==wide,"Server did not apply owner's lens change");
            if(wide)check(saved.toString().contains("WideLens:1b"),"Missing saved wide lens");
        });
    }
    private static final int[] LANDMARKS={24,48,64,80,96,112};
    private static final double[] SLOPES={-.83,.79,-.46,.46,.03,-.18};
    private static final Block[] MATERIALS={Blocks.RED_CONCRETE,Blocks.GOLD_BLOCK,Blocks.EMERALD_BLOCK,
            Blocks.LAPIS_BLOCK,Blocks.PURPLE_CONCRETE,Blocks.ORANGE_CONCRETE};
    private static void vista(boolean present){
        for(int i=0;i<LANDMARKS.length;i++){
            int distance=LANDMARKS[i];double slope=SLOPES[i];
            int z=(int)Math.round(distance/Math.sqrt(1+slope*slope)),x=(int)Math.round(z*slope);
            int radius=2+i;
            for(int dx=-radius;dx<=radius;dx++)for(int y=-3;y<=radius+6;y++){
                boolean shape=switch(i){
                    case 0->Math.abs(dx)>=radius-1||y>=radius+3;           // Gate
                    case 1->Math.abs(dx)<=radius-Math.max(0,(y-2)/2);    // Stepped pyramid
                    case 2->Math.abs(dx)>=radius/2||y<3;                 // Twin towers
                    case 3->y<=6+dx;                                     // Sloping roof
                    case 4->Math.abs(dx)<=radius/2||y>=radius+2||y<=0;  // Tower with crown and base
                    default->Math.abs(dx)<=1||Math.abs(y-4)<=2;          // Distant cross
                };
                if(shape)setHorizon(SOURCE.offset(x+dx,y,-z),present?MATERIALS[i]:Blocks.AIR);
            }
        }
    }
    private static void setHorizon(BlockPos at,Block block){
        check(home.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4)!=null,"Synthetic scenery outside naturally loaded region: "+at);
        home.setBlockAndUpdate(at,block.defaultBlockState());
    }
    private static void horizonPlate(int distance,boolean present){
        var center=SOURCE.north(distance);
        for(int x=-16;x<=16;x++)for(int y=-10;y<=10;y++){
            boolean gold=Math.abs(x)<=6&&Math.abs(y)<=4||((Math.floorDiv(x,4)+Math.floorDiv(y,4))&1)==0;
            setHorizon(center.offset(x,y,0),present?(gold?Blocks.GOLD_BLOCK:Blocks.LAPIS_BLOCK):Blocks.AIR);
        }
    }
    private static void horizonSteps(){
        if(SOLO){
            step("integrated-room-releases-bootstrap-spawn-after-link",()->{
                home.setDefaultSpawnPos(HUB,0);
                home.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(2,server);
            },()->!tracked(0));
            horizonView("solo-unloaded-source","unavailable");
            viewStudio("solo-stock-source-unavailable",OWNER,"sharp.native.unavailable",-1);
            step("both-solo-views-leave-source-unloaded",()->{},()->{
                check(!tracked(0),"An integrated display loaded the source");
                return server.getTickCount()-started>=60;
            });
            return;
        }
        step("outside-player-naturally-loads-entire-comparison-course",()->{},()->{
            for(int x=-96;x<=96;x+=8)for(int z=-120;z<=0;z+=8){
                var p=SOURCE.offset(x,0,z);if(home.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null)return false;
            }
            return server.getTickCount()-started>=60;
        });
        verify("establish-brick-control",()->wallScene(Blocks.STONE_BRICKS));
        if(!HORIZON_OLD){
            perspective("candidate-perspective-first",OWNER,"first","visible",false);
            perspective("candidate-perspective-back",OWNER,"back","visible",false);
            perspective("candidate-perspective-front",OWNER,"front","visible",false);
            verify("naturally-listed-observer-enters-the-lens",()->{
                var observer=player(BUILDER);observer.connection.teleport(SOURCE.getX()+1.55,81,SOURCE.getZ()-3,-45,0);
                observer.setYHeadRot(-45);observer.yBodyRot=-45;
            });
            perspective("candidate-visible-profile",OWNER,"first","actor",false);
            verify("deny-actor-location-on-this-candidate",()->StudioEvidence.denied=player(BUILDER).blockPosition());
            perspective("candidate-hidden-profile",OWNER,"first","noactor",false);
            verify("restore-source-and-remove-observer-from-image",()->{
                StudioEvidence.denied=null;player(BUILDER).connection.teleport(SOURCE.getX()+4.5,81,SOURCE.getZ()+4.5,180,0);
            });
        }
        horizonPair("standard-brick-before","normal",false);
        verify("same-pose-world-changes-to-gold",()->wallScene(Blocks.GOLD_BLOCK));
        horizonPair("standard-gold-after","near-gold",false);
        verify("clear-near-wall-for-open-horizon",()->{
            for(var p:BlockPos.betweenClosed(SOURCE.north(5).offset(-6,-1,0),SOURCE.north(5).offset(6,2,0)))setHorizon(p,Blocks.AIR);
        });
        horizonPair("open-sky","open",false);
        verify("cover-open-plate-with-real-roof",()->fixtureRoof(true));
        horizonPair("covered-sky","roof",false);
        verify("restore-open-plate",()->fixtureRoof(false));
        verify("visible-claim-crosses-upper-lens-ray",()->StudioEvidence.denied=SOURCE.above().north());
        horizonView("intervening-claim-sky","masked-sky");
        verify("restore-vista-rights",()->StudioEvidence.denied=null);
        verify("place-six-different-distance-landmarks",()->{
            vista(true);
            for(int y=-2;y<=4;y++)setHorizon(SOURCE.north(7).east(8).above(y),Blocks.MAGENTA_CONCRETE);
        });
        horizonPair("vista-standard","vista",false);
        final List<Double> times=new ArrayList<>();
        final List<Map<String,Long>> workCounts=new ArrayList<>();
        step("measure-loaded-authorized-vista-capture-cost",()->{},()->{
            var captureType=Class.forName("dev.anya.storage.pocket.CameraSceneCapture");
            var method=captureType.getDeclaredMethod("capture",
                    ServerPlayer.class,CameraDevices.Device.class,ServerLevel.class,CameraDevices.Device.class,SourceSky.class,net.minecraft.world.entity.Mob.class);
            var atmos=SourceSky.class.getDeclaredMethod("observe",ServerLevel.class,BlockPos.class);
            method.setAccessible(true);atmos.setAccessible(true);
            var cam=StudioEvidence.device(home,SOURCE);var wall=StudioEvidence.device(inside(),studioWall());
            var observer=player(OWNER);
            var diagnostics=HORIZON_OLD?null:captureType.getDeclaredField("diagnostics");
            if(diagnostics!=null){
                diagnostics.setAccessible(true);
                diagnostics.set(null,(java.util.function.Consumer<Object>)work->{
                    var counts=new TreeMap<String,Long>();
                    try{
                        for(var component:work.getClass().getRecordComponents()){
                            var getter=component.getAccessor();getter.setAccessible(true);
                            counts.put(component.getName(),((Number)getter.invoke(work)).longValue());
                        }
                    }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
                    workCounts.add(counts);
                });
            }
            CameraScene packet;double millis;
            try{
                long start=System.nanoTime();
                var sky=atmos.invoke(null,home,SOURCE);
                packet=(CameraScene)method.invoke(null,observer,wall,home,cam,sky,null);
                millis=(System.nanoTime()-start)/1_000_000.0;
            }finally{if(diagnostics!=null)diagnostics.set(null,null);}
            check(packet.cells().length==1024,"Authorized camera capture unexpectedly empty");
            times.add(millis);
            if(times.size()<14)return false;
            Orders.write("server-capture-times.json",Orders.JSON.toJson(times.subList(2,times.size())));
            if(diagnostics!=null){
                check(workCounts.size()==times.size(),"Expected one set of work counters per actual server capture");
                Orders.write("server-capture-counters.json",Orders.JSON.toJson(workCounts.subList(2,workCounts.size())));
            }
            return true;
        });
        if(!HORIZON_OLD){
            HostedNativeStage.steps();
            changeLens(true);
            horizonPair("vista-wide","wide",true);
            changeLens(false);
        }
        verify("clear-landmarks-for-texture-trial",()->{
            vista(false);for(int y=-2;y<=4;y++)setHorizon(SOURCE.north(7).east(8).above(y),Blocks.AIR);
        });
        int previous=0;
        for(int distance:new int[]{24,48,64,80,96,112}){
            int oldDistance=previous;
            verify("place-checkerboard-"+distance,()->{
                if(oldDistance!=0)horizonPlate(oldDistance,false);
                horizonPlate(distance,true);
            });
            horizonPair("checker-"+distance,"plate-"+distance,false);
            if(distance==48){
                verify("blocking-claim-in-middle-of-path",()->StudioEvidence.curtain=SOURCE.north(28));
                horizonView("checker-hidden-behind-claim","masked-plate");
                verify("grant-region-again",()->StudioEvidence.curtain=null);
            }
            previous=distance;
        }
        verify("remove-distant-checker",()->{horizonPlate(112,false);wallScene(Blocks.STONE_BRICKS);});
        step("find-naturally-unloaded-foreground",Scenario::moveToFrontier,()->{
            int elapsed=server.getTickCount()-started;if(elapsed<180)return false;
            if(tracked(0)&&!tracked(-1))return true;
            if(elapsed%160==0){frontierDistance+=tracked(0)?1:-1;check(frontierDistance>=2&&frontierDistance<=16,"No genuine loading frontier");moveObserverSouth();}
            return false;
        });
        horizonView("genuine-unloaded-foreground","edge");
        verify("monitor-left-foreground-unloaded",()->check(tracked(0)&&!tracked(-1),"Camera pulled in foreground"));
        step("remove-outdoor-load-cause",()->player(BUILDER).connection.teleport(-400,-59,-400,0,0),()->!tracked(0));
        horizonView("after-last-observer-left","unavailable");
        step("return-the-only-outdoor-observer",()->player(BUILDER).connection.teleport(SOURCE.getX()+4.5,81,SOURCE.getZ()+4.5,180,0),()->tracked(0));
        horizonView("same-source-restored","normal");
        if(!HORIZON_OLD){
            step("owner-returns-to-hold-source-region-naturally",()->PocketService.recover(player(OWNER)),()->player(OWNER).level()==home);
            verify("owner-stays-outside-and-guest-can-view-storage",()->{
                player(OWNER).connection.teleport(SOURCE.getX()+4.5,81,SOURCE.getZ()+4.5,180,0);
                hub.setMember(player(BUILDER).getUUID(),BasePermissions.VISIT|BasePermissions.BUILD|BasePermissions.VIEW);
            });
            step("guest-enters-for-live-feed-refusal",()->front(BUILDER,DOOR,Items.AIR,false),()->PocketService.inPocket(player(BUILDER).level()));
            perspective("candidate-guest-private-in-front-view",BUILDER,"front","private",false);
            verify("source-was-still-loaded-for-denied-guest",()->check(tracked(0),"Source unavailable during guest rejection"));
        }
    }
    private static void rangeSteps(){
        perspective("perspective-first",OWNER,"first","visible",false);
        perspective("perspective-back",OWNER,"back",RANGE_OLD?"inactive":"visible",false);
        perspective("perspective-front",OWNER,"front",RANGE_OLD?"inactive":"visible",false);
        if(!RANGE_OLD){
            perspective("face-away-from-screen",OWNER,"back","inactive",true);
            perspective("front-camera-facing-away",OWNER,"front","inactive",true);
            verify("put-opaque-wall-between-player-and-monitor",()->skygridBlocker(true));
            perspective("screen-behind-opaque-wall",OWNER,"front","inactive",false);
            verify("remove-opaque-wall",()->skygridBlocker(false));
            perspective("back-view-restored",OWNER,"back","visible",false);
        }
        if(!SOLO){
            verify("outside-player-enters-camera-picture",()->{
                var p=player(BUILDER);
                p.connection.teleport(SOURCE.getX()+1.55,81,SOURCE.getZ()-3,-45,0);
                p.setYHeadRot(-45);p.yBodyRot=-45;
            });
            perspective("visible-skin",OWNER,"first","actor",false);
            sharpReference("stock-skin");
            verify("veto-the-actor-location",()->StudioEvidence.denied=player(BUILDER).blockPosition());
            perspective("denied-actor",OWNER,"first","noactor",false);
            verify("restore-actor-location",()->StudioEvidence.denied=null);
            verify("make-actor-invisible",()->player(BUILDER).addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.INVISIBILITY,1200,0,false,false)));
            perspective("invisible-skin",OWNER,"first","noactor",false);
            sharpReference("stock-invisible");
            verify("remove-invisibility",()->player(BUILDER).removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY));
            verify("load-range-course-from-outdoor-player",()->player(BUILDER).connection.teleport(
                    SOURCE.getX()+6.5,-59,SOURCE.getZ()-32.5,180,0));
        }
        step("all-range-course-chunks-naturally-loaded",()->{},()->{
            for(int z=SOURCE.north(65).getZ()>>4;z<=SOURCE.getZ()>>4;z++)
                if(!tracked(z))return false;
            return true;
        });
        verify("open-unobstructed-view-of-distance-course",()->{
            for(var pos:BlockPos.betweenClosed(SOURCE.north(5).offset(-6,-1,0),SOURCE.north(5).offset(6,2,0)))
                home.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
            // Give both versions the same bounded sightline. The aperture keeps the baseline
            // from spending its independent claim budget on hundreds of empty peripheral rays.
            var aperture=SOURCE.north(6);
            for(int x=-7;x<=7;x++)for(int y=-7;y<=7;y++)if(Math.abs(x)>1||Math.abs(y)>1)
                home.setBlockAndUpdate(aperture.offset(x,y,0),Blocks.SMOOTH_STONE.defaultBlockState());
            home.setBlockAndUpdate(SOURCE.north(4).east(3),Blocks.BOOKSHELF.defaultBlockState());
        });
        int previous=0;
        for(int distance:new int[]{24,25,32,48,64,65}){
            final int last=previous;
            verify("place-range-panel-at-"+distance,()->{
                if(last!=0)marker(last,Blocks.AIR);
                marker(distance,Blocks.GOLD_BLOCK);
            });
            perspective("range-"+distance,OWNER,"first",(distance<=(RANGE_OLD?24:64)?"seen-":"beyond-")+distance,false);
            if(distance==48&&!RANGE_OLD){
                verify("deny-only-the-intervening-volume",()->StudioEvidence.curtain=SOURCE.north(28));
                perspective("range-48-hidden-by-claim",OWNER,"back","masked-48",false);
                verify("clear-intervening-claim",()->StudioEvidence.curtain=null);
            }
            previous=distance;
        }
        verify("remove-final-range-panel",()->{
            marker(65,Blocks.AIR);
            var aperture=SOURCE.north(6);
            for(int x=-7;x<=7;x++)for(int y=-7;y<=7;y++)if(Math.abs(x)>1||Math.abs(y)>1)
                home.setBlockAndUpdate(aperture.offset(x,y,0),Blocks.AIR.defaultBlockState());
            wallScene(Blocks.STONE_BRICKS);
        });
        if(!RANGE_OLD){
            verify("veto-the-source-camera",()->StudioEvidence.denied=SOURCE);
            perspective("source-revoked-in-back-view",OWNER,"back","denied",false);
            verify("restore-the-source-camera",()->StudioEvidence.denied=null);
            perspective("source-restored-in-front-view",OWNER,"front","visible",false);
            if(!SOLO){
                verify("return-outdoor-player-to-lens",()->player(BUILDER).connection.teleport(SOURCE.getX()+3.5,81,SOURCE.getZ()+2.5,180,0));
                step("let-outdoor-tracking-settle",()->{},()->server.getTickCount()-started>=160);
                step("find-a-naturally-unloaded-foreground-chunk",Scenario::moveToFrontier,()->{
                    int elapsed=server.getTickCount()-started;
                    if(elapsed<160)return false;
                    if(tracked(0)&&!tracked(-1))return true;
                    if(elapsed%160==0){frontierDistance+=tracked(0)?1:-1;
                        check(frontierDistance>=2&&frontierDistance<=12,"No natural frontier");moveObserverSouth();}
                    return false;
                });
                perspective("foreground-unloaded-in-third-person",OWNER,"back","edge",false);
                verify("viewing-keeps-the-foreground-unloaded",()->check(tracked(0)&&!tracked(-1),"Watched chunk was loaded"));
            }
            step("remove-outdoor-load-cause",()->{
                home.setDefaultSpawnPos(HUB,0);home.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(2,server);
                if(!SOLO)player(BUILDER).connection.teleport(HUB.getX()+3,81,HUB.getZ()+3,0,0);
            },()->!tracked(0));
            perspective("source-unloaded-in-front-view",OWNER,"front","denied",false);
            step("viewing-does-not-reload-source",()->{},()->{
                check(!tracked(0),"Watching pulled the source back into memory");
                return server.getTickCount()-started>=50;
            });
        }
        if(!SOLO){
            var key=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse("roomroles:open_sky"));
            step("load-custom-open-sky-with-outdoor-player",()->{
                var level=Objects.requireNonNull(server.getLevel(key));
                player(BUILDER).teleportTo(level,.5,-63,.5,0,0);
            },()->server.getLevel(key).getChunkSource().getChunkNow(0,0)!=null);
            verify("custom-dimension-atmosphere",()->{
                var level=server.getLevel(key);
                var method=SourceSky.class.getDeclaredMethod("observe",ServerLevel.class,BlockPos.class);
                method.setAccessible(true);
                var sky=(SourceSky)method.invoke(null,level,new BlockPos(0,80,0));
                check((sky!=null)==!RANGE_OLD,"Incorrect support for custom dimension's Overworld sky");
                Orders.write("custom-atmosphere.ok",level.dimension().location()+" effects="+level.dimensionType().effectsLocation()+
                        " sampled="+(sky!=null)+(sky==null?"":" background="+sky.background()+" phase="+sky.phase()));
            });
        }
    }

    private static void studioOutside(){
        verify("slim-camera-six-attachments-selection-collision-and-source",()->{
            equip(player(OWNER),Items.AIR);MountProbe.sixFaces(player(OWNER));
            home.setBlockAndUpdate(CAMERA.south(),Blocks.SMOOTH_STONE.defaultBlockState());StudioEvidence.scenery(home);
        });
        step("place-dedicated-upward-sky-camera",()->floorUse(OWNER,UP,CameraDevices.SKY_CAMERA.get().asItem()),()->home.getBlockState(UP).is(CameraDevices.SKY_CAMERA.get()));
        step("mount-slim-camera",()->northUse(CAMERA.south(),CameraDevices.CAMERA.get().asItem(),false),()->{
            StudioEvidence.cameraId=StudioEvidence.tag(home,CAMERA).getUUID("Identity");return true;
        });
        step("skinny-wall-rails",()->order(OWNER,"photo",CAMERA,new Vec3(-.65,81,-1.1),Vec3.atCenterOf(CAMERA),Items.AIR,false,null,0),()->true);
        step("select-camera-on-tablet",()->northUse(CAMERA,Content.CUPID_TABLET.get(),true),()->true);
    }
    static BlockPos studioWall(){return roomPos(10,67,15);}
    private static void uiStep(String name,String action){
        step(name,()->{
            var p=player(OWNER);
            var cmd=new Orders.Order(current,"ui."+action,p.level().dimension().location().toString(),0,"",false,"",0,0,0,0);
            pending.put(OWNER,cmd);Orders.write(OWNER+".json",Orders.JSON.toJson(cmd));
        },()->true);
    }
    private static void viewStudio(String name,String role,String action,int color){
        step(name,()->{
            var pos=studioWall();var feet=new Vec3(pos.getX()+.5+(role.equals(BUILDER)?1.2:0),65,pos.getZ()-3);
            order(role,action,pos,feet,Vec3.atCenterOf(pos).add(0,0,.435),Items.AIR,false,null,color);
        },()->true);
    }
    private static void studioInside(){
        // Resolve the room-relative coordinates at execution, after the actual player entries.
        step("build-disposable-interior-backing-wall",()->{
            var wall=studioWall();StudioEvidence.screen=wall;
            for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)
                inside().setBlockAndUpdate(wall.south().offset(x,y,0),PocketContent.PANEL.get().defaultBlockState());
        },()->true);
        studioHit("mount-flush-monitor",OWNER,0,1,CameraDevices.WALL.get().asItem(),false);
        studioHit("link-flat-monitor",OWNER,0,0,Content.CUPID_TABLET.get(),true);
        viewStudio("one-block-screen",OWNER,"anyview",-1);
        step("screen-stays-on-beside-crosshair",()->{
            var pos=studioWall();var feet=new Vec3(pos.getX()+.5,65,pos.getZ()-3);
            order(OWNER,"peripheral",pos,feet,Vec3.atCenterOf(pos).add(.95,0,.435),Items.AIR,false,null,-1);
        },()->true);
        studioHit("open-monitor-settings",OWNER,0,0,Items.AIR,false);
        uiStep("widen-to-two","width");uiStep("widen-to-three","width");
        uiStep("raise-to-two","height");uiStep("raise-to-three","height");
        uiStep("watch-only-chosen-types","group");uiStep("watch-creepers","watch");uiStep("ignore-cows","ignore");
        uiStep("photograph-monitor-controls","photo");uiStep("return-to-game","close");
        verify("persist-settings-without-rebinding",()->{
            StudioEvidence.settings(home);check(StudioEvidence.property(inside(),studioWall(),"width")==3
                    &&StudioEvidence.property(inside(),studioWall(),"height")==3,"Actual wall geometry");
        });
        viewStudio("nine-block-screen",OWNER,"anyview",-1);
        viewStudio("private-guest-screen",BUILDER,"private",0);
        studioHit("right-click-outer-panel",OWNER,1,1,Items.AIR,false);
        uiStep("close-outer-panel-settings","close");
        studioHit("guest-clicks-outer-panel",BUILDER,1,1,Items.AIR,false);
        viewStudio("guest-cannot-open-controls-or-view",BUILDER,"private",0);
        step("observer-returns-outdoors-to-keep-source-naturally-ticking",()->PocketService.recover(player(BUILDER)),()->player(BUILDER).level()==home);
        verify("source-simulation-has-a-nearby-outdoor-player",()->player(BUILDER).connection.teleport(11.5,81,5.5,0,0));
        motionSteps();studioCeilingAndWall();
        verify("save-links-rules-and-size",()->StudioEvidence.checkpoint(server,inside(),studioWall()));
    }
    private static void studioHit(String name,String role,int dx,int dz,Item item,boolean sneak){
        step(name,()->{
            var target=studioWall().offset(dx,0,dz);var feet=new Vec3(target.getX()+.5,65,target.getZ()-2.35);
            order(role,"use",target,feet,Vec3.atCenterOf(target).add(0,0,dz==0?.435:0),item,sneak,NORTH,0);
        },()->true);
    }
    private static void motionSteps(){
        step("stationary-creeper-does-not-trigger",()->StudioEvidence.spawn(home),()->{
            check(StudioEvidence.tracked(home)==null,"Stationary triggered");return server.getTickCount()-started>38;
        });
        step("ignored-cow-and-unlisted-hostile-do-not-trigger",()->{},()->{
            if((server.getTickCount()-started)%8==0){StudioEvidence.move(StudioEvidence.cow);StudioEvidence.move(StudioEvidence.skeleton);}
            check(StudioEvidence.tracked(home)==null,"Filtered mob triggered");return server.getTickCount()-started>50;
        });
        step("blocked-claim-does-not-trigger",()->StudioEvidence.denied=CAMERA,()->{
            if((server.getTickCount()-started)%8==0)StudioEvidence.move(StudioEvidence.creeper);
            check(StudioEvidence.tracked(home)==null,"Motion disclosed through denied claim");return server.getTickCount()-started>40;
        });
        step("occluded-creeper-not-detected",()->{
            StudioEvidence.denied=null;
            for(int z=-3;z<=0;z++)for(int y=81;y<=83;y++)home.setBlockAndUpdate(new BlockPos(2,y,z),Blocks.STONE.defaultBlockState());
        },()->{
            if((server.getTickCount()-started)%8==0)StudioEvidence.move(StudioEvidence.creeper);
            check(StudioEvidence.tracked(home)==null,"Detected mob behind wall");return server.getTickCount()-started>45;
        });
        step("visible-motion-swivels-to-watched-creeper",()->{
            for(int z=-3;z<=0;z++)for(int y=81;y<=83;y++)home.removeBlock(new BlockPos(2,y,z),false);
        },()->{
            if((server.getTickCount()-started)%8==0)StudioEvidence.move(StudioEvidence.creeper);
            if((server.getTickCount()-started)%60==0)writeLog(StudioEvidence.motionStatus(home,player(OWNER)));
            return StudioEvidence.creeper.getUUID().equals(StudioEvidence.tracked(home))&&StudioEvidence.property(home,CAMERA,"pan")==1;
        });
        viewStudio("motion-on-indoor-monitor",OWNER,"motion",0xd4b9db);
        step("motion-ends-and-camera-returns-to-rest",()->{},()->StudioEvidence.tracked(home)==null&&StudioEvidence.property(home,CAMERA,"pan")==0);
        studioHit("open-size-controls-for-obstruction",OWNER,0,0,Items.AIR,false);
        uiStep("narrow-screen","width");
        verify("put-a-block-in-next-column",()->inside().setBlockAndUpdate(studioWall().west(),Blocks.AMETHYST_BLOCK.defaultBlockState()));
        uiStep("reject-expansion-over-build","blocked");
        verify("obstacle-kept-and-size-unchanged",()->{
            check(StudioEvidence.property(inside(),studioWall(),"width")==1&&inside().getBlockState(studioWall().west()).is(Blocks.AMETHYST_BLOCK),"Resize overwrote build");
            inside().removeBlock(studioWall().west(),false);
        });
        uiStep("restore-two-wide","width");uiStep("restore-three-wide","width");uiStep("finish-controls","close");
    }

    private static BlockPos cinema(){return roomPos(0,67,7);}
    private static void studioCeilingAndWall(){
        step("mount-cinema-on-inner-room-wall",()->{
            var a=cinema();order(OWNER,"use",a.west(),Vec3.atBottomCenterOf(a).add(2,-2,0),
                    Vec3.atCenterOf(a.west()).add(.49,0,0),CameraDevices.CINEMA.get().asItem(),false,Direction.EAST,0);
        },()->inside().getBlockState(cinema()).is(CameraDevices.CINEMA.get()));
        step("link-cinema",()->{
            var a=cinema();order(OWNER,"use",a,Vec3.atBottomCenterOf(a).add(2,-2,0),Vec3.atCenterOf(a).add(-.46,0,0),
                    Content.CUPID_TABLET.get(),true,Direction.EAST,0);
        },()->StudioEvidence.tag(inside(),cinema()).hasUUID("Camera"));
        step("open-cinema-settings",()->{
            var a=cinema();order(OWNER,"use",a,Vec3.atBottomCenterOf(a).add(2,-2,0),Vec3.atCenterOf(a).add(-.46,0,0),Items.AIR,false,Direction.EAST,0);
        },()->true);
        uiStep("fit-cinema-to-whole-wall","fit");
        verify("whole-room-wall-kept-shell-and-anchor",()->{
            check(StudioEvidence.property(inside(),cinema(),"width")==32&&StudioEvidence.property(inside(),cinema(),"height")==32,
                    "Expected 32 by 32, actually "+StudioEvidence.tag(inside(),cinema()).getCompound("Panel"));
            check(!inside().isEmptyBlock(cinema().west())&&inside().isEmptyBlock(cinema().above(15)),"Fit changed real blocks");
        });
        uiStep("close-cinema-controls","close");
        step("cinema-wall-live",()->{
            var a=cinema();var feet=Vec3.atBottomCenterOf(roomPos(20,65,8));
            order(OWNER,"cinema",a,feet,Vec3.atCenterOf(roomPos(0,75,8)),Items.AIR,false,null,-1);
        },()->true);
        step("raise-tablet-inside-skygrid",()->{
            var a=roomPos(18,65,13);order(OWNER,"tablet",a,Vec3.atBottomCenterOf(a),Vec3.atCenterOf(a).add(0,3,-4),
                    Content.CUPID_TABLET.get(),false,null,-1);
        },()->player(OWNER).containerMenu instanceof StoreMenu);
        uiStep("open-skygrid-cosmetics","cosmetics");uiStep("turn-on-live-ceiling","ceiling");
        uiStep("photograph-skygrid-tablet","photo");uiStep("lower-tablet","close");
        verify("daylight-outdoor-sky",()->{home.setDayTime(6000);home.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);});
        ceiling("ceiling-without-flyover","clear",0x4000000);
        verify("second-player-flies-over-upward-lens",()->{
            var other=player(BUILDER);other.getAbilities().mayfly=true;other.getAbilities().flying=true;other.onUpdateAbilities();
            other.connection.teleport(UP.getX()+.5,UP.getY()+3.2,UP.getZ()+.5,0,0);
        });
        ceiling("ceiling-player-overhead","fly",-1);
        verify("flyer-goes-invisible",()->player(BUILDER).addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY,1200,0,false,false)));
        ceiling("ceiling-invisible-flyer-masked","clear",0x4000000);
        verify("flyer-is-visible-again",()->player(BUILDER).removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY));
        verify("flyer-moves-off-camera",()->player(BUILDER).connection.teleport(UP.getX()+12,UP.getY()+3.2,UP.getZ()+.5,0,0));
        ceiling("ceiling-after-flyover","clear",0x4000000);
        verify("occlude-outdoor-lens",()->home.setBlockAndUpdate(UP.above(),Blocks.GOLD_BLOCK.defaultBlockState()));
        ceiling("ceiling-source-roof","clear",MapColor.GOLD.col);
        verify("claim-denies-current-sky-camera",()->StudioEvidence.denied=UP);
        ceiling("ceiling-camera-unavailable","unavailable",-1);
        verify("restore-source-claim",()->StudioEvidence.denied=null);
        verify("switch-to-outdoor-midnight",()->{home.removeBlock(UP.above(),false);home.setDayTime(18000);});
        ceiling("ceiling-night","clear",0x5000000);
    }
    private static void ceiling(String name,String action,int color){
        step(name,()->{
            var feet=Vec3.atBottomCenterOf(roomPos(16,65,14));
            order(OWNER,"sky."+action,roomPos(16,97,14),feet,feet.add(0,32,.015),Items.AIR,false,null,color);
        },()->true);
    }

    private static void outsideCameraSteps(){
        verify("six-attachment-faces-selection-collision-legacy-facing-and-lens-rays",()->{
            equip(player(OWNER),Items.AIR);
            MountProbe.sixFaces(player(OWNER));
            check(player(OWNER).getInventory().getItem(1).is(Content.CUPID_TABLET.get()),"Mount probe displaced the bound tablet");
            home.setBlockAndUpdate(CAMERA.south(),Blocks.SMOOTH_STONE.defaultBlockState());
            home.setBlockAndUpdate(WALL.south(),Blocks.SMOOTH_STONE.defaultBlockState());
        });
        step("mount-outdoor-camera-on-wall",()->northUse(CAMERA.south(),CameraDevices.CAMERA.get().asItem(),false),()->{
            var d=(CameraDevices.Device)home.getBlockEntity(CAMERA);
            check(d!=null && d.owned(player(OWNER)),"Camera owner");
            cameraId=d.saveWithoutMetadata(home.registryAccess()).getUUID("Identity");return true;
        });
        step("photograph-wall-bracket-housing-and-lens",()->order(OWNER,"photo",CAMERA,new Vec3(-.65,81,-1.1),
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


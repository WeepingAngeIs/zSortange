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
    private static final boolean STUDIO="studio".equals(System.getProperty("roomroles.trial"));
    private static final boolean BASELINE="baseline".equals(System.getProperty("roomroles.trial"));
    private static final BlockPos HUB=new BlockPos(7,81,-5), DOOR=HUB.east(2);
    private static final BlockPos CAMERA=new BlockPos(0,82,0), WALL=new BlockPos(6,82,5), UP=new BlockPos(-6,81,0);
    private static final Direction NORTH=Direction.NORTH, SOUTH=Direction.SOUTH;
    private static final List<String> ROLES=BASELINE||STUDIO?List.of(OWNER,BUILDER):List.of(OWNER,BUILDER,GUEST);
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
        if(now-started>600)throw new AssertionError("Timed out at "+current+" pending="+pending.keySet()+" received="+receivedClicks);
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
        var options=ExpansionRules.get();options.pocketStorage=true;options.pocketEditing=true;
        home.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        home.setDayTime(6000);home.setWeatherParameters(24000,0,false,false);
        for(var cell:BlockPos.betweenClosed(-14,80,-12,16,80,16))home.setBlockAndUpdate(cell,Blocks.SMOOTH_STONE.defaultBlockState());
        home.setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());
        home.setBlockAndUpdate(DOOR,PocketContent.ENTRANCE.get().defaultBlockState());
        var owner=player(OWNER);hub=(StorageEntity)home.getBlockEntity(HUB);hub.owner=owner.getUUID();
        ((PocketContent.EntranceEntity)home.getBlockEntity(DOOR)).owner=owner.getUUID();
        hub.setMember(player(BUILDER).getUUID(),BasePermissions.VISIT|BasePermissions.BUILD);
        if(!BASELINE&&!STUDIO)hub.setMember(player(GUEST).getUUID(),BasePermissions.VISIT);
        int i=0;
        for(String name:ROLES){
            var p=player(name);
            check(!p.hasPermissions(2) && p.gameMode.getGameModeForPlayer()==GameType.SURVIVAL,"Expected ordinary Survival "+name);
            p.teleportTo(home,11.5+i,81,8.5,0,0);i++;
        }
        owner.getInventory().setItem(1,new ItemStack(Content.CUPID_TABLET.get()));
        frontUse("bind-tablet-to-hub",HUB,Content.CUPID_TABLET.get(),true);
        frontUse("bind-room-cube",DOOR,Content.CUPID_TABLET.get(),true);
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
    private static BlockPos studioWall(){return roomPos(10,67,15);}
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


package dev.anya.storage.acceptance;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import xaero.pac.common.server.api.OpenPACServerAPI;

/** Test fixture only. All entitlement and network delivery decisions belong to the packaged mod. */
@EventBusSubscriber(modid=Acceptance.ID)
public final class PadPreviewProbe {
    static final BlockPos ENTRANCE = new BlockPos(8,81,1);
    private static final BlockPos HUB = new BlockPos(4,81,1);
    private static final int STARTER = 15, EDITED = 143;
    private static final StringBuilder results = new StringBuilder();
    private static final Deque<Step> steps = new ArrayDeque<>();
    private interface Condition { boolean ready() throws Exception; }
    private interface Action { void run() throws Exception; }
    private record Step(String name, Condition condition) {}
    private static MinecraftServer server;
    private static ServerPlayer owner, guest;
    private static StorageEntity hub;
    private static PocketLedger.Pocket room;
    private static ItemStack remote;
    private static boolean running, cold, previewOnly;
    private static BlockPos deniedEmptyPad;
    private static int blockedEmptyChecks;
    private static int stepStart;
    static boolean active(){return "pad-preview".equals(System.getenv("ANYA_ACCEPTANCE_SCENARIO"));}
    private static void check(boolean ok,String message){Acceptance.require(ok,message);}
    private static void waitFor(String name,Condition condition){steps.add(new Step(name,condition));}
    private static void act(String name,Action action){waitFor(name,()->{action.run();return true;});}
    private static ServerLevel target(){return PocketService.dimension(server);}
    private static PocketContent.EntranceEntity entrance(){return (PocketContent.EntranceEntity)server.overworld().getBlockEntity(ENTRANCE);}
    private static BlockPos extraPad(){return PocketRoomLayout.pads(room).get(3);}

    @SubscribeEvent public static void start(ServerStartedEvent event) throws Exception {
        if(!active())return;
        server=event.getServer();
        check("true".equals(System.getenv("GITHUB_ACTIONS"))&&Boolean.getBoolean("anya.acceptance"),"Hosted disposable fixture required");
        check(!Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Expected a fresh world");
        Acceptance.verifyJar("pad-server");
        ExpansionRules.get().pocketStorage=true;
        check(PocketService.enabled(),"This scenario opts into real pocket storage");
        var home=server.overworld();
        for(int x=0;x<=16;x++)for(int z=-5;z<=8;z++)
            home.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.SMOOTH_STONE.defaultBlockState());
        home.setBlockAndUpdate(HUB,Content.HUB.get().defaultBlockState());
        home.setBlockAndUpdate(ENTRANCE,PocketContent.ENTRANCE.get().defaultBlockState());
        home.setDefaultSpawnPos(new BlockPos(8,81,5),180);
        home.setDayTime(6000);
        server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        plan();stepStart=server.getTickCount();running=true;
        Acceptance.marker("server-ready","Two-player loaded-only pad preview");
    }

    private static void plan(){
        waitFor("two connected non-operator viewers",()->{
            owner=server.getPlayerList().getPlayerByName("AnyaQAAlpha");
            guest=server.getPlayerList().getPlayerByName("AnyaQABeta");
            if(owner==null||guest==null)return false;
            check(!owner.hasPermissions(2)&&!guest.hasPermissions(2),"Expected non-operators");
            return true;
        });
        act("link an unprepared room",()->{
            pose(owner,"south");pose(guest,"west");
            hub=(StorageEntity)server.overworld().getBlockEntity(HUB);
            hub.owner=owner.getUUID();entrance().owner=hub.owner;
            hub.setMember(guest.getUUID(),BasePermissions.VISIT);
            remote=new ItemStack(Content.REMOTE.get());
            var tag=BagInventory.data(remote);tag.putString("HubDimension","minecraft:overworld");tag.putLong("HubPos",HUB.asLong());BagInventory.data(remote,tag);
            PocketService.link(owner,entrance(),remote);
            room=PocketService.ledger(server).get(entrance().pocket);
            check(room!=null&&room.stage()==PocketLedger.Stage.RESERVED,"Link must reserve without preparation");
            cold=true;previewOnly=true;
        });
        frame(true,"public-south",-1,"south","public");
        frame(true,"public-east",-1,"east","public");
        frame(false,"visit-only-cold",-1,"west","public");
        act("prepare deliberately using the owner's ordinary pocket remote",()->{
            check(PocketService.ledger(server).get(room.id()).stage()==PocketLedger.Stage.RESERVED,"Focusing prepared a room");
            cold=false;previewOnly=false;
            check(PocketService.remote(owner,remote,hub),"Failed to start explicit room preparation");
        });
        waitFor("remote has finished preparing actual room",()->owner.containerMenu instanceof StoreMenu);
        act("pin the warm fixture separately and close its remote",()->{
            room=PocketService.ledger(server).get(room.id());check(room.stage()==PocketLedger.Stage.READY,"Room must be ready");
            pin(true);owner.closeContainer();
        });
        waitFor("no active storage leases or owned tickets",()->{
            if(PocketService.ownedTicketRequests()!=0)return false;
            previewOnly=true;return true;
        });
        frame(true,"base-east",STARTER,"east","image");
        frame(true,"base-south",STARTER,"south","image");
        frame(false,"visit-only-warm",-1,"west","public");
        act("place and seed a registered optional chest on pad eight",()->{
            var pad=extraPad();check(target().isEmptyBlock(pad),"Optional pad should be empty");
            target().setBlockAndUpdate(pad,Content.CRATE.get().defaultBlockState());
            var chest=(StorageEntity)target().getBlockEntity(pad);chest.owner=room.owner();chest.pocketId=room.id();
            var privateItem=new ItemStack(Items.EMERALD,11);
            privateItem.set(DataComponents.CUSTOM_NAME,Component.literal("Private pad sample"));
            chest.setItem(0,privateItem);chest.storageIdentity();chest.setChanged();
        });
        frame(true,"layout-south",EDITED,"south","image");
        frame(true,"layout-east",EDITED,"east","image");
        act("grant guest View only",()->hub.setMember(guest.getUUID(),BasePermissions.VIEW));
        frame(false,"guest-granted",EDITED,"west","remember");
        act("remove guest View while focused",()->hub.removeMember(guest.getUUID()));
        frame(false,"guest-revoked",-1,"west","clear");
        frame(true,"owner-still-authorized",EDITED,"east","state");
        frame(false,"guest-turn-away",-1,"west","away");
        frame(false,"old-reply-after-refocus",-1,"west","replay");
        act("restore guest View",()->hub.setMember(guest.getUUID(),BasePermissions.VIEW));
        frame(false,"restored-guest",EDITED,"west","remember");
        frame(false,"missed-refresh-expiry",-1,"west","expiry");
        frame(false,"fresh-after-expiry",EDITED,"west","remember");
        act("deny only the guest on one empty optional position",()->{
            deniedEmptyPad=PocketRoomLayout.pads(room).get(1);
            check(target().isEmptyBlock(deniedEmptyPad),"The single-position claim must cover an empty pad");
        });
        frame(false,"guest-empty-pad-clear",-1,"west","clear");
        frame(true,"owner-empty-pad-allowed",EDITED,"east","state");
        act("restore guest access to the empty position",()->{
            check(blockedEmptyChecks>0,"No real permission check visited the empty pad");deniedEmptyPad=null;
        });
        frame(false,"guest-empty-pad-restored",EDITED,"west","remember");
        act("real OPAC foreign claim on the remote pad",()->claim(true));
        frame(false,"guest-claim-clear",-1,"west","clear");
        frame(true,"owner-claim-clear",-1,"east","state");
        act("unclaim the sampled pocket chunk",()->claim(false));
        frame(true,"owner-unclaimed",EDITED,"east","state");
        frame(false,"guest-unclaimed",EDITED,"west","remember");
        act("disconnect guest while displaying the layout",()->Acceptance.marker("kill-beta","Disconnect focused guest"));
        waitFor("guest socket closed",()->server.getPlayerList().getPlayer(guest.getUUID())==null);
        act("revoke before reconnect",()->{hub.removeMember(guest.getUUID());Acceptance.marker("restart-beta","Reconnect denied viewer");});
        waitFor("same guest UUID on a new connection",()->{
            var next=server.getPlayerList().getPlayer(guest.getUUID());if(next==null||next==guest)return false;
            guest=next;pose(guest,"west");return true;
        });
        frame(false,"reconnected-denied",-1,"west","public");
        act("replace the bound entrance with an unbound block",()->{
            server.overworld().removeBlock(ENTRANCE,false);
            server.overworld().setBlockAndUpdate(ENTRANCE,PocketContent.ENTRANCE.get().defaultBlockState());
            check(entrance().pocket==null,"Replacement inherited a pocket ID");
        });
        frame(true,"replacement-fallback",-1,"east","state");
        act("owner reanchors existing room for the unload check",()->{
            entrance().owner=owner.getUUID();PocketService.reanchor(owner,room.id(),entrance(),remote);
            check(room.id().equals(entrance().pocket),"Unable to reanchor");
        });
        frame(true,"reanchored-layout",EDITED,"east","state");
        act("verify inventory conservation and release fixture warming",()->{
            var sample=((StorageEntity)target().getBlockEntity(extraPad())).getItem(0);
            check(sample.is(Items.EMERALD)&&sample.getCount()==11&&sample.getHoverName().getString().equals("Private pad sample"),"Viewing changed chest contents");
            pin(false);
        });
        waitFor("all destination chunks naturally unload while focused",()->{
            if(!unloaded())return false;
            cold=true;return true;
        });
        frame(true,"cold-ready-fallback",-1,"east","state");
        waitFor("continuous focus on cold READY room creates no chunks",()->server.getTickCount()-stepStart>=80);
    }

    private static void frame(boolean alpha,String name,int mask,String side,String mode){
        act("request "+name,()->{
            ServerPlayer p=alpha?owner:guest;
            if(!mode.equals("away"))pose(p,side);
            ServerProbe.send(p,"pad "+name+" "+mask+" "+side+" "+mode);
        });
        waitFor("client confirmed "+name,()->ServerProbe.done(alpha?owner:guest));
    }

    private static void pose(ServerPlayer p,String side){
        // Both observers stay on distinct faces and outside each other's sight line.
        Vec3 pos=switch(side){case "south"->new Vec3(8.0,81,4.5);case "east"->new Vec3(11.45,81,2.40);default->new Vec3(5.45,81,.9);};
        if(p.position().distanceToSqr(pos)<.002)return;
        Vec3 aim=Vec3.atCenterOf(ENTRANCE).subtract(pos.add(0,p.getEyeHeight(),0));
        float yaw=(float)Math.toDegrees(Math.atan2(-aim.x,aim.z));
        float pitch=(float)-Math.toDegrees(Math.atan2(aim.y,aim.horizontalDistance()));
        p.teleportTo(server.overworld(),pos.x,pos.y,pos.z,yaw,pitch);
    }
    private static void pin(boolean forced){
        for(int x=room.x()>>4;x<=(room.x()+31)>>4;x++)for(int z=room.z()>>4;z<=(room.z()+31)>>4;z++)
            check(target().setChunkForced(x,z,forced),"Fixture warming state did not change");
    }
    private static boolean unloaded(){
        for(int x=(room.x()-1)>>4;x<=(room.x()+32)>>4;x++)for(int z=(room.z()-1)>>4;z<=(room.z()+32)>>4;z++)
            if(target().getChunkSource().getChunkNow(x,z)!=null)return false;
        return true;
    }
    private static void claim(boolean foreign){
        var pad=extraPad();var dim=target().dimension().location();int x=pad.getX()>>4,z=pad.getZ()>>4;
        var claims=OpenPACServerAPI.get(server).getServerClaimsManager();
        if(foreign)claims.claim(dim,UUID.fromString("f88121bc-81b6-40af-a19e-900000000011"),0,x,z,false);
        else claims.unclaim(dim,x,z);
        check(Safety.locationReason(guest,target(),pad).isEmpty()!=foreign,"Real OPAC claim did not change target access");
        check(Safety.locationReason(guest,server.overworld(),ENTRANCE).isEmpty(),"Claim affected the home dimension");
    }
    @SubscribeEvent public static void exactEmptyPad(StorageAccessEvent event){
        if(deniedEmptyPad!=null&&event.player==guest&&event.level==target()&&deniedEmptyPad.equals(event.position)){
            blockedEmptyChecks++;event.setCanceled(true);
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        if(!running||event.getServer()!=server)return;
        try{
            if(cold)check(unloaded(),"Focused preview loaded the cold destination");
            if(previewOnly){
                check(PocketService.ownedTicketRequests()==0&&PocketService.lease(owner.getUUID())==null&&PocketService.lease(guest.getUUID())==null,"Preview acquired a storage lease or ticket");
                check(owner.level()==server.overworld()&&guest.level()==server.overworld(),"Preview moved a viewer");
            }
            var step=steps.peek();
            if(step==null){
                Acceptance.marker("PASSED",results.toString());
                running=false;server.saveEverything(true,true,true);server.halt(false);return;
            }
            check(server.getTickCount()-stepStart<3600,"Timed out at "+step.name());
            if(step.condition().ready()){
                results.append(step.name()).append('\n');
                dev.anya.storage.AnyaStorage.LOG.info("ANYA_PAD_ASSERT {}",step.name());
                steps.remove();stepStart=server.getTickCount();
            }
        }catch(Throwable e){
            running=false;Acceptance.marker("FAILED-pad-server",steps.peek()+" "+e);
            dev.anya.storage.AnyaStorage.LOG.error("Pad preview fixture failed",e);server.halt(false);
        }
    }
}

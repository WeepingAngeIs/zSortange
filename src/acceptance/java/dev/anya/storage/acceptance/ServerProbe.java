package dev.anya.storage.acceptance;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Test-only fixture orchestrator. Real client actions use ordinary item/menu/movement packets. */
@EventBusSubscriber(modid=Acceptance.ID)
public final class ServerProbe {
    static MinecraftServer server;
    static StorageEntity hub;
    static PocketContent.EntranceEntity entrance;
    static UUID pocket;
    static BlockPos hp=new BlockPos(1,81,1),ep=new BlockPos(3,81,1);
    static final Map<UUID,Integer> acks=new HashMap<>();
    static int phase,tick,since,seq;
    static boolean running;
    static ServerPlayer a(){return server.getPlayerList().getPlayerByName("AnyaQAAlpha");}
    static ServerPlayer b(){return server.getPlayerList().getPlayerByName("AnyaQABeta");}
    static void check(boolean v,String m){Acceptance.require(v,m);}
    static void log(String m){AnyaStorage.LOG.info("ANYA_ACCEPTANCE_ASSERT {}",m);}
    public static void ack(ServerPlayer p,Wire.Signal s){if(RemoteTicketProbe.active()){RemoteTicketProbe.ack(p,s);return;}if(MultiPocketProbe.active()){MultiPocketProbe.ack(p,s);return;}if(RoomProbe.active()){RoomProbe.ack(p,s);return;}if(s.action().equals("done")&&s.sequence()==seq)acks.put(p.getUUID(),seq);}
    static void send(ServerPlayer p,String s){seq++;acks.clear();PacketDistributor.sendToPlayer(p,new Wire.Signal(seq,s));since=tick;}
    static boolean done(ServerPlayer p){return p!=null&&Objects.equals(acks.get(p.getUUID()),seq);}
    static void next(){phase++;since=tick;}
    static PocketLedger.Pocket record()throws Exception{return PocketService.ledger(server).get(pocket);}
    static List<StorageEntity> chests()throws Exception {List<StorageEntity> out=new ArrayList<>();for(BlockPos p:PocketService.chestPositions(record()))out.add((StorageEntity)PocketService.dimension(server).getBlockEntity(p));return out;}
    static int count(Item item)throws Exception{int n=0;for(StorageEntity c:chests())for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(item))n+=c.getItem(i).getCount();return n;}
    static int inInventory(ServerPlayer p,Item item){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n;}
    static boolean inside(ServerPlayer p){return p!=null&&PocketService.inPocket(p.level());}
    static void homePosition(ServerPlayer p,double x){p.teleportTo(server.overworld(),x,81,3.5,180,10);}
    static String chestClick()throws Exception{BlockPos p=PocketService.chestPositions(record()).get(2);return "block "+p.getX()+" "+p.getY()+" "+p.getZ();}
    @SubscribeEvent public static void start(ServerStartedEvent e)throws Exception {
        if(RemoteTicketProbe.active())return;
        if(!Boolean.getBoolean("anya.acceptance")||FaultProbe.active()||RoomProbe.active()||MultiPocketProbe.active())return;
        server=e.getServer();Acceptance.verifyJar("server");
        check(server.getPlayerList().getPlayers().isEmpty(),"Acceptance requires no pre-existing users");
        check(!server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat").toFile().exists(),"Acceptance requires a fresh disposable world");
        ExpansionRules.get().pocketStorage=true;
        var l=server.overworld();for(int x=-5;x<25;x++)for(int z=-5;z<25;z++)l.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());
        l.setDefaultSpawnPos(new BlockPos(5,81,5),180);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        l.setBlockAndUpdate(hp,Content.HUB.get().defaultBlockState());l.setBlockAndUpdate(ep,PocketContent.ENTRANCE.get().defaultBlockState());
        hub=(StorageEntity)l.getBlockEntity(hp);entrance=(PocketContent.EntranceEntity)l.getBlockEntity(ep);
        running=true;Acceptance.marker("server-ready","ready");
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
        if(!running||e.getServer()!=server)return;tick++;
        try{
            if(tick-since>1800)throw new IllegalStateException("Phase timeout "+phase+" "+PocketService.status());
            switch(phase){
                case 0 -> {if(a()!=null&&b()!=null){
                    hub.owner=a().getUUID();entrance.owner=a().getUUID();
                    homePosition(a(),3.5);homePosition(b(),4.5);
                    a().getInventory().clearContent();b().getInventory().clearContent();
                    ItemStack remote=new ItemStack(Content.REMOTE.get());var tag=BagInventory.data(remote);tag.putString("HubDimension","minecraft:overworld");tag.putLong("HubPos",hp.asLong());BagInventory.data(remote,tag);
                    a().getInventory().setItem(0,remote);PocketService.link(a(),entrance,remote);pocket=entrance.pocket;check(pocket!=null,"Pocket link");b().getInventory().setItem(0,remote.copy());
                    ItemStack items=new ItemStack(Items.DIAMOND,17);items.set(DataComponents.CUSTOM_NAME,Component.literal("Network conserved diamonds"));a().getInventory().setItem(9,items);
                    a().inventoryMenu.broadcastChanges();b().inventoryMenu.broadcastChanges();
                    send(b(),"block 3 81 1");next();
                }}
                case 1 -> {if(done(b())){check(!inside(b())&&PocketService.lease(b().getUUID())==null,"Denied physical entry gained access");log("untrusted TCP client entry denied");hub.setMember(b().getUUID(),BasePermissions.VISIT);send(a(),"block 3 81 1");next();}}
                case 2 -> {if(inside(a())&&done(a())){send(a(),"walk");next();}}
                case 3 -> {if(done(a())){check(a().getZ()<record().z()+15,"Real movement packets did not traverse room");check(inInventory(a(),Items.DIAMOND)==17,"Travel changed inventory");log("real client room walk and inventory conservation");send(a(),chestClick());next();}}
                case 4 -> {if(done(a())){check(a().containerMenu instanceof StoreMenu m&&m.capacity()==54,"Physical chest menu missing");send(a(),"deposit");next();}}
                case 5 -> {if(done(a())){check(count(Items.DIAMOND)==17&&inInventory(a(),Items.DIAMOND)==0,"Network deposit conservation failed");send(a(),"photo stored-chest");next();}}
                case 6 -> {if(done(a())){send(b(),"block 3 81 1");next();}}
                case 7 -> {if(inside(b())&&done(b())){send(b(),"walk");next();}}
                case 8 -> {if(done(b())){send(b(),chestClick());next();}}
                case 9 -> {if(done(b())){check(b().containerMenu instanceof StoreMenu m&&m.privateView()&&m.resultCount()==0,"Visit-only player received chest items");send(b(),"withdraw");next();}}
                case 10 -> {if(done(b())){check(count(Items.DIAMOND)==17&&inInventory(b(),Items.DIAMOND)==0,"Visit-only client withdrew items");log("Visit-only physical contents private and withdrawal denied over TCP");send(a(),"exit");next();}}
                case 11 -> {if(done(a())&&!inside(a())){check(inside(b())&&PocketService.lease(b().getUUID())!=null&&PocketService.ownedTicketRequests()>0,"One visitor leaving released the other");send(b(),"exit");next();}}
                case 12 -> {if(done(b())&&!inside(b())){check(PocketService.ownedTicketRequests()==0,"Last visitor left requests");hub.setMember(b().getUUID(),BasePermissions.VISIT|BasePermissions.LEGACY);send(a(),"remote");next();}}
                case 13 -> {if(done(a())){check(a().containerMenu instanceof StoreMenu,"Alpha remote failed");send(b(),"remote");next();}}
                case 14 -> {if(done(b())){check(b().containerMenu instanceof StoreMenu,"Beta remote failed");send(a(),"close");next();}}
                case 15 -> {if(done(a())){check(b().containerMenu instanceof StoreMenu&&PocketService.lease(b().getUUID())!=null&&PocketService.ownedTicketRequests()>0,"Closing one remote invalidated other viewer");send(b(),"withdrawone");next();}}
                case 16 -> {if(done(b())){check(count(Items.DIAMOND)==16&&inInventory(b(),Items.DIAMOND)==1,"Second viewer couldn't transact after first closed");hub.removeMember(b().getUUID());send(b(),"stale");next();}}
                case 17 -> {if(done(b())){check(!(b().containerMenu instanceof StoreMenu)&&count(Items.DIAMOND)==16&&inInventory(b(),Items.DIAMOND)==1,"Revoked TCP menu accepted stale transfer");log("simultaneous remote viewers, independent close, revoked stale packet conservation");hub.setMember(b().getUUID(),BasePermissions.VISIT);send(b(),"block 3 81 1");next();}}
                case 18 -> {if(done(b())&&inside(b())){Acceptance.marker("kill-beta","SIGKILL client process while visiting");next();}}
                case 19 -> {if(b()==null){check(PocketService.lease(UUID.nameUUIDFromBytes("OfflinePlayer:AnyaQABeta".getBytes(java.nio.charset.StandardCharsets.UTF_8)))==null,"Socket loss retained lease");log("real client process killed: socket loss released lease");Acceptance.marker("restart-beta","same client identity");next();}}
                case 20 -> {if(b()!=null&&!inside(b())&&PocketService.ledger(server).returning(b().getUUID())==null&&tick-since>30){check(inInventory(b(),Items.DIAMOND)==1,"Reconnect lost/duplicated inventory");log("real client process restart and TCP reconnect recovered saved visit");send(b(),"block 3 81 1");next();}}
                case 21 -> {if(done(b())&&inside(b())){hub.removeMember(b().getUUID());next();}}
                case 22 -> {if(!inside(b())&&tick-since>10){log("live Visit revocation returned actual client");Integrations.verify(server,a(),hub,entrance,record());send(a(),"block 3 81 1");next();}}
                case 23 -> {if(done(a())&&inside(a())){Integrations.inside(a(),record());a().getInventory().setItem(9,new ItemStack(Items.IRON_INGOT,9));a().inventoryMenu.broadcastChanges();float health=a().getHealth();a().hurt(a().damageSources().generic(),5);check(a().getHealth()==health,"Prototype damage suppression changed");a().setHealth(0);a().die(a().damageSources().genericKill());next();}}
                case 24 -> {if(!a().isAlive()&&tick-since>15){check(PocketService.lease(a().getUUID())==null,"Death retained lease");send(a(),"respawn");next();}}
                case 25 -> {if(done(a())&&a().isAlive()&&!inside(a())){check(PocketService.ledger(server).returning(a().getUUID())==null,"Respawn failed return recovery");log("forced vanilla death plus real client respawn; normal damage suppression retained; grave mods absent");send(a(),"block 3 81 1");next();}}
                case 26 -> {if(done(a())&&inside(a())){send(a(),"exit");next();}}
                case 27 -> {if(done(a())&&!inside(a())){var before=record();server.overworld().removeBlock(ep,false);server.overworld().setBlockAndUpdate(new BlockPos(5,81,1),PocketContent.ENTRANCE.get().defaultBlockState());entrance=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(new BlockPos(5,81,1));entrance.owner=a().getUUID();a().getInventory().setItem(0,b().getInventory().getItem(0).copy());PocketService.reanchor(a(),pocket,entrance,a().getInventory().getItem(0));check(record().cell()==before.cell()&&record().epoch()>before.epoch()&&count(Items.DIAMOND)==16,"Reanchor changed cell or stored contents");log("reanchor original cell and physical contents preserved");next();}}
                case 28 -> {if(tick-since>=650){check(PocketService.ownedTicketRequests()==0,"Final owned tickets remain");var r=record();for(int x=(r.x()-1)>>4;x<=(r.x()+32)>>4;x++)for(int z=(r.z()-1)>>4;z<=(r.z()+32)>>4;z++)check(PocketService.dimension(server).getChunkSource().getChunkNow(x,z)==null,"Final destination still loaded");log("final requests zero and destination positions unloaded");Acceptance.marker("PASSED","Packaged server + two real Minecraft client JVMs over localhost TCP\n"+PocketService.status());running=false;server.saveEverything(true,true,true);server.halt(false);}}
            }
        }catch(Throwable ex){running=false;Acceptance.marker("FAILED-server","phase="+phase+" "+ex);AnyaStorage.LOG.error("ANYA_PACKAGED_ACCEPTANCE_FAILED phase="+phase,ex);server.halt(false);}
    }
}

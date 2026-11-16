package dev.anya.storage.acceptance;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import dev.anya.storage.ticketexperiment.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.*;
import java.util.*;

/** Actual TCP client acceptance for a test-only ticket policy. No user world is accepted. */
@EventBusSubscriber(modid=Acceptance.ID)
public final class RemoteTicketProbe {
    private interface Work {void run()throws Exception;}
    private interface Ready {boolean get()throws Exception;}
    private record Step(String name,Work start,Ready ready,Work verify){}
    private static final ArrayDeque<Step> STEPS=new ArrayDeque<>();
    private static final Map<UUID,Integer> EXPECTED=new HashMap<>(),ACKS=new HashMap<>();
    private static MinecraftServer server;
    private static Step step;
    private static int tick,since,seq,completed;
    private static boolean running;
    private static StorageEntity ha,hb;
    private static PocketContent.EntranceEntity ea,eb;
    private static ItemStack ra,rb;
    private static UUID aid,bid,betaId;
    private static long aDeadline;
    private static final BlockPos HA=new BlockPos(1,81,1),EA=new BlockPos(3,81,1),HB=new BlockPos(9,81,1),EB=new BlockPos(11,81,1);
    public static boolean active(){return Boolean.getBoolean("anya.remoteAcceptance");}
    private static ServerPlayer a(){return server.getPlayerList().getPlayerByName("AnyaQAAlpha");}
    private static ServerPlayer b(){return server.getPlayerList().getPlayerByName("AnyaQABeta");}
    private static void check(boolean v,String text){Acceptance.require(v,text);}
    public static void ack(ServerPlayer p,Wire.Signal s){if(s.action().equals("done")&&Objects.equals(EXPECTED.get(p.getUUID()),s.sequence()))ACKS.put(p.getUUID(),s.sequence());}
    private static void send(ServerPlayer p,String text){int n=++seq;EXPECTED.put(p.getUUID(),n);ACKS.remove(p.getUUID());PacketDistributor.sendToPlayer(p,new Wire.Signal(n,text));}
    private static boolean done(ServerPlayer p){return p!=null&&EXPECTED.containsKey(p.getUUID())&&Objects.equals(EXPECTED.get(p.getUUID()),ACKS.get(p.getUUID()));}
    private static boolean both(){return done(a())&&done(b());}
    private static PocketLedger.Pocket room(UUID id)throws Exception{return PocketService.ledger(server).get(id);}
    private static boolean inside(ServerPlayer p,UUID id)throws Exception{var r=room(id);return p!=null&&PocketService.inPocket(p.level())&&r.contains(p.blockPosition().getX(),p.blockPosition().getY(),p.blockPosition().getZ());}
    private static boolean outside(ServerPlayer p){return p!=null&&!PocketService.inPocket(p.level())&&!PocketService.recovering(p.getUUID());}
    private static void home(ServerPlayer p,BlockPos en){p.teleportTo(server.overworld(),en.getX()+.5,81,en.getZ()+2.5,180,10);}
    private static void held(ServerPlayer p,ItemStack stack){p.getInventory().selected=0;p.getInventory().setItem(0,stack.copy());p.inventoryMenu.broadcastChanges();}
    private static String block(BlockPos p){return "block "+p.getX()+" "+p.getY()+" "+p.getZ();}
    private static ItemStack remote(BlockPos hub){var s=new ItemStack(Content.REMOTE.get());var t=BagInventory.data(s);t.putString("HubDimension","minecraft:overworld");t.putLong("HubPos",hub.asLong());BagInventory.data(s,t);return s;}
    private static StorageEntity chest(UUID id)throws Exception{var pos=PocketService.chestPositions(room(id)).getFirst();var l=PocketService.dimension(server);check(l.hasChunkAt(pos),"Inspection must not load missing destination");return (StorageEntity)l.getBlockEntity(pos);}
    private static ItemStack sample(String name){var s=new ItemStack(name.equals("Alpha")?Items.DIAMOND:Items.EMERALD,name.equals("Alpha")?11:17);s.set(DataComponents.CUSTOM_NAME,Component.literal("Pocket "+name+" sample"));return s;}
    private static void contents(UUID id,String name,int amount)throws Exception{var c=chest(id);var expected=sample(name);expected.setCount(amount);check(c!=null&&id.equals(c.pocketId)&&Transactions.same(c.getItem(0),expected),"Conservation/identity failed "+name);}
    private static int count(ServerPlayer p,net.minecraft.world.item.Item item){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n;}
    private static void menu(ServerPlayer p,UUID id){check(p!=null&&p.containerMenu instanceof StoreMenu m&&m.hasPocket(id)&&m.capacity()==216,"Wrong remote menu");}
    private static void rank(UUID id,boolean visit){
        RemoteTickets.auditEngine();int expected=visit||!RemoteTickets.candidate()?2:0;
        check(RemoteTickets.destination(id,expected)==16&&RemoteTickets.destination(id,expected==0?2:0)==0,"Incorrect rank for "+id+" visit="+visit);
        for(var e:RemoteTickets.applied().entrySet())if(!e.getKey().dimension().equals(PocketService.DIMENSION))check(e.getValue()==2,"Changed home/recovery rank");
    }
    private static int loaded(UUID id)throws Exception{var r=room(id);var c=PocketService.dimension(server).getChunkSource();int n=0;for(int x=(r.x()-1)>>4;x<=(r.x()+32)>>4;x++)for(int z=(r.z()-1)>>4;z<=(r.z()+32)>>4;z++)if(c.getChunkNow(x,z)!=null)n++;return n;}
    private static void add(String name,Work start,Ready ready,Work verify){STEPS.add(new Step(name,start,ready,verify));}
    private static void hold(String name,int ticks,Work verify){add(name,()->{},()->tick-since>=ticks,verify);}
    private static void actionA(String name,String action,Work verify){add(name,()->send(a(),action),()->done(a()),verify);}
    private static void actionB(String name,String action,Work verify){add(name,()->send(b(),action),()->done(b()),verify);}
    private static void log(String text){Acceptance.marker("steps.log","");}
    @SubscribeEvent public static void start(ServerStartedEvent event)throws Exception{
        if(!active())return;server=event.getServer();Acceptance.verifyJar("remote-server-"+System.getProperty("anya.remoteLeg","seed"));
        check(Files.isRegularFile(Path.of(".anya-remote-fixture")),"Requires a disposable fixture directory");
        check(PocketService.ownedTicketRequests()==0,"Tickets persisted into fresh server process");
        var o=ExpansionRules.get();o.pocketStorage=true;o.pocketEditing=false;o.activePocketLimit=2;o.pocketLimit=2;o.pocketSessionTicks=12000;
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);server.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,server);
        String leg=System.getProperty("anya.remoteLeg","seed");
        if(leg.equals("seed")){
            check(!Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Refusing an existing seed world");
            var l=server.overworld();for(int x=-3;x<25;x++)for(int z=-3;z<20;z++)l.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());l.setDefaultSpawnPos(new BlockPos(12,81,12),0);
            for(BlockPos p:List.of(HA,HB))l.setBlockAndUpdate(p,Content.HUB.get().defaultBlockState());for(BlockPos p:List.of(EA,EB))l.setBlockAndUpdate(p,PocketContent.ENTRANCE.get().defaultBlockState());
        }else{
            server.overworld().getChunk(0,0);
            check(PocketService.ledger(server).pockets().size()==2,"Lost/reallocated pocket identities");
            check(PocketService.ledger(server).returnCount()==1,"Expected one unfinished saved visitor on restart");
            check(PocketService.dimension(server).getChunkSource().getLoadedChunksCount()==0,"Restart resurrected destination loading without a user");
        }
        ha=(StorageEntity)server.overworld().getBlockEntity(HA);hb=(StorageEntity)server.overworld().getBlockEntity(HB);
        ea=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(EA);eb=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(EB);
        if(leg.equals("seed"))seed();else resume(leg.equals("after-cut"));
        running=true;Acceptance.marker("server-ready","leg="+leg+" candidate="+RemoteTickets.candidate());
    }
    private static void initialize()throws Exception{
        check(a()!=null&&b()!=null,"Clients missing");betaId=b().getUUID();
        ha.owner=a().getUUID();ea.owner=a().getUUID();hb.owner=b().getUUID();eb.owner=b().getUUID();
        home(a(),EA);home(b(),EB);a().getInventory().clearContent();b().getInventory().clearContent();
        ra=remote(HA);rb=remote(HB);PocketService.link(a(),ea,ra);PocketService.link(b(),eb,rb);aid=ea.pocket;bid=eb.pocket;
        check(aid!=null&&bid!=null&&!aid.equals(bid),"Independent identities missing");held(a(),ra);held(b(),rb);
        ha.setMember(b().getUUID(),BasePermissions.LEGACY|BasePermissions.VISIT);
        var p=new Properties();p.setProperty("alpha",aid.toString());p.setProperty("beta",bid.toString());p.setProperty("alphaCell",""+room(aid).cell());p.setProperty("betaCell",""+room(bid).cell());
        try(var out=Files.newOutputStream(Path.of("fixture-identities.properties"))){p.store(out,"Disposable acceptance identity checkpoint");}
    }
    private static void seed(){
        add("two-network-clients",()->{},()->a()!=null&&b()!=null,RemoteTicketProbe::initialize);
        add("cold-visits",()->{send(a(),block(EA));send(b(),block(EB));},()->both()&&inside(a(),aid)&&inside(b(),bid),()->{chest(aid).setItem(0,sample("Alpha"));chest(bid).setItem(0,sample("Beta"));rank(aid,true);rank(bid,true);});
        hold("visit-steady",240,()->{rank(aid,true);rank(bid,true);});
        add("visit-to-remote-exit",()->{send(a(),"exit");send(b(),"exit");},()->both()&&outside(a())&&outside(b()),()->{});
        hold("return-confirmed",20,()->check(PocketService.ledger(server).returnCount()==0,"Return record not cleared"));
        add("independent-remotes",()->{send(a(),"remote");send(b(),"remote");},RemoteTicketProbe::both,()->{menu(a(),aid);menu(b(),bid);rank(aid,false);rank(bid,false);});
        for(int i=0;i<3;i++){
            hold("remote-steady-"+i,500,()->{contents(aid,"Alpha",11);contents(bid,"Beta",17);rank(aid,false);rank(bid,false);});
            if(i<2){actionA("one-view-close-"+i,"close",()->menu(b(),bid));actionA("one-view-reopen-"+i,"remote",()->{menu(a(),aid);menu(b(),bid);});}
        }
        actionA("network-conserved-withdrawal","multiwithdraw Alpha 11",()->{contents(aid,"Alpha",10);check(count(a(),Items.DIAMOND)==1,"Withdrawal lost/duplicated sample");});
        actionB("close-beta-own-remote","close",()->menu(a(),aid));
        add("overlapping-visitor-upgrades-ticket",()->{home(b(),EA);held(b(),ra);aDeadline=PocketService.lease(a().getUUID()).deadline();send(b(),block(EA));},()->done(b())&&inside(b(),aid),()->{menu(a(),aid);rank(aid,true);check(PocketService.lease(a().getUUID()).deadline()==aDeadline,"Visitor reset independent remote deadline");});
        hold("overlap-steady",200,()->rank(aid,true));
        actionB("visitor-leaves-remote-remains","exit",()->{check(outside(b()),"Exit failed");menu(a(),aid);});
        hold("remote-downgrade-after-return",40,()->{rank(aid,false);check(PocketService.lease(a().getUUID()).deadline()==aDeadline,"Return reset remote deadline");});
        add("beta-independent-again",()->{home(b(),EB);held(b(),rb);send(b(),"remote");},()->done(b()),()->menu(b(),bid));
        actionA("same-player-close-remote","close",()->menu(b(),bid));
        add("same-player-remote-to-visit",()->send(a(),block(EA)),()->done(a())&&inside(a(),aid),()->{rank(aid,true);rank(bid,false);menu(b(),bid);});
        actionA("same-player-visit-exit","exit",()->check(outside(a()),"Own visit exit failed"));
        actionA("same-player-visit-to-remote","remote",()->{menu(a(),aid);rank(aid,false);rank(bid,false);});
        actionB("prepare-shared-view","close",()->{});
        add("shared-remote-view",()->{home(b(),EA);held(b(),ra);send(b(),"remote");},()->done(b()),()->{menu(a(),aid);menu(b(),aid);rank(aid,false);check(RemoteTickets.applied().size()==17,"Shared viewers doubled ticket requests");});
        actionB("capture-revoked-intent","multicapture",()->{});
        add("revoke-one-viewer",()->{ha.removeMember(b().getUUID());send(b(),"stale");},()->done(b()),()->{check(PocketService.lease(b().getUUID())==null&&!(b().containerMenu instanceof StoreMenu),"Revoked viewer kept access");menu(a(),aid);rank(aid,false);contents(aid,"Alpha",10);check(count(b(),Items.DIAMOND)==0,"Revoked request moved item");});
        actionB("denied-remote-reopen","remote",()->check(PocketService.lease(b().getUUID())==null,"No-rights remote acquired lease"));
        add("restore-beta-own-remote",()->{home(b(),EB);held(b(),rb);send(b(),"remote");},()->done(b()),()->menu(b(),bid));
        add("kill-remote-client",()->Acceptance.marker("kill-beta-1","Kill actual remote-viewing client JVM"),()->b()==null,()->{check(PocketService.lease(betaId)==null,"Disconnected remote retained lease");menu(a(),aid);rank(aid,false);Acceptance.marker("restart-beta-1","Reconnect same identity");});
        add("remote-socket-reconnect",()->{},()->b()!=null&&outside(b()),()->{check(PocketService.lease(betaId)==null&&PocketService.ledger(server).returning(betaId)==null,"Remote reconnect invented visit");menu(a(),aid);});
        add("visit-before-second-kill",()->{home(b(),EB);send(b(),block(EB));},()->done(b())&&inside(b(),bid),()->{rank(aid,false);rank(bid,true);});
        add("kill-visiting-client",()->Acceptance.marker("kill-beta-2","Kill actual visiting client JVM"),()->b()==null,()->{check(PocketService.lease(betaId)==null&&PocketService.ledger(server).returning(betaId)!=null,"Interrupted visit record/lease incorrect");menu(a(),aid);Acceptance.marker("restart-beta-2","Reconnect visitor");});
        add("visit-socket-reconnect",()->{},()->b()!=null&&outside(b())&&PocketService.ledger(server).returning(betaId)==null,()->{menu(a(),aid);check(count(a(),Items.DIAMOND)==1,"Other viewer's inventory changed");});
        add("active-shutdown-visit",()->{home(b(),EB);send(b(),block(EB));},()->done(b())&&inside(b(),bid),()->{menu(a(),aid);contents(aid,"Alpha",10);contents(bid,"Beta",17);rank(aid,false);rank(bid,true);server.saveEverything(true,true,true);Acceptance.marker("ACTIVE_STOP_ARMED","two leases: Alpha remote / Beta visit, audited ticket levels\n");running=false;server.halt(false);});
    }
    private static void resume(boolean afterCut){
        add("reconnected-real-clients",()->{},()->a()!=null&&b()!=null&&outside(a())&&outside(b())&&PocketService.ledger(server).returnCount()==0,()->{
            Properties saved=new Properties();try(var in=Files.newInputStream(Path.of("fixture-identities.properties"))){saved.load(in);}
            aid=UUID.fromString(saved.getProperty("alpha"));bid=UUID.fromString(saved.getProperty("beta"));betaId=b().getUUID();
            check(aid.equals(ea.pocket)&&bid.equals(eb.pocket)&&room(aid).cell()==Integer.parseInt(saved.getProperty("alphaCell"))&&room(bid).cell()==Integer.parseInt(saved.getProperty("betaCell")),"Restart changed room identities");
            check(room(aid).owner().equals(a().getUUID())&&room(bid).owner().equals(b().getUUID()),"Restart changed ownership");
            check(count(a(),Items.DIAMOND)==1&&count(b(),Items.DIAMOND)==0,"Restart/recovery changed player item counts");
            ra=a().getInventory().getItem(0).copy();rb=b().getInventory().getItem(0).copy();home(a(),EA);home(b(),EB);
        });
        add("restart-remote-reopen",()->{send(a(),"remote");send(b(),"remote");},RemoteTicketProbe::both,()->{menu(a(),aid);menu(b(),bid);contents(aid,"Alpha",10);contents(bid,"Beta",17);rank(aid,false);rank(bid,false);});
        actionA("network-restart-alpha-contents","multiview Alpha 10",()->{});
        actionB("network-restart-beta-contents","multiview Beta 17",()->{});
        if(!afterCut){
            actionB("close-for-recovery-interruption","close",()->menu(a(),aid));
            add("visit-before-recovery-interruption",()->send(b(),block(EB)),()->done(b())&&inside(b(),bid),()->{rank(bid,true);server.saveEverything(true,true,true);Acceptance.marker("ARM_RECOVERY_CUT","Cut after teleport before save confirmation, original visit durable");});
            add("interrupt-return-save",()->send(b(),"exit"),()->false,()->{});
        }else{
            actionA("close-one-final-view","close",()->menu(b(),bid));
            hold("one-pocket-cleanup",650,()->{check(loaded(aid)==0,"Closed destination still loaded");menu(b(),bid);rank(bid,false);contents(bid,"Beta",17);});
            actionB("close-final-view","close",()->{});
            hold("final-cleanup",650,()->{
                RemoteTickets.auditEngine();check(PocketService.ownedTicketRequests()==0&&RemoteTickets.applied().isEmpty()&&PocketService.ledger(server).returnCount()==0,"Final resources/recovery not empty");
                check(loaded(aid)==0&&loaded(bid)==0&&PocketService.dimension(server).getChunkSource().getLoadedChunksCount()==0,"Destination chunks retained after final users");
                Acceptance.marker("PASSED","Actual two-client transitions, shared rank changes, sockets, active stop, interrupted recovery, identities/inventories and final cleanup. candidate="+RemoteTickets.candidate());
                running=false;server.saveEverything(true,true,true);server.halt(false);
            });
        }
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event){
        if(!running||event.getServer()!=server)return;tick++;
        try{
            if(step==null){step=STEPS.poll();check(step!=null,"Scenario exhausted without terminal marker");since=tick;RemoteMetrics.phase=step.name();step.start().run();}
            if(tick-since>1200)throw new IllegalStateException("Timeout at "+step.name()+" "+PocketService.status());
            if(step.ready().get()){
                step.verify().run();RemoteTickets.auditEngine();
                String line="tick="+tick+" stage="+(++completed)+" "+step.name()+" ranks="+RemoteTickets.applied().values()+"\n";
                RemoteTickets.marker("stages.log",line,true);AnyaStorage.LOG.info("ANYA_REMOTE_ACCEPTANCE {}",line.trim());step=null;
            }
        }catch(Throwable t){running=false;Acceptance.marker("FAILED-server",step==null?t.toString():step.name()+": "+t);AnyaStorage.LOG.error("ANYA_REMOTE_ACCEPTANCE_FAILED",t);server.halt(false);}
    }
}

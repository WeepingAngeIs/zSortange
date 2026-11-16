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

/** New disposable network scenario; never used on a user-supplied world. */
@EventBusSubscriber(modid=Acceptance.ID)
public final class ColdRecoveryProbe {
    private interface Work{void run()throws Exception;}
    private interface Ready{boolean get()throws Exception;}
    private record Step(String name,Work start,Ready ready,Work verify){}
    private static final ArrayDeque<Step> STEPS=new ArrayDeque<>();
    private static final Map<UUID,Integer> EXPECTED=new HashMap<>(),ACKS=new HashMap<>();
    private static final BlockPos HA=new BlockPos(2052,81,2052),EA=new BlockPos(2056,81,2052),HB=new BlockPos(4,81,4),EB=new BlockPos(8,81,4),SPAWN=new BlockPos(-1016,81,-1016);
    private static MinecraftServer server;
    private static UUID aid,bid,alphaId;
    private static PocketLedger.ReturnPoint back;
    private static Properties ids=new Properties();
    private static Step step;
    private static int tick,since,sequence,completed;
    private static long betaDeadline;
    private static boolean running,resume,restoreFallbackAtLogin;
    public static boolean active(){return Boolean.getBoolean("anya.coldRecoveryAcceptance");}
    private static ServerPlayer a(){return server.getPlayerList().getPlayerByName("AnyaQAAlpha");}
    private static ServerPlayer b(){return server.getPlayerList().getPlayerByName("AnyaQABeta");}
    private static void check(boolean v,String s){Acceptance.require(v,s);}
    public static void ack(ServerPlayer p,Wire.Signal s){if(s.action().equals("done")&&Objects.equals(EXPECTED.get(p.getUUID()),s.sequence()))ACKS.put(p.getUUID(),s.sequence());}
    private static void send(ServerPlayer p,String s){int n=++sequence;EXPECTED.put(p.getUUID(),n);ACKS.remove(p.getUUID());PacketDistributor.sendToPlayer(p,new Wire.Signal(n,s));}
    private static boolean done(ServerPlayer p){return p!=null&&EXPECTED.containsKey(p.getUUID())&&Objects.equals(EXPECTED.get(p.getUUID()),ACKS.get(p.getUUID()));}
    private static void add(String n,Work start,Ready ready,Work verify){STEPS.add(new Step(n,start,ready,verify));}
    private static void hold(String n,int ticks,Work verify){add(n,()->{},()->tick-since>=ticks,verify);}
    private static void aa(String n,String s,Work verify){add(n,()->send(a(),s),()->done(a()),verify);}
    private static PocketLedger ledger()throws Exception{return PocketService.ledger(server);}
    private static PocketLedger.Pocket room(UUID id)throws Exception{return ledger().get(id);}
    private static boolean inside(ServerPlayer p){return p!=null&&PocketService.inPocket(p.level());}
    private static boolean outside(ServerPlayer p){return p!=null&&!inside(p)&&!PocketService.recovering(p.getUUID());}
    private static boolean absent(BlockPos p){return server.overworld().getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4)==null;}
    private static BlockPos returnPos(){return BlockPos.containing(back.x(),back.y(),back.z());}
    private static void home(ServerPlayer p,BlockPos entrance){p.teleportTo(server.overworld(),entrance.getX()+.5,81,entrance.getZ()+2.5,180,0);}
    private static String block(BlockPos p){return "block "+p.getX()+" "+p.getY()+" "+p.getZ();}
    private static ItemStack remote(BlockPos p){var s=new ItemStack(Content.REMOTE.get());var t=BagInventory.data(s);t.putString("HubDimension","minecraft:overworld");t.putLong("HubPos",p.asLong());BagInventory.data(s,t);return s;}
    private static ItemStack sample(String name,int n){var s=new ItemStack(name.equals("Alpha")?Items.DIAMOND:Items.EMERALD,n);s.set(DataComponents.CUSTOM_NAME,Component.literal("Pocket "+name+" sample"));return s;}
    private static StorageEntity chest(UUID id)throws Exception{var p=PocketService.chestPositions(room(id)).getFirst();check(PocketService.dimension(server).hasChunkAt(p),"Observation may not force-load storage");return (StorageEntity)PocketService.dimension(server).getBlockEntity(p);}
    private static int count(ServerPlayer p,Item item){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n;}
    private static void contents(UUID id,String name,int n)throws Exception{var c=chest(id);check(c!=null&&id.equals(c.pocketId)&&Transactions.same(c.getItem(0),sample(name,n)),"Physical contents/components changed: "+name);check(c.storageIdentity().toString().equals(ids.getProperty(name+"Chest")),"Chest identity changed");}
    private static void beta()throws Exception{
        check(b()!=null&&b().containerMenu instanceof StoreMenu m&&m.hasPocket(bid),"Independent Beta view lost");var lease=PocketService.lease(b().getUUID());
        check(lease!=null&&lease.deadline()==betaDeadline,"Independent Beta deadline changed");contents(bid,"Beta",17);
        check(RemoteTickets.destination(bid,RemoteTickets.candidate()?0:2)==16,"Beta destination rank changed unexpectedly");
    }
    private static void verifyIds()throws Exception{
        check(room(aid).cell()==Integer.parseInt(ids.getProperty("AlphaCell"))&&room(bid).cell()==Integer.parseInt(ids.getProperty("BetaCell")),"Room cells reallocated");
        check(room(aid).owner().equals(alphaId)&&room(bid).owner().equals(b().getUUID()),"Room ownership changed");
        check(count(a(),Items.DIAMOND)==1&&count(b(),Items.DIAMOND)==0,"Player inventory conservation failed");
    }
    private static void floor(BlockPos center,boolean hazard){for(int x=-7;x<=7;x++)for(int z=-7;z<=7;z++)server.overworld().setBlockAndUpdate(new BlockPos(center.getX()+x,80,center.getZ()+z),(hazard?Blocks.MAGMA_BLOCK:Blocks.STONE).defaultBlockState());}
    private static void obstruction(boolean blocked){
        BlockPos center=returnPos();var l=server.overworld();
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)for(int y=80;y<=89;y++){
            BlockPos p=new BlockPos(center.getX()+x,y,center.getZ()+z);if(p.equals(HA)||p.equals(EA))continue;
            l.setBlock(p,(blocked?Blocks.MAGMA_BLOCK:y==80?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
        }
    }
    private static void armCold(String label,boolean spawn)throws Exception{
        check(absent(returnPos()),"Home is not genuinely unloaded");check(absent(SPAWN),"Fallback spawn is not genuinely unloaded");
        ColdRecoveryAudit.phase=label;ColdRecoveryAudit.expectCold(server.overworld(),returnPos(),alphaId);
        if(spawn)ColdRecoveryAudit.expectCold(server.overworld(),SPAWN,alphaId);
        // A ServerPlayer login may prepare the shared spawn before its saved dimension is restored.
        // Stage login elsewhere; restoring the fallback on login must not itself load that target.
        server.overworld().setDefaultSpawnPos(HB,0);restoreFallbackAtLogin=true;
        check(absent(returnPos())&&absent(SPAWN),"Login staging loaded a recovery target");
        Acceptance.marker("COLD-"+label,"homeAbsent=true spawnAbsent=true; no observer chunk load; "+returnPos()+" / "+SPAWN);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void loggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event){
        if(!active()||!(event.getEntity() instanceof ServerPlayer p)||p.getServer()!=server)return;
        try{
            if(p.getGameProfile().getName().equals("AnyaQABeta"))Acceptance.marker("BETA_LOGGED_IN","Actual Beta joined before Alpha is launched");
            if(restoreFallbackAtLogin&&p.getUUID().equals(alphaId)){
                check(absent(SPAWN),"Login warmed the fallback despite separate staging");
                server.overworld().setDefaultSpawnPos(SPAWN,0);
                check(absent(SPAWN),"Changing shared spawn loaded the fallback before recovery");
                restoreFallbackAtLogin=false;
                Acceptance.marker("FALLBACK-RESTORED-"+tick,"Fixture restored shared spawn after login; target remained unloaded. Recovery itself is unmodified.");
            }
        }catch(Throwable error){running=false;Acceptance.marker("FAILED-cold-login-staging",error.toString());AnyaStorage.LOG.error("ANYA_COLD_LOGIN_STAGING_FAILED",error);server.halt(false);}
    }
    private static void cycle(int n,String label,boolean spawn){
        add("kill-visitor-"+n,()->{server.saveEverything(true,true,true);Acceptance.marker("kill-alpha-"+n,"SIGKILL actual visiting Alpha client");},()->a()==null,()->{check(PocketService.lease(alphaId)==null&&ledger().returning(alphaId)!=null,"Logout lost recovery marker or retained lease");beta();});
        hold("cold-drain-"+n,650,()->{
            beta();check(RemoteTickets.destination(aid,0)+RemoteTickets.destination(aid,2)==0,"Departed Alpha retained destination requests");
            armCold(label,spawn);Acceptance.marker("restart-alpha-"+n,"Reconnect only after positive unloaded-home assertion");
        });
    }
    @SubscribeEvent public static void start(ServerStartedEvent e)throws Exception{
        if(!active())return;server=e.getServer();resume=Boolean.getBoolean("anya.coldResume");Acceptance.verifyJar("cold-server-"+(resume?"resume":"seed"));
        check(Files.isRegularFile(Path.of(".anya-cold-fixture")),"Cold probe refuses unmarked world directory");
        var rules=ExpansionRules.get();rules.pocketStorage=true;rules.pocketEditing=false;rules.pocketLimit=2;rules.activePocketLimit=2;rules.pocketSessionTicks=12000;
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);server.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,server);server.getGameRules().getRule(GameRules.RULE_SPAWN_RADIUS).set(0,server);
        if(!resume){
            check(!Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Seed needs a new world");
            floor(new BlockPos(2056,80,2056),false);floor(new BlockPos(8,80,8),false);floor(SPAWN,false);server.overworld().setDefaultSpawnPos(SPAWN,0);
            for(BlockPos p:List.of(HA,HB))server.overworld().setBlockAndUpdate(p,Content.HUB.get().defaultBlockState());
            for(BlockPos p:List.of(EA,EB))server.overworld().setBlockAndUpdate(p,PocketContent.ENTRANCE.get().defaultBlockState());
            seed();Acceptance.marker("server-ready","Fresh cold-home world; no native/client substitutes");
        }else{
            try(var in=Files.newInputStream(Path.of("cold-identities.properties"))){ids.load(in);}
            aid=UUID.fromString(ids.getProperty("Alpha"));bid=UUID.fromString(ids.getProperty("Beta"));alphaId=UUID.fromString(ids.getProperty("AlphaPlayer"));back=ledger().returning(alphaId);
            check(back!=null&&ledger().returnCount()==1&&PocketService.ownedTicketRequests()==0,"Unresolved recovery did not persist cleanly");resumeSteps();
        }
        running=true;
    }
    private static void seed(){
        add("two-real-clients",()->{},()->a()!=null&&b()!=null,()->{
            alphaId=a().getUUID();home(a(),EA);home(b(),EB);
            for(ServerPlayer p:List.of(a(),b()))p.getInventory().clearContent();
            var ha=(StorageEntity)server.overworld().getBlockEntity(HA);var hb=(StorageEntity)server.overworld().getBlockEntity(HB);var ea=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(EA);var eb=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(EB);
            ha.owner=alphaId;ea.owner=alphaId;hb.owner=b().getUUID();eb.owner=b().getUUID();ha.setChanged();hb.setChanged();ea.setChanged();eb.setChanged();
            var ra=remote(HA);var rb=remote(HB);PocketService.link(a(),ea,ra);PocketService.link(b(),eb,rb);aid=ea.pocket;bid=eb.pocket;check(aid!=null&&bid!=null&&!aid.equals(bid),"Pocket allocation/link failed");
            a().getInventory().setItem(0,ra);b().getInventory().setItem(0,rb);a().inventoryMenu.broadcastChanges();b().inventoryMenu.broadcastChanges();
        });
        add("prepare-two-visits",()->{send(a(),block(EA));send(b(),block(EB));},()->done(a())&&done(b())&&inside(a())&&inside(b()),()->{
            back=ledger().returning(alphaId);check(back!=null,"No durable return before visit");chest(aid).setItem(0,sample("Alpha",11));chest(bid).setItem(0,sample("Beta",17));
            ids.setProperty("Alpha",aid.toString());ids.setProperty("Beta",bid.toString());ids.setProperty("AlphaCell",""+room(aid).cell());ids.setProperty("BetaCell",""+room(bid).cell());ids.setProperty("AlphaPlayer",alphaId.toString());ids.setProperty("AlphaChest",chest(aid).storageIdentity().toString());ids.setProperty("BetaChest",chest(bid).storageIdentity().toString());
            try(var out=Files.newOutputStream(Path.of("cold-identities.properties"))){ids.store(out,"Disposable fixture identities");}
        });
        add("beta-exits",()->send(b(),"exit"),()->done(b())&&outside(b()),()->{});
        add("beta-remote",()->send(b(),"remote"),()->done(b()),()->{betaDeadline=PocketService.lease(b().getUUID()).deadline();beta();});
        cycle(1,"safe-home",false);
        add("cold-home-reconnect",()->{},()->outside(a())&&ledger().returning(alphaId)==null,()->{
            check(a().blockPosition().distSqr(returnPos())<9&&ColdRecoveryAudit.outstanding()==0,"Cold home return failed");beta();
        });
        aa("alpha-remote-after-cold","remote",()->contents(aid,"Alpha",11));
        aa("withdraw-once","multiwithdraw Alpha 11",()->{contents(aid,"Alpha",10);check(count(a(),Items.DIAMOND)==1,"Withdrawal conservation failed");});
        aa("close-alpha","close",()->{});
        add("visit-for-unsafe-home",()->send(a(),block(EA)),()->done(a())&&inside(a()),()->{obstruction(true);server.saveEverything(true,true,true);});
        cycle(2,"unsafe-home-safe-spawn",true);
        add("fallback-reconnect",()->{},()->outside(a())&&ledger().returning(alphaId)==null,()->{check(a().blockPosition().distSqr(SPAWN)<9&&ColdRecoveryAudit.outstanding()==0,"Cold fallback did not reach safe spawn");verifyIds();beta();});
        add("repair-home-for-next-visit",()->{obstruction(false);home(a(),EA);send(a(),"remote");},()->done(a()),()->contents(aid,"Alpha",10));
        aa("close-alpha-again","close",()->{});
        add("visit-for-both-unsafe",()->send(a(),block(EA)),()->done(a())&&inside(a()),()->{obstruction(true);floor(SPAWN,true);server.saveEverything(true,true,true);});
        cycle(3,"both-unsafe",true);
        add("unsafe-reconnect-stays-queued",()->{},()->a()!=null&&inside(a())&&PocketService.recovering(alphaId)&&ColdRecoveryAudit.outstanding()==0,()->{check(ledger().returning(alphaId)!=null&&PocketService.lease(alphaId)==null,"Unsafe recovery granted a lease/cleared marker");beta();});
        hold("bounded-unsafe-retries",250,()->{
            var attempts=ColdRecoveryAudit.snapshot().stream().filter(o->o.phase().equals("both-unsafe")&&o.chunk()==new net.minecraft.world.level.ChunkPos(returnPos()).toLong()).map(ColdRecoveryAudit.Observation::tick).toList();
            check(attempts.size()>=3&&attempts.size()<=4,"Unexpected unsafe retry count: "+attempts);for(int i=1;i<attempts.size();i++)check(attempts.get(i)-attempts.get(i-1)>=100,"Unsafe retries unbounded");
            check(inside(a())&&PocketService.recovering(alphaId)&&ledger().returning(alphaId)!=null&&PocketService.lease(alphaId)==null,"Unsafe player was stranded without recovery state");
            check(!PocketService.physicalAllowed(a(),chest(aid)),"Recovering player regained physical storage rights");contents(aid,"Alpha",10);verifyIds();beta();
            float health=a().getHealth();a().hurt(a().damageSources().generic(),4);check(a().getHealth()==health,"Prototype damage suppression changed");
            check(PocketService.ownedTicketRequests()<=19,"Recovery ticket accumulation");server.saveEverything(true,true,true);running=false;
            Acceptance.marker("UNSAFE_STOP_ARMED","Unresolved return retained, no visitor lease, Beta remote active; bounded attempts="+attempts);server.halt(false);
        });
    }
    private static void resumeSteps(){
        hold("empty-restart-drain",650,()->{check(server.getPlayerList().getPlayers().isEmpty(),"Clients started before cold restart checkpoint");armCold("restart-both-unsafe",true);Acceptance.marker("server-ready","Unloaded home and spawn positively verified before reconnect");});
        add("unresolved-reconnect",()->{},()->a()!=null&&b()!=null&&inside(a())&&PocketService.recovering(alphaId)&&ColdRecoveryAudit.outstanding()==0,()->{verifyIds();check(ledger().returning(alphaId)!=null&&PocketService.lease(alphaId)==null,"Restart erased unsafe recovery");});
        add("independent-view-after-restart",()->send(b(),"remote"),()->done(b()),()->{betaDeadline=PocketService.lease(b().getUUID()).deadline();beta();});
        hold("queue-survives-restart",120,()->{check(inside(a())&&ledger().returning(alphaId)!=null,"Unsafe spawn unexpectedly accepted");verifyIds();beta();});
        add("repair-only-spawn-floor",()->server.overworld().setBlockAndUpdate(SPAWN.below(),Blocks.STONE.defaultBlockState()),()->outside(a())&&ledger().returning(alphaId)==null,()->{check(a().blockPosition().distSqr(SPAWN)<9,"Retry did not return to repaired spawn");verifyIds();beta();});
        add("inspect-original-room",()->{obstruction(false);home(a(),EA);send(a(),"remote");},()->done(a()),()->{contents(aid,"Alpha",10);contents(bid,"Beta",17);verifyIds();});
        aa("client-confirms-original-sample","multiview Alpha 10",()->{});
        add("close-both",()->{send(a(),"close");send(b(),"close");},()->done(a())&&done(b()),()->{floor(SPAWN,false);home(a(),SPAWN);home(b(),SPAWN);});
        hold("all-destinations-cleanup",650,()->{
            RemoteTickets.auditEngine();check(RemoteTickets.applied().isEmpty()&&PocketService.ownedTicketRequests()==0&&ledger().returnCount()==0,"Recovery resources or return markers leaked");
            check(PocketService.dimension(server).getChunkSource().getLoadedChunksCount()==0&&absent(HA)&&absent(HB),"Final destination/home cleanup incomplete");verifyIds();
            Acceptance.marker("PASSED","Two real clients, genuinely unloaded home/spawn, unsafe fallback, both-unsafe bounded retries, unresolved shutdown/restart, repaired-spawn automatic return, identities/inventories and cleanup; candidate="+RemoteTickets.candidate());
            running=false;server.saveEverything(true,true,true);server.halt(false);
        });
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
        if(!running||e.getServer()!=server)return;tick++;
        try{
            if(step==null){step=STEPS.poll();check(step!=null,"Missing terminal checkpoint");since=tick;RemoteMetrics.phase=step.name();step.start().run();}
            if(tick-since>1500)throw new IllegalStateException("Cold scenario timed out: "+step.name());
            if(step.ready().get()){
                step.verify().run();RemoteTickets.auditEngine();String line="stage="+(++completed)+" tick="+tick+" "+step.name()+" requests="+PocketService.ownedTicketRequests()+"\n";
                RemoteTickets.marker("cold-stages.log",line,true);AnyaStorage.LOG.info("ANYA_COLD_RECOVERY {}",line.trim());step=null;
            }
        }catch(Throwable error){running=false;Acceptance.marker("FAILED-cold-probe",(step==null?"startup":step.name())+": "+error);AnyaStorage.LOG.error("ANYA_COLD_RECOVERY_FAILED",error);server.halt(false);}
    }
}

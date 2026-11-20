package dev.anya.storage.acceptance;

import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** New isolated scenario: two owners, separate cells, shared users, changing admission caps.
 * Only this fixture uses reflective read-only telemetry. It is never in the production JAR. */
@EventBusSubscriber(modid=Acceptance.ID)
public final class MultiPocketProbe {
    private interface Work {void run() throws Exception;}
    private interface Ready {boolean get() throws Exception;}
    private record Step(String name,Work start,Ready ready,Work verify){}
    private static final ArrayDeque<Step> STEPS=new ArrayDeque<>();
    private static final Map<UUID,Integer> EXPECTED=new HashMap<>(),ACKS=new HashMap<>();
    private static final Map<String,Field> FIELDS=new HashMap<>();
    private static MinecraftServer server;
    private static Step step;
    private static boolean running;
    private static int tick,since,sequence,number,maxRequests,maxShell,maxEngine,maxPending,maxPreparing,maxActive;
    private static StorageEntity ha,hb;
    private static PocketContent.EntranceEntity ea,eb;
    private static ItemStack ra,rb;
    private static UUID aid,bid;
    private static long bDeadline;
    private static final BlockPos HA=new BlockPos(1,81,1),EA=new BlockPos(3,81,1),HB=new BlockPos(9,81,1),EB=new BlockPos(11,81,1),REPLACEMENT=new BlockPos(5,81,1);
    public static boolean active(){return Boolean.getBoolean("anya.multiAcceptance");}
    private static ServerPlayer a(){return server.getPlayerList().getPlayerByName("AnyaQAAlpha");}
    private static ServerPlayer b(){return server.getPlayerList().getPlayerByName("AnyaQABeta");}
    private static void check(boolean v,String why){Acceptance.require(v,why);}
    public static void ack(ServerPlayer p,Wire.Signal s){if(s.action().equals("done")&&Objects.equals(EXPECTED.get(p.getUUID()),s.sequence()))ACKS.put(p.getUUID(),s.sequence());}
    private static void send(ServerPlayer p,String action){int n=++sequence;EXPECTED.put(p.getUUID(),n);ACKS.remove(p.getUUID());PacketDistributor.sendToPlayer(p,new Wire.Signal(n,action));}
    private static boolean done(ServerPlayer p){return p!=null&&EXPECTED.containsKey(p.getUUID())&&Objects.equals(EXPECTED.get(p.getUUID()),ACKS.get(p.getUUID()));}
    private static boolean both(){return done(a())&&done(b());}
    private static boolean inside(ServerPlayer p,UUID id)throws Exception {var r=room(id);return p!=null&&PocketService.inPocket(p.level())&&r.contains(p.blockPosition().getX(),p.blockPosition().getY(),p.blockPosition().getZ());}
    private static PocketLedger.Pocket room(UUID id)throws Exception{return PocketService.ledger(server).get(id);}
    private static StorageEntity chest(UUID id)throws Exception{var r=room(id);var l=PocketService.dimension(server);var pos=PocketService.chestPositions(r).getFirst();check(l.hasChunkAt(pos),"Inspection must not load absent storage");return (StorageEntity)l.getBlockEntity(pos);}
    private static ItemStack sample(String name){var item=new ItemStack(name.equals("Alpha")?Items.DIAMOND:Items.EMERALD,name.equals("Alpha")?11:17);item.set(DataComponents.CUSTOM_NAME,Component.literal("Pocket "+name+" sample"));return item;}
    private static void contents(UUID id,String name,int count)throws Exception{var c=chest(id);check(c!=null&&id.equals(c.pocketId)&&c.getItem(0).is(name.equals("Alpha")?Items.DIAMOND:Items.EMERALD)&&c.getItem(0).getCount()==count&&c.getItem(0).getHoverName().getString().equals("Pocket "+name+" sample"),"Physical sample changed: "+name);}
    private static int count(ServerPlayer p,Item item){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n;}
    private static void held(ServerPlayer p,ItemStack s){p.getInventory().selected=0;p.getInventory().setItem(0,s.copy());p.inventoryMenu.broadcastChanges();}
    private static void home(ServerPlayer p,int x){p.teleportTo(server.overworld(),x+.5,81,3.5,180,10);}
    private static String block(BlockPos p){return "block "+p.getX()+" "+p.getY()+" "+p.getZ();}
    private static void menu(ServerPlayer p,UUID id){check(p.containerMenu instanceof StoreMenu m&&m.hasPocket(id)&&m.capacity()==216,"Incorrect combined storage identity/capacity");}
    private static void noLease(ServerPlayer p){check(PocketService.lease(p.getUUID())==null,"Unexpected retained/acquired lease");}
    private static Object field(String name)throws Exception{Field f=FIELDS.get(name);if(f==null){f=PocketService.class.getDeclaredField(name);f.setAccessible(true);FIELDS.put(name,f);}return f.get(null);}
    private static PocketLeases leases()throws Exception{return (PocketLeases)field("LEASES");}
    private static int requests(UUID id)throws Exception {int count=0;for(Object t:(Set<?>)field("TICKETS")){var m=t.getClass().getDeclaredMethod("pocket");m.setAccessible(true);if(id.equals(m.invoke(t)))count++;}return count;}
    private static int loaded(UUID id)throws Exception {if(id==null)return 0;var r=room(id);var l=PocketService.dimension(server);int n=0;for(int x=(r.x()-1)>>4;x<=(r.x()+32)>>4;x++)for(int z=(r.z()-1)>>4;z<=(r.z()+32)>>4;z++)if(l.getChunkSource().getChunkNow(x,z)!=null)n++;return n;}
    private static void metrics(String label)throws Exception{
        if(aid==null)return;
        int shell=(int)field("lastPreparedBlocks"),pending=((Map<?,?>)field("PENDING")).size(),preparing=((Map<?,?>)field("PREPARING")).size();
        int requests=PocketService.ownedTicketRequests(),active=leases().active().size(),engine=PocketService.dimension(server).getChunkSource().getLoadedChunksCount();
        maxRequests=Math.max(maxRequests,requests);maxShell=Math.max(maxShell,shell);maxEngine=Math.max(maxEngine,engine);maxPending=Math.max(maxPending,pending);maxPreparing=Math.max(maxPreparing,preparing);maxActive=Math.max(maxActive,active);
        check(shell<=256,"Global shell preparation write budget exceeded");check(active<=2&&leases().snapshot().size()<=2,"Scenario exceeded bounded two-user/two-pocket allocation");
        // One home chunk + 16 destination requests per cell, plus at most two short recovery requests.
        check(requests<=36,"Unexpected ticket-request growth");
        if(label!=null||tick%20==0){
            String line=String.join(",",Integer.toString(tick),label==null?"sample":label,Integer.toString(ExpansionRules.get().activePocketLimit),Integer.toString(active),Integer.toString(leases().snapshot().size()),Integer.toString(requests),Integer.toString(requests(aid)),Integer.toString(requests(bid)),Integer.toString(loaded(aid)),Integer.toString(loaded(bid)),Integer.toString(engine),Integer.toString(shell),Integer.toString(pending),Integer.toString(preparing))+"\n";
            Files.writeString(Acceptance.control().resolve("multi-budget.csv"),line,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
    }
    private static void add(String name,Work start,Ready ready,Work verify){STEPS.add(new Step(name,start,ready,verify));}
    private static void expectA(String name,Work start,Work verify){add(name,start,()->done(a()),verify);}
    private static void expectB(String name,Work start,Work verify){add(name,start,()->done(b()),verify);}
    private static void waitFor(String name,int ticks,Work verify){add(name,()->{},()->tick-since>=ticks,verify);}
    @SubscribeEvent public static void start(ServerStartedEvent e)throws Exception{
        if(!active())return;server=e.getServer();Acceptance.verifyJar("multi-server");
        if(Boolean.getBoolean("anya.multiResume")){resume();return;}
        check(!Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Requires a fresh disposable world");
        var o=ExpansionRules.get();o.pocketStorage=true;o.pocketEditing=true;o.activePocketLimit=1;o.pocketLimit=2;
        var l=server.overworld();for(int x=-3;x<25;x++)for(int z=-3;z<20;z++)l.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());l.setDefaultSpawnPos(new BlockPos(12,81,12),0);
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        for(BlockPos p:List.of(HA,HB))l.setBlockAndUpdate(p,Content.HUB.get().defaultBlockState());for(BlockPos p:List.of(EA,EB))l.setBlockAndUpdate(p,PocketContent.ENTRANCE.get().defaultBlockState());
        ha=(StorageEntity)l.getBlockEntity(HA);hb=(StorageEntity)l.getBlockEntity(HB);ea=(PocketContent.EntranceEntity)l.getBlockEntity(EA);eb=(PocketContent.EntranceEntity)l.getBlockEntity(EB);
        Files.writeString(Acceptance.control().resolve("multi-budget.csv"),"tick,label,admissionCap,activePockets,leases,ownedRequests,requestsAlpha,requestsBeta,loadedRingAlpha,loadedRingBeta,totalLoadedDestinationChunks,shellWrites,pendingUsers,preparingJobs\n");
        scenarios();running=true;Acceptance.marker("server-ready","Two owners, two independent pockets; no user world");
    }
    private static ItemStack remote(BlockPos hub){var r=new ItemStack(Content.REMOTE.get());var t=BagInventory.data(r);t.putString("HubDimension","minecraft:overworld");t.putLong("HubPos",hub.asLong());BagInventory.data(r,t);return r;}
    private static void scenarios(){
        add("allocate-two-owner-identities",()->{},()->a()!=null&&b()!=null,()->{
            ha.owner=a().getUUID();ea.owner=a().getUUID();hb.owner=b().getUUID();eb.owner=b().getUUID();home(a(),3);home(b(),11);a().getInventory().clearContent();b().getInventory().clearContent();ra=remote(HA);rb=remote(HB);held(a(),ra);held(b(),rb);
            PocketService.link(a(),ea,ra);PocketService.link(b(),eb,rb);aid=ea.pocket;bid=eb.pocket;held(a(),ra);held(b(),rb);
            check(aid!=null&&bid!=null&&!aid.equals(bid)&&room(aid).cell()!=room(bid).cell()&&!room(aid).owner().equals(room(bid).owner()),"Owners/cells aliased");
            var extra=new BlockPos(7,81,1);server.overworld().setBlockAndUpdate(extra,PocketContent.ENTRANCE.get().defaultBlockState());var end=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(extra);end.owner=a().getUUID();PocketService.link(a(),end,remote(HA));check(end.pocket==null&&PocketService.ledger(server).pockets().size()==2,"Allocation cap did not deny third cell");server.overworld().removeBlock(extra,false);
        });
        add("cap-one-denies-other-owner",()->{send(a(),block(EA));},()->done(a())&&inside(a(),aid),()->{check(requests(aid)==17,"First cell request count");home(b(),11);send(b(),block(EB));});
        add("denial-has-no-destination-load",()->{},()->done(b()),()->{noLease(b());check(room(bid).stage()==PocketLedger.Stage.RESERVED&&requests(bid)==0&&loaded(bid)==0,"Budget denial prepared/loaded destination");});
        add("two-independent-visitors",()->{ExpansionRules.get().activePocketLimit=2;send(b(),block(EB));},()->done(b())&&inside(b(),bid),()->{
            check(inside(a(),aid)&&requests(aid)==17&&requests(bid)==17,"Second cell disrupted first");chest(aid).setItem(0,sample("Alpha"));chest(bid).setItem(0,sample("Beta"));isolation();
        });
        add("lower-limits-preserve-existing-storage",()->{
            var o=ExpansionRules.get();o.activePocketLimit=1;o.pocketContainers=1;o.pocketLimit=1;
            StoreMenu.pocket(a(),new PocketView(a(),room(aid),PocketLeases.Kind.VISIT));StoreMenu.pocket(b(),new PocketView(b(),room(bid),PocketLeases.Kind.VISIT));send(a(),"multiview Alpha 11");send(b(),"multiview Beta 17");
        },MultiPocketProbe::both,()->{check(leases().active().size()==2,"Lowered admission cap abruptly evicted existing users");menu(a(),aid);menu(b(),bid);contents(aid,"Alpha",11);contents(bid,"Beta",17);});
        add("close-one-pocket-retain-other",()->send(a(),"exit"),()->done(a())&&!PocketService.inPocket(a().level()),()->{noLease(a());check(requests(aid)==0&&requests(bid)==17&&inside(b(),bid),"Closing Alpha disrupted Beta or leaked Alpha requests");});
        expectA("lower-cap-rejects-alpha-readmission",()->{held(a(),ra);send(a(),block(EA));},()->{noLease(a());check(requests(aid)==0&&inside(b(),bid),"Lower cap not enforced on new admission");});
        expectA("cross-owner-storage-denial",()->{held(a(),rb);send(a(),"remote");},()->{noLease(a());check(!(a().containerMenu instanceof StoreMenu)&&inside(b(),bid),"Foreign remote gained storage/lease");});
        expectA("cross-owner-entry-denial",()->{home(a(),11);send(a(),block(EB));},()->{noLease(a());check(!PocketService.inPocket(a().level()),"Foreign entrance admitted owner of another pocket");home(a(),3);held(a(),ra);});
        add("both-pockets-drain",()->send(b(),"exit"),()->done(b())&&!PocketService.inPocket(b().level()),()->{});
        waitFor("independent-first-unload",650,()->{check(requests(aid)==0&&requests(bid)==0&&loaded(aid)==0&&loaded(bid)==0,"Idle destinations retained after both left");ha.setMember(b().getUUID(),BasePermissions.LEGACY);home(b(),3);held(b(),ra);});
        expectB("storage-rights-do-not-grant-visit",()->send(b(),block(EA)),()->{noLease(b());check(!PocketService.inPocket(b().level()),"Storage rights granted Visit");});
        add("existing-alpha-reopens-below-creation-quota",()->send(a(),block(EA)),()->done(a())&&inside(a(),aid),()->{contents(aid,"Alpha",11);});
        expectB("mixed-visit-and-remote-one-cell",()->send(b(),"remote"),()->{menu(b(),aid);check(inside(a(),aid)&&leases().snapshot().size()==2&&leases().active().size()==1&&requests(aid)==17,"Shared viewers duplicated requests");});
        expectB("one-real-withdrawal-while-other-visits",()->send(b(),"multiwithdraw Alpha 11"),()->{contents(aid,"Alpha",10);check(count(b(),Items.DIAMOND)==1,"Cross-viewer sample not conserved");});
        for(int n=0;n<2;n++){
            add("visitor-cycle-"+n+"-exit",()->send(a(),"exit"),()->done(a())&&!PocketService.inPocket(a().level()),()->{menu(b(),aid);check(requests(aid)==17,"Visitor departure released remote viewer");});
            add("visitor-cycle-"+n+"-enter",()->send(a(),block(EA)),()->done(a())&&inside(a(),aid),()->{contents(aid,"Alpha",10);menu(b(),aid);});
        }
        add("visitor-finish",()->send(a(),"exit"),()->done(a())&&!PocketService.inPocket(a().level()),()->{});
        expectB("close-last-shared-remote",()->send(b(),"close"),()->{noLease(b());check(requests(aid)==0,"Last view leaked requests");ExpansionRules.get().activePocketLimit=2;home(b(),11);held(b(),rb);});
        add("two-independent-remote-viewers",()->{held(a(),ra);send(a(),"remote");send(b(),"remote");},MultiPocketProbe::both,()->{menu(a(),aid);menu(b(),bid);contents(aid,"Alpha",10);contents(bid,"Beta",17);bDeadline=PocketService.lease(b().getUUID()).deadline();});
        for(int n=0;n<3;n++){
            expectA("remote-cycle-"+n+"-close",()->send(a(),"close"),()->{noLease(a());menu(b(),bid);check(requests(aid)==0&&requests(bid)==17,"Remote close damaged independent pocket");});
            expectA("remote-cycle-"+n+"-reopen",()->send(a(),"remote"),()->{menu(a(),aid);menu(b(),bid);check(PocketService.lease(b().getUUID()).deadline()==bDeadline,"Other pocket resets unrelated deadline");});
        }
        add("both-remotes-close",()->{send(a(),"close");send(b(),"close");},MultiPocketProbe::both,()->{noLease(a());noLease(b());hb.setMember(a().getUUID(),BasePermissions.LEGACY);held(a(),rb);held(b(),ra);home(b(),3);});
        add("cross-delegated-remote-views",()->{send(a(),"remote");send(b(),"remote");},MultiPocketProbe::both,()->{menu(a(),bid);menu(b(),aid);});
        expectB("capture-current-foreign-view-intent",()->send(b(),"multicapture"),()->{});
        expectB("revoke-one-cross-delegated-view",()->{ha.removeMember(b().getUUID());send(b(),"stale");},()->{noLease(b());check(!(b().containerMenu instanceof StoreMenu),"Revoked view remained open");menu(a(),bid);contents(bid,"Beta",17);check(count(b(),Items.DIAMOND)==1&&requests(aid)==0&&requests(bid)==17,"Revocation changed items/other pocket");ha.setMember(b().getUUID(),BasePermissions.LEGACY|BasePermissions.VISIT);});
        expectB("reopen-alpha-before-anchor-change",()->send(b(),"remote"),()->{menu(b(),aid);contents(aid,"Alpha",10);long epoch=room(aid).epoch();PocketService.reanchor(a(),aid,ea,ra);check(room(aid).epoch()==epoch,"Active pocket permitted reanchor");});
        waitFor("anchor-destruction-only-affects-own-cell",20,()->{server.overworld().removeBlock(EA,false);});
        waitFor("anchor-invalidates-one-view",30,()->{noLease(b());check(!(b().containerMenu instanceof StoreMenu)&&requests(aid)==0,"Destroyed anchor retained view");menu(a(),bid);
            var old=room(aid);server.overworld().setBlockAndUpdate(REPLACEMENT,PocketContent.ENTRANCE.get().defaultBlockState());ea=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(REPLACEMENT);ea.owner=a().getUUID();PocketService.reanchor(b(),aid,ea,ra);check(room(aid).epoch()==old.epoch(),"Other owner stole reanchor");PocketService.reanchor(a(),aid,ea,ra);check(room(aid).cell()==old.cell()&&room(aid).epoch()>old.epoch(),"Reanchor replaced physical cell");menu(a(),bid);
        });
        for(int n=1;n<=2;n++){
            final int cycle=n;
            add("reconnect-cycle-"+n+"-enter",()->{home(b(),5);held(b(),ra);send(b(),block(REPLACEMENT));},()->done(b())&&inside(b(),aid),()->{menu(a(),bid);contents(aid,"Alpha",10);Acceptance.marker("kill-beta-"+cycle,"SIGKILL visiting owner of another pocket; other user stays viewing Beta");});
            add("reconnect-cycle-"+n+"-socket-loss",()->{},()->b()==null,()->{check(requests(aid)==0&&requests(bid)==17,"Socket loss released wrong pocket or leaked requests");menu(a(),bid);Acceptance.marker("restart-beta-"+cycle,"Reconnect identical offline test identity");});
            add("reconnect-cycle-"+n+"-recovery",()->{},()->b()!=null&&!PocketService.inPocket(b().level())&&PocketService.ledger(server).returning(b().getUUID())==null,()->{noLease(b());check(count(b(),Items.DIAMOND)==1,"Repeated reconnect lost/duplicated withdrawn sample");menu(a(),bid);});
        }
        expectB("final-lower-cap-denial",()->{ExpansionRules.get().activePocketLimit=1;send(b(),block(REPLACEMENT));},()->{noLease(b());menu(a(),bid);check(requests(aid)==0,"Denial leaked ticket");});
        waitFor("alpha-unloads-while-beta-stays-open",650,()->{check(loaded(aid)==0&&requests(aid)==0&&loaded(bid)==16&&requests(bid)==17,"Independent destination cleanup failed");menu(a(),bid);});
        expectA("close-final-beta-view",()->send(a(),"close"),()->{noLease(a());});
        waitFor("all-destinations-unloaded",650,()->{check(PocketService.ownedTicketRequests()==0&&leases().active().isEmpty()&&loaded(aid)==0&&loaded(bid)==0,"Final requests/chunks retained");
            check(PocketService.ledger(server).returnCount()==0,"Unfinished return leaked");
            Acceptance.marker("multi-identities.txt",aid+"\n"+bid+"\n"+ha.storageIdentity()+"\n"+hb.storageIdentity()+"\n"+b().getUUID()+"\n"+room(aid).epoch()+"\n");
            Acceptance.marker("multi-peaks.txt","maxActive="+maxActive+"\nmaxOwnedRequests="+maxRequests+"\nmaxTotalLoadedDestinationChunks="+maxEngine+"\nmaxShellWritesPerTick="+maxShell+"\nmaxPendingUsers="+maxPending+"\nmaxPreparingJobs="+maxPreparing+"\nCounters are sampled on the server thread. Shell counter excludes the five final chest/exit writes and engine generation/lighting work.\n");
            server.saveEverything(true,true,true);metrics("final-clean");running=false;Acceptance.marker("PASSED","Two independent owners/pockets; cross-owner denials; mixed lease kinds; cap reductions; repeated cycles; two SIGKILL/reconnects; per-destination cleanup. See multi-budget.csv.");AnyaStorage.LOG.info("ANYA_MULTI_POCKET_NETWORK_OK");server.halt(false);
        });
    }
    private static void isolation()throws Exception{
        check(!PocketService.authorize(a(),room(bid),PocketLeases.Kind.VISIT,false,BasePermissions.BUILD).isEmpty(),"Owner rights leaked to another pocket");
        boolean denied=false;try{new PocketView(a(),room(bid),PocketLeases.Kind.VISIT);}catch(java.io.IOException expected){denied=true;}check(denied,"Foreign view accepted the wrong lease");
        var target=chest(bid);check(!PocketService.physicalAllowed(a(),target),"Foreign physical container accepted");
        var p=PocketService.chestPositions(room(bid)).getFirst();boolean rejected=false;try{RoomEditor.prepare(a(),new BlockHitResult(Vec3.atCenterOf(p),Direction.UP,p,false),true);}catch(java.io.IOException|IllegalArgumentException expected){rejected=true;}check(rejected,"Cross-cell forged build accepted");contents(aid,"Alpha",11);contents(bid,"Beta",17);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
        if(!running||e.getServer()!=server)return;tick++;
        try{
            metrics(null);
            if(step==null){step=STEPS.poll();check(step!=null,"Missing test completion");number++;since=tick;step.start().run();}
            if(tick-since>1800)throw new IllegalStateException("Timed out step "+step.name());
            if(tick-since>=5&&step.ready().get()){step.verify().run();AnyaStorage.LOG.info("ANYA_MULTI_ASSERT {} {}",number,step.name());metrics(step.name());step=null;}
        }catch(Throwable error){running=false;Acceptance.marker("FAILED-multi","step="+(step==null?"none":step.name())+" "+error);AnyaStorage.LOG.error("ANYA_MULTI_FAILED",error);server.halt(false);}
    }
    private static void resume()throws Exception{
        var lines=Files.readAllLines(Acceptance.control().resolve("multi-identities.txt"));aid=UUID.fromString(lines.get(0));bid=UUID.fromString(lines.get(1));server.overworld().getChunk(0,0);
        ha=(StorageEntity)server.overworld().getBlockEntity(HA);hb=(StorageEntity)server.overworld().getBlockEntity(HB);
        check(ha.storageIdentity().equals(UUID.fromString(lines.get(2)))&&hb.storageIdentity().equals(UUID.fromString(lines.get(3))),"Restart changed independent home identities");
        check(room(aid).epoch()==Long.parseLong(lines.get(5))&&room(aid).anchor().entrance()==REPLACEMENT.asLong()&&room(bid).anchor().entrance()==EB.asLong(),"Restart aliased/changed anchors");
        for(UUID id:List.of(aid,bid)){var r=room(id);for(var pos:PocketService.chestPositions(r))PocketService.dimension(server).getChunk(pos.getX()>>4,pos.getZ()>>4);check(PocketRoomLayout.present(PocketService.dimension(server),r).size()==4,"Restart hid original storage");}
        contents(aid,"Alpha",10);contents(bid,"Beta",17);check(PocketService.ownedTicketRequests()==0&&leases().snapshot().isEmpty()&&PocketService.ledger(server).returnCount()==0,"Restart restored ephemeral resource state");
        Acceptance.marker("MULTI_RESTART_PASSED","Separate packaged server process: both owner/hub/pocket identities and named samples preserved; anchor Alpha replaced, Beta untouched; no persisted leases/tickets. Chunks explicitly loaded only for inspection.");AnyaStorage.LOG.info("ANYA_MULTI_POCKET_RESTART_OK");server.halt(false);
    }
    private MultiPocketProbe(){}
}

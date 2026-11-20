package dev.anya.storage.minimal;
import dev.anya.storage.*;
import dev.anya.storage.durable.*;
import dev.anya.storage.pocket.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
/** Budget-specific fixture: require retained ownership every tick and an eventual second retry before releasing a synthetic dependency. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class DirtyCycles {
 private static final String MODE=System.getProperty("anya.minimalDirty","");
 public static final boolean ACTIVE=!MODE.isEmpty();
 private static final TicketType<UUID> TEST=TicketType.create("anya_minimal_dirty_reactivation",UUID::compareTo,60);
 private static final UUID KEY=UUID.fromString("501345c9-8e10-4db3-8007-3b069d4d1993");
 private static final int CYCLES=4;
 private static List<ServerPlayer> players;
 private static List<StorageEntity> hubs;
 private static List<UUID> ids;
 private static boolean started,finished,injected,released,reactivated,testTicket;
 private static int cycle,step;
 private static long begin,heldAt,key,ownedAttempts,staleAttempts,cycleRetired;
 private static ChunkHolder held;
 private static CompletableFuture<Void> flush;
 private static final Properties saved=new Properties();
 private static void check(boolean v,String s){if(!v)throw new IllegalStateException(s);}
 private static ItemStack sample(UUID id,int c){var x=new ItemStack(Items.DIAMOND,13);x.set(DataComponents.CUSTOM_NAME,Component.literal("Minimal dirty "+id+" cycle "+c));return x;}
 private static boolean sampleChunk(LevelChunk c){for(var be:c.getBlockEntities().values())if(be instanceof StorageEntity s&&s.pocketId!=null&&Transactions.same(s.getItem(0),sample(s.pocketId,cycle)))return true;return false;}
 private static Object field(String name)throws Exception{var f=Fixture.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 private static void disableOldFixture()throws Exception{var f=Fixture.class.getDeclaredField("running");f.setAccessible(true);f.setBoolean(null,false);}
 public static void beforeCallback(ServerLevel l,ChunkHolder h,long pos,OwnershipView view){
  if(!MODE.equals("seed")||!started||step!=2)return;
  if(held==h){if(view.anyaPending(pos)==h)ownedAttempts++;else staleAttempts++;}
  if(injected||view.anyaPending(pos)!=h||!h.isReadyForSaving()||h.getSaveSyncFuture().isCompletedExceptionally())return;
  if(!(h.getLatestChunk() instanceof LevelChunk chunk)||!sampleChunk(chunk))return;
  h.increaseGenerationRefCount();held=h;key=pos;heldAt=l.getServer().getTickCount();injected=true;ownedAttempts++;
  Trace.note("MINIMAL_OWNED_HOLD",null,"cycle="+cycle+" key="+key+" refs="+h.getGenerationRefCount()+" mode="+(cycle%2==0?"hold":"reactivate"));
 }
 private static void playerInventory(){
  if(players==null)return;for(var p:players){
   var s=p.getInventory().getItem(5);check(s.is(Items.EMERALD)&&s.getCount()==11&&s.getHoverName().getString().equals("Durable preparation inventory"),"Player sample changed");
   for(int i=0;i<p.getInventory().getContainerSize();i++)check(!p.getInventory().getItem(i).is(Items.DIAMOND),"Chest sample leaked into fixture player");
  }
 }
 private static boolean drained(ServerLevel l){var v=(OwnershipView)(Object)l.getChunkSource().chunkMap;return PocketService.ownedTicketRequests()==0&&l.getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0;}
 private static int inspect(MinecraftServer server,int expected,boolean loading)throws Exception{
  var level=PocketService.dimension(server);var rooms=PocketService.ledger(server).pockets();check(rooms.size()==8,"Lost or duplicated room allocation");int total=0;
  for(var r:rooms){
   check(r.stage()==PocketLedger.Stage.READY&&Objects.equals(saved.getProperty(r.id().toString()),r.cell()+"/"+r.owner()),"Room ID/cell/owner changed");
   var positions=PocketService.chestPositions(r);check(positions.size()==4,"Layout changed");
   for(int c=0;c<positions.size();c++){
    var pos=positions.get(c);if(loading)level.getChunk(pos.getX()>>4,pos.getZ()>>4);else check(level.hasChunkAt(pos),"Inspection would load a missing chunk");
    check(level.getBlockEntity(pos) instanceof StorageEntity,"Missing physical chest");var chest=(StorageEntity)level.getBlockEntity(pos);
    check(r.id().equals(chest.pocketId)&&r.owner().equals(chest.owner),"Chest identity changed");
    if(expected<0)check(chest.isEmpty(),"Seed would overwrite inventory");
    else if(c==0){check(Transactions.same(chest.getItem(0),sample(r.id(),expected)),"Missing/duplicated/wrong-component dirty sample");for(int slot=1;slot<chest.getContainerSize();slot++)check(chest.getItem(slot).isEmpty(),"Duplicate item slot");total+=13;}
    else check(chest.isEmpty(),"Duplicate sample in other chest");
   }
  }
  if(expected>=0)check(total==104,"Dirty inventory total is not 104");return total;
 }
 private static void dirty(MinecraftServer s)throws Exception{
  inspect(s,cycle-1,false);var l=PocketService.dimension(s);
  for(var r:PocketService.ledger(s).pockets()){
   var p=PocketService.chestPositions(r).getFirst();((StorageEntity)l.getBlockEntity(p)).setItem(0,sample(r.id(),cycle));check(l.getChunkAt(p).isUnsaved(),"Dirty flag missing");
  }
  injected=released=reactivated=testTicket=false;held=null;ownedAttempts=staleAttempts=0;cycleRetired=LeanMetrics.retired;flush=null;
  Trace.note("MINIMAL_DIRTY_WRITE",null,"cycle="+cycle+" conserved=104 no saveEverything after sample");
  for(var p:players){p.closeContainer();if(PocketService.inPocket(p.level()))PocketService.recover(p);}
  step=1;Fixture.phase="dirty-return-"+cycle;
 }
 @SuppressWarnings("unchecked") @SubscribeEvent(priority=EventPriority.LOWEST) public static void pre(ServerTickEvent.Pre event)throws Exception{
  if(!ACTIVE||finished)return;var server=event.getServer();var l=PocketService.dimension(server);if(l==null)return;long now=server.getTickCount();
  try{
   check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"Disposable marker missing");check(now<3800,"Bounded dirty-cycle tick deadline");
   if(MODE.equals("verify")){
    if(!started&&now>=20){try(var in=Files.newInputStream(Path.of("minimal-identities.properties"))){saved.load(in);}inspect(server,CYCLES-1,true);started=true;begin=now;Trace.marker("DIRTY_RESTART_ITEMS","104 correctly named items, 8 room identities, 32 chest identities checked on fresh process; inspection intentionally loads");}
    if(started&&now-begin>=650&&drained(l)){Trace.audit(server);Trace.marker("PASSED","four cycles and forced restart retained 104 named items and exact final engine queues");finished=true;server.halt(false);}return;
   }
   if(!started){
    if(!Fixture.phase.equals("visit-steady"))return;
    disableOldFixture();players=(List<ServerPlayer>)field("PLAYERS");hubs=(List<StorageEntity>)field("HUBS");ids=(List<UUID>)field("IDS");
    for(var r:PocketService.ledger(server).pockets())saved.setProperty(r.id().toString(),r.cell()+"/"+r.owner());
    try(var out=Files.newOutputStream(Path.of("minimal-identities.properties"))){saved.store(out,"Disposable fixed identities");}
    started=true;begin=now;dirty(server);return;
   }
   playerInventory();
   if(step==1&&players.stream().noneMatch(p->PocketService.inPocket(p.level()))&&PocketService.ledger(server).returnCount()==0){step=2;Fixture.phase="dirty-drain-"+cycle;}
   if(step==2&&injected&&!released){
    var v=(OwnershipView)(Object)l.getChunkSource().chunkMap;
    check(held.getGenerationRefCount()>0&&held.getLatestChunk() instanceof LevelChunk chunk&&sampleChunk(chunk),"Held sample lost before release");
    if(cycle%2==0)check(v.anyaPending(key)==held,"Owned save disappeared before release");
    if(cycle%2==1&&now-heldAt>=5&&!testTicket){l.getChunkSource().addRegionTicket(TEST,new ChunkPos(key),2,KEY);testTicket=true;}
    if(testTicket&&v.anyaActive(key)==held)reactivated=true;
    if(now-heldAt==20)Trace.note("BUDGET_RETRY_OBSERVATION",null,"cycle="+cycle+" pending_owns="+(v.anyaPending(key)==held)+" owner_attempts="+ownedAttempts+" callbacks="+v.anyaCallbackCount());
    check(now-heldAt<400,"Owned callback was not serviced within bounded 400-tick fixture interval");
    if(now-heldAt>=20&&(cycle%2==1||ownedAttempts>1)){
     if(cycle%2==0)check(v.anyaPending(key)==held&&ownedAttempts>1,"Still-owned original callback did not remain pending");else check(reactivated,"Actual holder did not reactivate");
     held.decreaseGenerationRefCount();held=null;released=true;if(testTicket){l.getChunkSource().removeRegionTicket(TEST,new ChunkPos(key),2,KEY);testTicket=false;}
     Trace.note("MINIMAL_BALANCED_RELEASE",null,"cycle="+cycle+" held_ticks="+(now-heldAt)+" owner_attempts="+ownedAttempts+" stale_attempts="+staleAttempts+" reactivated="+reactivated+" no deferral queue");
    }
   }
   if(step==2&&injected&&released&&drained(l)){
    if(flush==null){check(l.getChunkSource().chunkMap.chunkScanner() instanceof IOWorker,"Pinned worker unavailable");flush=((IOWorker)l.getChunkSource().chunkMap.chunkScanner()).synchronize(true);return;}
    if(!flush.isDone())return;flush.join();Trace.audit(server);
    Trace.note("MINIMAL_CYCLE_DRAINED",null,"cycle="+cycle+" pending=0 callbacks=0 requests=0 cache=0 retired_delta="+(LeanMetrics.retired-cycleRetired));
    if(++cycle==CYCLES){Trace.marker("CUT_AFTER_DIRTY_CYCLES","four cycles; 104 items; engine queues drained and explicit flush complete; Runtime.halt(77) before clean shutdown");Runtime.getRuntime().halt(77);}
    for(int i=0;i<players.size();i++)PocketService.remote(players.get(i),players.get(i).getInventory().getItem(0),hubs.get(i));step=3;Fixture.phase="dirty-reopen-"+cycle;
   }
   if(step==3&&players.stream().allMatch(p->p.containerMenu instanceof StoreMenu m&&m.capacity()==216)){
    inspect(server,cycle-1,false);dirty(server);
   }
  }catch(Throwable t){finished=true;Trace.marker("FAILED","Minimal dirty cycle "+cycle+" step="+step+" "+t);AnyaStorage.LOG.error("ANYA_MINIMAL_DIRTY_FAILED",t);server.halt(false);}
 }
}

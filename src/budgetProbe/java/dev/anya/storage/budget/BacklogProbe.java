package dev.anya.storage.budget;
import dev.anya.storage.*;
import dev.anya.storage.durable.*;
import dev.anya.storage.minimal.OwnershipView;
import dev.anya.storage.pocket.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
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
/** New fixture: stop or force-save with genuinely dirty queued chest chunks, not a pre-drained world. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class BacklogProbe {
 private static final String MODE=System.getProperty("anya.budgetBacklog","");
 private static boolean started,finished,draining;
 private static long since,forceBefore;
 private static List<ServerPlayer> players;
 private static CompletableFuture<Void> flush;
 private static final Properties identities=new Properties();
 private static void check(boolean v,String text){if(!v)throw new IllegalStateException(text);}
 private static ItemStack sample(UUID id){var s=new ItemStack(Items.DIAMOND,13);s.set(DataComponents.CUSTOM_NAME,Component.literal("Queued budget "+id));return s;}
 private static Object field(String name)throws Exception{var f=Fixture.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 private static boolean drained(ServerLevel l){var v=(OwnershipView)(Object)l.getChunkSource().chunkMap;return PocketService.ownedTicketRequests()==0&&l.getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0;}
 private static void checkItems(MinecraftServer s,boolean load)throws Exception{
  var l=PocketService.dimension(s);var rooms=PocketService.ledger(s).pockets();check(rooms.size()==8,"Room allocation count changed");int n=0;
  for(var r:rooms){check(Objects.equals(identities.getProperty(r.id().toString()),r.cell()+"/"+r.owner()),"Room identity changed");int c=0;
   for(var p:PocketService.chestPositions(r)){
    if(load)l.getChunk(p.getX()>>4,p.getZ()>>4);else check(l.hasChunkAt(p),"Inspection would load chunk");
    check(l.getBlockEntity(p) instanceof StorageEntity,"Chest absent");var chest=(StorageEntity)l.getBlockEntity(p);check(r.id().equals(chest.pocketId)&&r.owner().equals(chest.owner),"Chest identity mismatch");
    if(c++==0){check(Transactions.same(chest.getItem(0),sample(r.id())),"Queued sample lost/duplicated");n+=13;for(int i=1;i<chest.getContainerSize();i++)check(chest.getItem(i).isEmpty(),"Unexpected extra item");}else check(chest.isEmpty(),"Other chest unexpectedly filled");
   }
  }check(n==104,"Sample total changed");
 }
 @SuppressWarnings("unchecked") @SubscribeEvent(priority=EventPriority.LOWEST) public static void pre(ServerTickEvent.Pre e){
  if(MODE.isEmpty()||finished)return;var s=e.getServer();var l=PocketService.dimension(s);if(l==null)return;
  try{
   check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"Disposable marker required");check(s.getTickCount()<2600,"Backlog fixture tick ceiling");
   if(MODE.equals("verify")){
    if(!started&&s.getTickCount()>20){try(var in=Files.newInputStream(Path.of("budget-identities.properties"))){identities.load(in);}checkItems(s,true);started=true;since=s.getTickCount();Trace.marker("BACKLOG_RESTART_ITEMS","104 named items,8 room identities,32 chest identities verified");}
    if(started&&s.getTickCount()-since>=650&&drained(l)){Trace.audit(s);Trace.marker("PASSED","queued shutdown/force-save restart and cleanup");finished=true;s.halt(false);}return;
   }
   if(!started){
    if(!Fixture.phase.equals("visit-steady"))return;
    var f=Fixture.class.getDeclaredField("running");f.setAccessible(true);f.setBoolean(null,false);players=(List<ServerPlayer>)field("PLAYERS");
    for(var r:PocketService.ledger(s).pockets()){
     identities.setProperty(r.id().toString(),r.cell()+"/"+r.owner());var p=PocketService.chestPositions(r).getFirst();var chest=(StorageEntity)l.getBlockEntity(p);check(chest.isEmpty(),"Refusing overwrite");chest.setItem(0,sample(r.id()));check(l.getChunkAt(p).isUnsaved(),"Dirty chunk flag absent");
    }
    try(var out=Files.newOutputStream(Path.of("budget-identities.properties"))){identities.store(out,"Disposable backlog fixture");}
    checkItems(s,false);started=true;for(var p:players)PocketService.recover(p);Fixture.phase="budget-backlog";return;
   }
   if(draining){
    if(!drained(l))return;
    if(flush==null){flush=((IOWorker)l.getChunkSource().chunkMap.chunkScanner()).synchronize(true);return;}
    if(!flush.isDone())return;flush.join();check(BudgetState.pocket().totalForce>forceBefore,"Explicit save did not bypass backlog budget");Trace.audit(s);
    Trace.marker("CUT_AFTER_BUDGET_FORCE","explicit save bypass; real engine queues empty and flush succeeded; Runtime.halt(77)");Runtime.getRuntime().halt(77);return;
   }
   if(PocketService.ledger(s).returnCount()!=0)return;
   var v=(OwnershipView)(Object)l.getChunkSource().chunkMap;int dirty=0;
   for(var r:PocketService.ledger(s).pockets()){
    var pos=PocketService.chestPositions(r).getFirst();var h=v.anyaPending(new ChunkPos(pos).toLong());
    if(h!=null&&h.getLatestChunk() instanceof LevelChunk chunk&&chunk.isUnsaved()&&chunk.getBlockEntity(pos) instanceof StorageEntity chest&&Transactions.same(chest.getItem(0),sample(r.id())))dirty++;
   }
   if(dirty>0&&v.anyaCallbackCount()>0&&BudgetState.pocket().totalYields>0){
    Trace.marker("BACKLOG_PRESENT","dirty_sample_chunks="+dirty+" queued_callbacks="+v.anyaCallbackCount()+" pending="+v.anyaPendingCount()+" requested_stop_without_saveEverything=true\n");
    if(MODE.equals("stop")){Trace.marker("STOP_WITH_BACKLOG","no manual pre-save or queue drain");finished=true;s.halt(false);}
    else{forceBefore=BudgetState.pocket().totalForce;l.getChunkSource().save(true);draining=true;Trace.note("EXPLICIT_SAVE_BYPASS",null,"forced original saveAll path, including original flush");}
   }
  }catch(Throwable t){finished=true;Trace.marker("FAILED","backlog "+MODE+" "+t);AnyaStorage.LOG.error("ANYA_BUDGET_BACKLOG_FAILED",t);s.halt(false);}
 }
}

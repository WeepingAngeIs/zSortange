package dev.anya.storage.durable;
import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.*;
import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
@Mod("anyadurableprobe")
@EventBusSubscriber(modid="anyadurableprobe")
public final class Trace {
 public static long tick,prepareNanos,serviceNanos,snapshotNanos,ledgerNanos,nonblockingMisses,snapshots;
 private static long tickStart,serviceStart,prepareStart,callbacks,refsOnly,futureOnly,both,ready,repeated,totalCallbacks,totalRefs,totalRepeated;
 private static int examples;
 private static Object lastHolder,lastFuture;
 private static MinecraftServer server;
 private static BufferedWriter rows,notes,unloads;
 private static final Set<String> APPLIED=new HashSet<>();
 public Trace(){if(!"true".equals(System.getenv("GITHUB_ACTIONS")))throw new IllegalStateException("Isolated disposable CI module only");}
 public static Path output(){return Path.of(System.getProperty("anya.control"));}
 public static void marker(String name,String text){try{Files.createDirectories(output());Files.writeString(output().resolve(name),text);}catch(IOException e){throw new IllegalStateException(e);}}
 public static synchronized void note(String kind,UUID id,String detail){
  try{if(notes==null)return;notes.write(tick+"\t"+kind+"\t"+id+"\t"+Thread.currentThread().getName()+"\t"+detail+"\n");notes.flush();}catch(IOException e){throw new IllegalStateException(e);}
 }
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void pre(ServerTickEvent.Pre e)throws IOException{
  server=e.getServer();if(rows==null){Files.createDirectories(output());rows=Files.newBufferedWriter(output().resolve("durable-ticks.csv"));notes=Files.newBufferedWriter(output().resolve("durable-events.tsv"));unloads=Files.newBufferedWriter(output().resolve("unload-condition.tsv"));
   rows.write("tick,phase,event_ns,service_ns,prepare_ns,snapshot_ns,ledger_ns,snapshots,misses,cache,requests,heap_used,heap_committed,callbacks,refs_only,future_only,both,ready,repeated_completed_future\n");
   unloads.write("tick\tchunk\tgeneration_refs\tsave_future_done\tsame_completed_future\tticket_level\trepeats_this_tick\n");
  }
  tick++;tickStart=System.nanoTime();prepareNanos=serviceNanos=snapshotNanos=ledgerNanos=snapshots=nonblockingMisses=0;callbacks=refsOnly=futureOnly=both=ready=repeated=0;lastHolder=lastFuture=null;prepareStart=0;
 }
 public static void prepareBegin(){prepareStart=System.nanoTime();}
 public static void prepareEnd(){if(prepareStart!=0){prepareNanos+=System.nanoTime()-prepareStart;prepareStart=0;}}
 public static void serviceBegin(MinecraftServer s){server=s;serviceStart=System.nanoTime();}
 public static void serviceEnd(){prepareEnd();serviceNanos+=System.nanoTime()-serviceStart;}
 public static void unload(ServerLevel level,ChunkHolder holder,long key){
  if(server==null||!PocketService.inPocket(level))return;
  int refs=holder.getGenerationRefCount();Object f=holder.getSaveSyncFuture();boolean done=holder.getSaveSyncFuture().isDone();boolean repeat=lastHolder==holder&&lastFuture==f&&done;lastHolder=holder;lastFuture=f;
  callbacks++;totalCallbacks++;if(refs!=0&&done){refsOnly++;totalRefs++;}else if(refs==0&&!done)futureOnly++;else if(refs!=0)both++;else ready++;
  if(repeat&&refs!=0){repeated++;totalRepeated++;}
  if(refs!=0&&done&&examples<32&&(repeated==16||repeated==256||repeated==4096))try{
   unloads.write(tick+"\t"+new ChunkPos(key)+"\t"+refs+"\t"+done+"\t"+repeat+"\t"+holder.getTicketLevel()+"\t"+repeated+"\n");unloads.flush();examples++;
  }catch(IOException e){throw new IllegalStateException(e);}
 }
 public static <T> void ticket(ServerChunkCache cache,TicketType<T> type,ChunkPos pos,int r,T key,boolean add){
  if(type!=Preparation.field("TICKET"))return;
  if(r!=2&&!(r==0&&Boolean.getBoolean("anya.remoteTickets")&&PocketService.inPocket(cache.level)))throw new IllegalStateException("Unexpected ticket strength");
  String value=cache.level.dimension().location()+"/"+pos.toLong()+"/"+key+"/"+(33-r);if(add)APPLIED.add(value);else APPLIED.remove(value);
 }
 private static Field field(Class<?> cl,String name)throws Exception{for(Class<?> c=cl;c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
 public static void audit(MinecraftServer s){try{
  Set<String> actual=new HashSet<>();Object type=Preparation.field("TICKET");
  for(var level:s.getAllLevels()){
   Object dm=field(level.getChunkSource().getClass(),"distanceManager").get(level.getChunkSource());
   Map<?,?> sets=(Map<?,?>)field(dm.getClass(),"tickets").get(dm);
   for(var en:sets.entrySet())for(Object raw:(Iterable<?>)en.getValue()){
    Ticket<?> t=(Ticket<?>)raw;if(t.getType()==type)actual.add(level.dimension().location()+"/"+en.getKey()+"/"+field(t.getClass(),"key").get(t)+"/"+t.getTicketLevel());
   }
  }if(!actual.equals(APPLIED))throw new IllegalStateException("Actual engine ticket mismatch: "+actual+" != "+APPLIED);
 }catch(Exception e){throw new IllegalStateException(e);}}
 @SubscribeEvent(priority=EventPriority.LOW) public static void post(ServerTickEvent.Post e)throws IOException{
  if(rows==null)return;var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();var level=PocketService.dimension(e.getServer());int cache=level==null?0:level.getChunkSource().getLoadedChunksCount();
  rows.write(tick+","+Fixture.phase+","+(System.nanoTime()-tickStart)+","+serviceNanos+","+prepareNanos+","+snapshotNanos+","+ledgerNanos+","+snapshots+","+nonblockingMisses+","+cache+","+PocketService.ownedTicketRequests()+","+heap.getUsed()+","+heap.getCommitted()+","+callbacks+","+refsOnly+","+futureOnly+","+both+","+ready+","+repeated+"\n");
  if(tick%20==0){rows.flush();marker("heartbeat",tick+","+Fixture.phase);if(cache>15000||heap.getUsed()>heap.getMax()*.92){marker("FAILED","resource guard");e.getServer().halt(false);}}
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void stopping(ServerStoppingEvent e){
  audit(e.getServer());if(!APPLIED.isEmpty()||!Preparation.JOBS.isEmpty())throw new IllegalStateException("Stop leaked exact tickets or jobs");
  marker("stop-audit","exact_engine_owned_tickets=0 jobs=0 callbacks="+totalCallbacks+" refs_only="+totalRefs+" repeated_same_completed_future="+totalRepeated+"\n");
 }
 @SubscribeEvent public static void stopped(ServerStoppedEvent e)throws IOException{if(rows!=null){rows.close();notes.close();unloads.close();rows=notes=unloads=null;}}
}

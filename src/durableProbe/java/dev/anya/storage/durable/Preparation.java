package dev.anya.storage.durable;
import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
/** Experimental replacement only at the existing prepare save barrier. All state/world work is main-thread. */
public final class Preparation {
 public static boolean enabled(){return Boolean.getBoolean("anya.nonblockingPrepare");}
 public static final Map<UUID,Job> JOBS=new HashMap<>();
 public static final class Job {
  public final PocketLedger.Pocket identity; public final long started;
  public final List<ChunkPos> positions=new ArrayList<>();
  public final List<CompletableFuture<Void>> writes=new ArrayList<>();
  public final CompletableFuture<Void> testGate=new CompletableFuture<>();
  public int snapshot;public DurableFence fence;public volatile boolean actualFlushed;
  Job(PocketLedger.Pocket p){identity=p;started=Trace.tick;
   for(int x=(p.x()-1)>>4;x<=(p.x()+32)>>4;x++)for(int z=(p.z()-1)>>4;z<=(p.z()+32)>>4;z++)positions.add(new ChunkPos(x,z));
  }
 }
 private static final Map<String,Field> FIELDS=new HashMap<>();
 public static Object field(String name){try{Field f=FIELDS.computeIfAbsent(name,n->{try{Field q=PocketService.class.getDeclaredField(n);q.setAccessible(true);return q;}catch(Exception e){throw new IllegalStateException(e);}});return f.get(null);}catch(Exception e){throw new IllegalStateException(e);}}
 @SuppressWarnings("unchecked") private static Map<UUID,?> preparing(){return (Map<UUID,?>)field("PREPARING");}
 private static PocketLeases leases(){return (PocketLeases)field("LEASES");}
 public static void main(MinecraftServer s){if(!s.isSameThread())throw new IllegalStateException("Unsafe off-thread preparation access");}
 public static boolean has(UUID id){return JOBS.containsKey(id);}
 public static void begin(MinecraftServer s,PocketLedger.Pocket p)throws IOException{
  main(s);if(JOBS.containsKey(p.id())||JOBS.size()>=8)throw new IOException("Duplicate or over-budget preparation fence");
  var current=PocketService.ledger(s).get(p.id());
  if(current==null||current.stage()!=PocketLedger.Stage.BUILDING||!preparing().containsKey(p.id()))throw new IOException("Missing write-ahead BUILDING marker");
  JOBS.put(p.id(),new Job(current));Trace.note("SAVE_STARTED",p.id(),"16 bounded snapshots, one per server tick");
 }
 private static boolean valid(MinecraftServer s,Job j)throws IOException{
  var p=PocketService.ledger(s).get(j.identity.id());
  if(p==null||!p.equals(j.identity)||!preparing().containsKey(p.id()))return false;
  long clock=(long)field("clock");
  for(var l:leases().snapshot())if(l.pocket().equals(p.id())&&l.epoch()==p.epoch()&&clock<l.deadline()){
   var player=s.getPlayerList().getPlayer(l.player());
   if(player!=null&&PocketService.authorize(player,p,l.kind(),true,0).isEmpty())return true;
  }return false;
 }
 public static boolean poll(MinecraftServer s,PocketLedger.Pocket ignored)throws IOException{
  main(s);Job j=JOBS.get(ignored.id());if(j==null)throw new IOException("Preparation token missing");
  if(!valid(s,j)||Trace.tick-j.started>400){cancel(j.identity.id(),"identity/authorization/deadline changed");throw new IOException("Preparation cancelled without publishing READY");}
  var level=PocketService.dimension(s);var map=level.getChunkSource().chunkMap;
  try{
   if(j.snapshot<j.positions.size()){
    ChunkPos pos=j.positions.get(j.snapshot);var chunk=level.getChunkSource().getChunkNow(pos.x,pos.z);var holder=map.getVisibleChunkIfPresent(pos.toLong());
    if(chunk==null||holder==null||!holder.isReadyForSaving())return false;
    if(holder.getSaveSyncFuture().isCompletedExceptionally())throw new IOException("Chunk save dependencies failed");
    long before=System.nanoTime();
    var tag=ChunkSerializer.write(level,chunk);
    NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk,level,tag));
    if(tag.sizeInBytes()>2*1024*1024)throw new IOException("Prototype snapshot exceeds two MiB limit");
    // Deep detachment prevents IO tasks from observing later game-thread NBT mutations.
    var detached=tag.copy();Fixture.snapshotFault(detached);j.writes.add(map.write(pos,detached));j.snapshot++;
    Trace.snapshotNanos+=System.nanoTime()-before;Trace.snapshots++;
    if(j.snapshot==16){
     if(!(map.chunkScanner() instanceof IOWorker worker))throw new IOException("Pinned IOWorker completion API unavailable");
     j.fence=new DurableFence(j.writes,()->{
      main(s);Trace.note("FLUSH_SUBMITTED",j.identity.id(),"all 16 write receipts succeeded");
      Fixture.beforeFlush(j.identity.id());CompletableFuture<Void> actual=worker.synchronize(true);
      actual.thenRun(()->j.actualFlushed=true); // scalar evidence only, never world/ledger access
      return Fixture.hold(j.identity.id())?actual.thenCombine(j.testGate,(a,b)->null):actual;
     });
    }
    return false;
   }
   if(j.fence.poll()!=DurableFence.State.DONE)return false;
   // Recheck on the server thread immediately before durable publication. No completion callback publishes.
   if(!valid(s,j))throw new IOException("Stale completion refused");
   Trace.note("DURABLE_CONFIRMED",j.identity.id(),"all writes and RegionFile.force barrier completed");
   Fixture.beforeReady(j.identity.id());
   long before=System.nanoTime();PocketService.ledger(s).stage(j.identity.id(),PocketLedger.Stage.BUILDING,PocketLedger.Stage.READY);Trace.ledgerNanos+=System.nanoTime()-before;
   preparing().remove(j.identity.id());JOBS.remove(j.identity.id());Trace.note("READY",j.identity.id(),"ledger published after durability; ledger commit still synchronous");return true;
  }catch(RuntimeException e){cancel(j.identity.id(),"write/flush completion failed: "+e);throw new IOException("Unconfirmed durable preparation; BUILDING retained",e);}
 }
 public static void cancel(UUID id,String why){Job j=JOBS.remove(id);if(j!=null){if(j.fence!=null)j.fence.cancel();Trace.note("CANCELLED",id,why);}}
 public static void sweep(MinecraftServer s){main(s);for(UUID id:new ArrayList<>(JOBS.keySet()))if(!preparing().containsKey(id)||!leases().active().contains(id))cancel(id,"last user released");}
 public static void stop(MinecraftServer s){main(s);for(UUID id:new ArrayList<>(JOBS.keySet()))cancel(id,"server stopping; no late publication");}
 private Preparation(){}
}

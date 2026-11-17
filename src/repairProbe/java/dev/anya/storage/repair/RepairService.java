package dev.anya.storage.repair;

import dev.anya.storage.*;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.pocket.*;
import dev.anya.storage.repair.mixin.WorkerAccess;
import dev.anya.storage.repair.mixin.RegionAccess;
import com.mojang.datafixers.util.Either;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only full-chunk WAL. No player-inventory or transfer-command replay. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class RepairService {
 public static final boolean ENABLED=Boolean.getBoolean("anya.repairJournal");
 public static volatile MinecraftServer server;
 public static volatile SnapshotJournal journal;
 public static volatile boolean globalBad,stopping;
 private static final ConcurrentMap<Long,AtomicInteger> inFlight=new ConcurrentHashMap<>();
 private static final ConcurrentMap<Long,AtomicLong> epochs=new ConcurrentHashMap<>();
 private static final ConcurrentMap<Long,Set<UUID>> roomIds=new ConcurrentHashMap<>();
 private static final ConcurrentMap<Long,byte[]> emergency=new ConcurrentHashMap<>();
 private static final Map<CompoundTag,SnapshotJournal.Record> replay=Collections.synchronizedMap(new IdentityHashMap<>());
 private static final LinkedHashMap<Long,Retry> retries=new LinkedHashMap<>();
 private static final Set<Long> transientFaults=ConcurrentHashMap.newKeySet();
 public static volatile String fault="",cut="";
 public static volatile long faultKey=Long.MIN_VALUE;
 public static final AtomicLong writes=new AtomicLong(),failures=new AtomicLong(),journalNs=new AtomicLong(),regionNs=new AtomicLong(),repairs=new AtomicLong(),resets=new AtomicLong();
 public static final AtomicInteger ioFaults=new AtomicInteger();
 private static final ThreadLocal<Boolean> restoring=ThreadLocal.withInitial(()->false);
 private static final class Retry {final long key;final UUID token;int attempts;long due;CompletableFuture<Void> future;Retry(long k,UUID t,long now){key=k;token=t;due=now;}}
 public static boolean target(RegionStorageInfo info){return info.dimension().equals(PocketService.DIMENSION)&&info.type().equals("chunk");}
 public static boolean supported(CompoundTag tag){if(tag==null)return false;var list=tag.getList("block_entities",10);for(int i=0;i<list.size();i++)if(list.getCompound(i).hasUUID("PocketId"))return true;return false;}
 private static Set<UUID> ids(CompoundTag tag){Set<UUID> out=new HashSet<>();var list=tag.getList("block_entities",10);for(int i=0;i<list.size();i++)if(list.getCompound(i).hasUUID("PocketId"))out.add(list.getCompound(i).getUUID("PocketId"));return Set.copyOf(out);}
 public static byte[] encode(CompoundTag tag)throws IOException {if(tag.sizeInBytes()>SnapshotJournal.MAX_BYTES)throw new IOException("NBT bound exceeded");var bytes=new ByteArrayOutputStream();NbtIo.write(tag,new DataOutputStream(bytes));if(bytes.size()>SnapshotJournal.MAX_BYTES)throw new IOException("Encoded snapshot bound exceeded");return bytes.toByteArray();}
 public static CompoundTag decode(byte[] bytes)throws IOException {try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){var tag=NbtIo.read(in,NbtAccounter.create(SnapshotJournal.MAX_BYTES*4L));if(in.available()!=0)throw new IOException("Trailing NBT");return tag;}}
 public static void arm(String mode,long key){fault=mode;faultKey=key;ioFaults.set(0);Trace.note("REPAIR_FAULT_ARMED",null,mode+" key="+key);}
 public static void ioFault(RegionFileStorage storage,ChunkPos pos)throws IOException {
  if(!target(storage.info())||pos.toLong()!=faultKey)return;
  if(fault.equals("persistent")||fault.equals("dual")||fault.equals("write")&&ioFaults.get()==0){ioFaults.incrementAndGet();Trace.note("REPAIR_REAL_WRITE_FAILURE",null,"key="+faultKey+" thread="+Thread.currentThread().getName()+" repair="+restoring.get());throw new IOException("Injected ordinary region write failure");}
 }
 public static void flushFault(RegionFileStorage storage)throws IOException {if(target(storage.info())&&fault.equals("flush")&&ioFaults.compareAndSet(0,1)){Trace.note("REPAIR_REAL_FLUSH_FAILURE",null,Thread.currentThread().getName());throw new IOException("Injected region flush failure");}}
 public static boolean blocked(long key){var count=inFlight.get(key);return ENABLED&&(globalBad||transientFaults.contains(key)||count!=null&&count.get()>0||journal!=null&&journal.blocked(key));}
 public static boolean roomBlocked(UUID id){if(!ENABLED)return false;if(globalBad)return true;for(var en:roomIds.entrySet())if(en.getValue().contains(id)&&blocked(en.getKey()))return true;return false;}
 public static boolean containerBlocked(Container c){return c instanceof StorageEntity s&&s.pocketId!=null&&s.getLevel()!=null&&PocketService.inPocket(s.getLevel())&&blocked(new ChunkPos(s.getBlockPos()).toLong());}
 public static long epoch(Container c){if(!(c instanceof StorageEntity s)||s.pocketId==null)return 0;return epochs.computeIfAbsent(new ChunkPos(s.getBlockPos()).toLong(),x->new AtomicLong()).get();}
 private static void bump(long key){epochs.computeIfAbsent(key,x->new AtomicLong()).incrementAndGet();}
 public static void capture(IOWorker worker,ChunkPos pos,CompoundTag tag){
  if(!ENABLED||!target(worker.storageInfo())||!supported(tag))return;
  if(server==null||!server.isSameThread())throw new IllegalStateException("Snapshot submission off world owner thread");
  if(journal==null)globalBad=true;roomIds.put(pos.toLong(),ids(tag));inFlight.computeIfAbsent(pos.toLong(),x->new AtomicInteger()).incrementAndGet();bump(pos.toLong());
 }
 public static void receipt(IOWorker worker,ChunkPos pos,CompoundTag tag,CompletableFuture<Void> done){
  if(!ENABLED||!target(worker.storageInfo())||!supported(tag))return;
  done.whenComplete((v,error)->{var count=inFlight.get(pos.toLong());if(count!=null)count.decrementAndGet();if(error!=null)transientFaults.add(pos.toLong());bump(pos.toLong());});
 }
 public static Optional<CompoundTag> overlay(IOWorker worker,ChunkPos pos)throws IOException {
  if(!ENABLED||!target(worker.storageInfo())||journal==null)return Optional.empty();var r=journal.get(pos.toLong());
  if(r==null||!journal.blocked(pos.toLong()))return Optional.empty();var tag=decode(r.payload());
  if(tag.getInt("xPos")!=pos.x||tag.getInt("zPos")!=pos.z)throw new IOException("Snapshot coordinate mismatch");return Optional.of(tag);
 }
 public static void write(RegionFileStorage storage,ChunkPos pos,CompoundTag tag)throws IOException {
  if(!ENABLED||!target(storage.info())||!supported(tag)){((RegionAccess)(Object)storage).anyaWrite(pos,tag);return;}
  if(server!=null&&server.isSameThread())throw new IllegalStateException("Region IO worker required");
  SnapshotJournal.Record r=null;boolean repair=false;byte[] data=encode(tag);long start=System.nanoTime();
  try{
   if(globalBad||journal==null)throw new IOException("Journal needs inspection; no snapshot overwrite allowed");
   r=replay.remove(tag);repair=r!=null;restoring.set(repair);
   if(repair){var now=journal.get(pos.toLong());if(now==null||!now.token().equals(r.token()))throw new IOException("Stale repair token");}
   else {if(fault.equals("dual")&&pos.toLong()==faultKey)throw new IOException("Injected journal publication failure: newest copy not durable");r=journal.pending(pos.toLong(),data);}
   journalNs.addAndGet(System.nanoTime()-start);boundary("after-journal",pos);
   long region=System.nanoTime();((RegionAccess)(Object)storage).anyaWrite(pos,tag);storage.flush();writes.incrementAndGet();
   if(!tag.equals(storage.read(pos)))throw new IOException("Region read-back mismatch");regionNs.addAndGet(System.nanoTime()-region);
   boundary(repair?"repair-after-region":"after-region",pos);journal.applied(pos.toLong(),r.token(),repair);if(repair)repairs.incrementAndGet();
   boundary(repair?"repair-after-proof":"after-proof",pos);emergency.remove(pos.toLong());
  }catch(IOException|RuntimeException e){
   failures.incrementAndGet();transientFaults.add(pos.toLong());
   if(r!=null){try{journal.lock(pos.toLong(),r.token());}catch(Exception nested){globalBad=true;e.addSuppressed(nested);}}
   else {
    globalBad=true;if(emergency.size()<64)emergency.put(pos.toLong(),data);
    try{if(journal!=null)journal.failClosed("No confirmed latest journal snapshot: "+e);}catch(Exception nested){e.addSuppressed(nested);}
    // Do not remove the original save obligation when the secondary journal fails. Try the original store/flush, but never call it journal-protected.
    try{((RegionAccess)(Object)storage).anyaWrite(pos,tag);storage.flush();Trace.note("REPAIR_PRIMARY_ONLY",null,"key="+pos.toLong()+" journal unavailable; remains quarantined");}catch(Exception primary){e.addSuppressed(primary);}
   }
   Trace.note("REPAIR_WRITE_UNCONFIRMED",null,"key="+pos.toLong()+" snapshotDurable="+(r!=null)+" "+e);
   if(e instanceof IOException io)throw io;throw new IOException(e);
  }finally{restoring.remove();bump(pos.toLong());}
 }
 private static void boundary(String point,ChunkPos pos){if(cut.equals(point)&&pos.toLong()==faultKey){Trace.marker("CUT_"+point,"key="+pos.toLong()+" snapshotToken="+journal.get(pos.toLong()).token()+"; Runtime.halt(77)");Runtime.getRuntime().halt(77);}}
 public static IOWorker worker(){return (IOWorker)PocketService.dimension(server).getChunkSource().chunkMap.chunkScanner();}
 private static void validate(SnapshotJournal.Record r)throws IOException {
  if(!server.isSameThread())throw new IOException("Repair validation owner thread required");var level=PocketService.dimension(server);var pos=new ChunkPos(r.key());
  if(level.getChunkSource().getChunkNow(pos.x,pos.z)!=null)throw new IOException("Loaded chunk: leave/recover and unload before repair");
  var count=inFlight.get(r.key());if(count!=null&&count.get()!=0)throw new IOException("Write still in flight");
  var tag=decode(r.payload());if(tag.getInt("xPos")!=pos.x||tag.getInt("zPos")!=pos.z||!supported(tag))throw new IOException("Snapshot coordinate/type mismatch");
  var bes=tag.getList("block_entities",10);for(int i=0;i<bes.size();i++){var be=bes.getCompound(i);if(!be.hasUUID("PocketId"))continue;var p=PocketService.ledger(server).get(be.getUUID("PocketId"));
   if(p==null||!be.hasUUID("Owner")||!p.owner().equals(be.getUUID("Owner"))||!p.contains(be.getInt("x"),be.getInt("y"),be.getInt("z")))throw new IOException("Snapshot no longer matches room identity");}
 }
 public static void schedule(long key,UUID token)throws IOException {
  if(!ENABLED||globalBad||journal==null||stopping)throw new IOException("Repair unavailable");var r=journal.get(key);
  if(r==null||!r.token().equals(token)||!journal.blocked(key))throw new IOException("Stale or unnecessary repair");validate(r);
  if(retries.size()>=8&&!retries.containsKey(key))throw new IOException("Repair intent quota; retained snapshots unchanged");retries.putIfAbsent(key,new Retry(key,token,server.getTickCount()));
 }
 public static CompletableFuture<Void> reset(long key,UUID token)throws IOException {
  if(globalBad||journal==null)throw new IOException("Global journal fault cannot be reset blindly");var r=journal.get(key);
  if(r==null||!r.token().equals(token)||r.state()!=SnapshotJournal.State.REPAIRED)throw new IOException("Exact verified repair proof missing");validate(r);
  var w=worker();var storage=((WorkerAccess)(Object)w).anyaStorage();
  return ((WorkerAccess)(Object)w).anyaExecute(()->{
   try{var current=journal.get(key);if(!current.token().equals(token))throw new IOException("Reset token changed");var pos=new ChunkPos(key);storage.flush();
    if(!decode(r.payload()).equals(storage.read(pos)))throw new IOException("Reset read-back mismatch");journal.reset(key,token);transientFaults.remove(key);resets.incrementAndGet();bump(key);Trace.note("REPAIR_RESET_DURABLE",null,"key="+key+" token="+token);return Either.left(null);
   }catch(Exception ex){return Either.right(ex);}
  });
 }
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void boot(ServerAboutToStartEvent e){
  if(!ENABLED)return;server=e.getServer();try{
   journal=new SnapshotJournal(server.getWorldPath(LevelResource.ROOT).resolve("anya_repair_experiment"));globalBad=journal.fatal();
   for(var r:journal.all()){roomIds.put(r.key(),ids(decode(r.payload())));if(journal.blocked(r.key()))transientFaults.add(r.key());}
   Trace.marker("journal-start","records="+journal.all().size()+" quarantined="+transientFaults.size()+" fatal="+globalBad);
  }catch(Exception ex){globalBad=true;Trace.marker("journal-start-refused",ex.toString());}
 }
 @SubscribeEvent(priority=EventPriority.LOW) public static void poll(ServerTickEvent.Post e){
  if(!ENABLED||globalBad||stopping)return;long now=e.getServer().getTickCount();
  for(var it=retries.values().iterator();it.hasNext();){var r=it.next();
   if(r.future!=null){if(!r.future.isDone())continue;try{r.future.join();Trace.note("REPAIR_ATTEMPT_OK",null,"key="+r.key+" attempts="+r.attempts);it.remove();}
    catch(CompletionException ex){Trace.note("REPAIR_ATTEMPT_FAILED",null,"key="+r.key+" attempts="+r.attempts);r.future=null;r.due=now+20L*(1L<<Math.min(3,r.attempts-1));if(r.attempts>=3)it.remove();}continue;}
   if(now<r.due)continue;
   try{var entry=journal.get(r.key);if(entry==null||!entry.token().equals(r.token))throw new IOException("Stale queued repair");validate(entry);var tag=decode(entry.payload());replay.put(tag,entry);r.attempts++;
    r.future=worker().store(new ChunkPos(r.key),tag);r.future.whenComplete((v,error)->replay.remove(tag));Trace.note("REPAIR_ATTEMPT",null,"key="+r.key+" attempt="+r.attempts+" tick="+now);break;
   }catch(Exception ex){Trace.note("REPAIR_INTENT_REFUSED",null,ex.toString());it.remove();}
  }
 }
 public static boolean idle(){return retries.isEmpty()&&inFlight.values().stream().allMatch(c->c.get()==0);}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void stop(ServerStoppingEvent e){stopping=true;retries.clear();Trace.note("REPAIR_STOP",null,"new retry intents stopped; original worker flush/close and persistent snapshots remain");}
 @SubscribeEvent public static void stopped(ServerStoppedEvent e){if(ENABLED)Trace.marker("repair-summary","writes="+writes+" failures="+failures+" repairs="+repairs+" resets="+resets+" journalBytes="+(journal==null?-1:journal.bytes())+" globalBad="+globalBad+" journalNs="+journalNs+" regionNs="+regionNs);}
 private RepairService(){}
}

package dev.anya.storage.churn;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.repair.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
/** Faults and bounded worker-only scheduling control, never a save implementation. */
public final class ChurnAudit {
 public static final boolean ACTIVE=!System.getProperty("anya.repairChurn","").isEmpty();
 public static final Set<Long> FAIL=ConcurrentHashMap.newKeySet();
 public static final ConcurrentMap<Long,AtomicInteger> failures=new ConcurrentHashMap<>();
 public static final ConcurrentMap<Long,Integer> persisted=new ConcurrentHashMap<>();
 public static final AtomicLong attempted=new AtomicLong(),completed=new AtomicLong();
 public static volatile Throwable violation;
 private static final Pattern VERSION=Pattern.compile("Churn key=(\\d+) gen=(\\d+)");
 public static volatile long gateKey=Long.MIN_VALUE;
 public static volatile int gateVersion=-1;
 public static volatile boolean gateEntered;
 private static volatile CountDownLatch gate=new CountDownLatch(0);
 private static final Field RESTORING,REPLAY,RETRIES;
 public static final Map<UUID,Integer> tokenAttempts=new ConcurrentHashMap<>();
 private static long dispatchedTick=Long.MIN_VALUE;private static int dispatchedThisTick;
 public static long dispatches;
 static {try{RESTORING=RepairService.class.getDeclaredField("restoring");RESTORING.setAccessible(true);REPLAY=RepairService.class.getDeclaredField("replay");REPLAY.setAccessible(true);RETRIES=RepairService.class.getDeclaredField("retries");RETRIES.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 public static int version(CompoundTag tag){
  if(tag==null)return -1;int found=-1;var bes=tag.getList("block_entities",10);
  for(int i=0;i<bes.size();i++){var items=bes.getCompound(i).getList("Items",10);for(int j=0;j<items.size();j++){
   var match=VERSION.matcher(items.getCompound(j).toString());if(match.find()){if(found>=0)throw new IllegalStateException("Multiple workload samples in one chunk");found=Integer.parseInt(match.group(2));}
  }}return found;
 }
 @SuppressWarnings("unchecked") public static boolean repairing(){try{return ((ThreadLocal<Boolean>)RESTORING.get(null)).get();}catch(Exception e){throw new IllegalStateException(e);}}
 public static void arm(long key,int version){if(gate.getCount()!=0)throw new IllegalStateException("Unreleased gate");gateKey=key;gateVersion=version;gateEntered=false;gate=new CountDownLatch(1);}
 @SuppressWarnings("unchecked") public static void submission(net.minecraft.world.level.chunk.storage.IOWorker worker,ChunkPos pos,CompoundTag tag){
  if(!ACTIVE||!RepairService.target(worker.storageInfo()))return;
  try{var r=((Map<CompoundTag,SnapshotJournal.Record>)REPLAY.get(null)).get(tag);if(r==null)return;
   long tick=RepairService.server.getTickCount();if(dispatchedTick!=tick){dispatchedTick=tick;dispatchedThisTick=0;}
   var intent=((Map<Long,?>)RETRIES.get(null)).get(pos.toLong());if(intent==null)throw new IllegalStateException("Dispatch without owned retry intent");
   var attemptField=intent.getClass().getDeclaredField("attempts");attemptField.setAccessible(true);int attempt=attemptField.getInt(intent);
   if(++dispatchedThisTick>1||attempt<1||attempt>3)throw new IllegalStateException("Retry dispatch bound exceeded");tokenAttempts.merge(r.token(),1,Integer::sum);dispatches++;
   Trace.note("CHURN_REPAIR_DISPATCH",null,"key="+pos.toLong()+" token="+r.token()+" attempt="+attempt+" totalTokenAttempts="+tokenAttempts.get(r.token())+" intent="+System.identityHashCode(intent)+" generation="+version(tag));
  }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
 }
 public static void release(){gate.countDown();}
 public static void before(RegionFileStorage storage,ChunkPos pos,CompoundTag tag)throws IOException{
  if(!ACTIVE||!RepairService.target(storage.info()))return;int generation=version(tag);if(generation<0)return;
  if(RepairService.server.isSameThread())throw new IllegalStateException("Physical write on world owner thread");
  attempted.incrementAndGet();long key=pos.toLong();
  if(key==gateKey&&generation==gateVersion&&repairing()&&!gateEntered){
   gateEntered=true;Trace.note("CHURN_EXECUTING_REPAIR_HELD",null,"key="+key+" generation="+generation+" worker="+Thread.currentThread().getName());
   try{if(!gate.await(5,TimeUnit.SECONDS))throw new IOException("Bounded fixture gate not released within five seconds");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
  }
  if(FAIL.contains(key)){int n=failures.computeIfAbsent(key,k->new AtomicInteger()).incrementAndGet();Trace.note("CHURN_WORKER_FAILURE",null,"key="+key+" generation="+generation+" count="+n+" repairing="+repairing());throw new IOException("Injected sustained key failure "+key);}
  Integer before=persisted.get(key);if(before!=null&&generation<before){violation=new IllegalStateException("Stale physical overwrite key="+key+" old="+before+" incoming="+generation);throw new IOException(violation);}
 }
 public static void after(RegionFileStorage storage,ChunkPos pos,CompoundTag tag){
  if(!ACTIVE||!RepairService.target(storage.info()))return;int generation=version(tag);if(generation<0)return;
  persisted.put(pos.toLong(),generation);completed.incrementAndGet();Trace.note("CHURN_PRIMARY_WRITE_RETURN",null,"key="+pos.toLong()+" generation="+generation+" repairing="+repairing());
 }
 private ChurnAudit(){}
}

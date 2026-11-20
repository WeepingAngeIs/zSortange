package dev.anya.storage.resetgap;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.repair.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
/** Bounded worker-only pause and exact method-entry persistence faults. No save/proof is faked. */
public final class GapHooks {
 public static final String MODE=System.getProperty("anya.resetGap",""),CASE=System.getProperty("anya.resetGapCase","");
 public static volatile long key=Long.MIN_VALUE;
 public static volatile UUID token;
 public static volatile boolean armed,resetEntered,newEntered,latestJournal;
 public static final AtomicInteger lockFaults=new AtomicInteger(),markerFaults=new AtomicInteger();
 private static final CountDownLatch resetGate=new CountDownLatch(1),newGate=new CountDownLatch(1);
 private static final Pattern GENERATION=Pattern.compile("Reset gap gen=(\\d+)");
 public static int version(CompoundTag tag){if(tag==null)return -1;int n=-1;var list=tag.getList("block_entities",10);for(int i=0;i<list.size();i++){var items=list.getCompound(i).getList("Items",10);for(int j=0;j<items.size();j++){var m=GENERATION.matcher(items.getCompound(j).toString());if(m.find()){if(n!=-1)throw new IllegalStateException("Duplicate sample");n=Integer.parseInt(m.group(1));}}}return n;}
 private static void await(CountDownLatch gate,String at){
  if(RepairService.server.isSameThread())throw new IllegalStateException("World thread must never await fixture latch");
  Trace.note("GAP_WORKER_HELD",null,"point="+at+" key="+key);
  try{if(!gate.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Worker pause ceiling");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
 }
 public static void afterReset(long chunk,UUID id){if(!MODE.equals("seed")||!armed||chunk!=key||!id.equals(token))return;resetEntered=true;await(resetGate,"reset-return-after-durable-publication");}
 public static void beforeWrite(ChunkPos pos,CompoundTag tag){if(!MODE.equals("seed")||!armed||pos.toLong()!=key||version(tag)!=2||CASE.equals("latest-durable"))return;newEntered=true;await(newGate,"newest-before-journal-publication");}
 public static void boundary(String at,ChunkPos pos){if(MODE.equals("seed")&&armed&&CASE.equals("latest-durable")&&pos.toLong()==key&&at.equals("after-journal")&&versionSafe()==2){latestJournal=true;await(newGate,"newest-journal-durable-before-primary");}}
 private static int versionSafe(){try{return version(RepairService.decode(RepairService.journal.get(key).payload()));}catch(IOException e){throw new IllegalStateException(e);}}
 public static void beforeLock(long chunk,UUID id)throws IOException{if(MODE.equals("seed")&&armed&&resetEntered&&chunk==key&&id.equals(token)&&(CASE.equals("lock-fail")||CASE.equals("double-fail"))){lockFaults.incrementAndGet();Trace.note("GAP_INJECTED_RELOCK_FAILURE",null,"before any lock persistence;key="+key);throw new IOException("Injected compensating relock persistence failure");}}
 public static void beforeMarker()throws IOException{if(MODE.equals("seed")&&armed&&CASE.equals("double-fail")&&lockFaults.get()>0){markerFaults.incrementAndGet();Trace.note("GAP_INJECTED_FAULT_MARKER_FAILURE",null,"before any marker write;key="+key);throw new IOException("Injected persistent fault-marker failure");}}
 public static void releaseReset(){resetGate.countDown();}
 public static void releaseAll(){resetGate.countDown();newGate.countDown();}
 private GapHooks(){}
}

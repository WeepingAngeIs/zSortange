package dev.anya.storage.pressure;
import dev.anya.storage.*;
import dev.anya.storage.durable.Trace;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Bounded test evidence, not a replacement write queue. Polls receipts on main thread; keeps no world snapshot. */
public final class Receipts {
 public static final class Sample {
  public final UUID room; public final long key;
  public int version,confirmed=-1;
  public long dirtyTick,dirtyNano,writeTick=-1,writeNano=-1;
  public CompletableFuture<Void> write;
  public boolean error;
  Sample(UUID room,long key){this.room=room;this.key=key;}
  public String name(){return "Pressure "+room+" v"+version;}
 }
 public static MinecraftServer server;
 public static IOWorker worker;
 public static boolean failed;
 public static final Map<Long,Sample> SAMPLES=new LinkedHashMap<>();
 private static BufferedWriter csv;
 private static CompletableFuture<Void> flush;
 private static List<Sample> batch=List.of();
 public static long confirmedCount,maxDelay,maxDelayMs,flushCount,writeFailures,flushFailures;
 private static volatile String fault="";
 private static volatile boolean fired;
 private static volatile long faultKey;
 public static void register(UUID room,ChunkPos pos){if(SAMPLES.size()>=16)throw new IllegalStateException("Sample budget");SAMPLES.put(pos.toLong(),new Sample(room,pos.toLong()));}
 public static void dirty(Sample s,int version){
  if(!server.isSameThread()||s.version>s.confirmed&&s.write!=null)throw new IllegalStateException("Overwrite of unconfirmed sample");
  if(!fault.isEmpty()&&!fired&&faultKey==Long.MIN_VALUE){faultKey=s.key;Trace.note("PRESSURE_FAULT_TARGET",s.room,"key="+s.key+" version="+version);}
  s.version=version;s.dirtyTick=server.getTickCount();s.dirtyNano=System.nanoTime();s.write=null;s.writeTick=s.writeNano=-1;s.error=false;
 }
 public static long oldest(long now){long age=0;for(var s:SAMPLES.values())if(s.confirmed<s.version)age=Math.max(age,now-s.dirtyTick);return age;}
 public static boolean allConfirmed(){return SAMPLES.values().stream().allMatch(s->s.confirmed==s.version);}
 public static void arm(String kind){faultKey=Long.MIN_VALUE;fired=false;fault=kind;Trace.note("PRESSURE_FAULT_ARMED",null,"kind="+kind+" target=next-monitored-dirty-chunk");}
 public static boolean fired(){return fired;}
 private static boolean chunkStore(RegionFileStorage storage){return storage.info().dimension().equals(dev.anya.storage.pocket.PocketService.DIMENSION)&&storage.info().type().equals("chunk");}
 public static void ioWrite(RegionFileStorage storage,ChunkPos pos,CompoundTag tag)throws IOException{
  if(!fault.equals("write")||fired||!chunkStore(storage)||pos.toLong()!=faultKey||tag==null)return;
  if(server.isSameThread())throw new IllegalStateException("Expected actual IO worker, not fixture main thread");
  fired=true;Trace.note("PRESSURE_WRITE_FAULT_FIRED",null,"store="+storage.info()+" key="+pos.toLong()+" legacy_name_match="+tag.toString().contains("Pressure "));
  throw new IOException("Injected pressure receipt write failure");
 }
 public static void ioFlush(RegionFileStorage storage)throws IOException{
  if(fault.equals("flush")&&!fired&&chunkStore(storage)){
   if(server.isSameThread())throw new IllegalStateException("Expected actual IO worker flush");
   fired=true;Trace.note("PRESSURE_FLUSH_FAULT_FIRED",null,"store="+storage.info());throw new IOException("Injected pressure flush failure");
  }
 }
 public static void stored(IOWorker w,ChunkPos pos,CompoundTag tag,CompletableFuture<Void> receipt){
  if(w!=worker||tag==null||server==null)return;
  if(!server.isSameThread())throw new IllegalStateException("Observed serialization off server thread");
  Sample s=SAMPLES.get(pos.toLong());if(s==null||s.confirmed==s.version||s.write!=null)return;
  var bes=tag.getList("block_entities",10);boolean matched=false;
  for(int b=0;b<bes.size();b++){var be=bes.getCompound(b);if(!be.hasUUID("PocketId")||!be.getUUID("PocketId").equals(s.room))continue;
   var items=be.getList("Items",10);for(int i=0;i<items.size();i++){var stack=ItemStack.parseOptional(server.registryAccess(),items.getCompound(i));if(stack.getCount()==13&&stack.getHoverName().getString().equals(s.name()))matched=true;}
  }
  if(matched)s.write=receipt;
 }
 private static void row(Sample s,String result)throws IOException{
  if(csv==null){csv=Files.newBufferedWriter(Trace.output().resolve("durability.csv"));csv.write("room,version,dirty_tick,write_tick,confirmed_tick,dirty_to_write_ms,dirty_to_confirm_ms,status\n");}
  long now=server.getTickCount(),ms=(System.nanoTime()-s.dirtyNano)/1_000_000;
  csv.write(s.room+","+s.version+","+s.dirtyTick+","+s.writeTick+","+now+","+(s.writeNano<0?-1:(s.writeNano-s.dirtyNano)/1_000_000)+","+ms+","+result+"\n");csv.flush();
  if(result.equals("durable")){maxDelay=Math.max(maxDelay,now-s.dirtyTick);maxDelayMs=Math.max(maxDelayMs,ms);confirmedCount++;}
 }
 public static void poll()throws IOException{
  if(server==null)return;
  for(Sample s:SAMPLES.values())if(!s.error&&s.confirmed<s.version&&s.write!=null&&s.write.isDone()&&s.writeTick<0){
   try{s.write.join();s.writeTick=server.getTickCount();s.writeNano=System.nanoTime();}
   catch(CompletionException e){s.error=true;failed=true;writeFailures++;row(s,"write-failed");}
  }
  if(flush!=null&&flush.isDone()){
   try{flush.join();for(Sample s:batch){if(s.error)throw new IllegalStateException("Failed sample in completed batch");s.confirmed=s.version;row(s,"durable");}}
   catch(CompletionException e){failed=true;flushFailures++;for(Sample s:batch){s.error=true;row(s,"flush-failed");}}
   flush=null;batch=List.of();
  }
  if(flush==null&&!failed&&server.getTickCount()%20==0){
   batch=SAMPLES.values().stream().filter(s->!s.error&&s.confirmed<s.version&&s.writeTick>=0).toList();
   if(!batch.isEmpty()){flush=worker.synchronize(true);flushCount++;}
  }
 }
 public static void close()throws IOException{if(csv!=null){csv.close();csv=null;}}
 private Receipts(){}
}

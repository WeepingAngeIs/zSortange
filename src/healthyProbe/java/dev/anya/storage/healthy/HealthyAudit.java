package dev.anya.storage.healthy;

import dev.anya.storage.durable.Trace;
import dev.anya.storage.repair.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/** Bounded worker-only ordering controls. Observations do not cancel or replace storage operations. */
public final class HealthyAudit {
 public static final boolean ACTIVE=!System.getProperty("anya.healthyRace", "").isEmpty();
 private static final Pattern PATTERN=Pattern.compile("Healthy race gen=(\\d+)");
 private static final Field RESTORING;
 static {try{RESTORING=RepairService.class.getDeclaredField("restoring");RESTORING.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 private static volatile CountDownLatch gate=new CountDownLatch(0);
 public static volatile String point="";
 public static volatile long key=Long.MIN_VALUE;
 public static volatile int generation=-1;
 public static volatile UUID token;
 public static volatile boolean entered;
 public static volatile Throwable violation;
 public static final AtomicLong events=new AtomicLong(),writes=new AtomicLong();
 public static final Map<Long,Integer> primary=new ConcurrentHashMap<>();
 public static final Set<Integer> healthyRepairs=ConcurrentHashMap.newKeySet();
 public static int version(CompoundTag tag){
  if(tag==null)return -1;int found=-1;var bes=tag.getList("block_entities",10);
  for(int i=0;i<bes.size();i++){var items=bes.getCompound(i).getList("Items",10);for(int j=0;j<items.size();j++){
   var m=PATTERN.matcher(items.getCompound(j).toString());if(m.find()){if(found!=-1)throw new IllegalStateException("Duplicate sample");found=Integer.parseInt(m.group(1));}
  }}return found;
 }
 @SuppressWarnings("unchecked") public static boolean repairing(){try{return ((ThreadLocal<Boolean>)RESTORING.get(null)).get();}catch(Exception e){throw new IllegalStateException(e);}}
 public static void note(String kind,String text){Trace.note(kind,null,"event="+events.incrementAndGet()+" "+text);}
 public static void arm(String at,long chunk,int gen,UUID id){if(gate.getCount()!=0)throw new IllegalStateException("Previous gate held");point=at;key=chunk;generation=gen;token=id;entered=false;gate=new CountDownLatch(1);note("HEALTHY_GATE_ARMED","point="+at+" generation="+gen+" token="+id);}
 public static void release(){gate.countDown();}
 private static void hold(String at,long chunk,int gen,UUID id){
  if(!ACTIVE||!at.equals(point)||chunk!=key||entered||gen!=generation||!Objects.equals(id,token))return;
  if(RepairService.server.isSameThread())throw new IllegalStateException("Gate must never block world owner");
  entered=true;note("HEALTHY_GATE_ENTERED","point="+at+" generation="+gen+" token="+id);
  try{if(!gate.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Five-second worker-only ordering ceiling");}
  catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
  note("HEALTHY_GATE_RELEASED","point="+at+" generation="+gen);
 }
 public static void physical(RegionFileStorage storage,ChunkPos pos,CompoundTag tag,boolean after){
  if(!ACTIVE||!RepairService.target(storage.info()))return;int gen=version(tag);if(gen<0)return;
  if(RepairService.server.isSameThread())throw new IllegalStateException("Physical IO on owner thread");
  boolean repair=repairing();var r=RepairService.journal.get(pos.toLong());
  note(after?"HEALTHY_PHYSICAL_RETURN":"HEALTHY_PHYSICAL_ENTER","generation="+gen+" repairing="+repair+" key="+pos.toLong());
  if(after){Integer prior=primary.put(pos.toLong(),gen);if(prior!=null&&gen<prior)violation=new IllegalStateException("Successful physical generation regressed "+prior+" -> "+gen);writes.incrementAndGet();if(repair)healthyRepairs.add(gen);}
  if(repair&&r!=null)hold(after?"after-write":"before-write",pos.toLong(),gen,r.token());
 }
 public static void boundary(String at,ChunkPos pos){
  if(!ACTIVE||!at.startsWith("repair-"))return;var r=RepairService.journal.get(pos.toLong());if(r==null)return;
  try{int gen=version(RepairService.decode(r.payload()));note("HEALTHY_REPAIR_BOUNDARY","point="+at+" generation="+gen+" token="+r.token());
   if(at.equals("repair-after-region"))hold("after-flush",pos.toLong(),gen,r.token());
   if(at.equals("repair-after-proof"))hold("after-proof",pos.toLong(),gen,r.token());
  }catch(java.io.IOException e){throw new IllegalStateException(e);}
 }
 public static void reset(SnapshotJournal journal,long chunk,UUID id,boolean after){
  if(!ACTIVE)return;var r=journal.get(chunk);if(r==null)return;
  try{int gen=version(RepairService.decode(r.payload()));if(gen<0)return;note(after?"HEALTHY_RESET_RETURN":"HEALTHY_RESET_ENTER","generation="+gen+" token="+id+" actual="+r.token()+" locked="+r.locked());hold(after?"reset-after-publication":"reset-before-publication",chunk,gen,id);}
  catch(java.io.IOException e){throw new IllegalStateException(e);}
 }
 private HealthyAudit(){}
}

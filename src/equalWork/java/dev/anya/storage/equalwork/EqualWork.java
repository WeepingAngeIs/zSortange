package dev.anya.storage.equalwork;

import dev.anya.storage.*;
import dev.anya.storage.durable.*;
import dev.anya.storage.minimal.OwnershipView;
import dev.anya.storage.operator.*;
import dev.anya.storage.pocket.*;
import dev.anya.storage.pressure.*;
import dev.anya.storage.repair.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Fixed completed work, not fixed-duration throughput. No real network clients in this lane. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class EqualWork {
 public static final String MODE=System.getProperty("anya.equalWork","");
 public static String phase="startup";
 public static long completed,requests,grants,measuredStart,measuredEnd;
 private static MinecraftServer server;
 private static List<ServerPlayer> players=List.of();
 private static List<StorageEntity> hubs=List.of();
 private static List<UUID> ids=new ArrayList<>();
 private static final long[] open=new long[8],closed=new long[8],wanted=new long[8];
 private static final int[] versions=new int[8];
 private static final Properties expected=new Properties();
 private static boolean running,adopted;
 private static long phaseAt;
 private static BufferedWriter admissions;
 private static void check(boolean v,String text){if(!v)throw new IllegalStateException(text);}
 private static net.minecraft.server.level.ServerLevel level(){return PocketService.dimension(server);}
 private static PocketLedger.Pocket room(int i){try{return PocketService.ledger(server).get(ids.get(i));}catch(IOException e){throw new IllegalStateException(e);}}
 private static Receipts.Sample sample(int i){return Receipts.SAMPLES.get(new ChunkPos(PocketService.chestPositions(room(i)).getFirst()).toLong());}
 private static ItemStack item(int i,int v){var s=new ItemStack(Items.DIAMOND,13);s.set(DataComponents.CUSTOM_NAME,Component.literal("Pressure "+ids.get(i)+" v"+v));return s;}
 private static StorageEntity chest(int i){var pos=PocketService.chestPositions(room(i)).getFirst();check(level().hasChunkAt(pos),"Unexpected loading by fixture");return (StorageEntity)level().getBlockEntity(pos);}
 private static boolean menu(int i){return players.get(i).containerMenu instanceof StoreMenu m&&m.hasPocket(ids.get(i));}
 private static boolean drained()throws IOException{var v=(OwnershipView)(Object)level().getChunkSource().chunkMap;return PocketService.ownedTicketRequests()==0&&level().getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0&&RepairService.idle()&&PocketService.ledger(server).returnCount()==0;}
 private static void next(String value){phase=value;Fixture.phase="equal-"+value;phaseAt=server.getTickCount();Trace.note("EQUAL_PHASE",null,value+" completed="+completed+" confirmed="+Receipts.confirmedCount);}
 private static Object field(String name)throws Exception{var f=Fixture.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 private static Path region(){var id=PocketService.DIMENSION.location();return server.getWorldPath(LevelResource.ROOT).resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath()).resolve("region");}
 @SuppressWarnings("unchecked") private static void adopt()throws Exception{
  var f=Fixture.class.getDeclaredField("running");f.setAccessible(true);f.setBoolean(null,false);
  players=(List<ServerPlayer>)field("PLAYERS");hubs=(List<StorageEntity>)field("HUBS");ids=(List<UUID>)field("IDS");check(ids.size()==8,"Exact eight owners required");
  Receipts.server=server;Receipts.worker=(IOWorker)level().getChunkSource().chunkMap.chunkScanner();
  ExpansionRules.get().pocketSessionTicks=20000;
  for(int i=0;i<8;i++){
   var r=room(i);expected.setProperty("room."+i,r.id()+"/"+r.cell()+"/"+r.owner());int n=0;
   for(var p:PocketService.chestPositions(r)){var c=(StorageEntity)level().getBlockEntity(p);check(c!=null&&c.isEmpty(),"Refuse existing contents");expected.setProperty("chest."+i+"."+(n++),c.storageIdentity().toString());c.setChanged();}
   Receipts.register(r.id(),new ChunkPos(PocketService.chestPositions(r).getFirst()));Receipts.dirty(sample(i),0);chest(i).setItem(0,item(i,0));
   PocketService.recover(players.get(i));
  }
  adopted=true;next("setup-drain");
 }
 private static void request(int i)throws Exception{
  if(PocketService.lease(players.get(i).getUUID())!=null)return;
  long now=server.getTickCount();if(wanted[i]==0)wanted[i]=now;requests++;
  PocketService.remote(players.get(i),players.get(i).getInventory().getItem(0),hubs.get(i));
  boolean ok=PocketService.lease(players.get(i).getUUID())!=null;
  if(ok){grants++;admissions.write(now+","+i+","+(now-wanted[i])+",granted,"+phase+"\n");wanted[i]=0;}
  else admissions.write(now+","+i+","+(now-wanted[i])+",deferred,"+phase+"\n");
 }
 private static void dirty(int i,int v){
  check(sample(i).confirmed==sample(i).version,"Cannot overwrite unconfirmed sample");
  check(!RepairService.containerBlocked(chest(i))&&Transactions.same(chest(i).getItem(0),item(i,v-1)),"Quarantine/content mismatch before fixture edit");
  Receipts.dirty(sample(i),v);chest(i).setItem(0,item(i,v));check(level().getChunkAt(chest(i).getBlockPos()).isUnsaved(),"Required dirty flag absent");versions[i]=v;
 }
 private static void oracle()throws IOException{
  for(int i=0;i<8;i++)expected.setProperty("version."+i,""+versions[i]);
  try(var out=Files.newOutputStream(Path.of("equal-work.properties"))){expected.store(out,"Disposable independent test oracle, not recovery data");}
 }
 private static void rawVerify()throws Exception{
  check(PocketService.ledger(server).pockets().size()==8&&level().getChunkSource().getLoadedChunksCount()==0,"Raw verification must precede destination loading");int count=0,boxes=0;
  for(int i=0;i<8;i++){
   var r=room(i);check(expected.getProperty("room."+i).equals(r.id()+"/"+r.cell()+"/"+r.owner()),"Room identity changed");int index=0;
   for(var pos:PocketService.chestPositions(r)){
    long key=new ChunkPos(pos).toLong();var raw=ReadOnlyRegion.inspect(region(),key).tag();var rec=RepairService.journal.get(key);
    check(rec!=null&&!RepairService.blocked(key)&&rec.state()==SnapshotJournal.State.APPLIED,"Journal proof/quarantine mismatch");
    check(RepairService.decode(rec.payload()).equals(raw),"Journal differs from physical primary");
    CompoundTag found=null;var bes=raw.getList("block_entities",10);
    for(int j=0;j<bes.size();j++){var be=bes.getCompound(j);if(be.getInt("x")==pos.getX()&&be.getInt("y")==pos.getY()&&be.getInt("z")==pos.getZ())found=be;}
    check(found!=null&&found.getUUID("PocketId").equals(r.id())&&found.getUUID("Owner").equals(r.owner())&&found.getUUID("StorageIdentity").toString().equals(expected.getProperty("chest."+i+"."+index)),"Physical chest identity changed");
    var items=found.getList("Items",10);if(index==0){check(items.size()==1&&Transactions.same(ItemStack.parseOptional(server.registryAccess(),items.getCompound(0)),item(i,versions[i])),"Latest named sample lost or duplicated");count+=13;}else check(items.isEmpty(),"Unexpected duplicate items");index++;boxes++;
   }
  }check(count==104&&boxes==32,"Conservation total mismatch");Trace.marker("EQUAL_RAW_ITEMS","104 latest named items;8 rooms;32 chest identities;primary read before load;all matching APPLIED journal snapshots");
 }
 @SubscribeEvent public static void start(ServerStartedEvent e)throws Exception{
  if(MODE.isBlank())return;server=e.getServer();check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"Disposable marker required");
  check(RepairService.ENABLED&&Pressure.ENABLED&&dev.anya.storage.budget.BudgetState.ENABLED,"All safety stack switches must stay enabled");
  Metrics.start(server);admissions=Files.newBufferedWriter(Trace.output().resolve("equal-admission.csv"));admissions.write("tick,actor,wait_ticks,result,phase\n");running=true;
  if(MODE.equals("verify")){
   try(var in=Files.newInputStream(Path.of("equal-work.properties"))){expected.load(in);}ids=new ArrayList<>();for(int i=0;i<8;i++){ids.add(UUID.fromString(expected.getProperty("room."+i).split("/")[0]));versions[i]=Integer.parseInt(expected.getProperty("version."+i));}
   rawVerify();adopted=true;next("restart-drain");
  }
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
  if(!running||e.getServer()!=server)return;try{
   long now=server.getTickCount();check(now<7000&&now-phaseAt<3600,"Bounded fixed-work deadline phase="+phase);
   if(!adopted){if(Fixture.phase.equals("visit-steady"))adopt();return;}
   check(!RepairService.globalBad&&RepairService.failures.get()==0,"Unexpected safety failure; never discard required work");
   if(MODE.equals("verify")){if(now-phaseAt>=100&&drained()){Trace.audit(server);Trace.marker("PASSED","independent raw item/identity restart and exact engine cleanup");running=false;server.halt(false);}return;}
   Receipts.poll();check(!Receipts.failed,"No failed write may count as useful completed work");
   for(var p:players)check(p.getInventory().getItem(5).is(Items.EMERALD)&&p.getInventory().getItem(5).getCount()==11,"Player sample altered");
   if(Pressure.workload)Pressure.gate().update(now,Pressure.signal(players.getFirst()));
   if(phase.equals("setup-drain")){
    if(!drained()||!Receipts.allConfirmed())return;check(Receipts.confirmedCount==8,"Exact initial samples");Pressure.workload=true;Metrics.measure=true;measuredStart=System.nanoTime();next("fixed-work");
   }else if(phase.equals("fixed-work")){
    for(int i=0;i<8;i++){
     if(menu(i)){
      if(open[i]==0&&!RepairService.roomBlocked(ids.get(i))){dirty(i,versions[i]+1);completed++;open[i]=now;}
      if(open[i]>0&&now-open[i]>=30){players.get(i).closeContainer();open[i]=0;closed[i]=now;}
     }else if(versions[i]<6&&now-closed[i]>=20&&now%5==0&&sample(i).confirmed==sample(i).version)request(i);
    }
    if(completed==48&&Arrays.stream(versions).allMatch(v->v==6)&&players.stream().noneMatch(p->p.containerMenu instanceof StoreMenu))next("work-drain");
   }else if(phase.equals("work-drain")&&drained()&&Receipts.allConfirmed()){
    measuredEnd=System.nanoTime();check(Receipts.confirmedCount==56,"Equal completed useful work, including eight setup samples");Metrics.measure=false;
    oracle();rawVerify();Trace.audit(server);Trace.marker("EQUAL_WORK_DONE","updates=48 confirmed=48 elapsed_ns="+(measuredEnd-measuredStart)+" attempts="+requests+" grants="+grants+" denied="+Pressure.denied+" noDroppedWork=true");next("tail-open");
   }else if(phase.equals("tail-open")){
    if(now%5==0)for(int i=0;i<8;i++)request(i);boolean all=true;for(int i=0;i<8;i++)all&=menu(i)&&!RepairService.roomBlocked(ids.get(i));if(!all)return;
    for(int i=0;i<8;i++){dirty(i,7);players.get(i).closeContainer();}oracle();Trace.marker("EQUAL_DIRTY_TAIL","eight new dirty sample versions;mode="+MODE+" before any explicit flush");
    if(MODE.equals("stop")){next("stop");running=false;server.halt(false);}else{next("force-save");long t=System.nanoTime();level().getChunkSource().save(true);Trace.marker("EQUAL_FORCE_NS",""+(System.nanoTime()-t));next("force-drain");}
   }else if(phase.equals("force-drain")&&drained()&&Receipts.allConfirmed()){
    check(Receipts.confirmedCount==64,"Tail saves missing");rawVerify();Trace.audit(server);Trace.marker("EQUAL_FORCE_PASSED","48 work updates plus8 dirty tail updates;primary/journal readback;exact engine empty");Metrics.finish();admissions.flush();Receipts.close();Trace.marker("CUT_EQUAL_WORK","successful required saves and flushes;forced exit before clean shutdown");Runtime.getRuntime().halt(77);
   }
   if(now%20==0)admissions.flush();
  }catch(Throwable t){running=false;Trace.marker("FAILED","equal-work "+phase+" "+t);AnyaStorage.LOG.error("ANYA_EQUAL_WORK_FAILED",t);server.halt(false);}
 }
 @SubscribeEvent public static void stopped(ServerStoppedEvent e)throws Exception{if(!MODE.isBlank()){if(admissions!=null)admissions.close();Receipts.close();Metrics.finish();}}
 private EqualWork(){}
}

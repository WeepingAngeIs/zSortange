package dev.anya.storage.resetgap;
import com.mojang.datafixers.util.Either;
import dev.anya.storage.*;
import dev.anya.storage.durable.*;
import dev.anya.storage.minimal.OwnershipView;
import dev.anya.storage.pocket.*;
import dev.anya.storage.repair.*;
import dev.anya.storage.repair.mixin.WorkerAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** The latest submitted generation may have no durable copy. Quarantine must nevertheless survive uncertain reset completion. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class GapProbe {
 private static final String MODE=GapHooks.MODE,CASE=GapHooks.CASE;
 private static MinecraftServer server;private static ServerPlayer player;
 private static UUID roomId,oldToken;private static BlockPos box;private static long key,start,phaseAt;
 private static int phase;private static boolean initialized,running;
 private static CompletableFuture<Void> receipt,reset,task;
 private static final Properties oracle=new Properties();
 private static void check(boolean v,String text){if(!v)throw new IllegalStateException(text);}
 private static ServerLevel level(){return PocketService.dimension(server);}
 private static SnapshotJournal.Record record(){return RepairService.journal.get(key);}
 private static int gen()throws IOException{return GapHooks.version(RepairService.decode(record().payload()));}
 private static ItemStack sample(int v){var s=new ItemStack(Items.DIAMOND,13);s.set(DataComponents.CUSTOM_NAME,Component.literal("Reset gap gen="+v));return s;}
 private static void step(int value,String name){phase=value;phaseAt=server.getTickCount();Fixture.phase="reset-gap-"+name;Trace.note("GAP_PHASE",null,"case="+CASE+" phase="+phase+" "+name);}
 private static Object fixture(String field)throws Exception{var f=Fixture.class.getDeclaredField(field);f.setAccessible(true);return f.get(null);}
 private static boolean drained()throws IOException{var v=(OwnershipView)(Object)level().getChunkSource().chunkMap;return PocketService.ownedTicketRequests()==0&&level().getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0&&RepairService.idle()&&PocketService.ledger(server).returnCount()==0;}
 private static void saveOracle()throws IOException{
  var b=new ByteArrayOutputStream();oracle.store(b,"Disposable assertion oracle, never used by storage recovery");
  try(var f=FileChannel.open(Path.of("reset-gap-oracle.properties"),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var bytes=ByteBuffer.wrap(b.toByteArray());while(bytes.hasRemaining())f.write(bytes);f.force(true);}
 }
 private static CompletableFuture<Void> submit(int version)throws IOException{
  check(level().getChunkSource().getChunkNow(new ChunkPos(key).x,new ChunkPos(key).z)==null,"Detached write requires unloaded target");
  var tag=RepairService.decode(record().payload());var bes=tag.getList("block_entities",10);boolean found=false;
  for(int i=0;i<bes.size();i++){var be=bes.getCompound(i);if(be.getInt("x")==box.getX()&&be.getInt("y")==box.getY()&&be.getInt("z")==box.getZ()){
   var items=be.getList("Items",10);check(items.size()==1,"Do not overwrite unknown inventory");var x=(CompoundTag)sample(version).save(server.registryAccess());x.putByte("Slot",(byte)0);items.set(0,x);found=true;
  }}check(found&&GapHooks.version(tag)==version,"Sample generation absent");var f=RepairService.worker().store(new ChunkPos(key),tag);Trace.note("GAP_SNAPSHOT_SUBMITTED",null,"generation="+version+" receiptDone="+f.isDone()+" blocked="+RepairService.blocked(key));return f;
 }
 private static CompletableFuture<Void> rawRead(int expected)throws IOException{
  var r=PocketService.ledger(server).get(roomId);check(r!=null&&PocketService.ledger(server).pockets().size()==1&&Objects.equals(oracle.getProperty("identity"),r.cell()+"/"+r.owner()),"Room identity changed");
  var worker=RepairService.worker();var storage=((WorkerAccess)(Object)worker).anyaStorage();var positions=List.copyOf(PocketService.chestPositions(r));
  return ((WorkerAccess)(Object)worker).anyaExecute(()->{try{
   storage.flush();int total=0,index=0;
   for(var p:positions){var tag=storage.read(new ChunkPos(p));check(tag!=null,"Physical region absent");var bes=tag.getList("block_entities",10);CompoundTag chest=null;
    for(int i=0;i<bes.size();i++){var be=bes.getCompound(i);if(be.getInt("x")==p.getX()&&be.getInt("y")==p.getY()&&be.getInt("z")==p.getZ()){check(chest==null,"Duplicate physical block entity");chest=be;}}
    check(chest!=null&&chest.hasUUID("PocketId")&&chest.getUUID("PocketId").equals(roomId)&&chest.hasUUID("Owner")&&chest.getUUID("Owner").equals(r.owner()),"Physical ownership mismatch");
    check(chest.toString().contains(oracle.getProperty("chest-nbt-id."+index)),"Physical storage identity missing");var items=chest.getList("Items",10);
    if(p.equals(box)){var wanted=(CompoundTag)sample(expected).save(server.registryAccess());wanted.putByte("Slot",(byte)0);check(items.size()==1&&items.getCompound(0).equals(wanted),"Raw region newest/previous item mismatch;expected="+expected);total+=13;}else check(items.isEmpty(),"Unexpected item outside target");index++;
   }check(total==13&&index==4,"Item/chest count changed");Trace.note("GAP_RAW_REGION_READBACK",null,"generation="+expected+" items=13 chests=4 overlay=false beforeChunkLoad=true");return Either.left(null);
  }catch(Exception ex){return Either.right(ex);}});
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void start(ServerStartedEvent event)throws Exception{
  if(MODE.isEmpty())return;server=event.getServer();check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"Disposable marker required");running=true;start=server.getTickCount();phaseAt=start;
  if(!MODE.equals("seed")){
   try(var in=Files.newInputStream(Path.of("reset-gap-oracle.properties"))){oracle.load(in);}roomId=UUID.fromString(oracle.getProperty("room"));box=BlockPos.of(Long.parseLong(oracle.getProperty("box")));key=new ChunkPos(box).toLong();oldToken=UUID.fromString(oracle.getProperty("token"));
   check(level().getChunkSource().getLoadedChunksCount()==0,"Read-back must precede destination loading");check(RepairService.journal!=null,"Journal unavailable on intended readable-marker test");
   int primary=MODE.equals("verify-repaired")?2:1;task=rawRead(primary);initialized=true;step(20,"restart-observation");
  }
 }
 @SuppressWarnings("unchecked") private static void setup()throws Exception{
  var ps=(List<ServerPlayer>)fixture("PLAYERS");if(ps.isEmpty()||!PocketService.inPocket(ps.getFirst().level()))return;player=ps.getFirst();var f=Fixture.class.getDeclaredField("running");f.setAccessible(true);f.setBoolean(null,false);
  roomId=((List<UUID>)fixture("IDS")).getFirst();var room=PocketService.ledger(server).get(roomId);check(room.stage()==PocketLedger.Stage.READY,"Entry before READY");box=PocketService.chestPositions(room).getFirst();key=new ChunkPos(box).toLong();GapHooks.key=key;
  oracle.setProperty("room",roomId.toString());oracle.setProperty("box",""+box.asLong());oracle.setProperty("identity",room.cell()+"/"+room.owner());int n=0;
  for(var p:PocketService.chestPositions(room)){var c=(StorageEntity)level().getBlockEntity(p);check(c!=null&&c.isEmpty(),"Seed refuses existing contents");var tag=c.saveWithFullMetadata(server.registryAccess());String id=null;
   for(var name:tag.getAllKeys())if(tag.hasUUID(name)&&tag.getUUID(name).equals(c.storageIdentity()))id=tag.get(name).toString();check(id!=null,"Storage identity tag not found");oracle.setProperty("chest-nbt-id."+(n++),id);
  }
  ((StorageEntity)level().getBlockEntity(box)).setItem(0,sample(0));server.saveEverything(true,true,true);PocketService.recover(player);initialized=true;step(1,"initial-drain");
 }
 private static void cut(String text){Trace.marker("GAP_PROCESS_CUT","case="+CASE+" "+text+"; Runtime.halt(77), no clean shutdown");Runtime.getRuntime().halt(77);}
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event){
  if(!running||event.getServer()!=server)return;long now=server.getTickCount();try{
   check(now-start<2600&&now-phaseAt<900,"Bounded reset-gap timeout phase="+phase);
   if(!initialized){setup();return;}
   if(player!=null){var inv=player.getInventory().getItem(5);check(inv.is(Items.EMERALD)&&inv.getCount()==11,"Unrelated player sample changed");}
   if(phase==1&&drained()){
    check(gen()==0,"Initial durable snapshot absent");RepairService.arm("write",key);receipt=submit(1);step(2,"bootstrap-failure");
   }else if(phase==2&&receipt.isDone()&&RepairService.idle()){
    check(receipt.isCompletedExceptionally()&&RepairService.ioFaults.get()==1&&record().state()==SnapshotJournal.State.PENDING&&gen()==1,"Bootstrap failure not confirmed");RepairService.arm("",Long.MIN_VALUE);oldToken=record().token();RepairService.schedule(key,oldToken);step(3,"healthy-repair");
   }else if(phase==3&&RepairService.idle()&&record().state()==SnapshotJournal.State.REPAIRED){
    check(RepairService.repairs.get()==1&&RepairService.ioFaults.get()==0&&gen()==1,"Healthy old repair not complete");oracle.setProperty("token",oldToken.toString());saveOracle();GapHooks.token=oldToken;GapHooks.armed=true;reset=RepairService.reset(key,oldToken);step(4,"reset-publication");
   }else if(phase==4&&GapHooks.resetEntered){
    check(record().state()==SnapshotJournal.State.APPLIED&&!record().locked(),"Must pause after actual unlocked record publication");
    if(CASE.equals("valid-reset")){GapHooks.releaseReset();step(7,"current-reset-control");return;}
    receipt=submit(2);check(!receipt.isDone()&&gen()==1&&RepairService.blocked(key),"Newest is in flight, not yet durable");
    if(CASE.equals("cut"))cut("newestSubmitted=2 durableJournal=1 durablePrimary=1 oldResetCompleted=false");
    GapHooks.releaseReset();step(5,"compensation-or-failure");
   }else if(phase==5&&reset.isDone()){
    check(reset.isCompletedExceptionally(),"Superseded reset must fail");
    if(CASE.equals("latest-durable")){
     if(!GapHooks.latestJournal)return;check(!receipt.isDone()&&gen()==2&&record().state()==SnapshotJournal.State.PENDING&&RepairService.blocked(key),"New durable snapshot not retained");cut("newestJournal=2 newestPrimary=1 pending=true before latest physical write");
    }else{
     if(!GapHooks.newEntered)return;check(!receipt.isDone()&&gen()==1&&GapHooks.lockFaults.get()==1&&RepairService.globalBad,"Relock-failure branch not actually reached");
     Path root=server.getWorldPath(LevelResource.ROOT).resolve("anya_repair_experiment");boolean marker=Files.exists(root.resolve("journal-fault"));
     check(CASE.equals("double-fail")?GapHooks.markerFaults.get()==1&&!marker:GapHooks.markerFaults.get()==0&&marker,"Fault-marker injection/result differs");
     Trace.note("GAP_FAULTS_CONFIRMED",null,"relock="+GapHooks.lockFaults+" faultMarker="+GapHooks.markerFaults+" durableFaultMarker="+marker+" newestNotJournaled=true");cut("newestSubmitted=2 durableJournal=1 durablePrimary=1 resetExceptional=true");
    }
   }else if(phase==7&&reset.isDone()){
    reset.join();check(!RepairService.blocked(key)&&gen()==1&&drained(),"Healthy current reset failed");cut("current reset acknowledged; no newer submission; durablePrimary=1");
   }else if(phase==20&&task.isDone()){
    task.join();int expectedJournal=CASE.equals("latest-durable")?2:1;check(gen()==expectedJournal,"Unexpected durable journal version");boolean allow=CASE.equals("valid-reset")||MODE.equals("verify-repaired");
    Trace.marker("GAP_RESTART_OBSERVATION","case="+CASE+" primary="+(MODE.equals("verify-repaired")?2:1)+" journal="+gen()+" state="+record().state()+" recordLocked="+record().locked()+" serviceBlocked="+RepairService.blocked(key)+" fatal="+RepairService.globalBad+" newSubmissionRecovered="+CASE.equals("latest-durable"));
    check(allow?!RepairService.blocked(key):RepairService.blocked(key),"RESET_GAP_DEFECT: uncertain reset reopened after process loss; newest bytes not durable is separate from quarantine safety");
    if(CASE.equals("latest-durable")&&!MODE.equals("verify-repaired")){check(!RepairService.globalBad,"Durable latest copy must remain repairable");RepairService.schedule(key,record().token());step(21,"repair-latest-after-restart");}
    else{if(!allow){boolean refused=false;try{RepairService.reset(key,oldToken);}catch(IOException expected){refused=true;}check(refused,"Blind reset of uncertain old proof accepted");}step(25,"final-cleanup");}
   }else if(phase==21&&RepairService.idle()&&record().state()==SnapshotJournal.State.REPAIRED){
    check(gen()==2&&RepairService.repairs.get()==1,"Restart repair wrong snapshot");task=rawRead(2);step(22,"newest-physical-confirmed");
   }else if(phase==22&&task.isDone()){
    task.join();reset=RepairService.reset(key,record().token());step(23,"explicit-newest-reset");
   }else if(phase==23&&reset.isDone()){
    reset.join();check(!RepairService.blocked(key)&&gen()==2&&drained(),"Newest verified reset did not finish");Trace.marker("GAP_LATEST_REPAIRED_CUT","newestJournal=2 primary=2 explicitReset=true flush/readback before Runtime.halt77");Runtime.getRuntime().halt(77);
   }else if(phase==25&&now-phaseAt>=650&&drained()){
    Trace.audit(server);Trace.marker("PASSED","case="+CASE+" independent primary/journal readback;13 named items and exact identities;no silent quarantine reopening;no real clients");running=false;server.halt(false);
   }
  }catch(Throwable error){GapHooks.releaseAll();running=false;Trace.marker("FAILED","reset-gap case="+CASE+" mode="+MODE+" phase="+phase+" "+error);AnyaStorage.LOG.error("ANYA_RESET_GAP_FAILED",error);server.halt(false);}
 }
 @SubscribeEvent public static void stopped(ServerStoppedEvent event){GapHooks.releaseAll();}
}

package dev.anya.storage.operator;

import com.mojang.datafixers.util.Either;
import dev.anya.storage.*;
import dev.anya.storage.durable.*;
import dev.anya.storage.minimal.OwnershipView;
import dev.anya.storage.pocket.*;
import dev.anya.storage.repair.*;
import dev.anya.storage.repair.mixin.*;
import net.minecraft.core.*;
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
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Operator fixture: zero real clients; actual region/journal files and independent server restarts. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class OperatorProbe {
 private static final String MODE=System.getProperty("anya.operatorProbe",""),CASE=System.getProperty("anya.operatorCase","");
 private static MinecraftServer server;private static ServerPlayer player;private static UUID roomId;private static BlockPos box;private static long key,start,phaseAt;
 private static int phase;private static boolean initialized,running;private static CompletableFuture<Void> task,receipt,resolve;
 private static CompletableFuture<OperatorResolution.Observation> inspection;private static OperatorResolution.Observation old;
 private static Map<String,String> hashes;private static final Properties oracle=new Properties();
 private static final AtomicBoolean injection=new AtomicBoolean();
 private static void check(boolean yes,String why){if(!yes)throw new IllegalStateException(why);}
 private static ServerLevel level(){return PocketService.dimension(server);}
 private static SnapshotJournal.Record record(){return RepairService.journal.get(key);}
 private static Path root(){return server.getWorldPath(LevelResource.ROOT);}
 private static Path region(){var id=PocketService.DIMENSION.location();return root().resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath()).resolve("region");}
 private static ItemStack sample(int n){var s=new ItemStack(Items.DIAMOND,13);s.set(DataComponents.CUSTOM_NAME,Component.literal("Operator resolution version="+n));return s;}
 private static void step(int n,String text){phase=n;phaseAt=server.getTickCount();Fixture.phase="operator-"+text;Trace.note("OPERATOR_PHASE",null,"case="+CASE+" mode="+MODE+" phase="+n+" "+text);}
 private static Object field(String name)throws Exception{var f=Fixture.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 private static boolean drained()throws IOException{var v=(OwnershipView)(Object)level().getChunkSource().chunkMap;return PocketService.ownedTicketRequests()==0&&level().getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0&&RepairService.idle()&&PocketService.ledger(server).returnCount()==0;}
 private static void saveOracle()throws IOException{var b=new ByteArrayOutputStream();oracle.store(b,"Disposable test expectations, not input to repair logic");try(var f=FileChannel.open(Path.of("operator-oracle.properties"),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var bytes=ByteBuffer.wrap(b.toByteArray());while(bytes.hasRemaining())f.write(bytes);f.force(true);}}
 private static void cut(String label){Trace.marker(label,"case="+CASE+" phase="+phase+" Runtime.halt77 before clean shutdown");Runtime.getRuntime().halt(77);}
 private static Map<String,String> files()throws Exception{
  Map<String,String> result=new TreeMap<>();for(Path dir:List.of(root().resolve("anya_repair_experiment"),region()))if(Files.isDirectory(dir))try(var walk=Files.walk(dir)){for(Path p:walk.toList())if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))result.put(root().relativize(p).toString(),JournalDisk.hash(Files.readAllBytes(p)));}return result;
 }
 private static void unchanged()throws Exception{check(hashes.equals(files()),"Inspection/refusal mutated persistent evidence");check(level().getChunkSource().getLoadedChunksCount()==0,"Inspection loaded destination chunks");Trace.note("OPERATOR_READ_ONLY_VERIFIED",null,"file names and contents unchanged; no destination loads; access times not asserted");}
 private static CompoundTag next(int version)throws IOException{var tag=RepairService.decode(record().payload());var bes=tag.getList("block_entities",10);boolean found=false;
  for(int i=0;i<bes.size();i++){var be=bes.getCompound(i);if(be.getInt("x")==box.getX()&&be.getInt("y")==box.getY()&&be.getInt("z")==box.getZ()){var items=be.getList("Items",10);check(items.size()==1,"Unexpected inventory");var s=(CompoundTag)sample(version).save(server.registryAccess());s.putByte("Slot",(byte)0);items.set(0,s);found=true;}}
  check(found,"Sample not found");return tag;
 }
 private static CompletableFuture<Void> submit(int n)throws IOException{check(level().getChunkSource().getChunkNow(new ChunkPos(key).x,new ChunkPos(key).z)==null,"Submit only detached unloaded fixture column");return RepairService.worker().store(new ChunkPos(key),next(n));}
 private static void rawItems(int expected)throws Exception{
  var room=PocketService.ledger(server).get(roomId);check(room!=null&&PocketService.ledger(server).pockets().size()==1&&Objects.equals(oracle.getProperty("identity"),room.cell()+"/"+room.owner()),"Room identity changed");int i=0,total=0;
  for(var p:PocketService.chestPositions(room)){var tag=ReadOnlyRegion.inspect(region(),new ChunkPos(p).toLong()).tag();var bes=tag.getList("block_entities",10);CompoundTag chest=null;
   for(int n=0;n<bes.size();n++){var b=bes.getCompound(n);if(b.getInt("x")==p.getX()&&b.getInt("y")==p.getY()&&b.getInt("z")==p.getZ()){check(chest==null,"Duplicate block entity");chest=b;}}
   check(chest!=null&&chest.hasUUID("PocketId")&&chest.getUUID("PocketId").equals(roomId)&&chest.hasUUID("Owner")&&chest.getUUID("Owner").equals(room.owner())&&chest.hasUUID("StorageIdentity")&&chest.getUUID("StorageIdentity").toString().equals(oracle.getProperty("chest."+i)),"Primary chest identity mismatch");
   var items=chest.getList("Items",10);if(p.equals(box)){var want=(CompoundTag)sample(expected).save(server.registryAccess());want.putByte("Slot",(byte)0);check(items.size()==1&&items.getCompound(0).equals(want),"Unexpected physical primary generation/items");total+=13;}else check(items.isEmpty(),"Unexpected duplicated item");i++;
  }check(total==13&&i==4,"Conservation mismatch");Trace.note("OPERATOR_RAW_ITEMS",null,"primaryVersion="+expected+" items=13 room/cell/owner=original physicalChests=4 readOnly=true overlay=false");
 }
 @SubscribeEvent public static void started(ServerStartedEvent e)throws Exception{
  if(MODE.isEmpty())return;server=e.getServer();check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"Disposable marker required");running=true;start=server.getTickCount();phaseAt=start;
  if(!MODE.equals("seed")){
   try(var in=Files.newInputStream(Path.of("operator-oracle.properties"))){oracle.load(in);}roomId=UUID.fromString(oracle.getProperty("room"));box=BlockPos.of(Long.parseLong(oracle.getProperty("box")));key=new ChunkPos(box).toLong();check(level().getChunkSource().getLoadedChunksCount()==0,"Inspection before all destination loading");
   initialized=true;hashes=files();int expected=MODE.equals("finish")?Integer.parseInt(oracle.getProperty("latest")):CASE.equals("uncertain")?1:CASE.equals("disagreement")?3:2;rawItems(expected);
   try{inspection=OperatorResolution.inspect(server.createCommandSourceStack(),key);}catch(Exception x){inspection=CompletableFuture.failedFuture(x);}step(MODE.equals("finish")?30:20,"restart-read-only");
  }
 }
 @SuppressWarnings("unchecked") private static void setup()throws Exception{
  var players=(List<ServerPlayer>)field("PLAYERS");if(players.isEmpty()||!PocketService.inPocket(players.getFirst().level()))return;player=players.getFirst();var f=Fixture.class.getDeclaredField("running");f.setAccessible(true);f.setBoolean(null,false);
  roomId=((List<UUID>)field("IDS")).getFirst();var room=PocketService.ledger(server).get(roomId);box=PocketService.chestPositions(room).getFirst();key=new ChunkPos(box).toLong();oracle.setProperty("room",roomId.toString());oracle.setProperty("box",""+box.asLong());oracle.setProperty("identity",room.cell()+"/"+room.owner());int n=0;
  for(var p:PocketService.chestPositions(room)){var c=(StorageEntity)level().getBlockEntity(p);check(c!=null&&c.isEmpty(),"Never overwrite existing inventory");oracle.setProperty("chest."+(n++),c.storageIdentity().toString());}
  ((StorageEntity)level().getBlockEntity(box)).setItem(0,sample(0));server.saveEverything(true,true,true);PocketService.recover(player);initialized=true;step(1,"seed-drain");
 }
 private static CompletableFuture<Void> projectIntent(){var w=RepairService.worker();return ((WorkerAccess)(Object)w).anyaExecute(()->{try{var r=record();RepairService.journal.beginReset(key,r.token());RepairService.journal.reset(key,r.token());return Either.left(null);}catch(Exception e){return Either.right(e);}});}
 private static void refuse(OperatorResolution.Observation o,UUID world,long targetKey,UUID token)throws Exception{
  boolean rejected=false;try{OperatorResolution.resolve(server.createCommandSourceStack(),world,targetKey,token,o.receipt());}catch(IOException e){rejected=true;Trace.note("OPERATOR_EXPECTED_REFUSAL",null,e.toString());}check(rejected,"Unsafe explicit resolution admitted");
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
  if(!running||e.getServer()!=server)return;long now=server.getTickCount();try{
   check(now-start<2600&&now-phaseAt<900,"Bounded operator timeout phase="+phase);
   if(!initialized){setup();return;}
   if(player!=null)check(player.getInventory().getItem(5).is(Items.EMERALD)&&player.getInventory().getItem(5).getCount()==11,"Unrelated fixture inventory changed");
   if(phase==1&&drained()){RepairService.arm("write",key);receipt=submit(1);step(2,"bootstrap-write-failure");}
   else if(phase==2&&receipt.isDone()&&RepairService.idle()){
    check(receipt.isCompletedExceptionally()&&record().state()==SnapshotJournal.State.PENDING&&RepairService.ioFaults.get()==1,"Failure precondition missing");RepairService.arm("",Long.MIN_VALUE);RepairService.schedule(key,record().token());step(3,"repair-first-snapshot");
   }else if(phase==3&&RepairService.idle()&&record().state()==SnapshotJournal.State.REPAIRED){task=projectIntent();step(4,"construct-durable-pending-intent");}
   else if(phase==4&&task.isDone()){
    task.join();check(RepairService.journal.resetPending(key)&&RepairService.blocked(key),"Uncertain seed not quarantined");if(CASE.equals("uncertain")){oracle.setProperty("latest","1");saveOracle();cut("OPERATOR_SEED_CUT");}else{receipt=submit(2);step(5,"known-newer-durable-snapshot");}
   }else if(phase==5&&receipt.isDone()&&RepairService.idle()){
    receipt.join();check(record().state()==SnapshotJournal.State.REPAIRED&&record().locked(),"Superseding snapshot must remain protected");rawItems(2);oracle.setProperty("latest","2");saveOracle();
    if(CASE.equals("disagreement")){var w=RepairService.worker();var storage=((WorkerAccess)(Object)w).anyaStorage();var tag=next(3);task=((WorkerAccess)(Object)w).anyaExecute(()->{try{((RegionAccess)(Object)storage).anyaWrite(new ChunkPos(key),tag);storage.flush();return Either.left(null);}catch(Exception ex){return Either.right(ex);}});step(6,"simulate-external-primary-disagreement");}
    else cut("OPERATOR_SEED_CUT");
   }else if(phase==6&&task.isDone()){task.join();rawItems(3);cut("OPERATOR_SEED_CUT");}
   else if(phase==20&&inspection.isDone()){
    old=inspection.join();unchanged();check(RepairService.blocked(key),"Pending storage reopened before resolution");
    if(CASE.equals("uncertain")||CASE.equals("disagreement")){
     String expect=CASE.equals("uncertain")?"REFUSE_UNCERTAIN_OLD_VERSION":"REFUSE_PRIMARY_JOURNAL_DISAGREEMENT";check(old.decision().equals(expect),"Wrong conservative decision: "+old.decision());refuse(old,old.disk().world(),key,old.disk().entry().token());unchanged();step(40,"refused-kept-quarantined");return;
    }
    check(old.decision().equals("ELIGIBLE_FOR_EXPLICIT_REVERIFY"),"Recoverable proof not eligible");
    boolean denied=false;try{OperatorResolution.inspect(server.createCommandSourceStack().withPermission(0),key);}catch(IOException expected){denied=true;}check(denied,"Nonoperator inspection accepted");
    denied=false;try{OperatorResolution.resolve(server.createCommandSourceStack().withPermission(0),old.disk().world(),key,old.disk().entry().token(),old.receipt());}catch(IOException expected){denied=true;}check(denied,"Nonoperator resolution accepted");
    refuse(old,UUID.randomUUID(),key,old.disk().entry().token());unchanged();
    inspection=OperatorResolution.inspect(server.createCommandSourceStack(),key);step(21,"fresh-confirmation");
   }else if(phase==21&&inspection.isDone()){
    old=inspection.join();if(CASE.equals("resolve")){receipt=submit(3);step(22,"invalidate-observed-token");}
    else{configureFault();resolve=OperatorResolution.resolve(server.createCommandSourceStack(),old.disk().world(),key,old.disk().entry().token(),old.receipt());step(25,"explicit-resolution-in-flight");}
   }else if(phase==22&&receipt.isDone()&&RepairService.idle()){
    receipt.join();hashes=files();refuse(old,old.disk().world(),key,old.disk().entry().token());unchanged();inspection=OperatorResolution.inspect(server.createCommandSourceStack(),key);step(23,"inspect-newest-after-stale-refusal");
   }else if(phase==23&&inspection.isDone()){
    old=inspection.join();refuse(old,old.disk().world(),key+1,old.disk().entry().token());unchanged();inspection=OperatorResolution.inspect(server.createCommandSourceStack(),key);step(24,"exact-current-token");
   }else if(phase==24&&inspection.isDone()){
    old=inspection.join();resolve=OperatorResolution.resolve(server.createCommandSourceStack(),old.disk().world(),key,old.disk().entry().token(),old.receipt());step(25,"explicit-resolution-in-flight");
   }else if(phase==25&&resolve.isDone()){
    if(CASE.endsWith("fail")){
     check(resolve.isCompletedExceptionally()&&injection.get()&&RepairService.globalBad&&RepairService.blocked(key),"Persistence failure did not retain quarantine");RepairService.journal.cuts(p->{});Trace.note("OPERATOR_PERSISTENCE_FAILURE_CONFIRMED",null,"case="+CASE+" original exception retained; no marker/temp removal");cut("OPERATOR_RESOLUTION_CUT");
    }else{
     resolve.join();check(!RepairService.blocked(key)&&record().state()==SnapshotJournal.State.APPLIED&&!RepairService.journal.resetPending(key),"Explicit reset failed");oracle.setProperty("latest","3");saveOracle();rawItems(3);cut("OPERATOR_RESOLUTION_CUT");
    }
   }else if(phase==30&&inspection.isDone()){
    unchanged();if(CASE.endsWith("fail")){check(inspection.isCompletedExceptionally()&&RepairService.globalBad&&RepairService.blocked(key),"Partial publication should refuse without deleting evidence");Trace.note("OPERATOR_INSPECTION_REFUSED_ON_RESTART",null,"partial publication retained; no mutation; target/global quarantine true");}
    else if(CASE.equals("cut-projection")){var o=inspection.join();check(o.decision().equals("REFUSE_UNCERTAIN_OLD_VERSION")&&RepairService.blocked(key),"Interrupted resolution incorrectly reopened");refuse(o,o.disk().world(),key,o.disk().entry().token());unchanged();}
    else{var o=inspection.join();check(o.decision().equals("REFUSE_NO_PENDING_INTENT")&&!RepairService.blocked(key),"Completed reset missing across restart");refuse(o,o.disk().world(),key,o.disk().entry().token());unchanged();}
    step(40,"independent-final-readback");
   }else if(phase==40&&now-phaseAt>=650&&drained()){
    Trace.audit(server);Trace.marker("PASSED","case="+CASE+" read-only inspection/refusal verified; exact world/key/token; original primary and journal evidence retained; independent raw readback;13 named items and4 chest identities;no actual clients");running=false;server.halt(false);
   }
  }catch(Throwable ex){running=false;try{if(RepairService.journal!=null)RepairService.journal.cuts(p->{});}catch(Exception ignored){}Trace.marker("FAILED","operator case="+CASE+" mode="+MODE+" phase="+phase+" "+ex);AnyaStorage.LOG.error("OPERATOR_FIXTURE_FAILED",ex);server.halt(false);}
 }
 private static void configureFault(){
  if(CASE.equals("intent-fail")||CASE.equals("completion-fail")){String point=CASE.equals("intent-fail")?"reset-intent-before-force":"reset-before-force";RepairService.journal.cuts(p->{if(p.equals(point)&&injection.compareAndSet(false,true))throw new IOException("Injected operator resolution publication failure "+point);});}
  if(CASE.equals("cut-projection")||CASE.equals("cut-complete")){String point=CASE.equals("cut-projection")?"reset-projection-published":"reset-published";RepairService.journal.cuts(p->{if(p.equals(point)){Trace.marker("OPERATOR_RESOLUTION_CUT","case="+CASE+" point="+point+" Runtime.halt77;no acknowledgement");Runtime.getRuntime().halt(77);}});}
 }
}

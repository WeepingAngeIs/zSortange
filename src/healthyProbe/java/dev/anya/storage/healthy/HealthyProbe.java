package dev.anya.storage.healthy;

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
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One-room deterministic successful-write boundaries; all connections are fixtures, never real clients. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class HealthyProbe {
 private static final String MODE=System.getProperty("anya.healthyRace","");
 private static final String LANE=System.getProperty("anya.healthyLane","repair");
 private static final String[] REPAIR_POINTS={"before-write","after-write","after-flush","after-proof"};
 private static final String[] RESET_POINTS={"reset-before-publication","reset-after-publication"};
 private static MinecraftServer server;
 private static ServerPlayer player;
 private static UUID roomId,oldToken;
 private static BlockPos box;
 private static long key,since,phaseAt;
 private static int phase,index,version,oldVersion;
 private static boolean running,initialized,submitted,resetRace;
 private static CompletableFuture<Void> receipt,operation,checkFuture;
 private static final Properties oracle=new Properties();
 private static String[] points(){return LANE.equals("reset")?RESET_POINTS:REPAIR_POINTS;}
 private static void check(boolean v,String s){if(!v)throw new IllegalStateException(s);}
 private static Object fixture(String field)throws Exception{var f=Fixture.class.getDeclaredField(field);f.setAccessible(true);return f.get(null);}
 private static ServerLevel level(){return PocketService.dimension(server);}
 private static PocketLedger.Pocket room()throws Exception{return PocketService.ledger(server).get(roomId);}
 private static SnapshotJournal.Record latest(){var r=RepairService.journal.get(key);check(r!=null,"Latest snapshot missing");return r;}
 private static ItemStack sample(int generation){var s=new ItemStack(Items.DIAMOND,13);s.set(DataComponents.CUSTOM_NAME,Component.literal("Healthy race gen="+generation));return s;}
 private static int generation(SnapshotJournal.Record r)throws Exception{return HealthyAudit.version(RepairService.decode(r.payload()));}
 private static void next(int p,String name){phase=p;phaseAt=server.getTickCount();Fixture.phase="healthy-"+LANE+"-"+name;HealthyAudit.note("HEALTHY_PHASE","index="+index+" phase="+p+" name="+name+" version="+version);}
 private static void saveOracle()throws Exception{
  oracle.setProperty("room",roomId.toString());oracle.setProperty("version",""+version);oracle.setProperty("oldToken",oldToken==null?"none":oldToken.toString());
  var bytes=new ByteArrayOutputStream();oracle.store(bytes,"Disposable assertion oracle, not player recovery state");
  try(var out=FileChannel.open(Path.of("healthy-oracle.properties"),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var b=java.nio.ByteBuffer.wrap(bytes.toByteArray());while(b.hasRemaining())out.write(b);out.force(true);}
 }
 private static boolean drained()throws IOException{var v=(OwnershipView)(Object)level().getChunkSource().chunkMap;return PocketService.ownedTicketRequests()==0&&level().getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0&&RepairService.idle()&&PocketService.ledger(server).returnCount()==0;}
 private static CompletableFuture<Void> raw(int expected,boolean staleProof){
  var worker=RepairService.worker();var storage=((WorkerAccess)(Object)worker).anyaStorage();var t=oldToken;
  return ((WorkerAccess)(Object)worker).anyaExecute(()->{try{
   var before=latest();if(staleProof){
    boolean refused=false;try{RepairService.journal.applied(key,t,true);}catch(IOException required){refused=true;}check(refused,"Stale proof changed newer journal record");
    refused=false;try{RepairService.journal.reset(key,t);}catch(IOException required){refused=true;}check(refused,"Stale reset changed newer journal record");
    check(latest().token().equals(before.token())&&latest().state()==before.state()&&latest().locked()==before.locked(),"Rejected stale metadata mutated latest state");
   }
   storage.flush();var tag=storage.read(new ChunkPos(key));check(HealthyAudit.version(tag)==expected,"Raw primary stale: expected="+expected+" observed="+HealthyAudit.version(tag));
   check(generation(latest())==expected,"Journal primary version mismatch");HealthyAudit.note("HEALTHY_RAW_CONFIRMED","version="+expected+" token="+latest().token()+" locked="+latest().locked()+" staleChecks="+staleProof);return Either.left(null);
  }catch(Exception e){return Either.right(e);}});
 }
 private static void staleService()throws Exception{
  boolean denied=false;try{RepairService.reset(key,oldToken);}catch(IOException correct){denied=true;}check(denied,"Service accepted superseded reset token");
  denied=false;try{RepairService.schedule(key,oldToken);}catch(IOException correct){denied=true;}check(denied,"Service accepted superseded repair token");
 }
 private static CompletableFuture<Void> submit(int newVersion)throws Exception{
  check(level().getChunkSource().getChunkNow(new ChunkPos(key).x,new ChunkPos(key).z)==null,"Target must be unloaded before detached submission");
  var tag=RepairService.decode(latest().payload());var list=tag.getList("block_entities",10);boolean found=false;
  for(int i=0;i<list.size();i++){var be=list.getCompound(i);if(be.getInt("x")==box.getX()&&be.getInt("y")==box.getY()&&be.getInt("z")==box.getZ()){
   var items=be.getList("Items",10);check(items.size()==1,"Do not overwrite unknown inventory");var item=(CompoundTag)sample(newVersion).save(server.registryAccess());item.putByte("Slot",(byte)0);items.set(0,item);found=true;
  }}check(found&&HealthyAudit.version(tag)==newVersion,"Newest detached snapshot missing");
  var future=RepairService.worker().store(new ChunkPos(key),tag);HealthyAudit.note("HEALTHY_NEW_SNAPSHOT_SUBMITTED","version="+newVersion+" currentToken="+latest().token()+" blocked="+RepairService.blocked(key));return future;
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void start(ServerStartedEvent e)throws Exception{
  if(MODE.isEmpty())return;server=e.getServer();check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"Disposable marker required");running=true;since=server.getTickCount();
  if(!MODE.equals("seed")){
   try(var in=Files.newInputStream(Path.of("healthy-oracle.properties"))){oracle.load(in);}roomId=UUID.fromString(oracle.getProperty("room"));version=Integer.parseInt(oracle.getProperty("version"));oldToken=UUID.fromString(oracle.getProperty("oldToken"));box=PocketService.chestPositions(room()).getFirst();key=new ChunkPos(box).toLong();
   check(PocketService.ledger(server).pockets().size()==1,"Restart allocated duplicate rooms");check(room().cell()==Integer.parseInt(oracle.getProperty("cell"))&&room().owner().toString().equals(oracle.getProperty("owner")),"Restart identity changed");
   check(level().getChunkSource().getChunkNow(new ChunkPos(key).x,new ChunkPos(key).z)==null,"Restart read-back must precede chunk load");
   check(generation(latest())==version,"Newest required snapshot lost at restart");
   if(MODE.equals("verify-proof"))check(latest().state()==SnapshotJournal.State.REPAIRED&&latest().locked()&&RepairService.blocked(key),"Pending reset quarantine lost");
   else check(latest().state()==SnapshotJournal.State.APPLIED&&!latest().locked()&&!RepairService.blocked(key),"Durable reset did not persist");
   initialized=true;checkFuture=raw(version,true);next(30,"restart-raw-readback");
  }
 }
 @SuppressWarnings("unchecked") private static void setup()throws Exception{
  var players=(List<ServerPlayer>)fixture("PLAYERS");if(players.isEmpty()||!PocketService.inPocket(players.getFirst().level()))return;
  var ids=(List<UUID>)fixture("IDS");roomId=ids.getFirst();check(room().stage()==PocketLedger.Stage.READY,"Entry before READY");
  var f=Fixture.class.getDeclaredField("running");f.setAccessible(true);f.setBoolean(null,false);player=players.getFirst();
  box=PocketService.chestPositions(room()).getFirst();key=new ChunkPos(box).toLong();int i=0;
  for(var pos:PocketService.chestPositions(room())){var c=(StorageEntity)level().getBlockEntity(pos);check(c!=null&&c.isEmpty(),"Seed refuses occupied storage");oracle.setProperty("chest."+(i++),c.storageIdentity().toString());}
  ((StorageEntity)level().getBlockEntity(box)).setItem(0,sample(0));oracle.setProperty("cell",""+room().cell());oracle.setProperty("owner",room().owner().toString());
  server.saveEverything(true,true,true);saveOracle();PocketService.recover(player);initialized=true;next(1,"drain-before-races");
 }
 private static void inspect()throws Exception{
  check(PocketService.ledger(server).pockets().size()==1,"Unexpected room count");int index=0,total=0;
  for(var pos:PocketService.chestPositions(room())){level().getChunk(pos.getX()>>4,pos.getZ()>>4);var c=(StorageEntity)level().getBlockEntity(pos);check(c!=null&&c.storageIdentity().toString().equals(oracle.getProperty("chest."+index))&&roomId.equals(c.pocketId)&&room().owner().equals(c.owner),"Physical chest identity mismatch");
   if(index==0){check(Transactions.same(c.getItem(0),sample(version)),"Physical newest item/component missing");total+=c.getItem(0).getCount();for(int s=1;s<c.getContainerSize();s++)check(c.getItem(s).isEmpty(),"Duplicate item slot");}else check(c.isEmpty(),"Extra items appeared");index++;
  }check(total==13&&index==4,"Inventory/chest conservation");Trace.marker("HEALTHY_PHYSICAL_RESTART","13 latest named diamonds, 1 room identity,4 physical chest identities; primary read before load confirmed");
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
  if(!running||server!=e.getServer())return;long now=server.getTickCount();try{
   check(now-since<2600&&now-phaseAt<900,"Bounded fixture timeout phase="+phase);check(!RepairService.globalBad,"Global fault in healthy race");if(HealthyAudit.violation!=null)throw new IllegalStateException(HealthyAudit.violation);
   if(!initialized){setup();return;}
   if(player!=null){var x=player.getInventory().getItem(5);check(x.is(Items.EMERALD)&&x.getCount()==11&&x.getHoverName().getString().equals("Durable preparation inventory"),"Unrelated player inventory changed");}
   if(phase==1&&drained()){
    check(!RepairService.blocked(key),"Unexpected initial quarantine");oldVersion=++version;RepairService.arm("write",key);receipt=submit(version);next(2,"bootstrap-only-write-failure");
   }else if(phase==2&&receipt.isDone()&&RepairService.idle()){
    check(receipt.isCompletedExceptionally()&&RepairService.ioFaults.get()==1,"Expected one bootstrap write failure");check(latest().state()==SnapshotJournal.State.PENDING&&latest().locked()&&generation(latest())==oldVersion,"Bootstrap newest record missing");
    oldToken=latest().token();RepairService.arm("",Long.MIN_VALUE);resetRace=LANE.equals("reset");submitted=false;
    if(!resetRace)HealthyAudit.arm(points()[index],key,oldVersion,oldToken);
    RepairService.schedule(key,oldToken);next(resetRace?5:3,"healthy-repair");
   }else if(phase==5&&RepairService.idle()&&latest().state()==SnapshotJournal.State.REPAIRED){
    check(HealthyAudit.healthyRepairs.contains(oldVersion),"Old physical repair never succeeded");HealthyAudit.arm(points()[index],key,oldVersion,oldToken);operation=RepairService.reset(key,oldToken);next(3,"executing-reset");
   }else if(phase==3&&HealthyAudit.entered&&!submitted){
    check(RepairService.ioFaults.get()==0&&RepairService.fault.isEmpty(),"Overlap must be healthy, not write-failing");
    receipt=submit(++version);submitted=true;check(RepairService.blocked(key),"New in-flight snapshot failed to quarantine");
    boolean refused=false;try{RepairService.reset(key,oldToken);}catch(IOException correct){refused=true;}check(refused,"Reset admitted while newer save in flight");
    HealthyAudit.release();next(4,"newer-save-and-proof");
   }else if(phase==4&&receipt.isDone()&&RepairService.idle()&&(!resetRace||operation.isDone())){
    receipt.join();check(HealthyAudit.healthyRepairs.contains(oldVersion),"Older physical write did not succeed");check(generation(latest())==version&&!latest().token().equals(oldToken),"New token/snapshot not published");
    check(latest().state()==SnapshotJournal.State.REPAIRED&&latest().locked()&&RepairService.blocked(key),"Older reset reopened newer generation without its explicit reset: point="+points()[index]+" state="+latest().state()+" locked="+latest().locked());
    if(resetRace)check(operation.isCompletedExceptionally(),"Superseded executing reset reported success");staleService();checkFuture=raw(version,true);next(6,"verify-newest-primary");
   }else if(phase==6&&checkFuture.isDone()){
    checkFuture.join();saveOracle();HealthyAudit.note("HEALTHY_CASE_OK","point="+points()[index]+" oldVersion="+oldVersion+" newestVersion="+version+" oldToken="+oldToken+" latestToken="+latest().token());
    if(index==points().length-1){Trace.marker("CUT_NEWEST_HEALTHY_PROOF","lane="+LANE+" version="+version+" primary flush/read-back + newest proof complete, reset intentionally pending");Runtime.getRuntime().halt(77);}
    operation=RepairService.reset(key,latest().token());next(7,"explicit-current-reset");
   }else if(phase==7&&operation.isDone()){
    operation.join();check(!RepairService.blocked(key),"Verified current reset did not reopen");index++;oldVersion=++version;RepairService.arm("write",key);receipt=submit(version);next(2,"bootstrap-next-case");
   }else if(phase==30&&checkFuture.isDone()){
    checkFuture.join();staleService();
    if(MODE.equals("verify-proof")){
     RepairService.journal.cuts(point->{if(point.equals("reset-published")){Trace.marker("CUT_HEALTHY_RESET_PUBLICATION","newest version="+version+" durable reset before acknowledgement");Runtime.getRuntime().halt(77);}});
     operation=RepairService.reset(key,latest().token());next(31,"reset-publication-cut");
    }else{inspect();next(40,"final-drain");}
   }else if(phase==31&&operation.isDone()){operation.join();throw new IllegalStateException("Expected reset cut did not occur");}
   else if(phase==40&&now-phaseAt>=650&&drained()){
    Trace.audit(server);Trace.marker("PASSED","healthy "+LANE+" boundaries, newest physical +journal version="+version+", stale proof/reset refused, independent restart, exact cleanup; fixture-only");running=false;server.halt(false);
   }
  }catch(Throwable t){HealthyAudit.release();running=false;Trace.marker("FAILED","healthy lane="+LANE+" mode="+MODE+" index="+index+" phase="+phase+" "+t);AnyaStorage.LOG.error("ANYA_HEALTHY_RACE_FAILED",t);server.halt(false);}
 }
 @SubscribeEvent public static void stopped(ServerStoppedEvent e){HealthyAudit.release();}
}

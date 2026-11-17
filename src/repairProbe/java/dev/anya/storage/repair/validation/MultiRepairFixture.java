package dev.anya.storage.repair.validation;
import dev.anya.storage.*;import dev.anya.storage.durable.*;import dev.anya.storage.minimal.OwnershipView;import dev.anya.storage.pocket.*;import dev.anya.storage.repair.*;import dev.anya.storage.repair.mixin.WorkerAccess;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.*;import net.minecraft.nbt.*;import net.minecraft.core.component.DataComponents;import net.minecraft.network.chat.Component;import net.minecraft.server.*;import net.minecraft.server.level.*;import net.minecraft.world.item.*;import net.minecraft.world.level.*;import net.minecraft.world.level.chunk.*;import net.minecraft.world.level.chunk.storage.*;import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;import net.neoforged.fml.common.EventBusSubscriber;import net.neoforged.neoforge.event.server.*;import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
/** Nine concurrent chunk keys, real storage worker. All player identities here are fixtures. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class MultiRepairFixture {
 private static final String MODE=System.getProperty("anya.multiRepair","");
 private static MinecraftServer s;private static boolean running,started;private static int phase;private static long since;
 private static List<ServerPlayer> players;private static final List<UUID> ids=new ArrayList<>();private static final List<BlockPos> boxes=new ArrayList<>();private static final List<Long> keys=new ArrayList<>();
 private static final List<CompletableFuture<Void>> receipts=new ArrayList<>();private static final List<UUID> tokens=new ArrayList<>();private static final Properties expected=new Properties();private static CompletableFuture<Void> task;private static int resetIndex;private static UUID old;private static final Map<Long,Integer> priorFailures=new HashMap<>();
 private static void check(boolean v,String m){if(!v)throw new IllegalStateException(m);}
 private static Object field(String name)throws Exception{var f=Fixture.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 private static ServerLevel l(){return PocketService.dimension(s);}private static PocketLedger.Pocket room(UUID id)throws Exception{return PocketService.ledger(s).get(id);}
 private static ItemStack item(int key,int version){var x=new ItemStack(Items.DIAMOND,13);x.set(DataComponents.CUSTOM_NAME,Component.literal("Multi snapshot "+key+" version "+version));return x;}
 private static StorageEntity box(int i){return (StorageEntity)l().getBlockEntity(boxes.get(i));}
 private static void step(int p,String name){phase=p;since=s.getTickCount();Fixture.phase="multi-"+name;Trace.note("MULTI_PHASE",null,"phase="+p+" "+name);}
 private static boolean drained(){var v=(OwnershipView)(Object)l().getChunkSource().chunkMap;return PocketService.ownedTicketRequests()==0&&l().getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0;}
 private static void ordinary(int i)throws Exception{
  var c=l().getChunkAt(boxes.get(i));check(c.isUnsaved(),"Not dirty before ordinary save");ValidationAudit.receipts.remove(keys.get(i));var m=ChunkMap.class.getDeclaredMethod("save",ChunkAccess.class);m.setAccessible(true);
  check((boolean)m.invoke(l().getChunkSource().chunkMap,c),"Save not submitted");var f=ValidationAudit.receipts.get(keys.get(i));check(f!=null,"Real store receipt missing");receipts.add(f);
 }
 private static void saveIds()throws Exception{
  if(!expected.containsKey("room.0"))for(int i=0;i<ids.size();i++){var r=room(ids.get(i));expected.setProperty("room."+i,r.id()+"/"+r.cell()+"/"+r.owner());int n=0;for(var p:PocketService.chestPositions(r))expected.setProperty("box."+i+"."+(n++),((StorageEntity)l().getBlockEntity(p)).storageIdentity().toString());}
  for(int i=0;i<keys.size();i++)expected.setProperty("key."+i,keys.get(i).toString());
  try(var out=Files.newOutputStream(Path.of("multi-identities.properties"))){expected.store(out,"Disposable exact identities");}
 }
 private static void inspect(boolean load)throws Exception{
  int count=0;check(PocketService.ledger(s).pockets().size()==8,"Room allocation changed");
  for(int i=0;i<8;i++){var r=room(ids.get(i));check(expected.getProperty("room."+i).equals(r.id()+"/"+r.cell()+"/"+r.owner()),"Room identity changed");int n=0;
   for(var p:PocketService.chestPositions(r)){
    if(load)l().getChunk(p.getX()>>4,p.getZ()>>4);else check(l().hasChunkAt(p),"Inspection would force load");var c=(StorageEntity)l().getBlockEntity(p);
    check(c!=null&&r.id().equals(c.pocketId)&&r.owner().equals(c.owner)&&c.storageIdentity().toString().equals(expected.getProperty("box."+i+"."+n++)),"Chest identity changed");
    int k=boxes.indexOf(p);if(k<0)check(c.isEmpty(),"Unexpected extra stack");else{int v=Integer.parseInt(expected.getProperty("version."+k));check(Transactions.same(c.getItem(0),item(k,v)),"Wrong latest snapshot key="+k+" expect="+v);count+=13;for(int slot=1;slot<c.getContainerSize();slot++)check(c.getItem(slot).isEmpty(),"Duplicate stack");}
   }
  }check(count==117,"Conservation must be 117");Trace.marker("MULTI_ITEMS","117 exact named items; 8 original rooms and32 original physical chest IDs");
 }
 @SubscribeEvent public static void start(ServerStartedEvent event)throws Exception{
  if(MODE.isEmpty())return;s=event.getServer();check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"Unmarked world refused");running=true;
  if(MODE.startsWith("verify")){
   try(var in=Files.newInputStream(Path.of("multi-identities.properties"))){expected.load(in);}for(int i=0;i<8;i++)ids.add(UUID.fromString(expected.getProperty("room."+i).split("/")[0]));
   for(var id:ids)boxes.add(PocketService.chestPositions(room(id)).getFirst());boxes.add(PocketService.chestPositions(room(ids.getFirst())).getLast());for(int i=0;i<9;i++){keys.add(Long.parseLong(expected.getProperty("key."+i)));check(new ChunkPos(boxes.get(i)).toLong()==keys.get(i),"Key identity mismatch");}
   check(l().getChunkSource().getLoadedChunksCount()==0,"Restart destination not cold");started=true;
   if(MODE.equals("verify-corrupt")||MODE.equals("verify-full")){check(RepairService.globalBad,"Damaged/full journal silently healthy");try{RepairService.reset(keys.getFirst(),UUID.randomUUID());throw new IllegalStateException("Fatal reset accepted");}catch(IOException correct){}step(80,"fatal-no-blind-reset");}
   else{check(!RepairService.globalBad,"Unexpected fatal journal");step(50,"restart-repair-unfinished");}
  }
 }
 @SuppressWarnings("unchecked") private static void begin()throws Exception{
  var f=Fixture.class.getDeclaredField("running");f.setAccessible(true);f.setBoolean(null,false);players=(List<ServerPlayer>)field("PLAYERS");ids.addAll((List<UUID>)field("IDS"));
  for(var id:ids)boxes.add(PocketService.chestPositions(room(id)).getFirst());boxes.add(PocketService.chestPositions(room(ids.getFirst())).getLast());
  for(int i=0;i<9;i++){keys.add(new ChunkPos(boxes.get(i)).toLong());check(box(i).isEmpty(),"Refusing overwrite");box(i).setItem(0,item(i,0));expected.setProperty("version."+i,"0");}
  check(new HashSet<>(keys).size()==9,"Fixture must have nine distinct storage keys");saveIds();s.saveEverything(true,true,true);started=true;
  if(MODE.equals("full")){
   // Fill the actual payload quota using structurally valid no-item NBT padding records. No invented chests or transferred items.
   task=((WorkerAccess)(Object)RepairService.worker()).anyaExecute(()->{try{
    long key=Long.MAX_VALUE-100;while(RepairService.journal.bytes()<SnapshotJournal.MAX_TOTAL){
     long left=SnapshotJournal.MAX_TOTAL-RepairService.journal.bytes();
     var t=new CompoundTag();t.putInt("xPos",new ChunkPos(key).x);t.putInt("zPos",new ChunkPos(key).z);t.putByteArray("QuotaFixturePadding",new byte[0]);int overhead=RepairService.encode(t).length;
     // The encoder bounds both estimated NBT memory and serialized bytes. Account for both; never raise the production limits.
     long safeEncoded=Math.min(SnapshotJournal.MAX_BYTES,SnapshotJournal.MAX_BYTES-(long)t.sizeInBytes()+overhead);
     int len=(int)Math.min(left,safeEncoded);
     if(left>len&&left-len<=overhead)len-=overhead+1-(int)(left-len);
     if(len<=overhead)throw new IOException("Padding remainder too small");t.putByteArray("QuotaFixturePadding",new byte[len-overhead]);
     byte[] payload=RepairService.encode(t);check(payload.length==len&&t.sizeInBytes()<=SnapshotJournal.MAX_BYTES,"Fixture padding violates an existing encoder bound");
     var r=RepairService.journal.pending(key,payload);RepairService.journal.applied(key,r.token(),false);key--;
    }Trace.marker("ACTUAL_JOURNAL_QUOTA_FILLED","payload="+RepairService.journal.bytes()+" records="+RepairService.journal.all().size()+" padding records contain no items");return Either.left(null);
   }catch(Exception ex){return Either.right(ex);}});step(70,"fill-payload-quota");return;
  }
  for(int i=0;i<9;i++){ValidationAudit.FAIL.add(keys.get(i));box(i).setItem(0,item(i,1));expected.setProperty("version."+i,"1");}
  saveIds();receipts.clear();for(int i=0;i<9;i++)ordinary(i);Trace.marker("NINE_CONCURRENT_SUBMISSIONS","9 same-tick submissions to real serialized engine worker, not nine parallel disk threads");step(1,"initial-failures");
 }
 private static void releasePlayers(){if(players!=null)for(var p:players){p.closeContainer();PocketService.recover(p);}}
 private static void denyOld(long key,UUID token)throws Exception{try{RepairService.schedule(key,token);throw new IllegalStateException("Stale repair accepted");}catch(IOException good){}try{RepairService.reset(key,token);throw new IllegalStateException("Stale reset accepted");}catch(IOException good){}}
 private static void finish()throws Exception{Trace.audit(s);Trace.marker("PASSED","multi-key "+MODE+" latest identities preserved; globalBad="+RepairService.globalBad);running=false;s.halt(false);}
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event){
  if(!running||s!=event.getServer())return;long now=s.getTickCount();try{
   check(now<5200,"Multi-key tick ceiling");if(!started){if(Fixture.phase.equals("visit-steady"))begin();return;}
   if(players!=null)for(var p:players){check(p.getInventory().getItem(5).is(Items.EMERALD)&&p.getInventory().getItem(5).getCount()==11,"Fixture player changed");for(int x=0;x<p.getInventory().getContainerSize();x++)check(!p.getInventory().getItem(x).is(Items.DIAMOND),"Chest item leaked to player");}
   if(phase==1&&receipts.stream().allMatch(CompletableFuture::isDone)){
    check(receipts.stream().allMatch(CompletableFuture::isCompletedExceptionally),"Expected actual worker errors");for(long k:keys){var r=RepairService.journal.get(k);check(r!=null&&RepairService.journal.blocked(k),"Required record missing");tokens.add(r.token());}releasePlayers();step(2,"drain-before-unloaded-repair");
   }else if(phase==2&&now-since>=650&&drained()&&RepairService.idle()){
    if(MODE.equals("corrupt")){Trace.marker("CUT_CORRUPT_TARGET","key="+Long.toUnsignedString(keys.getFirst(),16)+" expected latest1 but no valid copy after test corruption");Runtime.getRuntime().halt(77);}
    tokens.clear();for(long k:keys){tokens.add(RepairService.journal.get(k).token());priorFailures.put(k,ValidationAudit.failures.get(k).get());}
    for(int i=0;i<8;i++)RepairService.schedule(keys.get(i),tokens.get(i));
    try{RepairService.schedule(keys.get(8),tokens.get(8));throw new IllegalStateException("Ninth intent exceeded quota");}catch(IOException good){check(RepairService.journal.get(keys.get(8)).token().equals(tokens.get(8)),"Quota eviction lost snapshot");}
    ValidationAudit.FAIL.remove(keys.get(7));step(3,"eight-intents-seven-persistent-errors");
   }else if(phase==3&&now-since>=200&&RepairService.idle()){
    for(int i=0;i<7;i++)check(RepairService.journal.blocked(keys.get(i))&&ValidationAudit.failures.get(keys.get(i)).get()-priorFailures.get(keys.get(i))==3,"Three retry bound not observed for key"+i);
    check(RepairService.journal.get(keys.get(7)).state()==SnapshotJournal.State.REPAIRED,"Healthy key starved");
    check(RepairService.journal.get(keys.get(8)).token().equals(tokens.get(8)),"Waiting ninth snapshot lost");
    Trace.marker("MULTI_RETRY_FAIRNESS","seven keys each3 retries; eighth successful despite failures; ninth record retained on quota refusal");
    old=tokens.getFirst();RepairService.schedule(keys.getFirst(),old);
    var next=RepairService.decode(RepairService.journal.get(keys.getFirst()).payload());var bes=next.getList("block_entities",10);for(int i=0;i<bes.size();i++){var be=bes.getCompound(i);if(be.getInt("x")==boxes.getFirst().getX()&&be.getInt("z")==boxes.getFirst().getZ()){var items=be.getList("Items",10);check(!items.isEmpty(),"No supersession sample");var replacement=item(0,2).save(s.registryAccess());((CompoundTag)replacement).putByte("Slot",(byte)0);items.set(0,replacement);}}
    task=RepairService.worker().store(new ChunkPos(keys.getFirst()),next);expected.setProperty("version.0","2");saveIds();step(4,"newer-snapshot-supersedes-queued-token");
   }else if(phase==4&&task.isDone()&&now-since>30&&RepairService.idle()){
    check(task.isCompletedExceptionally(),"Newer primary write should fail");var current=RepairService.journal.get(keys.getFirst());check(!current.token().equals(old),"No new generation");denyOld(keys.getFirst(),old);
    check(RepairService.decode(current.payload()).toString().contains("version 2"),"Stale intent overwrote newer snapshot");Trace.marker("MULTI_STALE_TOKEN_REFUSED","old="+old+" new="+current.token());
    ValidationAudit.FAIL.clear();for(int i=0;i<8;i++){var r=RepairService.journal.get(keys.get(i));if(r.state()!=SnapshotJournal.State.REPAIRED)RepairService.schedule(r.key(),r.token());}var ninth=RepairService.journal.get(keys.get(8));RepairService.schedule(ninth.key(),ninth.token());step(5,"explicit-all-key-repair");
   }else if(phase==5&&RepairService.idle()){
    for(long k:keys)check(RepairService.journal.get(k).state()==SnapshotJournal.State.REPAIRED&&RepairService.blocked(k),"Repair reopened without reset");
    // Cut while reset publication is durably finished but in-memory acknowledgement has not occurred.
    RepairService.journal.cuts(p->{if(p.equals("reset-published")){Trace.marker("CUT_RESET_PUBLISHED","old token still memory; new APPLIED proof is durable; no transfer replay");Runtime.getRuntime().halt(77);}});
    task=RepairService.reset(keys.getFirst(),RepairService.journal.get(keys.getFirst()).token());step(6,"interrupted-reset-publication");
   }else if(phase==50){
    for(var r:RepairService.journal.all())if(RepairService.journal.blocked(r.key())&&r.state()!=SnapshotJournal.State.REPAIRED)RepairService.schedule(r.key(),r.token());step(51,"finish-repair-after-restart");
   }else if(phase==51&&RepairService.idle()){resetIndex=0;task=null;step(52,"explicit-reset-after-restart");}
   else if(phase==52){
    if(task!=null){if(!task.isDone())return;task.join();task=null;resetIndex++;}
    while(resetIndex<keys.size()&&!RepairService.journal.blocked(keys.get(resetIndex)))resetIndex++;
    if(resetIndex<keys.size()){var r=RepairService.journal.get(keys.get(resetIndex));task=RepairService.reset(r.key(),r.token());}else{inspect(true);step(81,"verify-drain");}
   }else if(phase==70&&task.isDone()){
    task.join();check(RepairService.journal.bytes()==SnapshotJournal.MAX_TOTAL,"Quota not exact");old=RepairService.journal.get(keys.getFirst()).token();box(0).setItem(0,item(0,1000));expected.setProperty("version.0","1000");saveIds();receipts.clear();ordinary(0);step(71,"full-journal-primary-fallback");
   }else if(phase==71&&receipts.getFirst().isDone()){
    check(receipts.getFirst().isCompletedExceptionally()&&RepairService.globalBad,"Quota must refuse journal protection");check(RepairService.journal.get(keys.getFirst()).token().equals(old)&&RepairService.journal.bytes()==SnapshotJournal.MAX_TOTAL,"Full journal evicted/overwrote old copy");releasePlayers();step(72,"full-drain");
   }else if(phase==72&&now-since>=650&&drained()){Trace.marker("CUT_FULL_FALLBACK","primary newest version1000 saved; journal quota fault persists; no reset claimed");Runtime.getRuntime().halt(77);}
   else if(phase==80){if(MODE.equals("verify-corrupt")){for(int i=0;i<9;i++)expected.setProperty("version."+i,"0");}inspect(true);step(81,"fatal-restart-drain");}
   else if(phase==81&&now-since>=650&&drained())finish();
  }catch(Throwable ex){running=false;Trace.marker("FAILED","multi mode="+MODE+" phase="+phase+" "+ex);AnyaStorage.LOG.error("ANYA_MULTI_REPAIR_FAILED",ex);s.halt(false);}
 }
}

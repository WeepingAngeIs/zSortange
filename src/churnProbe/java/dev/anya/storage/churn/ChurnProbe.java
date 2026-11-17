package dev.anya.storage.churn;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import dev.anya.storage.*;
import dev.anya.storage.durable.*;
import dev.anya.storage.minimal.OwnershipView;
import dev.anya.storage.pocket.*;
import dev.anya.storage.repair.*;
import dev.anya.storage.repair.mixin.WorkerAccess;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Bounded engine snapshot stress. All users in this lane are server fixtures, not connected clients. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class ChurnProbe {
 private static final String MODE=System.getProperty("anya.repairChurn","");
 private static final boolean REVERSE=Boolean.getBoolean("anya.churnReverse");
 private static MinecraftServer server;
 private static boolean running,started,viewers;
 private static int phase,round,resetAt;
 private static long since,phaseAt,submitted,saturated,transactions,peakQueue;
 private static final List<UUID> rooms=new ArrayList<>();
 private static final List<BlockPos> boxes=new ArrayList<>();
 private static final List<Long> keys=new ArrayList<>();
 private static List<ServerPlayer> initial;
 private static final List<ServerPlayer> observers=new ArrayList<>();
 private static final List<StorageEntity> hubs=new ArrayList<>();
 private static final long[] deadlines=new long[2];
 private static final int[] generation=new int[9],order=new int[9];
 private static final List<CompletableFuture<Void>> receipts=new ArrayList<>();
 private static final Map<Long,UUID> tokens=new HashMap<>();
 private static final Properties expected=new Properties();
 private static CompletableFuture<Void> task;
 private static BufferedWriter csv;
 private static void check(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
 private static ServerLevel level(){return PocketService.dimension(server);}
 private static PocketLedger.Pocket room(int i)throws Exception{return PocketService.ledger(server).get(rooms.get(i));}
 private static Object fixture(String name)throws Exception{var f=Fixture.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 private static Object privateValue(Object x,String name)throws Exception{var f=x.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(x);}
 @SuppressWarnings("unchecked") private static Map<Long,?> queue()throws Exception{var f=RepairService.class.getDeclaredField("retries");f.setAccessible(true);return (Map<Long,?>)f.get(null);}
 private static boolean dispatched(long key)throws Exception{var r=queue().get(key);return r!=null&&privateValue(r,"future")!=null;}
 private static ItemStack item(int i,int version){var s=new ItemStack(Items.DIAMOND,13);s.set(DataComponents.CUSTOM_NAME,Component.literal("Churn key="+i+" gen="+version));return s;}
 private static ItemStack healthy(int i){var s=new ItemStack(Items.EMERALD,17);s.set(DataComponents.CUSTOM_NAME,Component.literal("Unrelated viewer "+i));return s;}
 private static void step(int p,String name){phase=p;phaseAt=server.getTickCount();Fixture.phase="churn-"+name;Trace.note("CHURN_PHASE",null,"round="+round+" phase="+p+" "+name);}
 private static void saveExpected()throws Exception{
  expected.setProperty("round",""+round);for(int i=0;i<9;i++)expected.setProperty("version."+i,""+generation[i]);
  try(var out=Files.newOutputStream(Path.of("churn-identities.properties"))){expected.store(out,"Disposable workload oracle, not a game recovery journal");}
 }
 private static void geometry()throws Exception{
  for(int i=0;i<6;i++)boxes.add(PocketService.chestPositions(room(i)).getFirst());
  for(int i=0;i<3;i++)boxes.add(PocketService.chestPositions(room(i)).getLast());
  for(var b:boxes)keys.add(new ChunkPos(b).toLong());check(new HashSet<>(keys).size()==9,"Must use nine distinct chunk keys");
 }
 private static ServerPlayer login(int index){
  var profile=new GameProfile(UUID.nameUUIDFromBytes(("DurableFixture"+index).getBytes(StandardCharsets.UTF_8)),"Durable"+index);
  var p=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());
  var c=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND){
   @Override public boolean isMemoryConnection(){return true;}
   @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener callback,boolean flush){}
  };
  new io.netty.channel.embedded.EmbeddedChannel(c);net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(c);
  server.getPlayerList().placeNewPlayer(c,p,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false));return p;
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void start(ServerStartedEvent event)throws Exception{
  if(MODE.isEmpty())return;server=event.getServer();check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"Only disposable fixtures");
  var rules=ExpansionRules.get();rules.pocketStorage=true;rules.pocketEditing=false;rules.pocketLimit=16;rules.activePocketLimit=8;rules.pocketSessionTicks=12000;
  running=true;since=server.getTickCount();
  csv=Files.newBufferedWriter(Trace.output().resolve("churn.csv"));csv.write("tick,phase,round,retry_queue,records,pending,repaired,payload_bytes,submitted,worker_attempts,worker_success,worker_failures,transactions,saturated,heap_used,heap_committed,cache,requests\n");
  if(!MODE.equals("seed")){
   try(var in=Files.newInputStream(Path.of("churn-identities.properties"))){expected.load(in);}round=Integer.parseInt(expected.getProperty("round"));
   for(int i=0;i<8;i++)rooms.add(UUID.fromString(expected.getProperty("room."+i).split("/")[0]));geometry();
   for(int i=0;i<9;i++){generation[i]=Integer.parseInt(expected.getProperty("version."+i));check(level().getChunkSource().getChunkNow(new ChunkPos(keys.get(i)).x,new ChunkPos(keys.get(i)).z)==null,"Target must restart unloaded");var r=RepairService.journal.get(keys.get(i));check(r!=null&&ChurnAudit.version(RepairService.decode(r.payload()))==generation[i],"Newest required journal missing on restart");}
   for(int i=6;i<8;i++){var hp=new BlockPos(1+i*4,81,1);server.overworld().getChunk(hp.getX()>>4,hp.getZ()>>4);hubs.add((StorageEntity)server.overworld().getBlockEntity(hp));observers.add(login(i));}
   started=true;step(1,"restart-observers");
  }
 }
 @SuppressWarnings("unchecked") private static void setup()throws Exception{
  var f=Fixture.class.getDeclaredField("running");f.setAccessible(true);f.setBoolean(null,false);
  initial=(List<ServerPlayer>)fixture("PLAYERS");rooms.addAll((List<UUID>)fixture("IDS"));var hs=(List<StorageEntity>)fixture("HUBS");geometry();
  for(int i=0;i<8;i++){var r=room(i);expected.setProperty("room."+i,r.id()+"/"+r.cell()+"/"+r.owner());int n=0;for(var p:PocketService.chestPositions(r))expected.setProperty("chest."+i+"."+(n++),((StorageEntity)level().getBlockEntity(p)).storageIdentity().toString());}
  for(int i=0;i<9;i++){var b=(StorageEntity)level().getBlockEntity(boxes.get(i));check(b.isEmpty(),"Do not overwrite fixture contents");b.setItem(0,item(i,0));}
  for(int i=6;i<8;i++){var b=(StorageEntity)level().getBlockEntity(PocketService.chestPositions(room(i)).getFirst());b.setItem(0,healthy(i));observers.add(initial.get(i));hubs.add(hs.get(i));}
  server.saveEverything(true,true,true);saveExpected();for(var p:initial)PocketService.recover(p);started=true;step(1,"return-before-unloaded-work");
 }
 private static void openViewers(){for(int i=0;i<2;i++){var p=observers.get(i);PocketService.remote(p,p.getInventory().getItem(0),hubs.get(i));}}
 private static void stable()throws Exception{
  if(!viewers)return;
  for(int i=0;i<2;i++){var p=observers.get(i);check(p.containerMenu instanceof StoreMenu m&&m.hasPocket(rooms.get(i+6)),"Unrelated viewer lost menu");var lease=PocketService.lease(p.getUUID());check(lease!=null&&lease.deadline()==deadlines[i],"Independent deadline changed");}
  if(server.getTickCount()%80!=0)return;
  for(int i=0;i<2;i++){var p=observers.get(i);var b=(StorageEntity)level().getBlockEntity(PocketService.chestPositions(room(i+6)).getFirst());var dest=new MainInventory(p);var view=new PocketView(p,room(i+6),PocketLeases.Kind.REMOTE);
   var a=new Transactions.Plan(List.of(b,dest),view.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));check(a.move(b,0,List.of(dest),1)==1&&a.commit(server).isEmpty(),"Healthy guarded withdrawal failed");
   int slot=-1;for(int j=0;j<p.getInventory().getContainerSize();j++)if(p.getInventory().getItem(j).getHoverName().getString().equals("Unrelated viewer "+(i+6)))slot=j;check(slot>=0,"Withdrawn healthy sample absent");
   var z=new Transactions.Plan(List.of(b,dest),view.guard(BasePermissions.DEPOSIT));check(z.move(dest,slot,List.of(b),1)==1&&z.commit(server).isEmpty(),"Healthy guarded deposit failed");check(Transactions.same(b.getItem(0),healthy(i+6)),"Healthy sample changed");transactions++;
  }
 }
 private static boolean targetsCold(){for(long k:keys){var cp=new ChunkPos(k);if(level().getChunkSource().getChunkNow(cp.x,cp.z)!=null)return false;}return true;}
 private static void submit(int i)throws Exception{
  long key=keys.get(i);var r=RepairService.journal.get(key);check(r!=null,"Missing previous snapshot");var tag=RepairService.decode(r.payload());var bes=tag.getList("block_entities",10);boolean found=false;
  for(int x=0;x<bes.size();x++){var be=bes.getCompound(x);if(be.getInt("x")==boxes.get(i).getX()&&be.getInt("y")==boxes.get(i).getY()&&be.getInt("z")==boxes.get(i).getZ()){
   var list=be.getList("Items",10);check(list.size()==1,"Sample layout changed");var saved=(CompoundTag)item(i,++generation[i]).save(server.registryAccess());saved.putByte("Slot",(byte)0);list.set(0,saved);found=true;
  }}check(found&&ChurnAudit.version(tag)==generation[i],"New generation absent");
  receipts.add(RepairService.worker().store(new ChunkPos(key),tag));submitted++;
  Trace.note("CHURN_SNAPSHOT_SUBMITTED",null,"round="+round+" key="+key+" generation="+generation[i]+" existingRequired="+r.token());
 }
 private static void roundStart()throws Exception{
  check(targetsCold()&&RepairService.idle(),"Round starts with loaded/inflight target");receipts.clear();tokens.clear();ChurnAudit.FAIL.addAll(keys);
  for(int j=0;j<9;j++){int i=(round+(REVERSE?8-j:j))%9;order[j]=i;submit(i);submit(i);}
  saveExpected();step(10,"concurrent-same-tick-submissions");
 }
 private static void currentSnapshots()throws Exception{for(int i=0;i<9;i++){var r=RepairService.journal.get(keys.get(i));check(r!=null&&ChurnAudit.version(RepairService.decode(r.payload()))==generation[i],"Newest snapshot dropped key="+i);}}
 private static void refuseReset(long key,UUID token)throws Exception{try{RepairService.reset(key,token);throw new IllegalStateException("Unverified reset admitted");}catch(IOException required){}}
 private static void scheduleLatest()throws Exception{
  check(targetsCold(),"Repair must not target loaded chunks");for(int j=0;j<9;j++){int i=(round+(REVERSE?8-j:j))%9;order[j]=i;var r=RepairService.journal.get(keys.get(i));if(!RepairService.journal.blocked(r.key())||r.state()==SnapshotJournal.State.REPAIRED)continue;
   try{RepairService.schedule(r.key(),r.token());}catch(IOException quota){check(queue().size()==8&&RepairService.journal.get(r.key()).token().equals(r.token()),"Unexpected repair refusal or lost required token: "+quota);saturated++;}
  }
 }
 private static CompletableFuture<Void> rawVerify(){
  var w=RepairService.worker();var storage=((WorkerAccess)(Object)w).anyaStorage();int[] expectedGeneration=generation.clone();
  return ((WorkerAccess)(Object)w).anyaExecute(()->{try{storage.flush();for(int i=0;i<9;i++){var tag=storage.read(new ChunkPos(keys.get(i)));check(tag!=null&&ChurnAudit.version(tag)==expectedGeneration[i],"Raw primary is stale key="+i+" expected="+expectedGeneration[i]);}return Either.left(null);}catch(Exception e){return Either.right(e);}});
 }
 private static void inspectLoaded()throws Exception{
  int diamonds=0,emeralds=0;check(PocketService.ledger(server).pockets().size()==8,"Room count changed");
  for(int i=0;i<8;i++){var r=room(i);check(expected.getProperty("room."+i).equals(r.id()+"/"+r.cell()+"/"+r.owner()),"Room/cell/owner changed");int n=0;for(var p:PocketService.chestPositions(r)){
   level().getChunk(p.getX()>>4,p.getZ()>>4);var c=(StorageEntity)level().getBlockEntity(p);check(c!=null&&c.storageIdentity().toString().equals(expected.getProperty("chest."+i+"."+n))&&r.id().equals(c.pocketId)&&r.owner().equals(c.owner),"Physical chest identity changed");
   int k=boxes.indexOf(p);if(k>=0){check(Transactions.same(c.getItem(0),item(k,generation[k])),"Newest stack missing or changed");diamonds+=13;}else if(i>=6&&n==0){check(Transactions.same(c.getItem(0),healthy(i)),"Unrelated contents changed");emeralds+=17;}else check(c.isEmpty(),"Extra items appeared");
   for(int z=1;z<c.getContainerSize();z++)check(c.getItem(z).isEmpty(),"Duplicate stack");n++;
  }}check(diamonds==117&&emeralds==34,"Conservation failed");Trace.marker("CHURN_PHYSICAL_ITEMS","117 latest named diamonds +34 unrelated emeralds;8 rooms and32 physical chest identities");
 }
 private static void emit()throws Exception{
  var all=RepairService.journal.all();long pending=all.stream().filter(r->r.state()==SnapshotJournal.State.PENDING).count(),repaired=all.stream().filter(r->r.state()==SnapshotJournal.State.REPAIRED).count();int q=queue().size();peakQueue=Math.max(peakQueue,q);check(q<=8,"Retry queue unbounded");var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
  check(heap.getUsed()<heap.getMax()*.92&&level().getChunkSource().getLoadedChunksCount()<15000,"Predefined memory/cache guard");
  csv.write(server.getTickCount()+","+phase+","+round+","+q+","+all.size()+","+pending+","+repaired+","+RepairService.journal.bytes()+","+submitted+","+ChurnAudit.attempted+","+ChurnAudit.completed+","+ChurnAudit.failures.values().stream().mapToInt(x->x.get()).sum()+","+transactions+","+saturated+","+heap.getUsed()+","+heap.getCommitted()+","+level().getChunkSource().getLoadedChunksCount()+","+PocketService.ownedTicketRequests()+"\n");if(server.getTickCount()%20==0)csv.flush();
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event){
  if(!running||server!=event.getServer())return;long now=server.getTickCount();try{
   check(now-since<8000,"Bounded total tick ceiling");check(!RepairService.globalBad,"Unexpected global journal quarantine during valid supersession");if(ChurnAudit.violation!=null)throw new IllegalStateException(ChurnAudit.violation);
   if(!started){if(Fixture.phase.equals("visit-steady"))setup();return;}emit();stable();check(now-phaseAt<1600,"Bounded stage deadline phase="+phase);
   if(phase==1){if(initial!=null&&initial.stream().anyMatch(p->PocketService.inPocket(p.level())))return;if(PocketService.ledger(server).returnCount()!=0)return;openViewers();step(2,"independent-viewers");}
   else if(phase==2&&observers.stream().allMatch(p->p.containerMenu instanceof StoreMenu)){
    for(int i=0;i<2;i++)deadlines[i]=PocketService.lease(observers.get(i).getUUID()).deadline();viewers=true;step(3,"unload-targets-only");
   }else if(phase==3&&targetsCold()&&RepairService.idle()){
    if(MODE.equals("seed"))roundStart();else{currentSnapshots();ChurnAudit.FAIL.clear();if(MODE.equals("resume")){for(int j=0;j<7;j++)ChurnAudit.FAIL.add(keys.get((round+(REVERSE?8-j:j))%9));scheduleLatest();step(30,"restarted-storage-still-failing");}else{scheduleLatest();step(14,"restart-queued-repair");}}
   }else if(phase==10&&receipts.stream().allMatch(CompletableFuture::isDone)){
    check(receipts.stream().allMatch(CompletableFuture::isCompletedExceptionally),"Expected real failed ordinary writes");currentSnapshots();tokens.clear();for(long k:keys)tokens.put(k,RepairService.journal.get(k).token());
    ChurnAudit.arm(keys.get(order[0]),generation[order[0]]);ChurnAudit.FAIL.remove(keys.get(order[7]));
    for(int j=0;j<8;j++)RepairService.schedule(keys.get(order[j]),tokens.get(keys.get(order[j])));
    try{RepairService.schedule(keys.get(order[8]),tokens.get(keys.get(order[8])));throw new IllegalStateException("Ninth intent admitted");}catch(IOException expectedQuota){check(queue().size()==8&&RepairService.journal.get(keys.get(order[8])).token().equals(tokens.get(keys.get(order[8]))),"Quota dropped snapshot");saturated++;}
    step(11,"executing-and-queued-old-repairs");
   }else if(phase==11&&ChurnAudit.gateEntered&&dispatched(keys.get(order[1]))){
    check(!RepairService.idle(),"Held repair unexpectedly completed");submit(order[0]);submit(order[1]);saveExpected();
    for(int j=0;j<2;j++)refuseReset(keys.get(order[j]),tokens.get(keys.get(order[j])));
    Trace.note("CHURN_NEWER_DURING_EXECUTION",null,"round="+round+" executing="+keys.get(order[0])+" queued="+keys.get(order[1])+" worker_only_gate=true");ChurnAudit.release();step(12,"bounded-persistent-retries");
   }else if(phase==12&&now-phaseAt>=180&&RepairService.idle()&&receipts.stream().allMatch(CompletableFuture::isDone)){
    currentSnapshots();check(RepairService.journal.get(keys.get(order[7])).state()==SnapshotJournal.State.REPAIRED,"Healthy repair starved behind failed keys");
    for(int j=0;j<2;j++){long k=keys.get(order[j]);check(!RepairService.journal.get(k).token().equals(tokens.get(k)),"New snapshot token absent");try{RepairService.schedule(k,tokens.get(k));throw new IllegalStateException("Stale schedule accepted");}catch(IOException required){}refuseReset(k,tokens.get(k));}
    check(RepairService.journal.get(keys.get(order[8])).token().equals(tokens.get(keys.get(order[8]))),"Ninth required record lost");
    Trace.note("CHURN_FAILURE_ROUND_OK",null,"round="+round+" latest preserved; healthy key progressed; stale tokens refused; retry queue drained");saveExpected();
    if(MODE.equals("seed")&&round==5){Trace.marker("CHURN_STOP_PENDING","six sustained rounds; newest failure snapshots unresolved; two unrelated viewers active; original shutdown saves retained");running=false;server.halt(false);}
    else{ChurnAudit.FAIL.clear();scheduleLatest();step(14,"repair-after-storage-recovers");}
   }else if(phase==30&&now-phaseAt>=180&&RepairService.idle()){
    currentSnapshots();for(long k:ChurnAudit.FAIL){check(ChurnAudit.failures.getOrDefault(k,new java.util.concurrent.atomic.AtomicInteger()).get()==3&&RepairService.blocked(k),"Restarted failing key not bounded at three attempts");}
    Trace.note("CHURN_RESTARTED_FAILURES_BOUNDED",null,"seven keys each3 attempts; required newest snapshots retained");ChurnAudit.FAIL.clear();RepairService.cut="repair-after-region";RepairService.faultKey=keys.get((round+(REVERSE?8:0))%9);scheduleLatest();step(14,"explicit-repair-after-restart-fault-clear");
   }else if(phase==14&&RepairService.idle()){
    currentSnapshots();boolean remaining=false;for(long k:keys)if(RepairService.journal.get(k).state()!=SnapshotJournal.State.REPAIRED&&RepairService.journal.blocked(k))remaining=true;
    if(remaining){scheduleLatest();return;}
    resetAt=0;task=null;step(15,"explicit-key-resets");
   }else if(phase==15){
    if(task!=null){if(!task.isDone())return;task.join();task=null;resetAt++;}
    while(resetAt<9&&!RepairService.journal.blocked(keys.get(resetAt)))resetAt++;
    if(resetAt<9){var r=RepairService.journal.get(keys.get(resetAt));check(r.state()==SnapshotJournal.State.REPAIRED,"No repair proof");
     if(MODE.equals("after-repair-cut")&&round==11&&resetAt==0){saveExpected();RepairService.journal.cuts(p->{if(p.equals("reset-published")){Trace.marker("CHURN_CUT_RESET","12 workload rounds; durable reset publication before acknowledgement");Runtime.getRuntime().halt(77);}});}
     task=RepairService.reset(r.key(),r.token());
    }else{task=rawVerify();step(16,"physical-readback-after-flush");}
   }else if(phase==16&&task.isDone()){
    task.join();currentSnapshots();for(long k:keys)check(!RepairService.blocked(k),"Reset left stale quarantine");saveExpected();
    if(MODE.equals("final")){inspectLoaded();for(var p:observers)p.closeContainer();viewers=false;step(20,"final-drain");}
    else{round++;check(round<12,"Unexpected extra workload");roundStart();}
   }else if(phase==20&&now-phaseAt>=650){
    var v=(OwnershipView)(Object)level().getChunkSource().chunkMap;check(PocketService.ownedTicketRequests()==0&&level().getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0&&RepairService.idle()&&PocketService.ledger(server).returnCount()==0,"Incomplete final save/ticket/recovery cleanup");Trace.audit(server);
    Trace.marker("PASSED","12 sustained nine-key rounds, queued/executing supersession, persisted failures, interrupted repair/reset,117+34items; orderReverse="+REVERSE+" transactions="+transactions+" peakRetryQueue="+peakQueue);running=false;server.halt(false);
   }
  }catch(Throwable e){ChurnAudit.release();running=false;try{if(csv!=null)csv.flush();}catch(Exception ignore){}Trace.marker("FAILED","churn "+MODE+" phase="+phase+" round="+round+" "+e);AnyaStorage.LOG.error("ANYA_CHURN_FAILED",e);server.halt(false);}
 }
 @SubscribeEvent public static void stopped(ServerStoppedEvent e)throws Exception{if(csv!=null){csv.close();csv=null;}ChurnAudit.release();}
}

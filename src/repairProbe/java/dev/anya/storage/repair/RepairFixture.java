package dev.anya.storage.repair;
import dev.anya.storage.*;
import dev.anya.storage.durable.*;
import dev.anya.storage.pocket.*;
import dev.anya.storage.minimal.OwnershipView;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;

/** Disposable server fixtures, not network clients. Restores whole snapshots, never inventory intents. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class RepairFixture {
 private static final String MODE=System.getProperty("anya.repairScenario","");
 private static MinecraftServer server;
 private static List<ServerPlayer> players;
 private static List<StorageEntity> hubs;
 private static List<PocketContent.EntranceEntity> entrances;
 private static List<UUID> ids=new ArrayList<>();
 private static Properties expected=new Properties();
 private static boolean started,done;
 private static int phase,version,resetIndex;
 private static long since,dirtyAt,deadline0,deadline1,targetKey,lastRecovery=-1;
 private static CompletableFuture<Void> observed,flush,reset;
 private static Transactions.Plan stale;
 private static List<SnapshotJournal.Record> pending=List.of();
 private static final Map<UUID,List<Long>> recoveries=new HashMap<>();
 private static BufferedWriter csv;
 private static void check(boolean ok,String text){if(!ok)throw new IllegalStateException(text);}
 private static Object field(String name)throws Exception{var f=Fixture.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 private static void set(String name,Object value)throws Exception{var f=Fixture.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 private static ServerLevel level(){return PocketService.dimension(server);}
 private static PocketLedger.Pocket room(int i)throws IOException{return PocketService.ledger(server).get(ids.get(i));}
 private static StorageEntity chest(int i)throws Exception{var p=PocketService.chestPositions(room(i)).getFirst();check(level().hasChunkAt(p),"Inspection would load absent chunk");return (StorageEntity)level().getBlockEntity(p);}
 private static ItemStack item(int i,int v){var s=new ItemStack(Items.DIAMOND,13);s.set(DataComponents.CUSTOM_NAME,Component.literal("Repair latest "+ids.get(i)+" v"+v));return s;}
 private static boolean inside(ServerPlayer p){return PocketService.inPocket(p.level());}
 private static boolean absent(){var p=new ChunkPos(targetKey);return level().getChunkSource().getChunkNow(p.x,p.z)==null;}
 private static boolean drained(){var v=(OwnershipView)(Object)level().getChunkSource().chunkMap;return PocketService.ownedTicketRequests()==0&&level().getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0;}
 private static void phase(int n,String label){phase=n;since=server.getTickCount();Fixture.phase="repair-"+label;Trace.note("REPAIR_PHASE",null,label);}
 private static void saveExpected(int latest)throws Exception{
  if(expected.isEmpty())for(int i=0;i<8;i++){var r=room(i);expected.setProperty("room."+i,r.id()+"/"+r.cell()+"/"+r.owner());expected.setProperty("version."+i,""+(i==2?latest:0));int c=0;for(var pos:PocketService.chestPositions(r)){var box=(StorageEntity)level().getBlockEntity(pos);expected.setProperty("chest."+i+"."+(c++),box.storageIdentity().toString());}}
  expected.setProperty("version.2",""+latest);
  try(var out=Files.newOutputStream(Path.of("repair-expected.properties"))){expected.store(out,"Disposable exact snapshot expectations; not player data migration");}
 }
 public static void observe(IOWorker w,ChunkPos pos,CompoundTag tag,CompletableFuture<Void> future){
  if(MODE.isEmpty()||!started||pos.toLong()!=targetKey||w!=RepairService.worker()||tag==null)return;
  var bes=tag.getList("block_entities",10);for(int b=0;b<bes.size();b++)for(var raw:bes.getCompound(b).getList("Items",10)){
   var stack=ItemStack.parseOptional(server.registryAccess(),(CompoundTag)raw);if(stack.getHoverName().getString().equals(item(2,version).getHoverName().getString())){observed=future;return;}
  }
 }
 private static void ordinarySave()throws Exception{
  var chunk=level().getChunkAt(chest(2).getBlockPos());check(chunk.isUnsaved(),"Fixture failed to dirty ordinary chunk");observed=null;dirtyAt=System.nanoTime();
  var method=ChunkMap.class.getDeclaredMethod("save",ChunkAccess.class);method.setAccessible(true);check((boolean)method.invoke(level().getChunkSource().chunkMap,chunk),"Ordinary engine save not submitted");check(observed!=null,"Actual store receipt not captured");
 }
 private static void safePlayers(){if(players==null)return;for(var p:players){check(p.getInventory().getItem(5).is(Items.EMERALD)&&p.getInventory().getItem(5).getCount()==11,"Original player inventory changed");for(int i=0;i<p.getInventory().getContainerSize();i++)check(!p.getInventory().getItem(i).is(Items.DIAMOND),"Unexpected item leaked to player");}}
 private static void healthy()throws Exception{
  if(players==null)return;for(int i=0;i<2;i++){check(players.get(i).containerMenu instanceof StoreMenu,"Independent viewer lost its menu");check(PocketService.lease(players.get(i).getUUID()).deadline()==(i==0?deadline0:deadline1),"Independent deadline changed");check(Transactions.same(chest(i).getItem(0),item(i,0)),"Unaffected inventory changed");}
 }
 private static void transferUnaffected()throws Exception{
  healthy();var p=players.getFirst();var c=chest(0);var main=new MainInventory(p);var view=new PocketView(p,room(0),PocketLeases.Kind.REMOTE);
  var out=new Transactions.Plan(List.of(c,main),view.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));check(out.move(c,0,List.of(main),1)==1&&out.commit(server).isEmpty(),"Unaffected guarded withdrawal failed");
  var back=new Transactions.Plan(List.of(c,main),view.guard(BasePermissions.VIEW|BasePermissions.DEPOSIT));for(int i=0;i<main.getContainerSize();i++)if(main.getItem(i).is(Items.DIAMOND)){check(back.move(main,i,List.of(c),1)==1,"Return plan failed");break;}check(back.commit(server).isEmpty(),"Unaffected guarded return failed");safePlayers();
 }
 public static void recovery(ServerPlayer p){
  if(!MODE.equals("live")||phase!=6&&phase!=7)return;long tick=server.getTickCount();check(lastRecovery!=tick,"Multiple recovery attempts in one tick");lastRecovery=tick;
  var list=recoveries.computeIfAbsent(p.getUUID(),k->new ArrayList<>());if(!list.isEmpty())check(tick-list.getLast()>=100,"Recovery retry interval weakened");list.add(tick);Trace.note("REPAIR_RECOVERY_WHILE_FAILED",p.getUUID(),"tick="+tick+" latestPending="+RepairService.journal.blocked(targetKey));
 }
 @SubscribeEvent public static void boot(ServerStartedEvent e)throws Exception{
  if(MODE.isEmpty())return;server=e.getServer();RepairService.server=server;check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"No disposable marker");
  csv=Files.newBufferedWriter(Trace.output().resolve("repair-cases.csv"));csv.write("tick,phase,version,queue,cache,requests,journal_bytes,failures,repairs,resets\n");
  if(MODE.startsWith("verify")){
   var o=ExpansionRules.get();o.pocketStorage=true;o.activePocketLimit=8;o.pocketLimit=16;
   try(var in=Files.newInputStream(Path.of("repair-expected.properties"))){expected.load(in);}for(int i=0;i<8;i++)ids.add(UUID.fromString(expected.getProperty("room."+i).split("/")[0]));
   targetKey=new ChunkPos(PocketService.chestPositions(room(2)).getFirst()).toLong();check(absent(),"Restart fixture already loaded target before repair");started=true;
   if(RepairService.ENABLED){
    if(MODE.equals("verify-dual")){check(RepairService.globalBad,"Missing persistent unknown-latest fault");try{RepairService.reset(targetKey,UUID.randomUUID());throw new IllegalStateException("Fatal reset was allowed");}catch(IOException correct){}phase(23,"unrecoverable-newest-not-promised");}
    else {check(!RepairService.globalBad,"Unexpected corrupt journal");pending=RepairService.journal.all().stream().filter(r->RepairService.journal.blocked(r.key())).toList();
     if(MODE.equals("verify-cut")){RepairService.faultKey=targetKey;RepairService.cut="repair-after-region";}
     for(var r:pending)if(r.state()!=SnapshotJournal.State.REPAIRED)RepairService.schedule(r.key(),r.token());phase(20,"restart-repair");}
   }else phase(23,"baseline-previous-version-only");
  }
 }
 @SuppressWarnings("unchecked") private static void begin()throws Exception{
  set("running",false);players=(List<ServerPlayer>)field("PLAYERS");hubs=(List<StorageEntity>)field("HUBS");entrances=(List<PocketContent.EntranceEntity>)field("ENTRANCES");ids=(List<UUID>)field("IDS");check(ids.size()==8,"Expected eight prepared fixture rooms");
  for(int i=0;i<8;i++){check(chest(i).isEmpty(),"Refusing overwrite");chest(i).setItem(0,item(i,0));}
  saveExpected(0);server.saveEverything(true,true,true);targetKey=new ChunkPos(chest(2).getBlockPos()).toLong();for(var p:players)PocketService.recover(p);started=true;phase(1,"prime-return");
 }
 private static void verifyWorld()throws Exception{
  check(PocketService.ledger(server).pockets().size()==8,"Room allocations changed");int sum=0;
  for(int i=0;i<8;i++){
   var r=room(i);check(expected.getProperty("room."+i).equals(r.id()+"/"+r.cell()+"/"+r.owner()),"Room identity changed");int j=0;
   for(var pos:PocketService.chestPositions(r)){
    level().getChunk(pos.getX()>>4,pos.getZ()>>4);check(level().getBlockEntity(pos) instanceof StorageEntity,"Missing physical chest");var c=(StorageEntity)level().getBlockEntity(pos);
    check(c.pocketId.equals(r.id())&&c.owner.equals(r.owner())&&c.storageIdentity().toString().equals(expected.getProperty("chest."+i+"."+j)),"Physical identity changed");
    if(j++==0){check(Transactions.same(c.getItem(0),item(i,Integer.parseInt(expected.getProperty("version."+i)))),"Latest expected sample not restored");sum+=13;for(int k=1;k<c.getContainerSize();k++)check(c.getItem(k).isEmpty(),"Duplicate stack");}else check(c.isEmpty(),"Unexpected other chest items");
   }
  }check(sum==104,"Inventory conservation mismatch");Trace.marker("REPAIR_RESTART_ITEMS","104 exact named samples,8 rooms,32 physical storage identities; targetVersion="+expected.getProperty("version.2"));
  set("server",server);var m=Fixture.class.getDeclaredMethod("player",int.class);m.setAccessible(true);players=new ArrayList<>();for(int i=0;i<8;i++)players.add((ServerPlayer)m.invoke(null,i));safePlayers();
 }
 private static void finish(String label)throws Exception{safePlayers();Trace.audit(server);Trace.marker("PASSED",label+" enabled="+RepairService.ENABLED+" failures="+RepairService.failures+" repairs="+RepairService.repairs+" resets="+RepairService.resets);done=true;server.halt(false);}
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event){
  if(MODE.isEmpty()||done||server!=event.getServer())return;long now=server.getTickCount();
  try{
   check(now<4200,"Bounded repair fixture deadline");
   if(!started){if(Fixture.phase.equals("visit-steady"))begin();return;}
   var state=(OwnershipView)(Object)level().getChunkSource().chunkMap;
   csv.write(now+","+phase+","+version+","+state.anyaCallbackCount()+","+level().getChunkSource().getLoadedChunksCount()+","+PocketService.ownedTicketRequests()+","+(RepairService.journal==null?0:RepairService.journal.bytes())+","+RepairService.failures+","+RepairService.repairs+","+RepairService.resets+"\n");if(now%20==0)csv.flush();
   if(phase>=20){
    if(phase==20&&RepairService.idle()){
     for(var old:pending){var r=RepairService.journal.get(old.key());check(r.state()==SnapshotJournal.State.REPAIRED,"Repair not durable");check(RepairService.blocked(r.key()),"Repair silently reopened active writers");try{RepairService.reset(r.key(),UUID.randomUUID());throw new IllegalStateException("Stale reset accepted");}catch(IOException correct){}}
     phase(21,"explicit-reset");
    }else if(phase==21){
     if(reset!=null){if(!reset.isDone())return;reset.join();reset=null;resetIndex++;}
     if(resetIndex<pending.size()){var r=RepairService.journal.get(pending.get(resetIndex).key());reset=RepairService.reset(r.key(),r.token());}else phase(23,"read-physical-repaired-region");
    }else if(phase==23){verifyWorld();phase(24,"restart-drain");}
    else if(phase==24&&now-since>=650&&drained()&&PocketService.ledger(server).returnCount()==0)finish("restart exact data and cleanup; no inventory-command replay");return;
   }
   safePlayers();
   if(phase==1&&players.stream().noneMatch(RepairFixture::inside)&&PocketService.ledger(server).returnCount()==0){
    for(int i=0;i<2;i++)PocketService.remote(players.get(i),players.get(i).getInventory().getItem(0),hubs.get(i));
    if(MODE.equals("live")){
     for(int i=2;i<8;i++){if(i!=2)hubs.get(2).setMember(players.get(i).getUUID(),BasePermissions.VISIT);var ep=entrances.get(2).getBlockPos();players.get(i).teleportTo(server.overworld(),ep.getX()+.5,81,7.5,180,0);PocketService.enter(players.get(i),entrances.get(2));}
    }else PocketService.remote(players.get(2),players.get(2).getInventory().getItem(0),hubs.get(2));phase(2,"open-active-users");
   }else if(phase==2){
    if(!(players.get(0).containerMenu instanceof StoreMenu)||!(players.get(1).containerMenu instanceof StoreMenu))return;
    if(MODE.equals("live")&&!players.subList(2,8).stream().allMatch(RepairFixture::inside))return;
    if(!MODE.equals("live")&&!(players.get(2).containerMenu instanceof StoreMenu))return;
    deadline0=PocketService.lease(players.get(0).getUUID()).deadline();deadline1=PocketService.lease(players.get(1).getUUID()).deadline();
    version=1;chest(2).setItem(0,item(2,version));var p=players.get(2);var main=new MainInventory(p);var view=new PocketView(p,room(2),MODE.equals("live")?PocketLeases.Kind.VISIT:PocketLeases.Kind.REMOTE);
    stale=new Transactions.Plan(List.of(chest(2),main),view.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));check(stale.move(chest(2),0,List.of(main),1)==1,"Stale intent preparation failed");
    int want=RepairService.ENABLED&&!MODE.equals("dual")?1:0;saveExpected(want);
    if(MODE.startsWith("cut-")){RepairService.faultKey=targetKey;RepairService.cut=MODE.substring(4);}else if(!MODE.equals("measure"))RepairService.arm(MODE.equals("flush")?"flush":MODE.equals("dual")?"dual":"persistent",targetKey);
    ordinarySave();phase(3,"ordinary-async-write");
   }else if(phase==3){
    if(observed==null||!observed.isDone())return;
    if(MODE.equals("measure")){observed.join();flush=RepairService.worker().synchronize(true);phase(14,"measure-flush");return;}
    check(observed.isCompletedExceptionally()&&RepairService.ioFaults.get()>0,"Expected actual worker failure missing");
    if(RepairService.ENABLED){check(!stale.commit(server).isEmpty(),"Existing writer crossed failed snapshot");var p=players.get(2);var main=new MainInventory(p);var denied=new Transactions.Plan(List.of(chest(2),main),()->true);check(denied.move(chest(2),0,List.of(main),1)==1&&!denied.commit(server).isEmpty(),"New intent crossed quarantine");check(Transactions.same(chest(2).getItem(0),item(2,1)),"Denied transfer mutated latest sample");}
    safePlayers();if(!MODE.equals("dual"))transferUnaffected();
    if(MODE.equals("live")){
     int x=entrances.get(2).getBlockPos().getX();for(int dx=-3;dx<=3;dx++)for(int z=4;z<=10;z++)for(int y=81;y<=90;y++)server.overworld().setBlockAndUpdate(new BlockPos(x+dx,y,z),Blocks.OBSIDIAN.defaultBlockState());
     for(int xx=196;xx<=204;xx++)for(int zz=196;zz<=204;zz++)server.overworld().setBlockAndUpdate(new BlockPos(xx,80,zz),Blocks.MAGMA_BLOCK.defaultBlockState());server.overworld().setDefaultSpawnPos(new BlockPos(200,81,200),0);
     for(int i=2;i<8;i++)PocketService.recover(players.get(i));phase(6,"recovery-during-persistent-write-failure");
    }else{for(var q:players)q.closeContainer();phase(4,"failed-unload");}
   }else if(phase==4&&now-since>=650&&drained()){
    if(RepairService.ENABLED&&!MODE.equals("dual"))check(RepairService.journal.blocked(targetKey),"Failure lost persistent quarantine");Trace.audit(server);
    if(MODE.equals("stop")){Trace.marker("STOP_PENDING_REPAIR","normal shutdown with latest failed region snapshot in journal");done=true;server.halt(false);}
    else {Trace.marker("CUT_FAILED_WRITE","target latest is "+(RepairService.ENABLED&&!MODE.equals("dual")?"durable journal":"NOT protected by durable newest copy")+"; no clean shutdown rescue");Runtime.getRuntime().halt(77);}
   }else if(phase==6){
    healthy();check(((Map<?,?>)Preparation.field("RECOVERY")).size()==6,"Unsafe guests left recovery unexpectedly");check(RepairService.journal.blocked(targetKey),"Unresolved journal disappeared");if(now%40==0)transferUnaffected();
    if(now-since>=250){check(recoveries.size()==6,"Recovery fairness not exercised");int x=entrances.get(2).getBlockPos().getX();server.overworld().setBlockAndUpdate(new BlockPos(x,81,7),Blocks.AIR.defaultBlockState());server.overworld().setBlockAndUpdate(new BlockPos(x,82,7),Blocks.AIR.defaultBlockState());phase(7,"repair-safe-landings-only");}
   }else if(phase==7){healthy();if(((Map<?,?>)Preparation.field("RECOVERY")).isEmpty()){check(now-since<=130,"Recovery starved");Trace.marker("RECOVERY_WHILE_FAILED_OK","six guests recovered while region write failure and latest journal remained unresolved; other viewers/deadlines intact");phase(8,"wait-unloaded-for-repair");}}
   else if(phase==8&&absent()&&RepairService.idle()){
    healthy();var r=RepairService.journal.get(targetKey);try{RepairService.reset(targetKey,r.token());throw new IllegalStateException("Reset of pending data accepted");}catch(IOException correct){}
    RepairService.schedule(targetKey,r.token());phase(9,"bounded-persistent-retries");
   }else if(phase==9){healthy();if(now%40==0)transferUnaffected();if(now-since>180&&RepairService.idle()){
    check(RepairService.repairs.get()==0&&RepairService.journal.blocked(targetKey),"Persistent error was reported repaired");check(RepairService.ioFaults.get()>=4,"Three bounded retries not exercised");
    Trace.marker("RETRY_LIMIT_OK","three failed attempts stopped; latest durable record retained; no automatic intent replay");RepairService.fault="";var r=RepairService.journal.get(targetKey);RepairService.schedule(targetKey,r.token());phase(10,"explicit-retry-after-fault-cleared");}}
   else if(phase==10&&RepairService.idle()){
    healthy();var r=RepairService.journal.get(targetKey);check(r.state()==SnapshotJournal.State.REPAIRED&&RepairService.blocked(targetKey),"Repair proof did not retain lock");reset=RepairService.reset(targetKey,r.token());phase(11,"explicit-verified-reset");
   }else if(phase==11&&reset.isDone()){
    reset.join();check(!RepairService.blocked(targetKey),"Verified reset failed");PocketService.remote(players.get(2),players.get(2).getInventory().getItem(0),hubs.get(2));phase(12,"reopen-newest-after-repair");
   }else if(phase==12&&players.get(2).containerMenu instanceof StoreMenu){healthy();check(Transactions.same(chest(2).getItem(0),item(2,1)),"Live repair did not restore newest sample");for(var p:players)p.closeContainer();phase(13,"live-final-drain");}
   else if(phase==13&&now-since>=650&&drained()){Trace.audit(server);Trace.marker("CUT_LIVE_REPAIRED","latest repaired/reset version durable; exact queues empty");Runtime.getRuntime().halt(77);}
   else if(phase==14&&flush.isDone()){
    flush.join();Trace.note("REPAIR_MEASURED_SAVE",null,"version="+version+" elapsed_ns="+(System.nanoTime()-dirtyAt)+" candidate="+RepairService.ENABLED);
    if(version<12){version++;chest(2).setItem(0,item(2,version));ordinarySave();phase(3,"ordinary-async-write");}
    else{saveExpected(12);for(var p:players)p.closeContainer();phase(15,"measure-final-drain");}
   }else if(phase==15&&now-since>=650&&drained()){Trace.marker("CUT_MEASURED_SAVES","12 successful ordinary snapshot receipts and flushes; exact queues drained");Runtime.getRuntime().halt(77);}
  }catch(Throwable ex){done=true;Trace.marker("FAILED","repair scenario="+MODE+" phase="+phase+" "+ex);AnyaStorage.LOG.error("ANYA_REPAIR_FIXTURE_FAILED",ex);server.halt(false);}
 }
 @SubscribeEvent public static void stopped(ServerStoppedEvent e)throws IOException{if(csv!=null)csv.close();}
}

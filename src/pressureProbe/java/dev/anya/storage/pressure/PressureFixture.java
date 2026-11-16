package dev.anya.storage.pressure;
import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import dev.anya.storage.durable.*;
import dev.anya.storage.minimal.OwnershipView;
import dev.anya.storage.pocket.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
/** Sustained finite arrival stream and dirty-save evidence. Simulated packet sinks, NOT real clients. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class PressureFixture {
 private static final String MODE=System.getProperty("anya.pressureScenario","");
 private static MinecraftServer server;
 private static final List<ServerPlayer> players=new ArrayList<>();
 private static final List<StorageEntity> hubs=new ArrayList<>();
 private static final List<PocketContent.EntranceEntity> entrances=new ArrayList<>();
 private static final List<UUID> ids=new ArrayList<>();
 private static final List<ItemStack> remotes=new ArrayList<>();
 private static final Properties expected=new Properties();
 private static final long[] opened=new long[20],cooldown=new long[20],wanted=new long[20];
 private static final int[] cycles=new int[20];
 private static final Map<UUID,List<Long>> recoveryAttempts=new HashMap<>();
 private static long recoveryTick=-1;
 private static int n,phase,setupGroup,setupState;
 private static long start,phaseAt,activeAt,stableA,stableB,maxAdmissionDelay,recoveryAt;
 private static boolean running,dirtyMade,requestStarted,stopArmed;
 private static BufferedWriter csv,admissions;
 private static CompletableFuture<Void> finalFlush;
 public static boolean active(){return !MODE.isEmpty();}
 private static void check(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
 private static ServerLevel level(){return PocketService.dimension(server);}
 private static PocketLedger.Pocket room(int i)throws Exception{return PocketService.ledger(server).get(ids.get(i));}
 private static Receipts.Sample sample(int i)throws Exception{return Receipts.SAMPLES.get(new ChunkPos(PocketService.chestPositions(room(i)).getFirst()).toLong());}
 private static StorageEntity chest(int i)throws Exception{
  var pos=PocketService.chestPositions(room(i)).getFirst();check(level().hasChunkAt(pos),"Test inspection attempted to load absent sample");
  return (StorageEntity)level().getBlockEntity(pos);
 }
 private static ItemStack item(int i,int version){var s=new ItemStack(Items.DIAMOND,13);s.set(DataComponents.CUSTOM_NAME,Component.literal("Pressure "+ids.get(i)+" v"+version));return s;}
 private static boolean inside(ServerPlayer p){return PocketService.inPocket(p.level());}
 private static boolean menu(int i){return players.get(i).containerMenu instanceof StoreMenu;}
 private static boolean drained(){var v=(OwnershipView)(Object)level().getChunkSource().chunkMap;return PocketService.ownedTicketRequests()==0&&level().getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0;}
 private static void phase(int value,String label){phase=value;phaseAt=server.getTickCount();Fixture.phase=label;Trace.note("PRESSURE_PHASE",null,label);}
 private static ServerPlayer player(int i){
  var gp=new GameProfile(UUID.nameUUIDFromBytes(("PressureOwner"+i).getBytes(StandardCharsets.UTF_8)),"Pressure"+i);
  var p=new ServerPlayer(server,server.overworld(),gp,ClientInformation.createDefault());
  var c=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND){
   @Override public boolean isMemoryConnection(){return true;}
   @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener cb,boolean flush){}
  };
  new io.netty.channel.embedded.EmbeddedChannel(c);net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(c);
  server.getPlayerList().placeNewPlayer(c,p,net.minecraft.server.network.CommonListenerCookie.createInitial(gp,false));return p;
 }
 private static void home(int who,int owner,double z){var p=players.get(who);var pos=entrances.get(owner).getBlockPos();p.teleportTo(server.overworld(),pos.getX()+.5,81,z,180,0);}
 private static void request(int who,int owner,boolean visit)throws Exception{
  if(PocketService.lease(players.get(who).getUUID())!=null)return;
  if(wanted[who]==0)wanted[who]=server.getTickCount();
  if(visit)PocketService.enter(players.get(who),entrances.get(owner));else PocketService.remote(players.get(who),remotes.get(owner),hubs.get(owner));
  boolean acquired=PocketService.lease(players.get(who).getUUID())!=null;
  admissions.write(server.getTickCount()+","+who+","+owner+","+visit+","+acquired+","+Pressure.denied+"\n");
  if(acquired){maxAdmissionDelay=Math.max(maxAdmissionDelay,server.getTickCount()-wanted[who]);wanted[who]=0;}
 }
 private static void dirty(int i)throws Exception{
  var s=sample(i);check(s.confirmed==s.version,"Cannot overwrite an unconfirmed sample");
  check(Transactions.same(chest(i).getItem(0),item(i,s.version)),"Live sample identity/count changed");
  Receipts.dirty(s,s.version+1);chest(i).setItem(0,item(i,s.version));check(level().getChunkAt(chest(i).getBlockPos()).isUnsaved(),"Sample dirty flag missing");
  cycles[i]++;
 }
 private static void expectedFile(boolean current)throws Exception{
  for(int i=0;i<n;i++){var r=room(i);expected.setProperty("room."+i,r.id()+"/"+r.cell()+"/"+r.owner());expected.setProperty("version."+i,""+(current?sample(i).version:sample(i).confirmed));}
  expected.setProperty("rooms",""+n);try(var out=Files.newOutputStream(Path.of("pressure-expected.properties"))){expected.store(out,"Disposable saved identity/content expectations");}
 }
 private static void verifyDiskWorld()throws Exception{
  try(var in=Files.newInputStream(Path.of("pressure-expected.properties"))){expected.load(in);}n=Integer.parseInt(expected.getProperty("rooms"));
  check(PocketService.ledger(server).pockets().size()==n,"Duplicate/missing pocket allocations after restart");int count=0;
  for(int i=0;i<n;i++){
   String[] bits=expected.getProperty("room."+i).split("/");var id=UUID.fromString(bits[0]);ids.add(id);var r=room(i);
   check(r.stage()==PocketLedger.Stage.READY&&r.cell()==Integer.parseInt(bits[1])&&r.owner().equals(UUID.fromString(bits[2])),"Original room identity changed");
   int c=0;for(var p:PocketService.chestPositions(r)){
    level().getChunk(p.getX()>>4,p.getZ()>>4);check(level().getBlockEntity(p) instanceof StorageEntity,"Missing physical chest");var box=(StorageEntity)level().getBlockEntity(p);
    check(id.equals(box.pocketId)&&r.owner().equals(box.owner),"Chest identity changed");
    if(c++==0){check(Transactions.same(box.getItem(0),item(i,Integer.parseInt(expected.getProperty("version."+i)))),"Unconfirmed/missing/wrong saved sample "+i);count+=13;for(int k=1;k<box.getContainerSize();k++)check(box.getItem(k).isEmpty(),"Duplicate sample slot");}
    else check(box.isEmpty(),"Extra filled chest");
   }
  }
  check(count==n*13,"Inventory count changed");Trace.marker("PRESSURE_RESTART_ITEMS",count+" named items; "+n+" original rooms and "+(4*n)+" physical chests verified. Inspection intentionally loads chunks.");
 }
 @SubscribeEvent public static void started(ServerStartedEvent e)throws Exception{
  if(!active())return;server=e.getServer();check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"Refuse non-disposable world");
  Receipts.server=server;Receipts.worker=(IOWorker)level().getChunkSource().chunkMap.chunkScanner();
  var r=ExpansionRules.get();r.pocketStorage=true;r.pocketEditing=false;r.pocketLimit=16;r.activePocketLimit=8;r.pocketSessionTicks=30000;
  server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,server);
  csv=Files.newBufferedWriter(Trace.output().resolve("pressure.csv"));csv.write("tick,phase,queue,pending,cache,requests,heap_used,heap_committed,waiters,blocked,denied,accepted,oldest_dirty_ticks,confirmed_versions,max_durable_ticks,recovery_pending\n");
  admissions=Files.newBufferedWriter(Trace.output().resolve("admissions.csv"));admissions.write("tick,actor,room,visit,acquired,pressure_denials\n");
  if(MODE.equals("verify")){verifyDiskWorld();phase(90,"restart-inspection");running=true;return;}
  check(!Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Never seed existing player data");
  n=MODE.equals("stress")?12:8;
  for(int x=-4;x<65;x++)for(int z=-3;z<15;z++)server.overworld().setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());
  server.overworld().setDefaultSpawnPos(new BlockPos(5,81,12),0);
  for(int i=0;i<20;i++){var p=player(i);players.add(p);p.teleportTo(server.overworld(),5.5,81,3.5,180,0);p.getInventory().clearContent();p.getInventory().setItem(5,new ItemStack(Items.EMERALD,11));}
  for(int i=0;i<n;i++){
   BlockPos hp=new BlockPos(1+i*5,81,1),ep=hp.east();var p=players.get(i);
   server.overworld().setBlockAndUpdate(hp,Content.HUB.get().defaultBlockState());server.overworld().setBlockAndUpdate(ep,PocketContent.ENTRANCE.get().defaultBlockState());
   var hub=(StorageEntity)server.overworld().getBlockEntity(hp);var en=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(ep);hub.owner=p.getUUID();en.owner=p.getUUID();hub.setChanged();en.setChanged();
   hubs.add(hub);entrances.add(en);home(i,i,3.5);var remote=new ItemStack(Content.REMOTE.get());var t=BagInventory.data(remote);t.putString("HubDimension","minecraft:overworld");t.putLong("HubPos",hp.asLong());BagInventory.data(remote,t);p.getInventory().setItem(0,remote);
   PocketService.link(p,en,remote);check(en.pocket!=null,"Allocation failed");ids.add(en.pocket);remotes.add(remote);Receipts.register(en.pocket,new ChunkPos(PocketService.chestPositions(room(i)).getFirst()));
   if(i<8)hub.setMember(players.get(12+i).getUUID(),BasePermissions.LEGACY|BasePermissions.VISIT);
  }
  running=true;phase(0,"setup-cold");
 }
 private static void stable()throws Exception{
  for(int i=0;i<2;i++){check(menu(i)&&((StoreMenu)players.get(i).containerMenu).hasPocket(ids.get(i)),"Existing viewer lost its own menu");var lease=PocketService.lease(players.get(i).getUUID());check(lease!=null&&lease.deadline()==(i==0?stableA:stableB),"Independent viewer deadline reset");}
 }
 private static void transaction()throws Exception{
  var p=players.get(0);var c=chest(0);var dest=new MainInventory(p);var view=new PocketView(p,room(0),PocketLeases.Kind.REMOTE);
  var a=new Transactions.Plan(List.of(c,dest),view.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW));check(a.move(c,0,List.of(dest),1)==1&&a.commit(server).isEmpty(),"Existing viewer withdrawal failed under pressure");
  int slot=-1;for(int k=0;k<p.getInventory().getContainerSize();k++)if(p.getInventory().getItem(k).is(Items.DIAMOND))slot=k;check(slot>=0,"Withdrawn sample missing");
  var b=new Transactions.Plan(List.of(c,dest),view.guard(BasePermissions.DEPOSIT));check(b.move(dest,slot,List.of(c),1)==1&&b.commit(server).isEmpty(),"Existing viewer deposit failed under pressure");
  check(Transactions.same(c.getItem(0),item(0,0)),"Guarded transaction altered identity/count");
 }
 private static void setup()throws Exception{
  int from=setupGroup*6,to=Math.min(n,from+6);
  if(setupState==0){for(int i=from;i<to;i++)PocketService.enter(players.get(i),entrances.get(i));setupState=1;}
  else if(setupState==1){boolean all=true;for(int i=from;i<to;i++)all&=inside(players.get(i));if(!all)return;
   for(int i=from;i<to;i++){check(chest(i).isEmpty(),"Refuse sample overwrite");Receipts.dirty(sample(i),0);chest(i).setItem(0,item(i,0));PocketService.recover(players.get(i));}setupState=2;
  }else if(setupState==2){
   boolean confirms=true;for(int i=from;i<to;i++)confirms&=sample(i).confirmed==0;
   if(!confirms||!drained())return;
   if(to<n){setupGroup++;setupState=0;return;}
   expectedFile(false);server.saveEverything(true,true,true);Pressure.workload=true;phase(1,"open-permanent-viewers");
  }
 }
 public static void recoveryAttempt(ServerPlayer p){
  if(!running||phase!=5&&phase!=6)return;long tick=server.getTickCount();check(tick!=recoveryTick,"More than one recovery serviced in a tick");recoveryTick=tick;
  var times=recoveryAttempts.computeIfAbsent(p.getUUID(),k->new ArrayList<>());if(!times.isEmpty())check(tick-times.getLast()>=100,"Recovery retry interval weakened");times.add(tick);
  Trace.note("PRESSURE_RECOVERY_ATTEMPT",p.getUUID(),"tick="+tick+" queued="+((Map<?,?>)Preparation.field("RECOVERY")).size());
 }
 private static void stress(long now)throws Exception{
  if(phase==1){if(now%10==0){request(0,0,false);request(1,1,false);}if(menu(0)&&menu(1)){stableA=PocketService.lease(players.get(0).getUUID()).deadline();stableB=PocketService.lease(players.get(1).getUUID()).deadline();activeAt=now;phase(2,"sustained-arrivals");}return;}
  if(phase==2){
   stable();long age=now-activeAt;
   if(age==1200)ExpansionRules.get().activePocketLimit=4;
   if(age==1800)ExpansionRules.get().activePocketLimit=8;
   if(age%200==0)transaction();
   for(int i=2;i<n;i++){
    if(menu(i)){if(opened[i]==0){check(Transactions.same(chest(i).getItem(0),item(i,sample(i).version)),"Reopen changed sample");dirty(i);opened[i]=now;}
     if(now-opened[i]>=30){players.get(i).closeContainer();opened[i]=0;cooldown[i]=now+20;}}
    else if(now%10==0&&now>=cooldown[i]&&sample(i).confirmed==sample(i).version)request(i,i,false);
   }
   if(age>=3600){for(int i=2;i<n;i++){players.get(i).closeContainer();Pressure.gate().forget(players.get(i).getUUID());check(cycles[i]>=2,"Unserved contender "+i);}phase(3,"drain-before-recovery");}
  }else if(phase==3){stable();if(Receipts.allConfirmed()&&now-phaseAt>100){phase(4,"eight-visitor-recovery-setup");}}
  else if(phase==4){stable();if(now%10==0)for(int i=0;i<8;i++){int g=12+i;if(!inside(players.get(g))&&PocketService.lease(players.get(g).getUUID())==null){home(g,i,7.5);request(g,i,true);}}
   boolean all=true;for(int i=12;i<20;i++)all&=inside(players.get(i));if(!all)return;
   // Place obstacles beyond owners' remote-view positions. Exit route/room blocks remain unchanged.
   for(int i=0;i<8;i++){int x=entrances.get(i).getBlockPos().getX();for(int dx=-3;dx<=3;dx++)for(int z=4;z<=10;z++)for(int y=81;y<=90;y++)server.overworld().setBlockAndUpdate(new BlockPos(x+dx,y,z),Blocks.OBSIDIAN.defaultBlockState());}
   for(int x=196;x<=204;x++)for(int z=196;z<=204;z++)server.overworld().setBlockAndUpdate(new BlockPos(x,80,z),Blocks.MAGMA_BLOCK.defaultBlockState());server.overworld().setDefaultSpawnPos(new BlockPos(200,81,200),0);
   for(int i=12;i<20;i++)PocketService.recover(players.get(i));recoveryAt=now;phase(5,"eight-unsafe-recoveries");
  }else if(phase==5){stable();if(now%20==0)transaction();check(((Map<?,?>)Preparation.field("RECOVERY")).size()==8,"Unsafe visitor unexpectedly escaped recovery");
   if(now-recoveryAt>=250){for(int i=0;i<8;i++){int x=entrances.get(i).getBlockPos().getX();server.overworld().setBlockAndUpdate(new BlockPos(x,81,7),Blocks.AIR.defaultBlockState());server.overworld().setBlockAndUpdate(new BlockPos(x,82,7),Blocks.AIR.defaultBlockState());}phase(6,"repair-recovery-landings");}
  }else if(phase==6){stable();if(((Map<?,?>)Preparation.field("RECOVERY")).isEmpty()){
    check(now-phaseAt<=130,"Recovery starved after repair");check(recoveryAttempts.size()==8,"Not all eight recoveries serviced");for(var attempts:recoveryAttempts.values())check(attempts.size()>=3,"Insufficient retry evidence");
    check(PocketService.ledger(server).returnCount()==0,"Return records not cleared after safe save");for(var p:players)p.closeContainer();phase(7,"final-drain");
   }else check(now-phaseAt<150,"Bounded recovery failed");
  }else if(phase==7&&now-phaseAt>=650&&drained()&&Receipts.allConfirmed()){
   Trace.audit(server);expectedFile(false);check(Pressure.ENABLED?Pressure.denied>0:true,"No actual backpressure denials observed");
   Trace.marker("PRESSURE_LOAD_OK","cycles="+Arrays.toString(cycles)+" maxAdmissionTicks="+maxAdmissionDelay+" confirmedVersions="+Receipts.confirmedCount+" maxDurableTicks="+Receipts.maxDelay+" maxDurableMs="+Receipts.maxDelayMs+" existing_viewer_deadlines_unchanged=true recovery_users=8 exact_engine_empty=true");
   Trace.marker("CUT_AFTER_SUSTAINED_FLUSH","all measured write receipts and flushes succeeded; selected boundary, not atomicity");Runtime.getRuntime().halt(77);
  }
 }
 private static void faultsAndStop(long now)throws Exception{
  if(phase==1){if(now%10==0)for(int i=0;i<n;i++)request(i,i,false);boolean all=true;for(int i=0;i<n;i++)all&=menu(i);if(!all)return;
   if(MODE.contains("fault")){
    stableA=PocketService.lease(players.get(0).getUUID()).deadline();stableB=PocketService.lease(players.get(1).getUUID()).deadline();
    Receipts.arm(MODE.equals("write-fault")?"write":"flush");dirty(2);for(int i=2;i<n;i++)players.get(i).closeContainer();phase(20,"injected-unload-failure");
   }else{for(int i=0;i<n;i++)dirty(i);for(var p:players)p.closeContainer();phase(21,"dirty-backlog-stop-or-force");}
  }else if(phase==20){
   stable();if(Receipts.failed){check(Receipts.fired(),"No physical fault fired");check(sample(2).confirmed==0,"Failed latest write falsely confirmed");
    if(now%10==0)request(3,3,false);
    if(now-phaseAt>=300){check(Pressure.ENABLED&&Pressure.denied>0&&PocketService.lease(players.get(3).getUUID())==null,"Write/flush failure did not fail-close new admission");transaction();
     var pos=PocketService.chestPositions(room(2)).getFirst();check(level().getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null,"Failed sample must be unloaded before stop, not rescued by another save");
     expectedFile(false);if(MODE.equals("flush-fault"))expected.setProperty("version.2","1");try(var out=Files.newOutputStream(Path.of("pressure-expected.properties"))){expected.store(out,"Expected after original clean shutdown; failed write latest version deliberately NOT promised");}
     Trace.marker("PRESSURE_FAULT_OK","mode="+MODE+" latestConfirmed="+sample(2).confirmed+" writeFailures="+Receipts.writeFailures+" flushFailures="+Receipts.flushFailures+" new_admission_blocked=true existing_views_valid=true");
     running=false;server.halt(false);
    }
   }
  }else if(phase==21){
   var v=(OwnershipView)(Object)level().getChunkSource().chunkMap;int dirty=0;for(int i=0;i<n;i++){var pos=PocketService.chestPositions(room(i)).getFirst();var h=v.anyaPending(new ChunkPos(pos).toLong());if(h!=null&&h.getLatestChunk() instanceof LevelChunk c&&c.isUnsaved())dirty++;}
   if(!stopArmed&&dirty>0&&v.anyaCallbackCount()>2048){
    request(0,0,false);check(PocketService.lease(players.get(0).getUUID())==null&&Pressure.denied>0,"Queued dirty saves failed to block new admission");expectedFile(true);
    Trace.marker("PRESSURE_BACKLOG_OBSERVED","dirty="+dirty+" callbacks="+v.anyaCallbackCount()+" admitted=false");stopArmed=true;
    if(MODE.equals("stop")){Trace.marker("PRESSURE_STOP_WITH_BACKLOG","no manual save or queue drain before stop");running=false;server.halt(false);}else{level().getChunkSource().save(true);phase(22,"force-save-completion");}
   }
  }else if(phase==22&&drained()&&Receipts.allConfirmed()){
   Trace.audit(server);Trace.marker("PRESSURE_FORCE_OK","forced save kept all required work; receipts and flush complete");Runtime.getRuntime().halt(77);
  }
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
  if(!running||e.getServer()!=server)return;long now=server.getTickCount();
  try{
   check(now<10000,"Fixture tick ceiling");Receipts.poll();
   var v=(OwnershipView)(Object)level().getChunkSource().chunkMap;var h=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();var leases=(PocketLeases)Preparation.field("LEASES");
   check(leases.active().size()<=8&&leases.snapshot().size()<=16,"Active cap exceeded");
   if(!players.isEmpty())Pressure.gate().update(now,Pressure.signal(players.getFirst()));
   csv.write(now+","+Fixture.phase+","+v.anyaCallbackCount()+","+v.anyaPendingCount()+","+level().getChunkSource().getLoadedChunksCount()+","+PocketService.ownedTicketRequests()+","+h.getUsed()+","+h.getCommitted()+","+Pressure.gate().waiters()+","+Pressure.gate().blocked()+","+Pressure.denied+","+Pressure.accepted+","+Receipts.oldest(now)+","+Receipts.confirmedCount+","+Receipts.maxDelay+","+((Map<?,?>)Preparation.field("RECOVERY")).size()+"\n");
   if(now%20==0){csv.flush();admissions.flush();Trace.audit(server);}
   if(phase==90){if(now-phaseAt>=650&&drained()){Trace.audit(server);Trace.marker("PASSED","exact restart item/identity inspection and all engine callbacks/tickets drained");running=false;server.halt(false);}return;}
   for(var p:players)check(p.getInventory().getItem(5).is(Items.EMERALD)&&p.getInventory().getItem(5).getCount()==11,"Fixture player sample changed");
   if(phase==0)setup();else if(MODE.equals("stress"))stress(now);else faultsAndStop(now);
  }catch(Throwable t){running=false;Trace.marker("FAILED",MODE+" phase="+phase+" "+t);AnyaStorage.LOG.error("ANYA_PRESSURE_FAILED",t);server.halt(false);}
 }
 @SubscribeEvent public static void stopped(ServerStoppedEvent e)throws IOException{if(csv!=null)csv.close();if(admissions!=null)admissions.close();Receipts.close();}
}

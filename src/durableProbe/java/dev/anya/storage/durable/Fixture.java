package dev.anya.storage.durable;
import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
@EventBusSubscriber(modid="anyadurableprobe")
public final class Fixture {
 public static String phase="external-regression";
 private static final String MODE=System.getProperty("anya.durableScenario","");
 private static final AtomicBoolean BAD_FLUSH=new AtomicBoolean();
 private static MinecraftServer server;
 private static final List<ServerPlayer> PLAYERS=new ArrayList<>();
 private static final List<PocketContent.EntranceEntity> ENTRANCES=new ArrayList<>();
 private static final List<StorageEntity> HUBS=new ArrayList<>();
 private static final List<UUID> IDS=new ArrayList<>();
 private static int step;private static long since,flushedAt=-1;private static Preparation.Job cancelled;
 private static boolean running;private static final AtomicInteger WRITE_FAILURES=new AtomicInteger(),FLUSH_FAILURES=new AtomicInteger();
 public static boolean hold(UUID id){return !MODE.isEmpty()&&(MODE.equals("delayed")||MODE.equals("cancel")||MODE.equals("revoke")||MODE.equals("shutdown"));}
 public static void beforeReady(UUID id){if(MODE.equals("cut-before-ready"))cut("CUT_BEFORE_READY",id);}
 public static void beforeArrival(UUID id){if(MODE.equals("cut-after-ready"))cut("CUT_AFTER_READY_BEFORE_ENTRY",id);}
 public static void beforeFlush(UUID id){if(MODE.equals("cut-before-flush"))cut("CUT_BEFORE_FLUSH",id);if(MODE.equals("failflush"))BAD_FLUSH.set(true);}
 public static void snapshotFault(net.minecraft.nbt.CompoundTag tag){if(MODE.equals("failwrite"))tag.putBoolean("AnyaFixtureFailWrite",true);}
 public static void ioWrite(RegionFileStorage storage,ChunkPos pos,net.minecraft.nbt.CompoundTag tag)throws IOException{
  if(tag!=null&&tag.getBoolean("AnyaFixtureFailWrite")){
   if(server.isSameThread())throw new IllegalStateException("IO write incorrectly on server thread");
   WRITE_FAILURES.incrementAndGet();Trace.note("INJECTED_WRITE_FAILURE",null,"thread="+Thread.currentThread().getName()+" pos="+pos);throw new IOException("Injected disposable write failure");
  }
 }
 public static void ioFlush(RegionFileStorage storage)throws IOException{
  if(storage.info().toString().contains("anyastorage:pockets")&&BAD_FLUSH.compareAndSet(true,false)){
   if(server.isSameThread())throw new IllegalStateException("IO flush incorrectly on server thread");
   FLUSH_FAILURES.incrementAndGet();Trace.note("INJECTED_FLUSH_FAILURE",null,Thread.currentThread().getName());throw new IOException("Injected disposable flush failure");
  }
 }
 private static void cut(String marker,UUID id){Trace.marker(marker,"id="+id+" tick="+Trace.tick+" stage="+record(id).stage()+"; Runtime.halt(77), not a clean stop\n");Runtime.getRuntime().halt(77);}
 private static void check(boolean v,String m){if(!v)throw new IllegalStateException(m);}
 private static PocketLedger.Pocket record(UUID id){try{return PocketService.ledger(server).get(id);}catch(IOException e){throw new IllegalStateException(e);}}
 private static boolean inside(ServerPlayer p){return PocketService.inPocket(p.level());}
 private static ServerPlayer player(int i){
  var profile=new GameProfile(UUID.nameUUIDFromBytes(("DurableFixture"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)),"Durable"+i);
  var p=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());
  var c=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND){
   @Override public boolean isMemoryConnection(){return true;}
   @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener callback,boolean flush){}
  };
  new io.netty.channel.embedded.EmbeddedChannel(c);net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(c);
  server.getPlayerList().placeNewPlayer(c,p,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false));return p;
 }
 private static ItemStack named(){var s=new ItemStack(Items.EMERALD,11);s.set(DataComponents.CUSTOM_NAME,Component.literal("Durable preparation inventory"));return s;}
 @SubscribeEvent public static void started(ServerStartedEvent e)throws Exception{
  if(MODE.isEmpty())return;server=e.getServer();check(Files.isRegularFile(Path.of(".anya-durable-fixture")),"No disposable marker");
  var rules=ExpansionRules.get();rules.pocketStorage=true;rules.pocketEditing=false;rules.pocketLimit=16;rules.activePocketLimit=8;rules.pocketSessionTicks=12000;
  var level=server.overworld();level.getChunk(0,0);level.getChunk(1,0);server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,server);
  if(MODE.startsWith("resume-")){
   ServerPlayer p=player(0);PLAYERS.add(p);var home=new BlockPos(1,81,1);var en=new BlockPos(2,81,1);
   HUBS.add((StorageEntity)level.getBlockEntity(home));ENTRANCES.add((PocketContent.EntranceEntity)level.getBlockEntity(en));
   IDS.add(ENTRANCES.getFirst().pocket);check(PocketService.ledger(server).pockets().size()==1,"Restart allocated duplicate room");
   var prop=new Properties();try(var in=Files.newInputStream(Path.of("durable-identity.properties"))){prop.load(in);}
   check(IDS.getFirst().toString().equals(prop.getProperty("id"))&&record(IDS.getFirst()).cell()==Integer.parseInt(prop.getProperty("cell")),"Identity/cell changed");
   check(Transactions.same(p.getInventory().getItem(5),named()),"Saved inventory/components lost");
   phase="restart-inspection";running=true;return;
  }
  check(!Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Never seed an existing pocket world");
  for(int x=-3;x<34;x++)for(int z=-3;z<15;z++)level.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());level.setDefaultSpawnPos(new BlockPos(5,81,7),0);
  int n=MODE.equals("measure")?8:1;
  for(int i=0;i<n;i++){
   var p=player(i);PLAYERS.add(p);BlockPos hp=new BlockPos(1+i*4,81,1),ep=hp.east();
   level.setBlockAndUpdate(hp,Content.HUB.get().defaultBlockState());level.setBlockAndUpdate(ep,PocketContent.ENTRANCE.get().defaultBlockState());
   var hub=(StorageEntity)level.getBlockEntity(hp);var en=(PocketContent.EntranceEntity)level.getBlockEntity(ep);hub.owner=p.getUUID();en.owner=p.getUUID();hub.setChanged();en.setChanged();
   p.teleportTo(level,ep.getX()+.5,81,3.5,180,0);p.getInventory().clearContent();p.getInventory().setItem(5,named());
   var remote=new ItemStack(Content.REMOTE.get());var tag=BagInventory.data(remote);tag.putString("HubDimension","minecraft:overworld");tag.putLong("HubPos",hp.asLong());BagInventory.data(remote,tag);p.getInventory().setItem(0,remote);
   PocketService.link(p,en,remote);check(en.pocket!=null,"Link failed");HUBS.add(hub);ENTRANCES.add(en);IDS.add(en.pocket);
  }
  Properties prop=new Properties();prop.setProperty("id",IDS.getFirst().toString());prop.setProperty("cell",""+record(IDS.getFirst()).cell());try(var out=Files.newOutputStream(Path.of("durable-identity.properties"))){prop.store(out,"disposable identity checkpoint");}
  server.saveEverything(true,true,true);phase="cold-admission";running=true;
 }
 private static void safeContents(){for(int i=0;i<PLAYERS.size();i++)check(Transactions.same(PLAYERS.get(i).getInventory().getItem(5),named()),"Player inventory changed in preparation");}
 private static void chests(UUID id){var r=record(id);var level=PocketService.dimension(server);for(var pos:PocketService.chestPositions(r)){
  check(level.getBlockEntity(pos) instanceof StorageEntity,"Physical chest missing");var c=(StorageEntity)level.getBlockEntity(pos);check(c.pocketId.equals(id)&&c.owner.equals(r.owner())&&c.isEmpty(),"Chest duplicated/filled/misowned");
 }}
 private static void next(String phase){step++;since=Trace.tick;Fixture.phase=phase;}
 private static void finish(String text)throws Exception{safeContents();Trace.audit(server);check(Preparation.JOBS.isEmpty(),"Stale preparation jobs");Trace.marker("PASSED",MODE+" "+text+" requests="+PocketService.ownedTicketRequests()+" writeFailures="+WRITE_FAILURES+" flushFailures="+FLUSH_FAILURES);running=false;server.saveEverything(true,true,true);server.halt(false);}
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
  if(!running||e.getServer()!=server)return;
  try{
   if(Trace.tick>2100)throw new IllegalStateException("Bounded fixture timeout "+MODE+" "+phase);
   var p=PLAYERS.getFirst();UUID id=IDS.getFirst();var r=record(id);safeContents();
   if(MODE.startsWith("resume-")){resume();return;}
   if(step==0&&Trace.tick>10){for(int i=0;i<PLAYERS.size();i++)PocketService.enter(PLAYERS.get(i),ENTRANCES.get(i));next("prepare");return;}
   if(MODE.equals("measure")){
    if(step==1&&PLAYERS.stream().allMatch(Fixture::inside)){for(UUID q:IDS){check(record(q).stage()==PocketLedger.Stage.READY,"Entry before READY");chests(q);}next("visit-steady");}
    else if(step==2&&Trace.tick-since>=40){for(var q:PLAYERS)PocketService.recover(q);next("return");}
    else if(step==3&&PLAYERS.stream().noneMatch(Fixture::inside)&&PocketService.ledger(server).returnCount()==0){for(int i=0;i<PLAYERS.size();i++)PocketService.remote(PLAYERS.get(i),PLAYERS.get(i).getInventory().getItem(0),HUBS.get(i));next("cold-to-remote");}
    else if(step==4&&Trace.tick-since>=400){for(var q:PLAYERS){check(q.containerMenu instanceof StoreMenu m&&m.capacity()==216,"Remote contents unavailable");q.closeContainer();}next("drain");}
    else if(step==5&&Trace.tick-since>=650){check(PocketService.ownedTicketRequests()==0&&PocketService.dimension(server).getChunkSource().getLoadedChunksCount()==0,"Residual loading");finish("eight pockets; real server fixtures, not network clients; cold and unload measured");}return;
   }
   Preparation.Job j=Preparation.JOBS.get(id);
   if(step==1&&j!=null&&j.actualFlushed&&hold(id)){
    if(flushedAt<0)flushedAt=Trace.tick;
    check(r.stage()==PocketLedger.Stage.BUILDING&&!inside(p)&&PocketService.ledger(server).returnCount()==0,"Delayed completion published early");
    if(Trace.tick-flushedAt>=60){
     if(MODE.equals("delayed")){j.testGate.complete(null);next("gate-released");}
     else if(MODE.equals("shutdown")){Trace.marker("SHUTDOWN_PENDING","BUILDING with durable IO but unresolved publication gate");running=false;server.halt(false);}
     else{cancelled=j;if(MODE.equals("revoke"))HUBS.getFirst().owner=UUID.randomUUID();else PocketService.logout(p);next("cancel-await");}
    }
   }
   if(step==2&&(MODE.equals("cancel")||MODE.equals("revoke"))&&Trace.tick-since>=5){
    check(PocketService.lease(p.getUUID())==null&&!Preparation.has(id),"Cancelled job retained lease or publication token");HUBS.getFirst().owner=p.getUUID();
    var before=record(id);BlockPos replacement=new BlockPos(3,81,1);server.overworld().setBlockAndUpdate(replacement,PocketContent.ENTRANCE.get().defaultBlockState());var en=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(replacement);en.owner=p.getUUID();
    PocketService.reanchor(p,id,en,p.getInventory().getItem(0));check(record(id).epoch()>before.epoch()&&record(id).cell()==before.cell(),"Reanchor lost original cell");cancelled.testGate.complete(null);next("stale-completion");
   }
   if(step==3&&(MODE.equals("cancel")||MODE.equals("revoke"))&&Trace.tick-since>=60){check(record(id).stage()==PocketLedger.Stage.BUILDING&&!inside(p),"Stale completion published after reanchor");next("drain");}
   if(step==1&&(MODE.equals("failwrite")||MODE.equals("failflush"))&&PocketService.lease(p.getUUID())==null&&Trace.tick-since>40){
    check(record(id).stage()==PocketLedger.Stage.BUILDING&&!inside(p)&&!Preparation.has(id),"IO failure did not fail closed");check(MODE.equals("failwrite")?WRITE_FAILURES.get()>0:FLUSH_FAILURES.get()>0,"No actual IO fault observed");PocketService.enter(p,ENTRANCES.getFirst());check(PocketService.lease(p.getUUID())==null,"Interrupted BUILDING replayed");step=4;since=Trace.tick;phase="drain";
   }
   if(step==2&&MODE.equals("delayed")&&inside(p)){check(record(id).stage()==PocketLedger.Stage.READY,"Entry not READY");chests(id);PocketService.recover(p);step=4;since=Trace.tick;phase="drain";}
   if(step==4&&Trace.tick-since>=650){check(!inside(p)&&PocketService.ownedTicketRequests()==0&&PocketService.dimension(server).getChunkSource().getLoadedChunksCount()==0,"Failed final cleanup");finish("completion, cancellation and no premature entry checked");}
  }catch(Throwable t){running=false;Trace.marker("FAILED",MODE+" "+phase+" "+t);AnyaStorage.LOG.error("ANYA_DURABLE_FIXTURE_FAILED",t);server.halt(false);}
 }
 private static void resume()throws Exception{
  var p=PLAYERS.getFirst();UUID id=IDS.getFirst();var r=record(id);boolean ready=MODE.equals("resume-cut-after-ready");
  if(step==0&&Trace.tick>10){
   check(r.stage()==(ready?PocketLedger.Stage.READY:PocketLedger.Stage.BUILDING),"Wrong durable stage after cut/stop");check(!inside(p)&&PocketService.ledger(server).returnCount()==0,"Premature entry/return record");
   if(!MODE.equals("resume-cut-before-flush")){for(var pos:PocketService.chestPositions(r))PocketService.dimension(server).getChunk(pos.getX()>>4,pos.getZ()>>4);chests(id);}
   p.teleportTo(server.overworld(),2.5,81,3.5,180,0);PocketService.enter(p,ENTRANCES.getFirst());
   if(!ready)check(PocketService.lease(p.getUUID())==null,"BUILDING replayed after process interruption");next("restart-denial-or-admission");
  }
  if(step==1&&Trace.tick-since>50){if(ready){check(inside(p),"Durable READY could not be visited after restart");chests(id);PocketService.recover(p);}next("restart-drain");}
  if(step==2&&Trace.tick-since>=650){check(!inside(p)&&PocketService.ownedTicketRequests()==0&&PocketService.ledger(server).returnCount()==0,"Restart failed return/cleanup");finish("original identity preserved, no regeneration or inventory copy");}
 }
}

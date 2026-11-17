package dev.anya.storage.livecheck;
import com.google.gson.GsonBuilder;
import dev.anya.storage.*;
import dev.anya.storage.liveview.*;
import dev.anya.storage.pocket.*;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.repair.RepairService;
import dev.anya.storage.operator.ReadOnlyRegion;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.*;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import qouteall.imm_ptl.core.chunk_loading.*;
import qouteall.imm_ptl.core.network.PacketRedirection;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Disposable fixture, separate from the adapter: three real clients, two prepared production pockets. */
@Mod(LiveTest.ID) @EventBusSubscriber(modid=LiveTest.ID)
public final class LiveTest {
 public static final String ID="anyalivecheck";public static MinecraftServer server;
 static final BlockPos[] HP={new BlockPos(1,81,5),new BlockPos(13,81,5)},EP={new BlockPos(3,81,5),new BlockPos(15,81,5)};
 static final UUID[] ROOMS=new UUID[2];static final StorageEntity[] HUBS=new StorageEntity[2];static final PocketContent.EntranceEntity[] ENTRANCES=new PocketContent.EntranceEntity[2];
 static final Map<String,Integer> acks=new HashMap<>();static final Set<String> ready=new HashSet<>();static final Properties oracle=new Properties();
 static final List<Step> steps=new ArrayList<>();static Step active;static int index,seq;static long since,started,deadlineB;static boolean running,initialized,resume,forceDone;
 static Packet<?> delayed;static BufferedWriter metrics;static int peakCache,peakCe,peakBlock,peakEntity;static UUID oldBeta;
 @FunctionalInterface interface Action {void run()throws Exception;} @FunctionalInterface interface Test {boolean get()throws Exception;}
 record Step(String name,Action action,Test ready,int limit){}
 public LiveTest(){control();}
 public static Path control(){if(!"true".equals(System.getenv("GITHUB_ACTIONS")))throw new IllegalStateException("Disposable GitHub only");var p=Path.of(System.getProperty("anya.liveControl")).toAbsolutePath();if(!Files.isRegularFile(p.resolve(".disposable-live-view")))throw new IllegalStateException("Disposable marker absent");return p;}
 public static void write(String name,Object value){try{Files.writeString(control().resolve(name),new GsonBuilder().setPrettyPrinting().create().toJson(value)+"\n");}catch(IOException e){throw new IllegalStateException(e);}}
 public static void check(boolean value,String text){if(!value)throw new IllegalStateException(text);}
 public static void fail(Throwable t){write("FAILED",Map.of("step",active==null?"setup":active.name,"error",t.toString()));AnyaStorage.LOG.error("LIVE_VIEW_ACCEPTANCE_FAILED",t);if(server!=null)server.halt(false);}
 static ServerPlayer p(String role){return server.getPlayerList().getPlayerByName(role);}
 static PocketLedger.Pocket room(int i)throws Exception{return PocketService.ledger(server).get(ROOMS[i]);}
 static ServerLevel dest(){return PocketService.dimension(server);}
 static void send(String role,String action){
  if(action.startsWith("block ")){
   var words=action.split(" ");var pos=new BlockPos(Integer.parseInt(words[1]),Integer.parseInt(words[2]),Integer.parseInt(words[3]));var actor=p(role);
   boolean before=actor.canInteractWithBlock(pos,0);double distance=actor.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
   // Disposable actor staging only. Keep actual survival reach and send one
   // ordinary use packet after the client independently observes readiness.
   check(actor.serverLevel().hasChunkAt(pos),"Fixture interaction target is not loaded");
   actor.teleportTo(actor.serverLevel(),pos.getX()+.5,pos.getY(),pos.getZ()-1.5,0,0);
   check(actor.canInteractWithBlock(pos,0),"Fixture staging did not establish ordinary block reach");
   AnyaStorage.LOG.info("LIVE_BLOCK_REACH role={} target={} before={} old_eye_distance_sq={} after={}",role,pos,before,distance,actor.canInteractWithBlock(pos,0));
  }
  seq++;acks.clear();PacketDistributor.sendToPlayer(p(role),new TestWire.Signal(seq,action));
 }
 static boolean ack(String role){return Objects.equals(acks.get(role),seq);}
 public static void receive(ServerPlayer p,TestWire.Signal s){if(s.action().equals("ready")){ready.add(p.getGameProfile().getName());return;}if(s.sequence()==seq&&s.action().equals("done"))acks.put(p.getGameProfile().getName(),seq);}
 static void add(String name,Action action,Test done,int limit){steps.add(new Step(name,action,done,limit));}
 static void once(String name,Action action){add(name,action,()->true,80);}
 static void command(String role,String cmd){add(role+" command "+cmd,()->send(role,"cmd "+cmd),()->ack(role),180);}
 static void returnedClient(String role){add(role+" usable home client after return",()->send(role,"return-ready"),()->ack(role),900);}
 static void inspect(String role,int mask){add(role+" applied mask "+mask,()->send(role,"inspect "+mask),()->ack(role),240);}
 static boolean inside(String role){return p(role)!=null&&PocketService.inPocket(p(role).level());}
 static void home(String role){var player=p(role);double x=role.equals("Alpha")?3.5:9.5;player.teleportTo(server.overworld(),x,81,1.5,0,0);}
 static long handleDeadline(String role,int pocket){return LiveViews.HANDLES.values().stream().filter(h->h.player.equals(p(role).getUUID())&&h.pocket.equals(ROOMS[pocket])).findFirst().orElseThrow().deadline;}
 static long handles(String role){return LiveViews.HANDLES.values().stream().filter(h->p(role)!=null&&h.player.equals(p(role).getUUID())).count();}
 static ItemStack sample(int i){var s=new ItemStack(i==0?Items.DIAMOND:Items.EMERALD,i==0?13:17);s.set(DataComponents.CUSTOM_NAME,Component.literal("Live view conserved "+i));return s;}
 static void seed(int i)throws Exception{
  var r=room(i);oracle.setProperty("room."+i,r.id()+"/"+r.cell()+"/"+r.owner());int j=0;
  for(var pos:PocketService.chestPositions(r)){var chest=(StorageEntity)dest().getBlockEntity(pos);check(chest!=null&&chest.isEmpty(),"Seed refuses existing items");oracle.setProperty("chest."+i+"."+j,chest.storageIdentity().toString());if(j++==0)chest.setItem(0,sample(i));}
  // Flat diagnostic plane is test geometry, not a cosmetic redesign.
  for(int x=8;x<=16;x++)for(int y=65;y<=71;y++)dest().setBlock(new BlockPos(r.x()+x,y,r.z()+12),(i==0?Blocks.RED_CONCRETE:Blocks.LIME_CONCRETE).defaultBlockState(),3);
  var stand=new ArmorStand(dest(),r.x()+12.5,65,r.z()+8.5);stand.setCustomName(Component.literal("No remote entity stream "+i));dest().addFreshEntity(stand);
 }
 static void checkItems(boolean load)throws Exception{
  int total=0,boxes=0;for(int i=0;i<2;i++){var r=room(i);check(oracle.getProperty("room."+i).equals(r.id()+"/"+r.cell()+"/"+r.owner()),"Room identity changed");int j=0;
   for(var pos:PocketService.chestPositions(r)){if(load)dest().getChunk(pos.getX()>>4,pos.getZ()>>4);else check(dest().hasChunkAt(pos),"Unexpected unloaded item check");
    var chest=(StorageEntity)dest().getBlockEntity(pos);check(chest!=null&&r.id().equals(chest.pocketId)&&r.owner().equals(chest.owner)&&chest.storageIdentity().toString().equals(oracle.getProperty("chest."+i+"."+j)),"Chest identity changed");
    if(j++==0){check(Transactions.same(chest.getItem(0),sample(i)),"Item/component conservation failure");total+=chest.getItem(0).getCount();for(int n=1;n<chest.getContainerSize();n++)check(chest.getItem(n).isEmpty(),"Extra target item");}else check(chest.isEmpty(),"Unexpected duplicate in another chest");boxes++;
   }}check(total==30&&boxes==8,"Wrong item/chest totals");
 }
 static boolean exactEmpty()throws Exception{
  var map=LiveViews.watches().get(PocketService.DIMENSION);var chunkMap=dest().getChunkSource().chunkMap;
  return LiveViews.HANDLES.isEmpty()&&(map==null||map.isEmpty())&&ImmPtlChunkTickets.get(dest()).getLoadedChunkNum()==0&&dest().getChunkSource().getLoadedChunksCount()==0&&PocketService.ownedTicketRequests()==0&&PocketService.ledger(server).returnCount()==0&&((Map<?,?>)field(chunkMap,"pendingUnloads")).isEmpty()&&((Queue<?>)field(chunkMap,"unloadQueue")).isEmpty()&&RepairService.idle();
 }
 static Object field(Object o,String n)throws Exception{for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignore){}throw new NoSuchFieldException(n);}
 @SuppressWarnings("unchecked") static void metrics()throws Exception{
  if(dest()==null)return;int ce=0,block=0,entity=0,full=0;var cache=dest().getChunkSource();Map<Long,ChunkHolder> holders=(Map)field(cache.chunkMap,"updatingChunkMap");
  for(var h:holders.values()){if(h.getTickingChunk()!=null)block++;var f=h.getEntityTickingChunkFuture().getNow(null);if(f!=null&&f.orElse(null)!=null)entity++;var g=h.getFullChunkFuture().getNow(null);if(g!=null&&g.orElse(null)!=null)full++;}
  Map<?,?> tickets=(Map<?,?>)field(field(cache,"distanceManager"),"tickets");for(var row:tickets.values())for(var raw:(Iterable<?>)row)if(((net.minecraft.server.level.Ticket<?>)raw).getType()==ImmPtlChunkTickets.TICKET_TYPE)ce++;
  Set<Long> requested=new HashSet<>();for(var p:server.getPlayerList().getPlayers())requested.addAll(LiveViews.requested(p));int watches=0;var watchMap=LiveViews.watches().get(PocketService.DIMENSION);if(watchMap!=null)for(var row:watchMap.values())watches+=row.size();
  peakCache=Math.max(peakCache,cache.getLoadedChunksCount());peakCe=Math.max(peakCe,ce);peakBlock=Math.max(peakBlock,block);peakEntity=Math.max(peakEntity,entity);
  check(requested.size()<=50,"Requested-column bound exceeded");check(ce<=50,"Exact CE ticket bound exceeded");check(cache.getLoadedChunksCount()<=4096,"Actual destination cache bound exceeded");
  metrics.write(server.getTickCount()+","+index+","+LiveViews.HANDLES.size()+","+requested.size()+","+watches+","+ImmPtlChunkTickets.get(dest()).getLoadedChunkNum()+","+ce+","+cache.getLoadedChunksCount()+","+full+","+block+","+entity+","+PacketFence.accepted+","+PacketFence.denied+","+PacketFence.forgotten+","+PocketService.ownedTicketRequests()+"\n");if(server.getTickCount()%20==0)metrics.flush();
 }
 @SubscribeEvent public static void start(ServerStartedEvent e)throws Exception{
  server=e.getServer();resume=Boolean.getBoolean("anya.liveResume");started=server.getTickCount();running=true;ExpansionRules.get().pocketStorage=true;
  metrics=Files.newBufferedWriter(control().resolve(resume?"restart-metrics.csv":"live-metrics.csv"));metrics.write("tick,step,handles,requested_columns,watch_records,ce_registry,actual_ce_tickets,cache,full_future_ready,block_ticking_ready,entity_ticking_ready,accepted_packets,denied_packets,forget_packets,anya_requests\n");
  for(var l:server.getAllLevels()){l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);l.setDayTime(6000);l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);}
  if(resume){try(var in=Files.newInputStream(Path.of("live-view-oracle.properties"))){oracle.load(in);}for(int i=0;i<2;i++)ROOMS[i]=UUID.fromString(oracle.getProperty("room."+i).split("/")[0]);
   check(LiveViews.HANDLES.isEmpty(),"View handles persisted");var region=server.getWorldPath(LevelResource.ROOT).resolve("dimensions/anyastorage/pockets/region");for(int i=0;i<2;i++){var r=room(i);var primary=ReadOnlyRegion.inspect(region,new ChunkPos(PocketService.chestPositions(r).getFirst()).toLong());check(primary.tag().toString().contains("Live view conserved "+i),"Independent primary read-back lacks newest sample");}
   checkItems(true);write("RESTART_ITEMS",Map.of("items",30,"rooms",2,"chests",8,"primary_read_before_chunk_load",true));initialized=true;since=server.getTickCount();return;
  }
  check(!Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Only a new disposable world");
  for(int x=-8;x<=30;x++)for(int z=-8;z<=18;z++)server.overworld().setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());server.overworld().setDefaultSpawnPos(new BlockPos(8,81,2),0);
  for(int i=0;i<2;i++){server.overworld().setBlockAndUpdate(HP[i],Content.HUB.get().defaultBlockState());server.overworld().setBlockAndUpdate(EP[i],PocketContent.ENTRANCE.get().defaultBlockState());HUBS[i]=(StorageEntity)server.overworld().getBlockEntity(HP[i]);ENTRANCES[i]=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(EP[i]);}
  write("server-ready",Map.of("clients",3,"world","fresh disposable"));
 }
 static void setup()throws Exception{
  for(String role:List.of("Alpha","Beta","Gamma")){home(role);p(role).setGameMode(GameType.SURVIVAL);p(role).getInventory().clearContent();p(role).getInventory().setItem(9,new ItemStack(Items.COAL,5));p(role).inventoryMenu.broadcastChanges();}
  for(int i=0;i<2;i++){var owner=p(i==0?"Alpha":"Beta");HUBS[i].owner=owner.getUUID();ENTRANCES[i].owner=owner.getUUID();ItemStack remote=new ItemStack(Content.REMOTE.get());var t=BagInventory.data(remote);t.putString("HubDimension","minecraft:overworld");t.putLong("HubPos",HP[i].asLong());BagInventory.data(remote,t);owner.getInventory().setItem(0,remote);PocketService.link(owner,ENTRANCES[i],remote);ROOMS[i]=ENTRANCES[i].pocket;check(ROOMS[i]!=null,"Original link failed");owner.inventoryMenu.broadcastChanges();}
  add("prepare first through real entrance",()->send("Alpha","block 3 81 5"),()->ack("Alpha")&&inside("Alpha"),1400);
  once("seed first conserved inventory",()->seed(0));command("Alpha","anya pocket exit");add("first safe return",()->{},()->!inside("Alpha")&&PocketService.lease(p("Alpha").getUUID())==null,300);returnedClient("Alpha");
  add("prepare second through real entrance",()->send("Beta","block 15 81 5"),()->ack("Beta")&&inside("Beta"),1400);
  once("seed second conserved inventory",()->seed(1));command("Beta","anya pocket exit");add("second safe return",()->{},()->!inside("Beta")&&PocketService.lease(p("Beta").getUUID())==null,300);returnedClient("Beta");
  once("durable setup and view-only trust",()->{server.saveEverything(true,true,true);HUBS[0].setMember(p("Beta").getUUID(),BasePermissions.VIEW);HUBS[1].setMember(p("Alpha").getUUID(),BasePermissions.VIEW);home("Alpha");home("Beta");});
  add("settled setup receipts",()->{},()->RepairService.idle()&&!RepairService.roomBlocked(ROOMS[0])&&!RepairService.roomBlocked(ROOMS[1]),400);
  command("Alpha","anyaliveview open "+ROOMS[0]);inspect("Alpha",1);
  command("Beta","anyaliveview open "+ROOMS[0]);command("Beta","anyaliveview open "+ROOMS[1]);inspect("Beta",3);inspect("Gamma",0);
  command("Gamma","anyaliveview open "+ROOMS[0]);once("unauthorized open and entity stream refused",()->{check(handles("Gamma")==0,"Unauthorized handle");check(PacketFence.COUNTS.getOrDefault(p("Gamma").getUUID(),Map.of()).entrySet().stream().noneMatch(en->en.getKey().startsWith("allow:")&&!en.getKey().equals("allow:forget")),"Unauthorized destination data");deadlineB=handleDeadline("Beta",1);});
  add("View does not grant Visit",()->send("Beta","block 3 81 5"),()->ack("Beta")&&!inside("Beta"),180);
  add("forged reach/travel/storage intents",()->send("Beta","forge"),()->ack("Beta"),180);
  once("queue a real chunk packet then revoke one pocket",()->{var r=room(0);var pos=new ChunkPos(r.x()>>4,r.z()>>4);var chunk=dest().getChunkSource().getChunkNow(pos.x,pos.z);check(chunk!=null,"Expected loaded target");delayed=PacketRedirection.createRedirectedMessage(server,PocketService.DIMENSION,new ClientboundLevelChunkWithLightPacket(chunk,dest().getLightEngine(),null,null));HUBS[0].removeMember(p("Beta").getUUID());p("Beta").connection.send(delayed);});
  inspect("Beta",2);inspect("Alpha",1);inspect("Gamma",0);
  once("other handle deadline preserved",()->check(handleDeadline("Beta",1)==deadlineB,"Revoking one pocket changed another deadline"));
  once("grant Visit only to observer",()->{HUBS[1].setMember(p("Gamma").getUUID(),BasePermissions.VISIT);home("Gamma");});
  add("mixed real visitation",()->send("Gamma","block 15 81 5"),()->ack("Gamma")&&inside("Gamma"),500);inspect("Gamma",2);
  once("visitor at physical chest",()->{var r=room(1);p("Gamma").teleportTo(dest(),r.x()+6.5,65,r.z()+4.5,0,0);});
  add("Visit-only chest view",()->{var q=PocketService.chestPositions(room(1)).getFirst();send("Gamma","block "+q.getX()+" "+q.getY()+" "+q.getZ());},()->ack("Gamma")&&p("Gamma").containerMenu instanceof StoreMenu,180);
  add("Visit-only transfer denial",()->send("Gamma","deny-items"),()->ack("Gamma"),180);
  command("Alpha","anyaliveview close");inspect("Alpha",0);inspect("Beta",2);
  once("visitor and unrelated view survive close",()->{check(inside("Gamma")&&PocketService.lease(p("Gamma").getUUID())!=null,"Closing preview released visitor");check(handleDeadline("Beta",1)==deadlineB,"Closing another view extended deadline");});
  add("first pocket scoped drain while clients remain",()->{},()->{var r=room(0);return ((Map<?,?>)field(dest().getChunkSource().chunkMap,"updatingChunkMap")).keySet().stream().mapToLong(k->((Number)k).longValue()).noneMatch(k->Math.abs(ChunkPos.getX(k)-(r.x()>>4))<=16&&Math.abs(ChunkPos.getZ(k)-(r.z()>>4))<=16);},700);
  command("Alpha","anyaliveview open "+ROOMS[0]);inspect("Alpha",1);
  once("request actual Beta socket interruption",()->{oldBeta=p("Beta").getUUID();write("kill-beta",Map.of("action","SIGKILL real client while viewing"));});
  add("socket release preserves visitor",()->{},()->{if(p("Beta")!=null)return false;check(LiveViews.HANDLES.values().stream().noneMatch(h->h.player.equals(oldBeta)),"Socket loss retained handles");check(inside("Gamma")&&PocketService.lease(p("Gamma").getUUID())!=null,"Socket loss disturbed other visitor");write("restart-beta",Map.of("same_identity",true));return true;},300);
  add("new Beta client returns with no handles",()->{},()->p("Beta")!=null&&ready.contains("Beta")&&handles("Beta")==0,1500);inspect("Beta",0);inspect("Alpha",1);
  once("range loss",()->{
   // Keep the survival actor on real footing while exercising the same distant
   // location. The previous fixture dropped it into empty terrain and the
   // accumulated fall killed it on return, testing death instead of range loss.
   for(int x=998;x<=1002;x++)for(int z=-1;z<=3;z++)server.overworld().setBlockAndUpdate(new BlockPos(x,80,z),Blocks.STONE.defaultBlockState());
   p("Alpha").teleportTo(server.overworld(),1000,81,1,0,0);
  });inspect("Alpha",0);
  once("return to home without auto reopening",()->{check(p("Alpha").isAlive(),"Range test actor died");home("Alpha");});command("Alpha","anyaliveview open "+ROOMS[0]);inspect("Alpha",1);
  once("entrance loss invalidates only its own view",()->server.overworld().setBlockAndUpdate(EP[0],Blocks.AIR.defaultBlockState()));inspect("Alpha",0);
  once("other visitor retained on anchor loss",()->check(inside("Gamma")&&PocketService.lease(p("Gamma").getUUID())!=null,"Other pocket visitor lost"));
  command("Gamma","anya pocket exit");add("normal Visit recovery intact",()->{},()->!inside("Gamma")&&PocketService.ledger(server).returnCount()==0,300);returnedClient("Gamma");
  inspect("Gamma",0);inspect("Alpha",0);inspect("Beta",0);
  add("all clients remain connected through complete drainage",()->since=server.getTickCount(),()->server.getTickCount()-since>=650&&exactEmpty(),1000);
  once("force-save physical data without changing inventories",()->{checkItems(true);server.saveEverything(true,true,true);for(int i=0;i<2;i++)oracle.setProperty("id."+i,ROOMS[i].toString());try(var out=Files.newOutputStream(Path.of("live-view-oracle.properties"))){oracle.store(out,"Disposable acceptance oracle");}write("FORCE_SAVE_ITEMS",Map.of("items",30,"rooms",2,"chests",8));});
  add("post-flush final drainage",()->since=server.getTickCount(),()->server.getTickCount()-since>=650&&exactEmpty(),1000);
  once("stop with every client still connected",()->{Trace.audit(server);check(PacketFence.denied>0&&PacketFence.privacyChecks>0&&LiveViews.autoLoadersSuppressed>0&&LiveViews.nestedQueriesSuppressed>0,"Missing isolation path coverage");write("PASSED",Map.of("clients",3,"peak_cache",peakCache,"peak_ce_tickets",peakCe,"peak_block_ready",peakBlock,"peak_entity_ready",peakEntity,"packet_counts",PacketFence.COUNTS,"required_snapshots_retained",true));running=false;server.halt(false);});
  initialized=true;
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){if(!running||server!=e.getServer())return;try{
  check(server.getTickCount()-started<16000,"Total fixture tick ceiling");check(!LiveViews.failed&&!LiveViews.pressure,"Adapter fail-closed/resource state");metrics();
  if(resume){if(server.getTickCount()-since>=650&&exactEmpty()){Trace.audit(server);write("RESTART_PASSED",Map.of("items",30,"room_ids",2,"chest_ids",8,"all_runtime_queues_empty",true));running=false;server.halt(false);}return;}
  if(!initialized){check(server.getTickCount()-started<3600,"Client readiness bound");if(ready.containsAll(List.of("Alpha","Beta","Gamma"))&&p("Alpha")!=null&&p("Beta")!=null&&p("Gamma")!=null)setup();return;}
  if(active==null&&index<steps.size()){active=steps.get(index);since=server.getTickCount();active.action.run();write("heartbeat",Map.of("step",index,"name",active.name,"tick",since));}
  if(active!=null){check(server.getTickCount()-since<active.limit,"Step deadline: "+active.name);if(active.ready.get()){AnyaStorage.LOG.info("LIVE_VIEW_CHECKPOINT {} {}",index,active.name);index++;active=null;}}
 }catch(Throwable t){running=false;fail(t);}}
 @SubscribeEvent public static void stopped(ServerStoppedEvent e)throws IOException{if(metrics!=null){metrics.close();metrics=null;}}
}

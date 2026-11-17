package dev.anya.storage.acceptance;
import com.mojang.datafixers.util.Either;
import dev.anya.storage.*;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.minimal.OwnershipView;
import dev.anya.storage.operator.*;
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
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.*;

/** New isolated command-path scenario; operator inspect/resolve are NEVER called directly here. */
@EventBusSubscriber(modid=Acceptance.ID)
public final class RepairClientProbe {
 private interface Work{void run()throws Exception;}private interface Ready{boolean get()throws Exception;}private record Step(String name,Work start,Ready ready,Work verify,int limit){}
 private record Reply(UUID world,long key,UUID token,String receipt,String conclusion){}
 private static final ArrayDeque<Step> steps=new ArrayDeque<>();private static final Set<UUID> ready=new HashSet<>();
 private static final Map<UUID,Integer> expected=new HashMap<>(),acks=new HashMap<>(),netExpected=new HashMap<>();private static final Map<UUID,String> replies=new HashMap<>();
 private static final Properties oracle=new Properties();private static MinecraftServer server;private static Step step;private static boolean running,watchBeta;
 private static int tick,since,seq,completed;private static long ka,kc,bDeadline;private static UUID aid,bid;private static BlockPos boxA,boxC;
 private static CompletableFuture<Void> future;private static Reply last,saved;private static String frozen;private static String leg;
 private static final BlockPos HA=new BlockPos(1,81,1),EA=new BlockPos(3,81,1),HB=new BlockPos(35,81,1),EB=new BlockPos(37,81,1);
 private static final String ELIGIBLE="ELIGIBLE_FOR_EXPLICIT_REVERIFY",UNKNOWN="UNKNOWN_OR_REPLAYED_RECEIPT",EXACT="EXACT_WORLD_KEY_TOKEN_REQUIRED",STALE="STALE_INSPECTION",UNCERTAIN="REFUSE_UNCERTAIN_OLD_VERSION",CONFLICT="REFUSE_PRIMARY_JOURNAL_DISAGREEMENT",SUCCESS="Exact current reset durably completed";
 private static void check(boolean b,String m){Acceptance.require(b,m);}private static ServerPlayer a(){return server.getPlayerList().getPlayerByName("AnyaQAAlpha");}private static ServerPlayer b(){return server.getPlayerList().getPlayerByName("AnyaQABeta");}
 private static ServerLevel level(){return PocketService.dimension(server);}private static Path root(){return server.getWorldPath(LevelResource.ROOT);}private static Path region(){var id=PocketService.DIMENSION.location();return root().resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath()).resolve("region");}
 private static PocketLedger.Pocket room(UUID id)throws Exception{return PocketService.ledger(server).get(id);}
 private static SnapshotJournal.Record record(long key){return RepairService.journal.get(key);}
 private static boolean outside(ServerPlayer p){return p!=null&&!PocketService.inPocket(p.level())&&!PocketService.recovering(p.getUUID());}
 private static boolean inside(ServerPlayer p){return p!=null&&PocketService.inPocket(p.level());}
 private static boolean absent(long key){var p=new ChunkPos(key);return level().getChunkSource().getChunkNow(p.x,p.z)==null;}
 private static void home(ServerPlayer p,BlockPos pos){p.teleportTo(server.overworld(),pos.getX()+.5,81,pos.getZ()+2.5,180,10);}
 private static ItemStack sample(boolean c,int gen){var s=new ItemStack(c?Items.GOLD_INGOT:Items.DIAMOND,c?7:13);s.set(DataComponents.CUSTOM_NAME,Component.literal("Operator client "+(c?"C":"A")+" gen="+gen));return s;}
 private static ItemStack betaSample(int n){var s=new ItemStack(Items.EMERALD,n);s.set(DataComponents.CUSTOM_NAME,Component.literal("Pocket Beta sample"));return s;}
 private static ItemStack remote(BlockPos pos){var s=new ItemStack(Content.REMOTE.get());var d=BagInventory.data(s);d.putString("HubDimension","minecraft:overworld");d.putLong("HubPos",pos.asLong());BagInventory.data(s,d);return s;}
 private static String block(BlockPos p){return "block "+p.getX()+" "+p.getY()+" "+p.getZ();}
 private static StorageEntity chest(UUID id,int i)throws Exception{var p=PocketService.chestPositions(room(id)).get(i);check(level().hasChunkAt(p),"Observation must not load destination");return (StorageEntity)level().getBlockEntity(p);}
 private static int inventory(ServerPlayer p,Item item){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n;}
 private static void beta()throws Exception{
  check(b()!=null&&b().containerMenu instanceof StoreMenu m&&m.hasPocket(bid),"Unrelated viewer lost menu");var lease=PocketService.lease(b().getUUID());check(lease!=null&&lease.deadline()==bDeadline,"Unrelated viewer deadline changed");
  check(Transactions.same(chest(bid,0).getItem(0),betaSample(16))&&inventory(b(),Items.EMERALD)==1,"Unrelated item conservation changed");
 }
 public static void ack(ServerPlayer p,Wire.Signal m){if(m.action().equals("done")&&Objects.equals(expected.get(p.getUUID()),m.sequence()))acks.put(p.getUUID(),m.sequence());}
 public static void observe(ServerPlayer p,OperatorNet.Message m){
  if(!Boolean.getBoolean("anya.operatorNetwork"))return;
  if(m.verb().equals("ready")){check(p.getGameProfile().getName().equals("AnyaQA"+m.text()),"Unexpected ready identity");ready.add(p.getUUID());return;}
  check(m.verb().equals("result")&&Objects.equals(netExpected.get(p.getUUID()),m.sequence()),"Unexpected/mismatched client command observation");
  replies.put(p.getUUID(),m.text());Trace.note("OPERATOR_ACTUAL_CLIENT_RESULT",p.getUUID(),"sequence="+m.sequence()+" "+m.text());
 }
 private static void send(ServerPlayer p,String action){int n=++seq;expected.put(p.getUUID(),n);acks.remove(p.getUUID());PacketDistributor.sendToPlayer(p,new Wire.Signal(n,action));}
 private static boolean done(ServerPlayer p){return p!=null&&expected.containsKey(p.getUUID())&&Objects.equals(expected.get(p.getUUID()),acks.get(p.getUUID()));}
 private static void add(String n,Work s,Ready r,Work v){steps.add(new Step(n,s,r,v,900));}
 private static void action(String name,boolean beta,String action,Work verify){add(name,()->send(beta?b():a(),action),()->done(beta?b():a()),verify);}
 private static void hold(String name,int ticks,Work verify){add(name,()->{},()->tick-since>=ticks,verify);}
 private static void command(String name,boolean beta,Supplier<String> command,String response,Work verify){
  add(name,()->{var p=beta?b():a();int n=++seq;netExpected.put(p.getUUID(),n);replies.remove(p.getUUID());String c=command.get();check(c.startsWith("anya_repair_operator "),"Wrong command namespace");PacketDistributor.sendToPlayer(p,new OperatorNet.Message(n,"command",response+"\n"+c));},()->replies.containsKey((beta?b():a()).getUUID()),()->{check(replies.get((beta?b():a()).getUUID()).contains(response),"Actual response mismatch");verify.run();});
 }
 private static String field(String text,String name){var m=Pattern.compile("(?:^| )"+name+"=([^ ;]+)").matcher(text);check(m.find(),"Missing network receipt field "+name);return m.group(1);}
 private static Reply parse(String text){return new Reply(UUID.fromString(field(text,"world")),Long.parseUnsignedLong(field(text,"key"),16),UUID.fromString(field(text,"token")),field(text,"receipt"),field(text,"conclusion"));}
 private static String resolve(Reply r){return "anya_repair_operator resolve "+r.world+" "+Long.toUnsignedString(r.key,16)+" "+r.token+" "+r.receipt;}
 private static String evidence(long key)throws Exception{
  check(absent(key),"Operator check loaded its target");Path journal=root().resolve("anya_repair_experiment");String hex=Long.toUnsignedString(key,16);StringBuilder result=new StringBuilder();
  for(String suffix:List.of(".wal",".reset")){var p=journal.resolve(hex+suffix);result.append(suffix).append(':').append(Files.exists(p)?JournalDisk.hash(Files.readAllBytes(p)):"absent");}
  result.append(':').append(ReadOnlyRegion.inspect(region(),key).digest());return result.toString();
 }
 private static void unchanged(long key)throws Exception{check(frozen.equals(evidence(key)),"Inspection/refusal changed target persistent bytes");check(RepairService.blocked(key),"Refusal cleared quarantine");}
 private static void inspect(String name,long key,String conclusion){
  add(name+"-fingerprint",()->frozen=evidence(key),()->true,()->{});
  command(name,false,()->"anya_repair_operator inspect "+Long.toUnsignedString(key,16),"conclusion="+conclusion,()->{
   last=parse(replies.get(a().getUUID()));check(last.key==key&&last.token.equals(record(key).token()),"Network inspection token/key mismatch");unchanged(key);
  });
 }
 private static CompoundTag next(long key,int gen)throws Exception{
  var tag=RepairService.decode(record(key).payload());var pos=key==ka?boxA:boxC;var bes=tag.getList("block_entities",10);boolean found=false;
  for(int i=0;i<bes.size();i++){var be=bes.getCompound(i);if(be.getInt("x")==pos.getX()&&be.getInt("y")==pos.getY()&&be.getInt("z")==pos.getZ()){
   var items=be.getList("Items",10);check(items.size()==1,"Snapshot would alter unexpected inventory");var item=(CompoundTag)sample(key==kc,gen).save(server.registryAccess());item.putByte("Slot",(byte)0);items.set(0,item);found=true;
  }}check(found&&absent(key),"Detached fixture target missing/loaded");return tag;
 }
 private static void newer(String name,long key,int gen){add(name,()->future=RepairService.worker().store(new ChunkPos(key),next(key,gen)),()->future.isDone()&&RepairService.idle(),()->{future.join();check(RepairService.blocked(key)&&record(key).state()==SnapshotJournal.State.REPAIRED,"New snapshot silently reopened");});}
 private static void quarantine(String name,long key){
  add(name+"-failed-primary",()->{RepairService.arm("write",key);future=RepairService.worker().store(new ChunkPos(key),next(key,1));},()->future.isDone()&&RepairService.idle(),()->{check(future.isCompletedExceptionally()&&record(key).state()==SnapshotJournal.State.PENDING,"Missing injected bootstrap failure");RepairService.arm("",Long.MIN_VALUE);RepairService.schedule(key,record(key).token());});
  add(name+"-repair-proof",()->{},()->RepairService.idle()&&record(key).state()==SnapshotJournal.State.REPAIRED,()->{});
  add(name+"-pending-intent",()->{var w=RepairService.worker();future=((WorkerAccess)(Object)w).anyaExecute(()->{try{var r=record(key);RepairService.journal.beginReset(key,r.token());RepairService.journal.reset(key,r.token());return Either.left(null);}catch(Exception x){return Either.right(x);}});},()->future.isDone(),()->{future.join();check(RepairService.journal.resetPending(key)&&RepairService.blocked(key),"Pending fixture marker absent");});
 }
 private static void saveOracle()throws Exception{var out=new ByteArrayOutputStream();oracle.store(out,"Disposable expectations; not consumed by storage recovery");try(var f=FileChannel.open(Path.of("operator-network.properties"),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){var buf=ByteBuffer.wrap(out.toByteArray());while(buf.hasRemaining())f.write(buf);f.force(true);}}
 private static void identities()throws Exception{
  for(UUID id:List.of(aid,bid)){var r=room(id);oracle.setProperty("room."+id,r.cell()+"/"+r.owner());int i=0;for(var p:PocketService.chestPositions(r)){var c=(StorageEntity)level().getBlockEntity(p);oracle.setProperty("chest."+id+"."+(i++),c.storageIdentity().toString());}}
 }
 private static void rawAll()throws Exception{
  check(PocketService.ledger(server).pockets().size()==2,"Room count changed");int total=0;
  for(UUID id:List.of(aid,bid)){var r=room(id);check(Objects.equals(oracle.getProperty("room."+id),r.cell()+"/"+r.owner()),"Room/cell/owner changed");int ci=0;
   for(var p:PocketService.chestPositions(r)){var tag=ReadOnlyRegion.inspect(region(),new ChunkPos(p).toLong()).tag();var bes=tag.getList("block_entities",10);CompoundTag found=null;
    for(int i=0;i<bes.size();i++){var be=bes.getCompound(i);if(be.getInt("x")==p.getX()&&be.getInt("y")==p.getY()&&be.getInt("z")==p.getZ())found=be;}
    check(found!=null&&found.getUUID("PocketId").equals(id)&&found.getUUID("Owner").equals(r.owner())&&found.getUUID("StorageIdentity").toString().equals(oracle.getProperty("chest."+id+"."+ci)),"Physical chest identity mismatch");
    var items=found.getList("Items",10);ItemStack expected=id.equals(bid)&&ci==0?betaSample(16):p.equals(boxA)?sample(false,3):p.equals(boxC)?sample(true,3):ItemStack.EMPTY;
    if(expected.isEmpty())check(items.isEmpty(),"Unexpected item in other chest");else{check(items.size()==1&&Transactions.same(ItemStack.parseOptional(server.registryAccess(),items.getCompound(0)),expected),"Physical newest sample mismatch");total+=expected.getCount();}ci++;
   }
  }check(total==36,"Stored item count not 36 (13 diamonds,7 gold,16 emeralds)");
  check(!RepairService.blocked(ka)&&RepairService.blocked(kc)&&RepairService.journal.resetPending(kc),"Per-key quarantine incorrectly reset");
  check(!RepairService.decode(record(kc).payload()).equals(ReadOnlyRegion.inspect(region(),kc).tag()),"Conflicting evidence was overwritten");
  Trace.note("OPERATOR_NETWORK_RAW_VERIFIED",null,"13 A gen3 diamonds,7 primary C gen3 gold with journal C gen2 retained,16 emeralds;2 rooms;8 chest identities;raw not overlay");
 }
 private static boolean drained()throws Exception{var v=(OwnershipView)(Object)level().getChunkSource().chunkMap;return PocketService.ownedTicketRequests()==0&&level().getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0&&RepairService.idle()&&PocketService.ledger(server).returnCount()==0;}
 @SubscribeEvent public static void start(ServerStartedEvent e)throws Exception{
  if(!Boolean.getBoolean("anya.operatorNetwork"))return;server=e.getServer();leg=System.getProperty("anya.operatorNetworkLeg","seed");check(Files.isRegularFile(Path.of(".anya-remote-fixture")),"Disposable marker missing");Acceptance.verifyJar("operator-server-"+leg);
  var rules=ExpansionRules.get();rules.pocketStorage=true;rules.pocketEditing=false;rules.activePocketLimit=2;rules.pocketLimit=2;rules.pocketSessionTicks=16000;
  server.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,server);server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
  if(leg.equals("seed")){
   check(!Files.exists(root().resolve("anya_storage/pockets.v1.dat")),"Never seed an existing world");
   for(BlockPos center:List.of(EA,EB))for(int x=-7;x<=7;x++)for(int z=-7;z<=7;z++)server.overworld().setBlockAndUpdate(new BlockPos(center.getX()+x,80,center.getZ()+z),Blocks.STONE.defaultBlockState());
   server.overworld().setDefaultSpawnPos(EB.offset(0,0,4),0);
   for(BlockPos p:List.of(HA,HB))server.overworld().setBlockAndUpdate(p,Content.HUB.get().defaultBlockState());for(BlockPos p:List.of(EA,EB))server.overworld().setBlockAndUpdate(p,PocketContent.ENTRANCE.get().defaultBlockState());
  }else{try(var in=Files.newInputStream(Path.of("operator-network.properties"))){oracle.load(in);}aid=UUID.fromString(oracle.getProperty("aid"));bid=UUID.fromString(oracle.getProperty("bid"));boxA=BlockPos.of(Long.parseLong(oracle.getProperty("boxA")));boxC=BlockPos.of(Long.parseLong(oracle.getProperty("boxC")));ka=new ChunkPos(boxA).toLong();kc=new ChunkPos(boxC).toLong();check(level().getChunkSource().getLoadedChunksCount()==0,"Restart inspection preloaded pockets");rawAll();}
  steps.add(new Step("two-ready-network-clients",()->{},()->a()!=null&&b()!=null&&ready.contains(a().getUUID())&&ready.contains(b().getUUID()),()->{
   server.overworld().getChunk(0,0);server.overworld().getChunk(2,0);home(a(),EA);home(b(),EB);
   if(leg.equals("seed")){server.getPlayerList().deop(a().getGameProfile());server.getPlayerList().deop(b().getGameProfile());
    var ha=(StorageEntity)server.overworld().getBlockEntity(HA);var hb=(StorageEntity)server.overworld().getBlockEntity(HB);var ea=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(EA);var eb=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(EB);
    ha.owner=a().getUUID();ea.owner=a().getUUID();hb.owner=b().getUUID();eb.owner=b().getUUID();ha.setChanged();hb.setChanged();ea.setChanged();eb.setChanged();
    a().getInventory().clearContent();b().getInventory().clearContent();var ra=remote(HA);var rb=remote(HB);PocketService.link(a(),ea,ra);PocketService.link(b(),eb,rb);aid=ea.pocket;bid=eb.pocket;a().getInventory().setItem(0,ra);b().getInventory().setItem(0,rb);a().inventoryMenu.broadcastChanges();b().inventoryMenu.broadcastChanges();
   }else{server.getPlayerList().op(a().getGameProfile());check(inventory(b(),Items.EMERALD)==1&&inventory(a(),Items.DIAMOND)==0,"Restart player item conservation");}
  },3600));
  if(leg.equals("seed"))seed();else resume();running=true;Acceptance.marker("server-ready","operator network leg="+leg+" separate 3600-tick join ceiling;900-tick gameplay steps");
 }
 private static void seed(){
  add("real-client-first-visits",()->{send(a(),block(EA));send(b(),block(EB));},()->done(a())&&done(b())&&inside(a())&&inside(b()),()->{
   identities();boxA=PocketService.chestPositions(room(aid)).getFirst();boxC=PocketService.chestPositions(room(aid)).get(3);ka=new ChunkPos(boxA).toLong();kc=new ChunkPos(boxC).toLong();check(ka!=kc,"Targets need different columns");
   chest(aid,0).setItem(0,sample(false,0));chest(aid,3).setItem(0,sample(true,0));chest(bid,0).setItem(0,betaSample(17));server.saveEverything(true,true,true);
   oracle.setProperty("aid",aid.toString());oracle.setProperty("bid",bid.toString());oracle.setProperty("boxA",""+boxA.asLong());oracle.setProperty("boxC",""+boxC.asLong());
  });
  add("network-safe-exits",()->{send(a(),"exit");send(b(),"exit");},()->done(a())&&done(b())&&outside(a())&&outside(b()),()->{});
  action("unrelated-view",true,"remote",()->check(b().containerMenu instanceof StoreMenu m&&m.hasPocket(bid),"Beta menu absent"));
  action("one-network-withdrawal",true,"multiwithdraw Beta 17",()->{bDeadline=PocketService.lease(b().getUUID()).deadline();watchBeta=true;beta();});
  add("target-unloaded-before-inspection",()->{},()->absent(ka)&&absent(kc)&&RepairService.idle(),()->{});
  quarantine("eligible-A",ka);newer("durable-successor-A",ka,2);quarantine("uncertain-C",kc);
  command("nonoperator-inspection",false,()->"anya_repair_operator inspect "+Long.toUnsignedString(ka,16),"Unknown or incomplete command",()->check(RepairService.blocked(ka),"Permission failure reopened"));
  command("nonoperator-confirmation",false,()->"anya_repair_operator resolve "+UUID.randomUUID()+" "+Long.toUnsignedString(ka,16)+" "+record(ka).token()+" "+UUID.randomUUID(),"Unknown or incomplete command",()->{server.getPlayerList().op(a().getGameProfile());server.getPlayerList().op(b().getGameProfile());});
  inspect("inspect-for-actor-binding",ka,ELIGIBLE);
  command("other-actor-cannot-consume-authority",true,()->resolve(last),UNKNOWN,()->unchanged(ka));
  command("consumed-receipt-cannot-replay",false,()->resolve(last),UNKNOWN,()->unchanged(ka));
  command("unknown-receipt",false,()->resolve(new Reply(last.world,ka,last.token,UUID.randomUUID().toString(),ELIGIBLE)),UNKNOWN,()->unchanged(ka));
  inspect("inspect-wrong-world",ka,ELIGIBLE);command("wrong-world",false,()->resolve(new Reply(UUID.randomUUID(),ka,last.token,last.receipt,ELIGIBLE)),EXACT,()->unchanged(ka));
  inspect("inspect-wrong-key",ka,ELIGIBLE);command("wrong-key",false,()->resolve(new Reply(last.world,kc,last.token,last.receipt,ELIGIBLE)),EXACT,()->unchanged(ka));
  inspect("inspect-wrong-token",ka,ELIGIBLE);command("wrong-token",false,()->resolve(new Reply(last.world,ka,UUID.randomUUID(),last.receipt,ELIGIBLE)),EXACT,()->unchanged(ka));
  inspect("inspect-natural-expiry",ka,ELIGIBLE);hold("elapsed-620-real-server-ticks",620,()->{unchanged(ka);beta();});command("expired-receipt",false,()->resolve(last),STALE,()->unchanged(ka));
  inspect("inspect-before-newer-submission",ka,ELIGIBLE);add("retain-old-network-receipt",()->saved=last,()->true,()->{});newer("newer-A-gen3",ka,3);add("fingerprint-new-A",()->frozen=evidence(ka),()->true,()->{});command("stale-submission-receipt",false,()->resolve(saved),STALE,()->unchanged(ka));
  inspect("ambiguous-old-C",kc,UNCERTAIN);command("ambiguous-confirmation-refused",false,()->resolve(last),UNCERTAIN,()->unchanged(kc));
  newer("durable-successor-C",kc,2);inspect("inspect-C-before-external-change",kc,ELIGIBLE);
  add("retained-primary-conflict",()->{saved=last;var tag=next(kc,3);var w=RepairService.worker();var storage=((WorkerAccess)(Object)w).anyaStorage();future=((WorkerAccess)(Object)w).anyaExecute(()->{try{((RegionAccess)(Object)storage).anyaWrite(new ChunkPos(kc),tag);storage.flush();return Either.left(null);}catch(Exception ex){return Either.right(ex);}});},()->future.isDone(),()->{future.join();frozen=evidence(kc);});
  command("changed-evidence-confirmation",false,()->resolve(saved),"RESOLUTION_EVIDENCE_CHANGED",()->unchanged(kc));
  inspect("explicit-conflict-C",kc,CONFLICT);command("conflicting-evidence-refused",false,()->resolve(last),CONFLICT,()->unchanged(kc));
  action("quarantined-room-entry-refused",false,block(EA),()->check(outside(a())&&PocketService.lease(a().getUUID())==null,"Quarantined room admitted client"));
  inspect("fresh-eligible-A",ka,ELIGIBLE);
  command("explicit-current-network-confirmation",false,()->resolve(last),SUCCESS,()->{check(!RepairService.blocked(ka)&&record(ka).state()==SnapshotJournal.State.APPLIED&&!RepairService.journal.resetPending(ka),"Current reset not durable");check(RepairService.blocked(kc),"Unrelated ambiguous key unlocked");oracle.setProperty("receipt",last.receipt);oracle.setProperty("world",last.world.toString());oracle.setProperty("token",last.token.toString());});
  command("successful-receipt-one-use",false,()->resolve(last),UNKNOWN,()->check(!RepairService.blocked(ka)&&RepairService.blocked(kc),"Replay changed resolved/quarantined keys"));
  add("close-last-independent-view",()->{watchBeta=false;send(b(),"close");},()->done(b()),()->{});
  add("seed-exact-cleanup",()->{},()->drained(),()->{server.saveEverything(true,true,true);rawAll();saveOracle();Trace.audit(server);Acceptance.marker("OPERATOR_NETWORK_CUT","network confirmation durably complete;conflicting key retained;no clean shutdown rescue;Runtime.halt77");Runtime.getRuntime().halt(77);});
 }
 private static void resume(){
  action("restart-independent-remote",true,"remote",()->{bDeadline=PocketService.lease(b().getUUID()).deadline();watchBeta=true;beta();});
  action("restart-client-sees-conserved-Beta",true,"multiview Beta 16",()->beta());
  command("old-process-receipt-unusable",false,()->"anya_repair_operator resolve "+oracle.getProperty("world")+" "+Long.toUnsignedString(ka,16)+" "+oracle.getProperty("token")+" "+oracle.getProperty("receipt"),UNKNOWN,()->check(!RepairService.blocked(ka)&&RepairService.blocked(kc),"Receipt survived incorrectly"));
  command("restart-completed-key-inspection",false,()->"anya_repair_operator inspect "+Long.toUnsignedString(ka,16),"conclusion=REFUSE_NO_PENDING_INTENT",()->{last=parse(replies.get(a().getUUID()));check(!RepairService.blocked(ka),"Completed reset lost");});
  command("redundant-completed-resolution-refused",false,()->resolve(last),"REFUSE_NO_PENDING_INTENT",()->{});
  inspect("restart-conflict-still-present",kc,CONFLICT);command("restart-cannot-choose-conflicting-copy",false,()->resolve(last),CONFLICT,()->unchanged(kc));
  add("close-final-independent-view",()->{watchBeta=false;send(b(),"close");},()->done(b()),()->{});
  hold("final-650-tick-drain",650,()->{check(drained(),"Final actual queues or leases retained");rawAll();check(inventory(b(),Items.EMERALD)==1&&inventory(a(),Items.DIAMOND)==0,"Player conservation changed");Trace.audit(server);Acceptance.marker("PASSED","Actual client operator command parser/permissions/actor/receipt/expiry/stale/evidence/confirmation;independent viewer;restart;retained conflict;no actual-client injected disk failure;final engine queues empty");running=false;server.halt(false);});
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
  if(!running||e.getServer()!=server)return;tick++;
  try{if(watchBeta)beta();if(step==null){step=steps.poll();check(step!=null,"No terminal operator stage");since=tick;step.start.run();}
   check(tick-since<=step.limit,"Operator network deadline at "+step.name);if(step.ready.get()){step.verify.run();Trace.note("OPERATOR_NETWORK_STAGE",null,"stage="+(++completed)+" tick="+tick+" "+step.name);step=null;}
  }catch(Throwable ex){running=false;Acceptance.marker("FAILED-operator-network",(step==null?"unknown":step.name)+": "+ex);AnyaStorage.LOG.error("OPERATOR_NETWORK_FAILED",ex);server.halt(false);}
 }
 private RepairClientProbe(){}
}

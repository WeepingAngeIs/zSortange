package dev.anya.storage.acceptance;
import com.mojang.datafixers.util.Either;
import dev.anya.storage.*;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.disconnect.InterruptAudit;
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

/** Missing mid-command disconnect/publication-failure cases only. Commands use genuine clients; fixture creates the initial eligible state. */
@EventBusSubscriber(modid=Acceptance.ID)
public final class RepairClientProbe {
 private interface Work{void run()throws Exception;}private interface Ready{boolean get()throws Exception;}private record Step(String name,Work start,Ready ready,Work verify,int limit){}
 private record Reply(UUID world,long key,UUID token,String receipt,String conclusion){}
 private static final ArrayDeque<Step> steps=new ArrayDeque<>();private static final Set<UUID> ready=new HashSet<>();
 private static final Map<UUID,Integer> expected=new HashMap<>(),acks=new HashMap<>(),netExpected=new HashMap<>();private static final Map<UUID,String> replies=new HashMap<>();
 private static final Properties oracle=new Properties();private static MinecraftServer server;private static Step step;private static boolean running,watchBeta;
 private static int tick,since,seq,completed;private static long ka,kc,bDeadline;private static UUID aid,bid;private static BlockPos boxA,boxC;
 private static final String CASE=System.getProperty("anya.operatorInterruption","");
 private static UUID alphaId;private static boolean fault(){return CASE.endsWith("-fail");}
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
  for(UUID id:List.of(aid,bid)){var r=room(id);oracle.setProperty("room."+id,r.cell()+"/"+r.owner());int i=0;for(var p:PocketService.chestPositions(r).getClass()!=null?PocketService.chestPositions(r):List.<BlockPos>of()){var c=(StorageEntity)level().getBlockEntity(p);oracle.setProperty("chest."+id+"."+(i++),c.storageIdentity().toString());}}
 }
 private static void rawAll()throws Exception{
  check(PocketService.ledger(server).pockets().size()==2,"Room count changed");int total=0;
  for(UUID id:List.of(aid,bid)){var r=room(id);check(Objects.equals(oracle.getProperty("room."+id),r.cell()+"/"+r.owner()),"Room/cell/owner changed");int ci=0;
   for(var p:PocketService.chestPositions(r)){var tag=ReadOnlyRegion.inspect(region(),new ChunkPos(p).toLong()).tag();var bes=tag.getList("block_entities",10);CompoundTag found=null;
    for(int i=0;i<bes.size();i++){var be=bes.getCompound(i);if(be.getInt("x")==p.getX()&&be.getInt("y")==p.getY()&&be.getInt("z")==p.getZ())found=be;}
    check(found!=null&&found.getUUID("PocketId").equals(id)&&found.getUUID("Owner").equals(r.owner())&&found.getUUID("StorageIdentity").toString().equals(oracle.getProperty("chest."+id+"."+ci)),"Physical chest identity mismatch");
    var items=found.getList("Items",10);ItemStack wanted=id.equals(bid)&&ci==0?betaSample(16):p.equals(boxA)?sample(false,2):p.equals(boxC)?sample(true,0):ItemStack.EMPTY;
    if(wanted.isEmpty())check(items.isEmpty(),"Unexpected item in other chest");else{check(items.size()==1&&Transactions.same(ItemStack.parseOptional(server.registryAccess(),items.getCompound(0)),wanted),"Physical sample/component mismatch");total+=wanted.getCount();}ci++;
   }
  }check(total==36,"Stored item count not 36");
  check(RepairService.blocked(ka)==fault(),"Runtime quarantine does not match durable result");
  if(fault())check(RepairService.globalBad,"Publication failure not globally protected");
  else check(record(ka)!=null&&record(ka).state()==SnapshotJournal.State.APPLIED&&!RepairService.journal.resetPending(ka),"Committed reset not retained");
  Trace.note("INTERRUPT_RAW_VERIFIED",null,"case="+CASE+" primary read not overlay;13 A gen2 diamonds,7 C gen0 gold,16 Beta emeralds;2 rooms;8 chest identities;blocked="+fault());
 }
 private record Disk(UUID world,UUID token,int state,boolean locked,UUID intent,boolean pending){}
 private static DataInputStream checked(Path path,int max)throws Exception{
  byte[] f=JournalDisk.bytes(path,max);check(f.length>32,"Truncated fixture read");int n=f.length-32;
  check(JournalDisk.hash(Arrays.copyOf(f,n)).equals(HexFormat.of().formatHex(Arrays.copyOfRange(f,n,f.length))),"Stored checksum mismatch");return new DataInputStream(new ByteArrayInputStream(f,0,n));
 }
 private static UUID uuid(DataInputStream d)throws IOException{return new UUID(d.readLong(),d.readLong());}
 private static Disk disk()throws Exception{
  Path dir=root().resolve("anya_repair_experiment");String h=Long.toUnsignedString(ka,16);UUID world,token,mark;int state;boolean locked,pending;
  try(var d=checked(dir.resolve(h+".wal"),SnapshotJournal.MAX_BYTES+128)){
   check(d.readInt()==0x41525031&&d.readInt()==1,"WAL format");world=uuid(d);token=uuid(d);check(d.readLong()==ka,"WAL key");state=d.readUnsignedByte();locked=d.readBoolean();int n=d.readInt();check(n==d.available()&&n<=SnapshotJournal.MAX_BYTES,"WAL length");
   check(RepairService.decode(d.readNBytes(n)).equals(ReadOnlyRegion.inspect(region(),ka).tag()),"Retained snapshot disagrees with primary");
  }
  try(var d=checked(dir.resolve(h+".reset"),81)){
   check(d.readInt()==0x41525331&&d.readInt()==1&&uuid(d).equals(world),"Intent binding");mark=uuid(d);check(d.readLong()==ka,"Intent key");pending=d.readBoolean();check(d.available()==0,"Intent trailing bytes");
  }return new Disk(world,token,state,locked,mark,pending);
 }
 private static void rememberEvidence()throws Exception{
  Path dir=root().resolve("anya_repair_experiment");String h=Long.toUnsignedString(ka,16);
  try(var files=Files.list(dir)){for(var p:files.toList()){String n=p.getFileName().toString();if(n.equals(h+".wal")||n.equals(h+".reset")||n.contains(".tmp-"))oracle.setProperty("evidence."+n,JournalDisk.hash(Files.readAllBytes(p)));}}
  if(fault()){check(Files.exists(dir.resolve("journal-fault")),"Persistent fault marker missing");long partial=oracle.stringPropertyNames().stream().filter(k->k.startsWith("evidence.")&&k.contains(".tmp-")).count();check(partial==1,"Expected one retained partial reset publication");}
 }
 private static void preservedEvidence()throws Exception{
  Path dir=root().resolve("anya_repair_experiment");for(var name:oracle.stringPropertyNames())if(name.startsWith("evidence.")){var p=dir.resolve(name.substring(9));check(Files.isRegularFile(p)&&oracle.getProperty(name).equals(JournalDisk.hash(Files.readAllBytes(p))),"Required evidence changed/missing: "+name);}
 }
 private static void finalState()throws Exception{
  var d=disk();check(d.token.toString().equals(oracle.getProperty("token")),"Current exact token changed");check(d.world.toString().equals(oracle.getProperty("world")),"World binding changed");
  if(fault()){check(d.pending&&RepairService.blocked(ka),"Failed confirmation reopened");if(CASE.equals("completion-fail"))check(d.state==0&&!d.locked,"Expected intermediate projection retained");else check(d.state==2&&d.locked,"Failure lost repaired snapshot protection");}
  else check(d.state==0&&!d.locked&&!d.pending&&!RepairService.blocked(ka),"Successful accepted operation not committed");
  Trace.note("INTERRUPT_DURABLE_STATE",null,"case="+CASE+" primary=2 journal=2 state="+d.state+" recordLocked="+d.locked+" intentPending="+d.pending+" serviceBlocked="+RepairService.blocked(ka));
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
  }else{try(var in=Files.newInputStream(Path.of("operator-network.properties"))){oracle.load(in);}aid=UUID.fromString(oracle.getProperty("aid"));bid=UUID.fromString(oracle.getProperty("bid"));boxA=BlockPos.of(Long.parseLong(oracle.getProperty("boxA")));boxC=BlockPos.of(Long.parseLong(oracle.getProperty("boxC")));ka=new ChunkPos(boxA).toLong();kc=new ChunkPos(boxC).toLong();check(level().getChunkSource().getLoadedChunksCount()==0,"Restart inspection preloaded pockets");preservedEvidence();finalState();rawAll();}
  steps.add(new Step("two-ready-network-clients",()->{},()->a()!=null&&b()!=null&&ready.contains(a().getUUID())&&ready.contains(b().getUUID()),()->{
   server.overworld().getChunk(0,0);server.overworld().getChunk(2,0);home(a(),EA);home(b(),EB);
   if(leg.equals("seed")){server.getPlayerList().op(a().getGameProfile());server.getPlayerList().deop(b().getGameProfile());alphaId=a().getUUID();
    var ha=(StorageEntity)server.overworld().getBlockEntity(HA);var hb=(StorageEntity)server.overworld().getBlockEntity(HB);var ea=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(EA);var eb=(PocketContent.EntranceEntity)server.overworld().getBlockEntity(EB);
    ha.owner=a().getUUID();ea.owner=a().getUUID();hb.owner=b().getUUID();eb.owner=b().getUUID();ha.setChanged();hb.setChanged();ea.setChanged();eb.setChanged();
    a().getInventory().clearContent();b().getInventory().clearContent();var ra=remote(HA);var rb=remote(HB);PocketService.link(a(),ea,ra);PocketService.link(b(),eb,rb);aid=ea.pocket;bid=eb.pocket;a().getInventory().setItem(0,ra);b().getInventory().setItem(0,rb);a().inventoryMenu.broadcastChanges();b().inventoryMenu.broadcastChanges();
    boxA=PocketService.chestPositions(room(aid)).getFirst();boxC=PocketService.chestPositions(room(aid)).get(3);ka=new ChunkPos(boxA).toLong();kc=new ChunkPos(boxC).toLong();check(ka!=kc,"Targets need distinct allocated columns");seed();
   }else{server.getPlayerList().op(a().getGameProfile());check(inventory(b(),Items.EMERALD)==1&&inventory(a(),Items.DIAMOND)==0,"Restart player item conservation");resume();}
  },3600));
  running=true;Acceptance.marker("server-ready","operator network leg="+leg+" case="+CASE+" separate 3600-tick join ceiling;900-tick gameplay steps");
 }
 private static void launchCommand(String expectedText){
  int n=++seq;netExpected.put(a().getUUID(),n);replies.remove(a().getUUID());
  PacketDistributor.sendToPlayer(a(),new OperatorNet.Message(n,"command",expectedText+"\n"+resolve(last)));
 }
 private static void seed(){
  add("actual-client-visits",()->{send(a(),block(EA));send(b(),block(EB));},()->done(a())&&done(b())&&inside(a())&&inside(b()),()->{
   identities();chest(aid,0).setItem(0,sample(false,0));chest(aid,3).setItem(0,sample(true,0));chest(bid,0).setItem(0,betaSample(17));server.saveEverything(true,true,true);
   oracle.setProperty("aid",aid.toString());oracle.setProperty("bid",bid.toString());oracle.setProperty("boxA",""+boxA.asLong());oracle.setProperty("boxC",""+boxC.asLong());
  });
  add("safe-exits",()->{send(a(),"exit");send(b(),"exit");},()->done(a())&&done(b())&&outside(a())&&outside(b()),()->{});
  action("independent-view",true,"remote",()->check(b().containerMenu instanceof StoreMenu m&&m.hasPocket(bid),"Beta view missing"));
  action("one-conserved-client-withdrawal",true,"multiwithdraw Beta 17",()->{bDeadline=PocketService.lease(b().getUUID()).deadline();watchBeta=true;beta();});
  add("target-unloaded",()->{},()->absent(ka)&&RepairService.idle(),()->{});
  quarantine("protected-target",ka);newer("healthy-retained-successor",ka,2);
  inspect("network-receipt-before-interruption",ka,ELIGIBLE);
  add("send-one-confirmation",()->{
   oracle.setProperty("world",last.world.toString());oracle.setProperty("token",last.token.toString());oracle.setProperty("receipt",last.receipt);saveOracle();
   InterruptAudit.arm(ka,last.receipt,CASE);RepairService.journal.cuts(InterruptAudit::publication);
   launchCommand(fault()?"Injected operator confirmation "+CASE:SUCCESS);
  },()->InterruptAudit.entered,()->{
   check(InterruptAudit.consumed,"Gate reached without receipt consumption");beta();var d=disk();
   if(CASE.equals("receipt-disconnect"))check(d.state==2&&d.locked&&d.pending&&!d.intent.equals(d.token),"Receipt boundary published reset early");
   if(CASE.equals("intent-disconnect"))check(d.state==2&&d.locked&&d.pending&&d.intent.equals(d.token),"Current intent not durable at gate");
   if(CASE.equals("projection-disconnect"))check(d.state==0&&!d.locked&&d.pending,"Projection boundary missing uncertainty");
   if(CASE.equals("completion-disconnect")||CASE.equals("ack-disconnect"))check(d.state==0&&!d.locked&&!d.pending,"Completion not durable at lost reply boundary");
   Trace.note("NETWORK_BOUNDARY_STATE",null,"case="+CASE+" state="+d.state+" locked="+d.locked+" pending="+d.pending+" actual client confirmation still not acknowledged");
   if(fault())InterruptAudit.release();else{ready.remove(alphaId);Acceptance.marker("KILL_ALPHA","One actual client process killed at "+CASE+"; no world-thread pause");}
  });
  if(fault()){
   add("received-real-failure-reply",()->{},()->InterruptAudit.finished&&replies.containsKey(alphaId),()->{
    check(InterruptAudit.error!=null&&InterruptAudit.faults.get()==1&&replies.get(alphaId).contains("Injected operator confirmation "+CASE),"Expected actual network failure response absent");
    check(RepairService.globalBad&&RepairService.blocked(ka),"Publication error did not retain protection");beta();ready.remove(alphaId);Acceptance.marker("KILL_ALPHA","Disconnect after actual confirmation failure response; persistence evidence retained");
   });
  }
  add("actual-socket-disconnected",()->{},()->a()==null,()->{
   check(fault()||!replies.containsKey(alphaId),"Unexpected successful reply reached disconnected client");InterruptAudit.release();
   Trace.note("ACTUAL_CLIENT_DISCONNECTED",alphaId,"case="+CASE+" unrelated view retained;release worker only after server observes logout");
  });
  add("accepted-operation-settles",()->{},()->InterruptAudit.finished&&RepairService.idle(),()->{
   check(InterruptAudit.commands.get()==1,"Mutating confirmation replayed");check((InterruptAudit.error!=null)==fault(),"Unexpected async outcome");
   check(RepairService.resets.get()==(fault()?0:1),"Unexpected durable reset count");check(InterruptAudit.withheld.get()==(CASE.equals("ack-disconnect")?1:0),"Ack suppression mismatch");
   RepairService.journal.cuts(p->{});finalState();beta();rememberEvidence();saveOracle();Acceptance.marker("RESTART_ALPHA","Fresh client must inspect actual durable state; never repeat confirmation");
  });
  steps.add(new Step("fresh-alpha-network-ready",()->{},()->a()!=null&&ready.contains(alphaId),()->{check(outside(a()),"Reconnect stranded Alpha");server.getPlayerList().op(a().getGameProfile());},3600));
  command("reconnect-inspect-no-mutation-replay",false,()->"anya_repair_operator inspect "+Long.toUnsignedString(ka,16),fault()?"INSPECTION_REFUSED":"conclusion=REFUSE_NO_PENDING_INTENT",()->{preservedEvidence();finalState();beta();});
  action("unrelated-client-can-still-view",true,"multiview Beta 16",()->beta());
  add("close-independent-view",()->{watchBeta=false;send(b(),"close");},()->done(b()),()->{});
  add("save-verified-terminal-state",()->{},RepairClientProbe::drained,()->{
   server.saveEverything(true,true,true);rawAll();finalState();preservedEvidence();saveOracle();Trace.audit(server);
   check(InterruptAudit.commands.get()==1,"Blind retry occurred");Acceptance.marker("INTERRUPT_NETWORK_CUT","case="+CASE+" accepted command attempts=1;lost reply is not rollback;explicit player save and raw read-back complete;Runtime.halt77");Runtime.getRuntime().halt(77);
  });
 }
 private static void resume(){
  command("restart-inspects-durable-state",false,()->"anya_repair_operator inspect "+Long.toUnsignedString(ka,16),fault()?"INSPECTION_REFUSED":"conclusion=REFUSE_NO_PENDING_INTENT",()->{preservedEvidence();finalState();});
  action("unrelated-reopen-after-restart",true,"remote",()->{
   if(fault()){check(!(b().containerMenu instanceof StoreMenu)&&PocketService.lease(b().getUUID())==null&&RepairService.globalBad,"Global publication fault must not admit a new view");}
   else{bDeadline=PocketService.lease(b().getUUID()).deadline();watchBeta=true;beta();}
  });
  if(!fault())action("restart-network-sees-conserved-items",true,"multiview Beta 16",()->beta());
  add("close-final-view",()->{watchBeta=false;send(b(),"close");},()->done(b()),()->{});
  hold("final-cleanup",650,()->{
   check(drained(),"Required runtime work not drained");rawAll();preservedEvidence();finalState();check(inventory(b(),Items.EMERALD)==1&&inventory(a(),Items.DIAMOND)==0,"Player inventory changed");Trace.audit(server);
   Acceptance.marker("PASSED","case="+CASE+" genuine command and received receipt;mid-command disconnect/lost ack or injected confirmation failure;reconnect inspection not mutation replay;independent primary read-back and preserved snapshots/markers;2 rooms8 chests;final runtime queues empty");running=false;server.halt(false);
  });
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
  if(!running||e.getServer()!=server)return;tick++;
  try{if(watchBeta)beta();if(step==null){step=steps.poll();check(step!=null,"No terminal operator stage");since=tick;step.start.run();}
   check(tick-since<=step.limit,"Operator interruption "+CASE+" deadline at "+step.name);if(step.ready.get()){step.verify.run();Trace.note("OPERATOR_NETWORK_STAGE",null,"stage="+(++completed)+" tick="+tick+" "+step.name);step=null;}
  }catch(Throwable ex){InterruptAudit.release();running=false;Acceptance.marker("FAILED-operator-network",(step==null?"unknown":step.name)+": "+ex);AnyaStorage.LOG.error("OPERATOR_NETWORK_FAILED",ex);server.halt(false);}
 }
 private RepairClientProbe(){}
}

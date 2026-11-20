package dev.anya.storage.acceptance;
import dev.anya.storage.*;import dev.anya.storage.pocket.*;import dev.anya.storage.durable.Trace;import dev.anya.storage.minimal.OwnershipView;import dev.anya.storage.repair.*;import dev.anya.storage.repair.validation.ValidationAudit;
import com.mojang.brigadier.arguments.*;
import net.minecraft.commands.Commands;import net.minecraft.core.*;import net.minecraft.core.component.DataComponents;import net.minecraft.network.chat.Component;import net.minecraft.server.*;import net.minecraft.server.level.*;import net.minecraft.world.item.*;import net.minecraft.world.level.*;import net.minecraft.world.level.block.Blocks;import net.minecraft.world.level.chunk.*;import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;import net.neoforged.fml.common.EventBusSubscriber;import net.neoforged.neoforge.event.server.*;import net.neoforged.neoforge.event.tick.ServerTickEvent;import net.neoforged.neoforge.event.RegisterCommandsEvent;import net.neoforged.neoforge.network.PacketDistributor;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
/** Test-only operator command and actual TCP client scenario. No production repair UI is installed. */
@EventBusSubscriber(modid=Acceptance.ID)
public final class RepairClientProbe {
 private interface Work{void run()throws Exception;}private interface Ready{boolean get()throws Exception;}private record Step(String name,Work start,Ready ready,Work verify){}
 private static final ArrayDeque<Step> steps=new ArrayDeque<>();private static final Map<UUID,Integer> expected=new HashMap<>(),acks=new HashMap<>();private static final Properties ids=new Properties();
 private static MinecraftServer s;private static Step step;private static boolean running;private static int tick,since,seq,completed;private static UUID aid,bid;private static long ka,kb,bDeadline,oldFresh,oldStale,oldGuard;private static int commands;
 private static final BlockPos HA=new BlockPos(1,81,1),EA=new BlockPos(3,81,1),HB=new BlockPos(35,81,1),EB=new BlockPos(37,81,1),SPAWN=new BlockPos(200,81,200);
 private static ItemStack ra,rb;private static CompletableFuture<Void> reset;private static List<CompletableFuture<Void>> writes=new ArrayList<>();private static String leg;
 public static boolean active(){return Boolean.getBoolean("anya.repairNetwork");}
 private static void check(boolean b,String m){Acceptance.require(b,m);}private static ServerPlayer a(){return s.getPlayerList().getPlayerByName("AnyaQAAlpha");}private static ServerPlayer b(){return s.getPlayerList().getPlayerByName("AnyaQABeta");}
 public static void ack(ServerPlayer p,Wire.Signal msg){if(msg.action().equals("done")&&Objects.equals(expected.get(p.getUUID()),msg.sequence()))acks.put(p.getUUID(),msg.sequence());}
 private static void send(ServerPlayer p,String text){int n=++seq;expected.put(p.getUUID(),n);acks.remove(p.getUUID());PacketDistributor.sendToPlayer(p,new Wire.Signal(n,text));}
 private static boolean done(ServerPlayer p){return p!=null&&expected.containsKey(p.getUUID())&&Objects.equals(expected.get(p.getUUID()),acks.get(p.getUUID()));}
 private static boolean both(){return done(a())&&done(b());}private static void add(String n,Work w,Ready r,Work v){steps.add(new Step(n,w,r,v));}private static void hold(String n,int ticks,Work v){add(n,()->{},()->tick-since>=ticks,v);}private static void aa(String n,String action,Work v){add(n,()->send(a(),action),()->done(a()),v);}private static void bb(String n,String action,Work v){add(n,()->send(b(),action),()->done(b()),v);}
 private static PocketLedger.Pocket room(UUID id)throws Exception{return PocketService.ledger(s).get(id);}private static ServerLevel level(){return PocketService.dimension(s);}
 private static boolean inside(ServerPlayer p){return p!=null&&PocketService.inPocket(p.level());}private static boolean outside(ServerPlayer p){return p!=null&&!inside(p)&&!PocketService.recovering(p.getUUID());}
 private static void home(ServerPlayer p,BlockPos en){p.teleportTo(s.overworld(),en.getX()+.5,81,en.getZ()+2.5,180,0);}
 private static StorageEntity chest(UUID id,int index)throws Exception{var pos=PocketService.chestPositions(room(id)).get(index);check(level().hasChunkAt(pos),"Observation cannot force load");return (StorageEntity)level().getBlockEntity(pos);}
 private static ItemStack item(String id,int v,int n){var x=new ItemStack(id.equals("A")?Items.DIAMOND:id.equals("B")?Items.EMERALD:Items.GOLD_INGOT,n);x.set(DataComponents.CUSTOM_NAME,Component.literal("Repair client "+id+" v"+v));return x;}
 private static ItemStack remote(BlockPos hub){var x=new ItemStack(Content.REMOTE.get());var t=BagInventory.data(x);t.putString("HubDimension","minecraft:overworld");t.putLong("HubPos",hub.asLong());BagInventory.data(x,t);return x;}
 private static String block(BlockPos p){return "block "+p.getX()+" "+p.getY()+" "+p.getZ();}
 private static int inv(ServerPlayer p,Item item){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))n+=p.getInventory().getItem(i).getCount();return n;}
 private static void beta()throws Exception{check(b().containerMenu instanceof StoreMenu m&&m.hasPocket(bid),"Independent view lost");check(PocketService.lease(b().getUUID()).deadline()==bDeadline,"Independent deadline reset");check(Transactions.same(chest(bid,0).getItem(0),item("B",0,16))&&inv(b(),Items.EMERALD)==1,"Beta conservation changed");}
 private static void ordinary(int index)throws Exception{var c=chest(aid,index);long key=new ChunkPos(c.getBlockPos()).toLong();ValidationAudit.receipts.remove(key);var m=ChunkMap.class.getDeclaredMethod("save",ChunkAccess.class);m.setAccessible(true);check((boolean)m.invoke(level().getChunkSource().chunkMap,level().getChunkAt(c.getBlockPos())),"Ordinary save not submitted");writes.add(Objects.requireNonNull(ValidationAudit.receipts.get(key),"Missing actual receipt"));}
 private static boolean absent(long key){var p=new ChunkPos(key);return level().getChunkSource().getChunkNow(p.x,p.z)==null;}
 private static boolean drained(){var v=(OwnershipView)(Object)level().getChunkSource().chunkMap;return PocketService.ownedTicketRequests()==0&&level().getChunkSource().getLoadedChunksCount()==0&&v.anyaPendingCount()==0&&v.anyaCallbackCount()==0;}
 private static void op(ServerPlayer p){s.getPlayerList().op(p.getGameProfile());}
 @SubscribeEvent public static void register(RegisterCommandsEvent e){
  if(!active())return;
  var base=Commands.literal("repairvalidation").requires(x->x.hasPermission(4));
  for(String verb:List.of("repair","reset"))base.then(Commands.literal(verb).then(Commands.argument("key",LongArgumentType.longArg()).then(Commands.argument("token",StringArgumentType.word()).executes(ctx->{
   var p=ctx.getSource().getPlayerOrException();long key=LongArgumentType.getLong(ctx,"key");UUID token=UUID.fromString(StringArgumentType.getString(ctx,"token"));
   try{check(p==a()&&(key==ka||key==kb),"Test command outside declared owner/keys");if(verb.equals("repair"))RepairService.schedule(key,token);else reset=RepairService.reset(key,token);commands++;Trace.note("REAL_CLIENT_REPAIR_COMMAND",p.getUUID(),verb+" key="+key+" token="+token);return 1;}
   catch(Exception ex){Trace.note("REAL_CLIENT_REPAIR_COMMAND_REFUSED",p.getUUID(),verb+" "+ex);ctx.getSource().sendFailure(Component.literal(ex.toString()));return 0;}
  }))));e.getDispatcher().register(base);
 }
 @SubscribeEvent public static void started(ServerStartedEvent e)throws Exception{
  if(!active())return;s=e.getServer();leg=System.getProperty("anya.repairNetworkLeg","seed");check(Files.isRegularFile(Path.of(".anya-remote-fixture")),"Disposable marker missing");Acceptance.verifyJar("repair-network-"+leg);
  var rules=ExpansionRules.get();rules.pocketStorage=true;rules.pocketEditing=false;rules.activePocketLimit=2;rules.pocketLimit=2;rules.pocketSessionTicks=12000;
  s.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,s);s.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,s);
  if(leg.equals("seed")){
   check(!Files.exists(s.getWorldPath(LevelResource.ROOT).resolve("anya_storage/pockets.v1.dat")),"Fresh seed required");
   for(BlockPos center:List.of(EA,EB,SPAWN))for(int x=-7;x<=7;x++)for(int z=-7;z<=7;z++)s.overworld().setBlockAndUpdate(new BlockPos(center.getX()+x,80,center.getZ()+z),Blocks.STONE.defaultBlockState());
   s.overworld().setDefaultSpawnPos(EB.offset(0,0,4),0);
   for(BlockPos p:List.of(HA,HB))s.overworld().setBlockAndUpdate(p,Content.HUB.get().defaultBlockState());for(BlockPos p:List.of(EA,EB))s.overworld().setBlockAndUpdate(p,PocketContent.ENTRANCE.get().defaultBlockState());seed();
  }else{
   try(var in=Files.newInputStream(Path.of("repair-network.properties"))){ids.load(in);}aid=UUID.fromString(ids.getProperty("A"));bid=UUID.fromString(ids.getProperty("B"));ka=Long.parseLong(ids.getProperty("keyA"));kb=Long.parseLong(ids.getProperty("keyC"));
   check(absent(ka)&&absent(kb),"Repair targets were loaded on restart");resume();
  }
  running=true;Acceptance.marker("server-ready","actual-client repair leg="+leg);
 }
 private static void seed(){
  add("two-real-clients",()->{},()->a()!=null&&b()!=null,()->{
   s.overworld().getChunk(0,0);s.overworld().getChunk(2,0);op(a());
   var ha=(StorageEntity)s.overworld().getBlockEntity(HA);var hb=(StorageEntity)s.overworld().getBlockEntity(HB);var ea=(PocketContent.EntranceEntity)s.overworld().getBlockEntity(EA);var eb=(PocketContent.EntranceEntity)s.overworld().getBlockEntity(EB);
   ha.owner=a().getUUID();ea.owner=a().getUUID();hb.owner=b().getUUID();eb.owner=b().getUUID();ra=remote(HA);rb=remote(HB);home(a(),EA);home(b(),EB);a().getInventory().clearContent();b().getInventory().clearContent();PocketService.link(a(),ea,ra);PocketService.link(b(),eb,rb);aid=ea.pocket;bid=eb.pocket;
   a().getInventory().setItem(0,ra);b().getInventory().setItem(0,rb);a().getInventory().setItem(9,new ItemStack(Items.COAL,5));a().inventoryMenu.broadcastChanges();b().inventoryMenu.broadcastChanges();
  });
  add("real-cold-entry",()->{send(a(),block(EA));send(b(),block(EB));},()->both()&&inside(a())&&inside(b()),()->{
   chest(aid,0).setItem(0,item("A",0,13));chest(aid,3).setItem(0,item("C",0,7));chest(bid,0).setItem(0,item("B",0,17));s.saveEverything(true,true,true);
   ka=new ChunkPos(chest(aid,0).getBlockPos()).toLong();kb=new ChunkPos(chest(aid,3).getBlockPos()).toLong();check(ka!=kb,"Two actual chunk keys required");
   ids.setProperty("A",aid.toString());ids.setProperty("B",bid.toString());ids.setProperty("cellA",""+room(aid).cell());ids.setProperty("cellB",""+room(bid).cell());ids.setProperty("keyA",""+ka);ids.setProperty("keyC",""+kb);int n=0;for(UUID id:List.of(aid,bid))for(var pos:PocketService.chestPositions(room(id)))ids.setProperty("chest."+(n++),((StorageEntity)level().getBlockEntity(pos)).storageIdentity().toString());
   try(var out=Files.newOutputStream(Path.of("repair-network.properties"))){ids.store(out,"Disposable exact client test IDs");}
  });
  bb("beta-return","exit",()->check(outside(b()),"Beta return failed"));
  bb("unrelated-remote","remote",()->check(b().containerMenu instanceof StoreMenu,"Beta remote failed"));
  bb("unrelated-one-withdrawal","repairtry 256",()->{check(inv(b(),Items.EMERALD)==1&&chest(bid,0).getItem(0).getCount()==16,"Single Beta transfer failed");bDeadline=PocketService.lease(b().getUUID()).deadline();});
  add("open-real-physical-chest",()->{var p=chest(aid,0).getBlockPos();a().teleportTo(level(),p.getX()+.5,65,p.getZ()+2.5,180,15);send(a(),block(p));},()->done(a()),()->check(a().containerMenu instanceof StoreMenu m&&m.capacity()==54,"Actual physical menu missing"));
  aa("capture-pre-failure-intent","multicapture",()->{oldStale=ValidationAudit.staleActions;});
  add("two-concurrent-failing-snapshots",()->{ValidationAudit.FAIL.add(ka);ValidationAudit.FAIL.add(kb);chest(aid,0).setItem(0,item("A",1,13));chest(aid,3).setItem(0,item("C",1,7));ordinary(0);ordinary(3);},()->writes.size()==2&&writes.stream().allMatch(CompletableFuture::isDone),()->{
   check(writes.stream().allMatch(CompletableFuture::isCompletedExceptionally)&&RepairService.blocked(ka)&&RepairService.blocked(kb),"Actual failure/quarantine missing");beta();
  });
  aa("stale-client-packet-denied","stale",()->{check(ValidationAudit.staleActions>oldStale,"Stale packet did not reach server");check(inv(a(),Items.DIAMOND)==0&&chest(aid,0).getItem(0).getCount()==13,"Stale packet moved item");oldFresh=ValidationAudit.freshActions;oldGuard=ValidationAudit.blockedPlans;});
  aa("fresh-withdrawal-denied","repairtry 256",()->{check(ValidationAudit.freshActions>oldFresh&&ValidationAudit.blockedPlans>oldGuard,"Fresh network action never reached quarantine guard");check(inv(a(),Items.DIAMOND)==0&&chest(aid,0).getItem(0).getCount()==13,"Quarantine lost diamond");oldFresh=ValidationAudit.freshActions;oldGuard=ValidationAudit.blockedPlans;});
  aa("fresh-deposit-denied","repairtry 54",()->{check(ValidationAudit.freshActions>oldFresh&&ValidationAudit.blockedPlans>oldGuard,"Fresh deposit not checked by quarantine");check(inv(a(),Items.COAL)==5&&chest(aid,0).getItem(1).isEmpty(),"Denied deposit changed inventory");beta();});
  add("arm-unsafe-return",()->{
   var back=PocketService.ledger(s).returning(a().getUUID());var center=BlockPos.containing(back.x(),back.y(),back.z());
   for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)for(int y=81;y<=90;y++){var p=new BlockPos(center.getX()+x,y,center.getZ()+z);if(!p.equals(HA)&&!p.equals(EA))s.overworld().setBlockAndUpdate(p,Blocks.OBSIDIAN.defaultBlockState());}
   for(int x=-7;x<=7;x++)for(int z=-7;z<=7;z++)s.overworld().setBlockAndUpdate(new BlockPos(200+x,80,200+z),Blocks.MAGMA_BLOCK.defaultBlockState());s.overworld().setDefaultSpawnPos(SPAWN,0);ValidationAudit.recovery.clear();send(a(),"exit");
  },()->done(a()),()->check(inside(a())&&PocketService.recovering(a().getUUID()),"Unsafe return did not remain queued"));
  hold("quarantine-return-retries",220,()->{beta();check(ValidationAudit.recovery.get(a().getUUID()).size()>=3&&RepairService.journal.blocked(ka)&&RepairService.journal.blocked(kb),"Unresolved failure/retry not simultaneous");var times=ValidationAudit.recovery.get(a().getUUID());for(int i=1;i<times.size();i++)check(times.get(i)-times.get(i-1)==100,"Unbounded recovery retries");s.overworld().setBlockAndUpdate(new BlockPos(200,80,200),Blocks.STONE.defaultBlockState());});
  add("return-with-two-pending-snapshots",()->{},()->outside(a()),()->{beta();check(RepairService.blocked(ka)&&RepairService.blocked(kb),"Return cleared write quarantine");for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)for(int y=81;y<=90;y++){var p=new BlockPos(EA.getX()+x,y,EA.getZ()+2+z);if(!p.equals(HA)&&!p.equals(EA))s.overworld().setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());}});
  hold("unload-affected-keys",650,()->{beta();check(absent(ka)&&absent(kb),"Affected chunks did not unload");});
  add("deny-reopen-quarantined-room",()->{home(a(),EA);send(a(),"remote");},()->done(a()),()->check(!(a().containerMenu instanceof StoreMenu)&&PocketService.lease(a().getUUID())==null,"Quarantined reopen acquired access"));
  add("explicit-operator-repair-still-failing",()->{var r=RepairService.journal.get(ka);send(a(),"command repairvalidation repair "+ka+" "+r.token());},()->done(a()),()->check(commands==1,"Repair was not requested by actual client command"));
  hold("bounded-operator-retry",180,()->{beta();check(RepairService.idle()&&RepairService.journal.blocked(ka)&&ValidationAudit.failures.get(ka).get()>=4,"Repair retries did not stop with retained copy");});
  add("reject-premature-operator-reset",()->send(a(),"command repairvalidation reset "+ka+" "+RepairService.journal.get(ka).token()),()->done(a()),()->{check(commands==1&&RepairService.blocked(ka),"Pending reset was accepted");beta();});
  add("stop-with-pending-latest-and-viewer",()->{s.saveEverything(true,true,true);Acceptance.marker("NETWORK_PENDING_STOP","two durable latest snapshots pending; Beta live view; no successful repair or reset");running=false;s.halt(false);},()->false,()->{});
 }
 private void unused(){}
 private static void resume(){
  add("real-clients-reconnected",()->{},()->a()!=null&&b()!=null&&outside(a())&&outside(b()),()->{op(a());check(room(aid).cell()==Integer.parseInt(ids.getProperty("cellA"))&&room(bid).cell()==Integer.parseInt(ids.getProperty("cellB")),"Room cells changed");check(room(aid).owner().equals(a().getUUID())&&room(bid).owner().equals(b().getUUID()),"Owners changed");check(inv(a(),Items.DIAMOND)==0&&inv(a(),Items.COAL)==5&&inv(b(),Items.EMERALD)==1,"Player files changed");home(a(),EA);home(b(),EB);});
  bb("independent-view-after-restart","remote",()->{check(b().containerMenu instanceof StoreMenu,"Beta view after restart failed");bDeadline=PocketService.lease(b().getUUID()).deadline();});
  add("stale-token-command-refused",()->send(a(),"command repairvalidation reset "+ka+" "+UUID.randomUUID()),()->done(a()),()->check(commands==0,"Stale token accepted"));
  if(leg.equals("repair-cut")){
   add("client-request-repair-before-cut",()->{RepairService.faultKey=ka;RepairService.cut="repair-after-region";send(a(),"command repairvalidation repair "+ka+" "+RepairService.journal.get(ka).token());},()->false,()->{});return;
  }
  if(!leg.equals("final"))add("explicit-repair-A",()->{var r=RepairService.journal.get(ka);if(r.state()!=SnapshotJournal.State.REPAIRED)send(a(),"command repairvalidation repair "+ka+" "+r.token());},()->RepairService.idle()&&RepairService.journal.get(ka).state()==SnapshotJournal.State.REPAIRED,()->check(RepairService.blocked(ka),"Repair auto-unlocked A"));
  if(!leg.equals("final"))add("explicit-repair-C",()->{var r=RepairService.journal.get(kb);if(r.state()!=SnapshotJournal.State.REPAIRED)send(a(),"command repairvalidation repair "+kb+" "+r.token());},()->RepairService.idle()&&RepairService.journal.get(kb).state()==SnapshotJournal.State.REPAIRED,()->check(RepairService.blocked(kb),"Repair auto-unlocked C"));
  if(leg.equals("reset-cut")){
   add("client-request-reset-before-cut",()->{RepairService.journal.cuts(p->{if(p.equals("reset-published")){Acceptance.marker("NETWORK_RESET_CUT","reset proof durably published before acknowledgement; Runtime.halt(77)");Runtime.getRuntime().halt(77);}});send(a(),"command repairvalidation reset "+ka+" "+RepairService.journal.get(ka).token());},()->false,()->{});return;
  }
  add("durable-reset-proof-survived",()->{},()->true,()->check(!RepairService.journal.blocked(ka)&&RepairService.journal.blocked(kb),"Interrupted reset proof wrong"));
  add("real-explicit-reset-C",()->send(a(),"command repairvalidation reset "+kb+" "+RepairService.journal.get(kb).token()),()->done(a())&&reset!=null&&reset.isDone(),()->{reset.join();check(!RepairService.blocked(kb),"Verified reset failed");beta();});
  aa("reopen-repaired-physical-region","remote",()->{check(a().containerMenu instanceof StoreMenu,"Post-reset reopen failed");int i=0;for(UUID id:List.of(aid,bid))for(var p:PocketService.chestPositions(room(id)))check(((StorageEntity)level().getBlockEntity(p)).storageIdentity().toString().equals(ids.getProperty("chest."+(i++))),"Physical chest identity changed");check(Transactions.same(chest(aid,0).getItem(0),item("A",1,13))&&Transactions.same(chest(aid,3).getItem(0),item("C",1,7)),"Latest primary snapshots not recovered");});
  aa("actual-client-latest-snapshot","repairview A 1 13",()->beta());
  aa("single-post-reset-withdrawal","repairtry 256",()->{check(inv(a(),Items.DIAMOND)==1&&chest(aid,0).getItem(0).getCount()==12,"Post-reset transfer not conserved");beta();});
  add("close-each-view",()->{send(a(),"close");send(b(),"close");},RepairClientProbe::both,()->{});
  hold("exact-final-cleanup",650,()->{check(drained()&&RepairService.idle()&&PocketService.ledger(s).returnCount()==0,"Final cleanup incomplete");Trace.audit(s);Acceptance.marker("PASSED","actual clients: stale/fresh write quarantine, concurrent two-key errors, unrelated view, unsafe return, repair/reset commands, two interrupted boundaries, preserved identities/counts; no transfer retry");running=false;s.saveEverything(true,true,true);s.halt(false);});
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post e){
  if(!running||s!=e.getServer())return;tick++;try{
   if(step==null){step=steps.poll();check(step!=null,"No terminal state");since=tick;dev.anya.storage.durable.Fixture.phase="repair-client-"+step.name();step.start().run();}
   check(tick-since<=1600,"Client repair timeout "+step.name());if(step.ready().get()){step.verify().run();Trace.note("REPAIR_CLIENT_STAGE",null,(++completed)+" "+step.name());step=null;}
  }catch(Throwable ex){running=false;Acceptance.marker("FAILED-repair-network",(step==null?"none":step.name())+" "+ex);AnyaStorage.LOG.error("REPAIR_NETWORK_FAILED",ex);s.halt(false);}
 }
}

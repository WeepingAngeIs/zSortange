package dev.anya.storage.livecheck;

import com.mojang.datafixers.util.Either;
import dev.anya.storage.*;
import dev.anya.storage.liveview.*;
import dev.anya.storage.pocket.*;
import dev.anya.storage.repair.*;
import dev.anya.storage.repair.mixin.WorkerAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import qouteall.imm_ptl.core.network.PacketRedirection;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.anya.storage.livecheck.LiveTest.*;

/** Additional opt-in disposable world. Never clears quarantine or replays inventory commands. */
final class LiveSafety {
 static Hold hold;static UUID alphaId,betaId;static long alphaDeadline,betaDeadline,otherDeadline,begin,forgottenBefore;
 static long failedKey;static String newest;
 static final class Hold {
  final CountDownLatch release=new CountDownLatch(1);final CompletableFuture<Void> barrier,write;
  final ChunkPos pos;final String marker;
  Hold(String label,boolean fail)throws Exception {
   var r=room(0);pos=new ChunkPos(PocketService.chestPositions(r).getFirst());
   var chunk=dest().getChunkSource().getChunkNow(pos.x,pos.z);check(chunk!=null,"Safety write requires already loaded target");
   var chest=(StorageEntity)dest().getBlockEntity(PocketService.chestPositions(r).getFirst());
   check(chest!=null&&chest.getItem(0).getCount()==13,"Safety refuses unexpected inventory");
   marker="Live safety newest "+label;var item=chest.getItem(0).copy();item.set(DataComponents.CUSTOM_NAME,Component.literal(marker));chest.setItem(0,item);
   var tag=ChunkSerializer.write(dest(),chunk);check(RepairService.supported(tag),"Safety snapshot lacks pocket identity");
   // FIFO submitTask is ahead of store. capture() runs synchronously on the owner
   // thread, while this bounded latch parks only the ordinary IO worker.
   var worker=RepairService.worker();
   barrier=((WorkerAccess)(Object)worker).anyaExecute(()->{
    try {if(!release.await(20,TimeUnit.SECONDS))return Either.right(new IOException("Safety IO barrier exceeded 20 seconds"));return Either.left(null);}
    catch(InterruptedException ex){Thread.currentThread().interrupt();return Either.right(ex);}
   });
   if(fail)RepairService.arm("persistent",pos.toLong());
   write=worker.store(pos,tag);
   check(RepairService.roomBlocked(r.id()),"Real store submission did not establish in-flight gate");
   LiveTest.write("safety-write-"+label,Map.of("key",pos.toLong(),"marker",marker,"persistent",fail,"owner_tick",server.getTickCount()));
  }
  void release(){release.countDown();}
 }
 static LiveViews.Handle handle(String role,int pocket){return LiveViews.HANDLES.values().stream().filter(h->h.player.equals(p(role).getUUID())&&h.pocket.equals(ROOMS[pocket])).findFirst().orElseThrow();}
 static void remember() {var a=handle("Alpha",0);var b=handle("Beta",0);alphaId=a.id;betaId=b.id;alphaDeadline=a.deadline;betaDeadline=b.deadline;otherDeadline=handle("Beta",1).deadline;}
 static void unchanged() {
  check(LiveViews.HANDLES.containsKey(alphaId)&&LiveViews.HANDLES.get(alphaId).deadline==alphaDeadline,"Suspension replaced or extended Alpha handle");
  check(LiveViews.HANDLES.containsKey(betaId)&&LiveViews.HANDLES.get(betaId).deadline==betaDeadline,"Suspension replaced or extended Beta handle");
  check(handle("Beta",1).deadline==otherDeadline,"Suspension changed unrelated deadline");
 }
 static void denied()throws Exception {
  check(RepairService.roomBlocked(ROOMS[0]),"Expected real persistence gate");
  var r=room(0);long key=new ChunkPos(PocketService.chestPositions(r).getFirst()).toLong();
  for(String role:List.of("Alpha","Beta")) {
   check(!LiveViews.allowedColumn(p(role),key),"Suspended view allowed destination data");
   var chunk=dest().getChunkSource().getChunkNow(ChunkPos.getX(key),ChunkPos.getZ(key));
   if(chunk!=null){var packet=PacketRedirection.createRedirectedMessage(server,PocketService.DIMENSION,new ClientboundLevelChunkWithLightPacket(chunk,dest().getLightEngine(),null,null));check(PacketFence.filter(p(role),packet)==null,"Final fence accepted a packet under the write gate");}
   check(PacketFence.SENT.getOrDefault(p(role).getUUID(),Set.of()).stream().noneMatch(k->LiveViews.covers(r,k)),"Suspension retained projected target columns");
  }
  check(handle("Beta",1).deadline==otherDeadline,"Other room deadline changed");
 }
 static void startHold(String label,boolean fail)throws Exception {remember();forgottenBefore=PacketFence.forgotten;begin=server.getTickCount();hold=new Hold(label,fail);}
 static void requireReceipt() {
  check(hold.barrier.isDone()&&!hold.barrier.isCompletedExceptionally(),"IO barrier failed");
  check(hold.write.isDone()&&!hold.write.isCompletedExceptionally(),"Ordinary write did not complete");
  var record=RepairService.journal.get(hold.pos.toLong());
  check(record!=null&&!record.locked()&&record.state()==SnapshotJournal.State.APPLIED,"Ordinary write did not obtain durable proof");
  try{check(RepairService.decode(record.payload()).toString().contains(hold.marker),"Newest ordinary snapshot missing");}catch(IOException e){throw new IllegalStateException(e);}
 }
 static void views(){command("Alpha","anyaliveview open "+ROOMS[0]);command("Beta","anyaliveview open "+ROOMS[0]);command("Beta","anyaliveview open "+ROOMS[1]);inspect("Alpha",1);inspect("Beta",3);}
 static void settle(){add("safety all ordinary receipts settled",()->{},()->RepairService.idle()&&!RepairService.roomBlocked(ROOMS[0])&&!RepairService.roomBlocked(ROOMS[1]),400);}
 static void close(){command("Alpha","anyaliveview close");command("Beta","anyaliveview close");inspect("Alpha",0);inspect("Beta",0);}
 static void append(){
  views();settle();
  add("safety real in-flight write remains bounded for 12 ticks",()->startHold("receipt",false),()->{if(server.getTickCount()==begin)return false;denied();unchanged();check(LiveViews.requested(p("Alpha")).size()==25,"Suspension lost bounded loader ownership");if(server.getTickCount()-begin<12)return false;check(PacketFence.forgotten>forgottenBefore,"No actual destination cleanup observed");hold.release();return true;},40);
  add("safety ordinary receipt restores same handles",()->{},()->{if(!hold.write.isDone()||RepairService.roomBlocked(ROOMS[0]))return false;requireReceipt();unchanged();return true;},300);
  inspect("Alpha",1);inspect("Beta",3);close();settle();
  // Fresh handle budgets per independent case; no renewal of a suspended handle.
  views();settle();
  once("safety permission revocation while write is in flight",()->{startHold("revoke",false);HUBS[0].removeMember(p("Beta").getUUID());});
  add("safety permission loss immediately retires only that handle",()->{},()->{if(server.getTickCount()==begin)return false;check(!LiveViews.HANDLES.containsKey(betaId),"Permission loss retained suspended handle");check(LiveViews.HANDLES.containsKey(alphaId),"Permission loss removed other owner");check(handle("Beta",1).deadline==otherDeadline,"Permission loss touched other room");hold.release();return true;},20);
  add("safety revoke receipt",()->{},()->{if(!hold.write.isDone()||RepairService.roomBlocked(ROOMS[0]))return false;requireReceipt();return true;},300);
  inspect("Alpha",1);inspect("Beta",2);close();settle();
  once("safety restore test-only View grant",()->HUBS[0].setMember(p("Beta").getUUID(),BasePermissions.VIEW));views();settle();
  once("safety socket interruption during real write",()->{startHold("socket",false);ready.remove("Beta");write("kill-beta",Map.of("action","SIGKILL real client during suspended view"));});
  add("safety disconnected viewer retires before receipt",()->{},()->{if(p("Beta")!=null)return false;check(!LiveViews.HANDLES.containsKey(betaId),"Logout retained suspended handle");check(LiveViews.HANDLES.containsKey(alphaId)&&LiveViews.HANDLES.get(alphaId).deadline==alphaDeadline,"Logout disturbed suspended owner");hold.release();write("restart-beta",Map.of("same_identity",true));return true;},80);
  add("safety reconnect and receipt",()->{},()->{if(p("Beta")==null||!ready.contains("Beta")||!hold.write.isDone()||RepairService.roomBlocked(ROOMS[0]))return false;requireReceipt();check(handles("Beta")==0,"Reconnect restored suspended handles");return true;},1500);
  inspect("Alpha",1);inspect("Beta",0);close();settle();views();settle();
  once("safety inject persistent real region write failure",()->{startHold("persistent",true);failedKey=hold.pos.toLong();newest=hold.marker;hold.release();});
  add("safety persistent failure remains quarantined",()->{},()->{if(!hold.write.isDone())return false;check(hold.write.isCompletedExceptionally()&&RepairService.ioFaults.get()>0,"Persistent store did not fail in region IO");check(RepairService.roomBlocked(ROOMS[0]),"Failed region write lost quarantine");return true;},300);
  add("safety bounded suspended handles expire without dropping save proof",()->{},()->{
   check(RepairService.roomBlocked(ROOMS[0]),"Persistent quarantine cleared without repair");denied();
   if(LiveViews.HANDLES.containsKey(alphaId)||LiveViews.HANDLES.containsKey(betaId))return false;
   check(server.getTickCount()-begin<=LiveViews.MAX_SUSPENDED_TICKS+30,"Persistent suspension exceeded bounded expiry");
   check(LiveViews.requested(p("Alpha")).isEmpty(),"Expired failed view retained loader ownership");return true;
  },LiveViews.MAX_SUSPENDED_TICKS+35);
  inspect("Alpha",0);inspect("Beta",2);
  once("safety retain newest failed snapshot and report",()->{
   var record=RepairService.journal.get(failedKey);check(record!=null&&record.locked()&&RepairService.journal.blocked(failedKey),"Failed snapshot is not durably locked");
   check(RepairService.decode(record.payload()).toString().contains(newest),"Newest failed snapshot missing");
   check(handle("Beta",1).deadline==otherDeadline,"Persistent failure touched independent view");
   Files.write(control().resolve("failed-newest-snapshot.nbt"),record.payload());
   var proof=new Properties();proof.setProperty("key",Long.toString(failedKey));proof.setProperty("marker",newest);proof.setProperty("room",ROOMS[0].toString());
   try(var out=Files.newOutputStream(Path.of("live-safety-oracle.properties"))){proof.store(out,"Disposable failed-write oracle; no repair authority");}
   write("SAFETY_PASSED",Map.of("real_io_failure",true,"real_inflight_gate",true,"socket_cycle",true,"quarantine_retained",true,"newest_marker",newest,"snapshot_token",record.token(),"failed_key",failedKey,"max_suspended_ticks",LiveViews.MAX_SUSPENDED_TICKS));
   // No reset/repair or fault disarm: failed shutdown saves must retain evidence.
   write("PASSED",Map.of("scenario","persistence-suspension","production",false));running=false;server.halt(false);
  });
 }
 static void verifyRestart()throws Exception {
  var proof=new Properties();try(var in=Files.newInputStream(Path.of("live-safety-oracle.properties"))){proof.load(in);}
  long key=Long.parseLong(proof.getProperty("key"));var id=UUID.fromString(proof.getProperty("room"));
  var record=RepairService.journal.get(key);
  check(record!=null&&record.locked()&&RepairService.journal.blocked(key)&&RepairService.roomBlocked(id),"Restart lost failed-write quarantine");
  check(RepairService.decode(record.payload()).toString().contains(proof.getProperty("marker")),"Restart lost newest failed edit");
  check(LiveViews.HANDLES.isEmpty()&&PocketService.ownedTicketRequests()==0,"Restart recreated view/visit ownership");
  write("RESTART_SAFETY_PASSED",Map.of("newest_failed_snapshot",true,"quarantine_retained",true,"no_chunk_load",true,"snapshot_token",record.token()));
 }
 static void releaseOnFailure(){if(hold!=null)hold.release();}
 private LiveSafety(){}
}

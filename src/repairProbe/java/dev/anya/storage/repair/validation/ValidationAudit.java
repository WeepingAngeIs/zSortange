package dev.anya.storage.repair.validation;
import dev.anya.storage.*;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.repair.RepairService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.nbt.CompoundTag;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Opt-in observation/fault input only; no gameplay retry or acknowledgement substitution. */
public final class ValidationAudit {
 public static final boolean ACTIVE=Boolean.getBoolean("anya.repairNetwork")||!System.getProperty("anya.multiRepair","").isEmpty();
 public static final Set<Long> FAIL=ConcurrentHashMap.newKeySet();
 public static final ConcurrentMap<Long,AtomicInteger> failures=new ConcurrentHashMap<>();
 public static final ConcurrentMap<Long,CompletableFuture<Void>> receipts=new ConcurrentHashMap<>();
 public static final Map<UUID,List<Long>> recovery=new LinkedHashMap<>();
 public static long freshActions,staleActions,blockedPlans,submitted;
 public static void fault(RegionFileStorage storage,ChunkPos pos)throws IOException {
  if(ACTIVE&&RepairService.target(storage.info())&&FAIL.contains(pos.toLong())){
   int n=failures.computeIfAbsent(pos.toLong(),k->new AtomicInteger()).incrementAndGet();
   Trace.note("MULTI_WORKER_FAILURE",null,"key="+pos.toLong()+" count="+n+" thread="+Thread.currentThread().getName());
   throw new IOException("Isolated multi-key write failure "+pos.toLong());
  }
 }
 public static void receipt(IOWorker worker,ChunkPos pos,CompoundTag tag,CompletableFuture<Void> result){
  if(!ACTIVE||!RepairService.target(worker.storageInfo())||!RepairService.supported(tag))return;
  receipts.put(pos.toLong(),result);submitted++;
  Trace.note("MULTI_SUBMITTED",null,"key="+pos.toLong()+" sequence="+submitted);
  result.whenComplete((v,e)->Trace.note("MULTI_RECEIPT",null,"key="+pos.toLong()+" error="+(e!=null)));
 }
 public static void action(StoreMenu menu,Packets.Action action){
  if(!ACTIVE||!action.verb().equals("slot"))return;
  boolean fresh=action.session()==menu.session()&&action.revision()==menu.revision();if(fresh)freshActions++;else staleActions++;
  Trace.note("NETWORK_REPAIR_ACTION",null,"fresh="+fresh+" revision="+action.revision()+" serverRevision="+menu.revision()+" value="+action.value());
 }
 public static void plan(Collection<net.minecraft.world.Container> containers){
  if(!ACTIVE)return;if(containers.stream().anyMatch(RepairService::containerBlocked)){blockedPlans++;Trace.note("NETWORK_QUARANTINE_PLAN",null,"blocked="+blockedPlans);}
 }
 public static void recovery(ServerPlayer p){
  if(!ACTIVE)return;long t=p.getServer().getTickCount();var times=recovery.computeIfAbsent(p.getUUID(),k->new ArrayList<>());times.add(t);
  Trace.note("MULTI_RECOVERY",p.getUUID(),"tick="+t+" failingKeys="+FAIL.size()+" pending="+(RepairService.journal==null?-1:RepairService.journal.all().stream().filter(r->RepairService.journal.blocked(r.key())).count()));
 }
 private ValidationAudit(){}
}

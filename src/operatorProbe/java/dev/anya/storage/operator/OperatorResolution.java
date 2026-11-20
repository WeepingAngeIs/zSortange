package dev.anya.storage.operator;

import com.mojang.brigadier.arguments.*;
import com.mojang.datafixers.util.Either;
import dev.anya.storage.durable.Trace;
import dev.anya.storage.pocket.PocketService;
import dev.anya.storage.repair.*;
import dev.anya.storage.repair.mixin.WorkerAccess;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Experimental explicit operator path. Inspection never writes or creates a file, flushes, or loads a chunk. */
@EventBusSubscriber(modid="anyadurableprobe")
public final class OperatorResolution {
 public record Observation(JournalDisk.View disk,String primaryHash,String journalHash,boolean samePrimary,String decision,long epoch,long tick,String receipt){}
 private record Permit(Observation view,String actor){}
 private static final Map<String,Permit> issued=new LinkedHashMap<>();
 private static final Field EPOCHS=field("epochs"),FLIGHT=field("inFlight");
 private static Field field(String name){try{var f=RepairService.class.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @SuppressWarnings("unchecked") private static long epoch(long key){try{var a=((Map<Long,AtomicLong>)EPOCHS.get(null)).get(key);return a==null?0:a.get();}catch(Exception e){throw new IllegalStateException(e);}}
 @SuppressWarnings("unchecked") private static int flight(long key){try{var a=((Map<Long,AtomicInteger>)FLIGHT.get(null)).get(key);return a==null?0:a.get();}catch(Exception e){throw new IllegalStateException(e);}}
 private static void check(boolean v,String why)throws IOException{if(!v)throw new IOException(why);}
 private static void owner(CommandSourceStack source)throws IOException{
  check(RepairService.ENABLED&&RepairService.server!=null&&RepairService.server.isSameThread(),"OWNER_THREAD_AND_EXPERIMENT_REQUIRED");
  check(source.getServer()==RepairService.server&&source.hasPermission(4),"OPERATOR_PERMISSION_REQUIRED");check(!RepairService.stopping,"STOPPING_REFUSED");
 }
 private static void available(long key)throws IOException{var p=new ChunkPos(key);check(PocketService.dimension(RepairService.server).getChunkSource().getChunkNow(p.x,p.z)==null,"LOADED_DESTINATION_REFUSED");check(flight(key)==0&&RepairService.idle(),"OUTSTANDING_SAVE_OR_REPAIR_REFUSED");}
 private static void identity(JournalDisk.Entry e)throws IOException{
  var p=new ChunkPos(e.key());var tag=RepairService.decode(e.payload());check(tag.getInt("xPos")==p.x&&tag.getInt("zPos")==p.z&&RepairService.supported(tag),"JOURNAL_COORDINATE_OR_TYPE");
  var bes=tag.getList("block_entities",10);Set<UUID> seen=new HashSet<>();
  for(int i=0;i<bes.size();i++){var be=bes.getCompound(i);if(!be.hasUUID("PocketId"))continue;var room=PocketService.ledger(RepairService.server).get(be.getUUID("PocketId"));
   check(room!=null&&be.hasUUID("Owner")&&room.owner().equals(be.getUUID("Owner"))&&room.contains(be.getInt("x"),be.getInt("y"),be.getInt("z")),"ROOM_OR_OWNER_MISMATCH");
   check(be.hasUUID("StorageIdentity")&&seen.add(be.getUUID("StorageIdentity")),"STORAGE_IDENTITY_MISSING_OR_DUPLICATE");
  }
 }
 private static Path root(){return RepairService.server.getWorldPath(LevelResource.ROOT);}
 private static Path region(){var id=PocketService.DIMENSION.location();return root().resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath()).resolve("region");}
 private static CompletableFuture<Observation> inspectInternal(CommandSourceStack source,long key,boolean issue)throws IOException{
  owner(source);available(key);long expected=epoch(key);long tick=RepairService.server.getTickCount();var result=new CompletableFuture<Observation>();
  var slot=new AtomicReference<Observation>();var w=RepairService.worker();
  var work=((WorkerAccess)(Object)w).anyaExecute(()->{try{
   check(epoch(key)==expected&&flight(key)==0,"SUBMISSION_CHANGED_BEFORE_INSPECTION");
   var v=JournalDisk.read(root().resolve("anya_repair_experiment"),key);
   var primary=ReadOnlyRegion.inspect(region(),key);boolean same=RepairService.decode(v.entry().payload()).equals(primary.tag());
   // A second journal read rejects mixed snapshots without forcing or writing anything.
   var again=JournalDisk.read(root().resolve("anya_repair_experiment"),key);check(v.fingerprint().equals(again.fingerprint())&&epoch(key)==expected&&flight(key)==0,"EVIDENCE_CHANGED_DURING_INSPECTION");
   String decision=JournalDisk.decision(v,same);String receipt=UUID.randomUUID().toString();
   slot.set(new Observation(v,primary.digest(),JournalDisk.hash(v.entry().payload()),same,decision,expected,tick,receipt));return Either.left(null);
  }catch(Exception ex){return Either.right(ex);}});
  work.whenComplete((unused,error)->RepairService.server.execute(()->{
   try{if(error!=null)throw new IOException("INSPECTION_REFUSED",error);owner(source);available(key);var o=slot.get();check(epoch(key)==expected,"SUBMISSION_CHANGED_AFTER_INSPECTION");identity(o.disk.entry());
    var live=RepairService.journal==null?null:RepairService.journal.get(key);
    check(live!=null&&live.token().equals(o.disk.entry().token())&&live.state().ordinal()==o.disk.entry().state()&&live.locked()==o.disk.entry().locked()&&JournalDisk.hash(live.payload()).equals(o.journalHash),"LIVE_AND_DISK_EVIDENCE_DISAGREE");
    if(RepairService.globalBad&&!o.disk.fatal())throw new IOException("PROCESS_GLOBAL_FAULT_NO_BLIND_CLEAR");
    if(issue){issued.entrySet().removeIf(e->tick-e.getValue().view.tick>600);check(issued.size()<16,"INSPECTION_RECEIPT_LIMIT");issued.put(o.receipt,new Permit(o,source.getTextName()));}
    Trace.note("OPERATOR_READ_ONLY",null,summary(o));result.complete(o);
   }catch(Exception e){result.completeExceptionally(e);}
  }));return result;
 }
 public static CompletableFuture<Observation> inspect(CommandSourceStack source,long key)throws IOException{return inspectInternal(source,key,true);}
 public static CompletableFuture<Void> resolve(CommandSourceStack source,UUID world,long key,UUID token,String receipt)throws IOException{
  owner(source);Permit permit=issued.remove(receipt);check(permit!=null&&permit.actor.equals(source.getTextName()),"UNKNOWN_OR_REPLAYED_RECEIPT");var old=permit.view;
  check(old.disk.world().equals(world)&&old.disk.entry().key()==key&&old.disk.entry().token().equals(token),"EXACT_WORLD_KEY_TOKEN_REQUIRED");
  check(RepairService.server.getTickCount()-old.tick<=600&&epoch(key)==old.epoch,"STALE_INSPECTION");
  check(old.decision.equals("ELIGIBLE_FOR_EXPLICIT_REVERIFY"),old.decision);
  var complete=new CompletableFuture<Void>();
  inspectInternal(source,key,false).whenComplete((fresh,error)->{
   try{if(error!=null)throw new IOException("RESOLUTION_REINSPECTION_REFUSED",error);owner(source);available(key);
    check(fresh.disk.fingerprint().equals(old.disk.fingerprint())&&fresh.primaryHash.equals(old.primaryHash)&&fresh.epoch==old.epoch&&fresh.decision.equals(old.decision),"RESOLUTION_EVIDENCE_CHANGED");
    check(!RepairService.globalBad,"GLOBAL_FAULT_REFUSED");
    // Existing owner-thread reset validates unloaded state and its submission epoch again.
    RepairService.reset(key,token).whenComplete((v,ex)->{if(ex!=null)complete.completeExceptionally(ex);else complete.complete(null);});
   }catch(Exception e){complete.completeExceptionally(e);}
  });return complete;
 }
 public static String summary(Observation o){var v=o.disk;return "world="+v.world()+" key="+Long.toUnsignedString(v.entry().key(),16)+" token="+v.entry().token()+" intent="+(v.intent()==null?"missing":v.intent().token()+"/pending="+v.intent().pending())+" state="+v.entry().state()+" recordLocked="+v.entry().locked()+" primarySha256="+o.primaryHash+" journalSha256="+o.journalHash+" equal="+o.samePrimary+" conclusion="+o.decision+" receipt="+o.receipt+"; hashes identify bytes, UUIDs are not version numbers";}
 @SubscribeEvent public static void commands(RegisterCommandsEvent e){
  if(!RepairService.ENABLED)return;
  e.getDispatcher().register(Commands.literal("anya_repair_operator").requires(s->s.hasPermission(4))
   .then(Commands.literal("inspect").then(Commands.argument("key",StringArgumentType.word()).executes(c->{try{
    long key=Long.parseUnsignedLong(StringArgumentType.getString(c,"key"),16);inspect(c.getSource(),key).whenComplete((o,x)->RepairService.server.execute(()->{if(x!=null)c.getSource().sendFailure(Component.literal(x.toString()));else c.getSource().sendSuccess(()->Component.literal(summary(o)),false);}));
   }catch(Exception x){c.getSource().sendFailure(Component.literal(x.toString()));}return 1;})))
   .then(Commands.literal("resolve").then(Commands.argument("world",StringArgumentType.word()).then(Commands.argument("key",StringArgumentType.word()).then(Commands.argument("token",StringArgumentType.word()).then(Commands.argument("receipt",StringArgumentType.word()).executes(c->{try{
    resolve(c.getSource(),UUID.fromString(StringArgumentType.getString(c,"world")),Long.parseUnsignedLong(StringArgumentType.getString(c,"key"),16),UUID.fromString(StringArgumentType.getString(c,"token")),StringArgumentType.getString(c,"receipt")).whenComplete((v,x)->RepairService.server.execute(()->{if(x!=null)c.getSource().sendFailure(Component.literal(x.toString()));else c.getSource().sendSuccess(()->Component.literal("Exact current reset durably completed; no snapshot or marker deleted."),false);}));
   }catch(Exception x){c.getSource().sendFailure(Component.literal(x.toString()));}return 1;})))))));
 }
 private OperatorResolution(){}
}

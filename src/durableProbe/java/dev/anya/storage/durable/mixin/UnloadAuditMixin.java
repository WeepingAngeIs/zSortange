package dev.anya.storage.durable.mixin;

import dev.anya.storage.durable.Trace;
import dev.anya.storage.durable.unload.*;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.function.BooleanSupplier;

/** Pinned, pocket-dimension-only experiment. No refcount, save, ticket or pending ownership is modified. */
@Mixin(ChunkMap.class)
public abstract class UnloadAuditMixin implements UnloadControl {
 @Shadow @Final private ServerLevel level;
 @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
 @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
 @Shadow private void scheduleUnload(long key,ChunkHolder holder){throw new AssertionError();}
 @Unique private DeferredRetries<Long,ChunkHolder> anya$retries;
 @Unique private boolean anya$scope(){return ReferenceTrace.ACTIVE&&PocketService.inPocket(level);}
 @Unique private DeferredRetries<Long,ChunkHolder> anya$queue(){
  if(!level.getServer().isSameThread())throw new IllegalStateException("Unload queue used off server thread");
  if(anya$retries==null)anya$retries=new DeferredRetries<>(8192);return anya$retries;
 }
 @Unique private boolean anya$blocked(ChunkHolder h){return h.getGenerationRefCount()>0&&h.getSaveSyncFuture().isDone()&&!h.getSaveSyncFuture().isCompletedExceptionally();}
 @Inject(method="lambda$scheduleUnload$12",at=@At("HEAD"),cancellable=true)
 private void callback(ChunkHolder holder,long key,CallbackInfo c){
  if(!ReferenceTrace.ACTIVE){Trace.unload(level,holder,key);return;}if(!anya$scope())return;
  if(!level.getServer().isSameThread())throw new IllegalStateException("Unload callback not on owner thread");
  UnloadPersistenceProbe.beforeCallback(level,holder,key,pendingUnloads.get(key)==holder);
  ReferenceTrace.callback(holder,pendingUnloads.get(key)==holder,updatingChunkMap.get(key)==holder);
  if(ReferenceTrace.DEFER&&anya$blocked(holder)){
   if(pendingUnloads.get(key)!=holder){ReferenceTrace.staleRetired++;c.cancel();return;}
   if(anya$queue().defer(key,holder)){ReferenceTrace.accepted++;c.cancel();}else ReferenceTrace.overflow++;
  }
 }
 @Inject(method="processUnloads",at=@At("HEAD"))
 private void retry(BooleanSupplier time,CallbackInfo c){
  if(anya$scope()&&ReferenceTrace.DEFER&&anya$retries!=null)
   anya$queue().drain(256,(key,h)->pendingUnloads.get(key.longValue())==h,this::anya$blocked,(key,h)->scheduleUnload(key,h));
 }
 @Inject(method="close",at=@At("HEAD"))
 private void closing(CallbackInfo c){
  if(!anya$scope())return;
  // Retire only stale identities. Never discard an owned pending save to make shutdown appear clean.
  if(anya$retries!=null)anya$queue().drain(8192,(key,h)->pendingUnloads.get(key.longValue())==h,h->true,(key,h)->{throw new AssertionError();});
  ReferenceTrace.closed(level,this);
 }
 @Redirect(method="processUnloads",at=@At(value="INVOKE",target="Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;put(JLjava/lang/Object;)Ljava/lang/Object;"))
 private Object pending(Long2ObjectLinkedOpenHashMap<Object> map,long key,Object value){
  Object old=map.put(key,value);if(anya$scope())ReferenceTrace.scheduled((ChunkHolder)value);return old;
 }
 @Redirect(method="updateChunkScheduling",at=@At(value="INVOKE",target="Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;remove(J)Ljava/lang/Object;"))
 private Object resurrect(Long2ObjectLinkedOpenHashMap<Object> map,long key){
  Object old=map.remove(key);if(anya$scope()&&old instanceof ChunkHolder h)ReferenceTrace.resurrect(h);return old;
 }
 @Inject(method="acquireGeneration",at=@At("RETURN"))
 private void acquire(long key,CallbackInfoReturnable<GenerationChunkHolder> c){if(anya$scope())ReferenceTrace.acquire(c.getReturnValue());}
 @Inject(method="releaseGeneration",at=@At("RETURN"))
 private void release(GenerationChunkHolder h,CallbackInfo c){if(anya$scope())ReferenceTrace.release(h);}
 public int anyaRetryCount(){return anya$retries==null?0:anya$retries.size();}
 public long[] anyaRetryStats(){var q=anya$retries;return q==null?new long[5]:new long[]{q.retired,q.rearmed,q.replaced,q.polls,q.peak};}
}

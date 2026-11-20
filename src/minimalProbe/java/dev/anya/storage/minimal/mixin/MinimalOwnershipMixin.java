package dev.anya.storage.minimal.mixin;
import dev.anya.storage.minimal.*;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.Queue;
/** Pocket-only early identity guard. Still-owned callbacks execute wholly unchanged. No deferred queue. */
@Mixin(ChunkMap.class)
public abstract class MinimalOwnershipMixin implements OwnershipView {
 @Shadow @Final private ServerLevel level;
 @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;
 @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;
 @Shadow @Final private Queue<Runnable> unloadQueue;
 @Inject(method="lambda$scheduleUnload$12",at=@At("HEAD"),cancellable=true)
 private void staleOnly(ChunkHolder holder,long key,CallbackInfo ci){
  if(!LeanMetrics.CANDIDATE&&!DirtyCycles.ACTIVE)return;
  if(!PocketService.inPocket(level))return;
  if(!level.getServer().isSameThread())throw new IllegalStateException("Callback off owner thread");
  if(DirtyCycles.ACTIVE)DirtyCycles.beforeCallback(level,holder,key,this);
  if(LeanMetrics.CANDIDATE&&pendingUnloads.get(key)!=holder){LeanMetrics.retired++;ci.cancel();}
 }
 @Inject(method="close",at=@At("HEAD"))
 private void closeAudit(CallbackInfo ci){if(PocketService.inPocket(level))LeanMetrics.closed(this);}
 public ChunkHolder anyaPending(long key){return pendingUnloads.get(key);}
 public ChunkHolder anyaActive(long key){return updatingChunkMap.get(key);}
 public int anyaPendingCount(){return pendingUnloads.size();}
 public int anyaCallbackCount(){return unloadQueue.size();}
}

package dev.anya.storage.budget.mixin;
import dev.anya.storage.budget.BudgetState;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.server.level.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.Queue;
/** Budget only normal processUnloads admission; leave every postponed callback in the engine queue. */
@Mixin(ChunkMap.class)
public abstract class SerializationBudgetMixin {
 @Shadow @Final private ServerLevel level;
 @Shadow private boolean saveChunkIfNeeded(ChunkHolder holder){throw new AssertionError();}
 @Unique private BudgetState anyaBudget;
 @Unique private BudgetState budget(){if(anyaBudget==null)anyaBudget=new BudgetState(level);return anyaBudget;}
 @Inject(method="processUnloads",at=@At("HEAD")) private void begin(CallbackInfo ci){if(PocketService.inPocket(level))budget().reset();}
 @Redirect(method="processUnloads",at=@At(value="INVOKE",target="Ljava/util/function/BooleanSupplier;getAsBoolean()Z"))
 private boolean time(java.util.function.BooleanSupplier original){return original.getAsBoolean()&&(!PocketService.inPocket(level)||budget().timeAllowed());}
 @Redirect(method="processUnloads",at=@At(value="INVOKE",target="Ljava/util/Queue;poll()Ljava/lang/Object;"))
 private Object poll(Queue<Runnable> queue){return PocketService.inPocket(level)?budget().poll(queue):queue.poll();}
 @Redirect(method="processUnloads",at=@At(value="INVOKE",target="Ljava/lang/Runnable;run()V"))
 private void run(Runnable work){if(PocketService.inPocket(level))budget().run(work);else work.run();}
 @Redirect(method="processUnloads",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ChunkMap;saveChunkIfNeeded(Lnet/minecraft/server/level/ChunkHolder;)Z"))
 private boolean incremental(ChunkMap ignored,ChunkHolder holder){
  if(!PocketService.inPocket(level))return saveChunkIfNeeded(holder);
  var b=budget();if(!b.allowIncremental())return false;
  boolean[] result={false};b.incremental(()->result[0]=saveChunkIfNeeded(holder));return result[0];
 }
 @Inject(method="saveAllChunks",at=@At("HEAD")) private void forceBegin(boolean flush,CallbackInfo ci){if(PocketService.inPocket(level))budget().forceDepth++;}
 @Inject(method="saveAllChunks",at=@At("RETURN")) private void forceEnd(boolean flush,CallbackInfo ci){if(PocketService.inPocket(level)&&--budget().forceDepth<0)throw new IllegalStateException("Unbalanced save scope");}
}

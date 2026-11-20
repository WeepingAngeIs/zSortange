package dev.anya.storage.churn.mixin;
import dev.anya.storage.churn.ChurnAudit;import net.minecraft.world.level.chunk.storage.IOWorker;import net.minecraft.world.level.ChunkPos;import net.minecraft.nbt.CompoundTag;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;import java.util.concurrent.CompletableFuture;
@Mixin(IOWorker.class) public abstract class ChurnSubmissionMixin {
 @Inject(method="store",at=@At("HEAD")) private void submission(ChunkPos p,CompoundTag t,CallbackInfoReturnable<CompletableFuture<Void>> c){ChurnAudit.submission((IOWorker)(Object)this,p,t);}
}

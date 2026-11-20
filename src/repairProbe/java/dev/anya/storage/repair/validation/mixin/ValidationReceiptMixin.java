package dev.anya.storage.repair.validation.mixin;
import dev.anya.storage.repair.validation.ValidationAudit;import net.minecraft.world.level.chunk.storage.IOWorker;import net.minecraft.world.level.ChunkPos;import net.minecraft.nbt.CompoundTag;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;import java.util.concurrent.CompletableFuture;
@Mixin(IOWorker.class) public abstract class ValidationReceiptMixin {
 @Inject(method="store",at=@At("RETURN")) private void observe(ChunkPos p,CompoundTag t,CallbackInfoReturnable<CompletableFuture<Void>> c){ValidationAudit.receipt((IOWorker)(Object)this,p,t,c.getReturnValue());}
}

package dev.anya.storage.pressure.mixin;
import dev.anya.storage.pressure.Receipts;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.concurrent.CompletableFuture;
@Mixin(IOWorker.class)
public abstract class ReceiptMixin {
 @Inject(method="store",at=@At("RETURN")) private void receipt(ChunkPos p,CompoundTag t,CallbackInfoReturnable<CompletableFuture<Void>> ci){Receipts.stored((IOWorker)(Object)this,p,t,ci.getReturnValue());}
}

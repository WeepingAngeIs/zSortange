package dev.anya.storage.repair.mixin;
import dev.anya.storage.repair.RepairFixture;import net.minecraft.world.level.chunk.storage.IOWorker;import net.minecraft.world.level.ChunkPos;import net.minecraft.nbt.CompoundTag;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;import java.util.concurrent.CompletableFuture;
@Mixin(IOWorker.class) public abstract class FixtureReceiptMixin {
 @Inject(method="store",at=@At("RETURN")) private void observe(ChunkPos p,CompoundTag tag,CallbackInfoReturnable<CompletableFuture<Void>> c){RepairFixture.observe((IOWorker)(Object)this,p,tag,c.getReturnValue());}
}

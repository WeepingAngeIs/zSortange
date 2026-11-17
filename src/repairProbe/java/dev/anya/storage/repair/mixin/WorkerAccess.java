package dev.anya.storage.repair.mixin;
import net.minecraft.world.level.chunk.storage.*;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.gen.*;import com.mojang.datafixers.util.Either;import java.util.concurrent.CompletableFuture;import java.util.function.Supplier;
@Mixin(IOWorker.class) public interface WorkerAccess {
 @Accessor("storage") RegionFileStorage anyaStorage();
 @Invoker("submitTask") CompletableFuture<Void> anyaExecute(Supplier<Either<Void,Exception>> work);
}

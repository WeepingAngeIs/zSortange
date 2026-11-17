package dev.anya.storage.repair.mixin;
import dev.anya.storage.repair.RepairService;import net.minecraft.world.level.chunk.storage.*;import net.minecraft.world.level.ChunkPos;import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;import java.io.IOException;import java.util.Optional;import java.util.concurrent.CompletableFuture;
@Mixin(IOWorker.class) public abstract class JournalWorkerMixin {
 @Inject(method="store",at=@At("HEAD")) private void capture(ChunkPos p,CompoundTag t,CallbackInfoReturnable<CompletableFuture<Void>> c){RepairService.capture((IOWorker)(Object)this,p,t);}
 @Inject(method="store",at=@At("RETURN")) private void receipt(ChunkPos p,CompoundTag t,CallbackInfoReturnable<CompletableFuture<Void>> c){RepairService.receipt((IOWorker)(Object)this,p,t,c.getReturnValue());}
 @Redirect(method="runStore",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V")) private void journal(RegionFileStorage s,ChunkPos p,CompoundTag t)throws IOException{RepairService.write(s,p,t);}
 @Inject(method="loadAsync",at=@At("HEAD"),cancellable=true) private void overlay(ChunkPos p,CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> c){
  try{var tag=RepairService.overlay((IOWorker)(Object)this,p);if(tag.isPresent())c.setReturnValue(CompletableFuture.completedFuture(tag));}catch(IOException e){c.setReturnValue(CompletableFuture.failedFuture(e));}
 }
}

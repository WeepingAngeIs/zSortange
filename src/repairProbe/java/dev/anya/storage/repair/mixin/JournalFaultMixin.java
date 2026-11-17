package dev.anya.storage.repair.mixin;
import dev.anya.storage.repair.RepairService;import net.minecraft.world.level.chunk.storage.RegionFileStorage;import net.minecraft.world.level.ChunkPos;import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;import java.io.IOException;
@Mixin(RegionFileStorage.class) public abstract class JournalFaultMixin {
 @Inject(method="write",at=@At("HEAD")) private void fault(ChunkPos p,CompoundTag t,CallbackInfo c)throws IOException{RepairService.ioFault((RegionFileStorage)(Object)this,p);}
 @Inject(method="flush",at=@At("HEAD")) private void flush(CallbackInfo c)throws IOException{RepairService.flushFault((RegionFileStorage)(Object)this);}
}

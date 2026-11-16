package dev.anya.storage.pressure.mixin;
import dev.anya.storage.pressure.Receipts;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.io.IOException;
@Mixin(RegionFileStorage.class)
public abstract class PressureFaultMixin {
 @Inject(method="write",at=@At("HEAD")) private void write(ChunkPos p,CompoundTag t,CallbackInfo ci)throws IOException{Receipts.ioWrite((RegionFileStorage)(Object)this,p,t);}
 @Inject(method="flush",at=@At("HEAD")) private void flush(CallbackInfo ci)throws IOException{Receipts.ioFlush((RegionFileStorage)(Object)this);}
}

package dev.anya.storage.durable.mixin;
import dev.anya.storage.durable.Fixture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.io.IOException;
@Mixin(RegionFileStorage.class)
public abstract class IOFaultMixin {
 @Inject(method="write",at=@At("HEAD")) private void write(ChunkPos p,CompoundTag tag,CallbackInfo c)throws IOException{
  Fixture.ioWrite((RegionFileStorage)(Object)this,p,tag);
 }
 @Inject(method="flush",at=@At("HEAD")) private void flush(CallbackInfo c)throws IOException{
  Fixture.ioFlush((RegionFileStorage)(Object)this);
 }
}

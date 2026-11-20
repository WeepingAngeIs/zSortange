package dev.anya.storage.healthy.mixin;
import dev.anya.storage.healthy.HealthyAudit;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(RegionFileStorage.class) public abstract class HealthyWorkerMixin {
 @Inject(method="write",at=@At("HEAD")) private void before(ChunkPos p,CompoundTag t,CallbackInfo c){HealthyAudit.physical((RegionFileStorage)(Object)this,p,t,false);}
 @Inject(method="write",at=@At("RETURN")) private void after(ChunkPos p,CompoundTag t,CallbackInfo c){HealthyAudit.physical((RegionFileStorage)(Object)this,p,t,true);}
}

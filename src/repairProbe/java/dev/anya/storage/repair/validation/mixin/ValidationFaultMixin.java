package dev.anya.storage.repair.validation.mixin;
import dev.anya.storage.repair.validation.ValidationAudit;import net.minecraft.world.level.chunk.storage.RegionFileStorage;import net.minecraft.world.level.ChunkPos;import net.minecraft.nbt.CompoundTag;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.*;import java.io.IOException;
@Mixin(RegionFileStorage.class) public abstract class ValidationFaultMixin {
 @Inject(method="write",at=@At("HEAD")) private void fail(ChunkPos p,CompoundTag t,CallbackInfo c)throws IOException{ValidationAudit.fault((RegionFileStorage)(Object)this,p);}
}

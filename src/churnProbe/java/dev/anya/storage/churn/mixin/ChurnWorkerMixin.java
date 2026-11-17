package dev.anya.storage.churn.mixin;
import dev.anya.storage.churn.ChurnAudit;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.io.IOException;
@Mixin(RegionFileStorage.class) public abstract class ChurnWorkerMixin {
 @Inject(method="write",at=@At("HEAD")) private void before(ChunkPos p,CompoundTag t,CallbackInfo c)throws IOException{ChurnAudit.before((RegionFileStorage)(Object)this,p,t);}
 @Inject(method="write",at=@At("RETURN")) private void after(ChunkPos p,CompoundTag t,CallbackInfo c){ChurnAudit.after((RegionFileStorage)(Object)this,p,t);}
}

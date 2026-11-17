package dev.anya.storage.resetgap.mixin;
import dev.anya.storage.resetgap.GapHooks;
import dev.anya.storage.repair.RepairService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=RepairService.class,remap=false)
public abstract class GapServiceMixin {
 @Inject(method="write",at=@At("HEAD")) private static void write(RegionFileStorage storage,ChunkPos pos,CompoundTag tag,CallbackInfo ci){GapHooks.beforeWrite(pos,tag);}
 @Inject(method="boundary",at=@At("HEAD")) private static void boundary(String at,ChunkPos pos,CallbackInfo ci){GapHooks.boundary(at,pos);}
}

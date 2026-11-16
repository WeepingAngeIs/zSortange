package dev.anya.storage.budget.mixin;
import dev.anya.storage.budget.BudgetState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ChunkSerializer.class)
public abstract class SerializationCountMixin {
 @Inject(method="write",at=@At("HEAD")) private static void count(ServerLevel level,ChunkAccess chunk,CallbackInfoReturnable<CompoundTag> ci){BudgetState.snapshot(level);}
}

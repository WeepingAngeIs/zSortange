package dev.anya.storage.profiling.mixin;
import dev.anya.storage.profiling.ProfileProbe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Counts actual tickChunk invocations, not ticket levels or cached holders. */
@Mixin(ServerLevel.class)
public abstract class ChunkWorkMixin {
    @Inject(method="tickChunk",at=@At("HEAD"))
    private void profile(LevelChunk chunk,int randomTicks,CallbackInfo ci){ProfileProbe.chunkTick((ServerLevel)(Object)this);}
}

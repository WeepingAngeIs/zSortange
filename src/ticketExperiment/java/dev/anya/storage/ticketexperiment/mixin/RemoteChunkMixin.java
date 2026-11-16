package dev.anya.storage.ticketexperiment.mixin;
import dev.anya.storage.ticketexperiment.RemoteMetrics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerLevel.class)
public abstract class RemoteChunkMixin {
    @Inject(method="tickChunk",at=@At("HEAD")) private void count(LevelChunk c,int speed,CallbackInfo ci){RemoteMetrics.chunk((ServerLevel)(Object)this);}
}

package dev.anya.storage.liveview.mixin;
import dev.anya.storage.liveview.LiveViews;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import qouteall.imm_ptl.core.chunk_loading.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=ImmPtlChunkTracking.class,remap=false)
public abstract class NoExtraLoaders {
 @Inject(method="addPerPlayerAdditionalChunkLoader",at=@At("HEAD"),cancellable=true)
 private static void player(ServerPlayer p,ChunkLoader loader,CallbackInfo ci){if(LiveViews.ENABLED&&LiveViews.protectedDimension(loader.dimension()))ci.cancel();}
 @Inject(method="addGlobalAdditionalChunkLoader",at=@At("HEAD"),cancellable=true)
 private static void global(MinecraftServer server,ChunkLoader loader,CallbackInfo ci){if(LiveViews.ENABLED&&LiveViews.protectedDimension(loader.dimension()))ci.cancel();}
}

package dev.anya.storage.liveview.mixin;
import dev.anya.storage.liveview.LiveViews;
import net.minecraft.server.level.ServerPlayer;
import qouteall.imm_ptl.core.chunk_loading.PlayerChunkLoading;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=PlayerChunkLoading.class,remap=false)
public abstract class PendingSendFence {
 @Inject(method="doChunkSending",at=@At("HEAD")) private void stalePending(ServerPlayer player,CallbackInfo ci){if(LiveViews.ENABLED)LiveViews.reconcile();}
}
